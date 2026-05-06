package one.bitby.exko.stimulus

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Post-format processor for Stimulus JS template strings.
 *
 * Runs AFTER IntelliJ's standard formatting pass (e.g., Reformat Code action).
 * It takes the JS content from StimulusController string templates, reformats it
 * using the JS formatter, and reindents it to align with the Kotlin host code.
 *
 * This processor handles two scenarios:
 * 1. Formatting triggered on the Kotlin host file — finds all Stimulus templates in range
 * 2. Formatting triggered on the injected JS fragment — reformats the host template
 */
class ExkoStimulusPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, range: TextRange, settings: CodeStyleSettings): TextRange {
        return when (source.language.id) {
            "kotlin" -> processKotlinFile(source, range)
            "JavaScript" -> processInjectedJs(source)
            else -> range
        }
    }

    /**
     * Called when formatting is triggered on the Kotlin file.
     * Finds all StimulusController string templates that overlap with the formatted range
     * and reformats their JS content.
     */
    private fun processKotlinFile(source: PsiFile, range: TextRange): TextRange {
        val project = source.project
        val document = PsiDocumentManager.getInstance(project).getDocument(source) ?: return range

        // Collect all Stimulus template strings within the formatted range
        val templates = mutableListOf<KtStringTemplateExpression>()
        source.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is KtStringTemplateExpression
                    && range.intersects(element.textRange)
                    && isStimulusControllerContext(element)
                ) {
                    templates.add(element)
                }
                super.visitElement(element)
            }
        })

        // Process templates from bottom to top so that document offset changes
        // from earlier replacements don't invalidate later template positions.
        templates.sortByDescending { it.textOffset }

        // Track total character count change to adjust the returned range
        var delta = 0
        for (template in templates) {
            delta += formatTemplate(template, document, project)
        }
        if (delta != 0) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        // Return adjusted range accounting for content length changes
        return TextRange(range.startOffset, range.endOffset + delta)
    }

    /**
     * Called when formatting is triggered on the injected JS file itself.
     * Navigates back to the host template and reformats it.
     */
    private fun processInjectedJs(source: PsiFile): TextRange {
        val project = source.project
        val injectionManager = InjectedLanguageManager.getInstance(project)
        if (!injectionManager.isInjectedFragment(source)) return source.textRange

        // Get the Kotlin host element that contains this injected JS
        val host = injectionManager.getInjectionHost(source)
        if (host !is KtStringTemplateExpression) return source.textRange
        if (!isStimulusControllerContext(host)) return source.textRange

        val hostFile = host.containingFile ?: return source.textRange
        val document = PsiDocumentManager.getInstance(project).getDocument(hostFile) ?: return source.textRange

        if (formatTemplate(host, document, project) != 0) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return source.textRange
    }

    /**
     * Core formatting logic for a single Stimulus template string.
     *
     * Steps:
     * 1. Extract JS content from the template, replacing interpolations with placeholders
     * 2. Create a temporary JS file and reformat it using IntelliJ's JS formatter
     * 3. Compute the base indentation (column where the template starts in Kotlin source)
     * 4. Reindent all formatted lines relative to that base indentation
     * 5. Restore interpolation placeholders to their original text
     * 6. Replace the template content in the document
     *
     * Returns the character count change (positive if content grew, negative if shrunk).
     */
    private fun formatTemplate(template: KtStringTemplateExpression, document: Document, project: Project): Int {
        val jsLanguage = Language.findLanguageByID("JavaScript") ?: return 0
        val text = template.text

        // Only process triple-quoted (raw) strings
        if (!text.startsWith("\"\"\"")) return 0
        val quoteLen = 3
        if (text.length <= quoteLen * 2) return 0

        // Step 1: Extract JS content, replacing interpolations ($var, ${expr}) with
        // unique placeholders like "__ph0__", "__ph1__" etc. This ensures the JS formatter
        // sees syntactically harmless identifiers instead of Kotlin interpolation syntax.
        val placeholders = mutableListOf<Pair<String, String>>()
        val jsBuilder = StringBuilder()
        for (entry in template.entries) {
            if (entry is KtLiteralStringTemplateEntry) {
                jsBuilder.append(entry.text)
            } else {
                val ph = "__ph${placeholders.size}__"
                placeholders.add(ph to entry.text)
                jsBuilder.append(ph)
            }
        }
        val sanitizedJs = jsBuilder.toString()

        // Step 2: Determine base indentation — the column where the template's opening
        // triple-quote sits in the Kotlin source. All JS lines will be indented to this level.
        val baseIndent = getBaseIndent(template, document)
        val indentStr = " ".repeat(baseIndent)

        // Step 3: Create a temporary JS file and reformat it using IntelliJ's JS formatter.
        // This normalizes spacing, braces, and indentation according to the project's JS style.
        val tempFile = PsiFileFactory.getInstance(project).createFileFromText("temp.js", jsLanguage, sanitizedJs)
        CodeStyleManager.getInstance(project).reformatText(tempFile, 0, tempFile.textLength)

        // Step 4: Extract the meaningful content lines (skip leading/trailing blank lines
        // that the formatter may have added).
        val formatted = tempFile.text
        val bodyLines = formatted.lines()
        val firstNonBlank = bodyLines.indexOfFirst { it.isNotBlank() }
        val lastNonBlank = bodyLines.indexOfLast { it.isNotBlank() }
        if (firstNonBlank < 0) return 0

        val contentLines = bodyLines.subList(firstNonBlank, lastNonBlank + 1)

        // Step 5: Compute the minimum indentation in the formatted JS output.
        // This is the "base" that the JS formatter used — we'll subtract it and
        // replace with our own base indentation from the Kotlin host.
        val jsBaseIndent = contentLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        // Step 6: Reindent each line — shift from JS formatter's base to Kotlin host's base.
        // Blank lines become empty (no trailing whitespace).
        val reindentedContent = contentLines.map { line ->
            if (line.isBlank()) ""
            else {
                // Preserve relative indentation (nested blocks stay indented relative to base)
                val relativeIndent = (line.length - line.trimStart().length) - jsBaseIndent
                indentStr + " ".repeat(maxOf(0, relativeIndent)) + line.trimStart()
            }
        }

        // Step 7: Reassemble the string and restore interpolation placeholders
        var reindentedStr = reindentedContent.joinToString("\n")
        for ((ph, original) in placeholders) {
            reindentedStr = reindentedStr.replace(ph, original)
        }

        // Step 8: Build the final template content with proper structure:
        // """\n<reindented content>\n<baseIndent>"""
        val newContent = "\"\"\"\n" + reindentedStr + "\n" + indentStr + "\"\"\""
        if (newContent == text) return 0

        // Step 9: Replace in the document and return the length delta
        val docStart = template.textRange.startOffset
        val docEnd = template.textRange.endOffset
        document.replaceString(docStart, docEnd, newContent)
        return newContent.length - text.length
    }

    /**
     * Returns the column offset where the template string starts on its line.
     * This determines the base indentation for all JS content lines.
     */
    private fun getBaseIndent(template: KtStringTemplateExpression, document: Document): Int {
        val startOffset = template.textRange.startOffset
        val line = document.getLineNumber(startOffset)
        val lineStart = document.getLineStartOffset(line)
        return startOffset - lineStart
    }

    private fun isStimulusControllerContext(element: KtStringTemplateExpression): Boolean {
        val lambda = element.parentOfType(KtLambdaExpression::class.java) ?: return false
        val argHolder = lambda.parent
        val call: PsiElement? = when (argHolder) {
            is KtLambdaArgument -> argHolder.parent
            is KtValueArgument -> (argHolder.parent as? KtValueArgumentList)?.parent
            else -> null
        }
        return when (call) {
            is KtCallExpression -> {
                val callee = call.calleeExpression as? KtNameReferenceExpression
                callee?.getReferencedName() == "StimulusController"
            }
            is KtSuperTypeCallEntry -> {
                val typeText = call.calleeExpression.typeReference?.text
                typeText == "StimulusController" || typeText?.endsWith(".StimulusController") == true
            }
            else -> false
        }
    }

    private fun <T : PsiElement> PsiElement.parentOfType(clazz: Class<T>): T? {
        var current: PsiElement? = this.parent
        while (current != null) {
            if (clazz.isInstance(current)) {
                @Suppress("UNCHECKED_CAST")
                return current as T
            }
            current = current.parent
        }
        return null
    }
}

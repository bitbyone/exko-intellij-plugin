package one.bitby.exko.styled

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
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Post-format processor for Styled CSS template strings.
 *
 * Runs AFTER IntelliJ's standard formatting pass (e.g., Reformat Code action).
 * It takes the CSS content from Css { ... } string templates, reformats it using
 * the CSS formatter, and reindents it to align with the Kotlin host code.
 *
 * This processor handles two scenarios:
 * 1. Formatting triggered on the Kotlin host file — finds all CSS templates in range
 * 2. Formatting triggered on the injected CSS fragment — reformats the host template
 */
class ExkoStyledPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, range: TextRange, settings: CodeStyleSettings): TextRange {
        return when (source.language.id) {
            "kotlin" -> processKotlinFile(source, range)
            "CSS" -> processInjectedCss(source)
            else -> range
        }
    }

    /**
     * Called when formatting is triggered on the Kotlin file.
     * Finds all Css/css string templates that overlap with the formatted range
     * and reformats their CSS content.
     */
    private fun processKotlinFile(source: PsiFile, range: TextRange): TextRange {
        val project = source.project
        val document = PsiDocumentManager.getInstance(project).getDocument(source) ?: return range

        // Collect all CSS template strings within the formatted range
        val templates = mutableListOf<KtStringTemplateExpression>()
        source.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is KtStringTemplateExpression
                    && range.intersects(element.textRange)
                    && isCssDelegateContext(element)
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
     * Called when formatting is triggered on the injected CSS file itself.
     * Navigates back to the host template and reformats it.
     */
    private fun processInjectedCss(source: PsiFile): TextRange {
        val project = source.project
        val injectionManager = InjectedLanguageManager.getInstance(project)
        if (!injectionManager.isInjectedFragment(source)) return source.textRange

        // Get the Kotlin host element that contains this injected CSS
        val host = injectionManager.getInjectionHost(source)
        if (host !is KtStringTemplateExpression) return source.textRange
        if (!isCssDelegateContext(host)) return source.textRange

        val hostFile = host.containingFile ?: return source.textRange
        val document = PsiDocumentManager.getInstance(project).getDocument(hostFile) ?: return source.textRange

        if (formatTemplate(host, document, project) != 0) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return source.textRange
    }

    /**
     * Core formatting logic for a single CSS template string.
     *
     * Steps:
     * 1. Extract CSS content from the template, replacing interpolations with placeholders
     * 2. Wrap the content in `.x { ... }` so it's valid CSS (declarations need a rule context)
     * 3. Create a temporary CSS file and reformat it using IntelliJ's CSS formatter
     * 4. Extract just the body (between { and }) from the formatted result
     * 5. Compute the base indentation from the Kotlin property declaration
     * 6. Reindent all formatted lines relative to that base indentation
     * 7. Restore interpolation placeholders to their original text
     * 8. Replace the template content in the document
     *
     * Returns the character count change (positive if content grew, negative if shrunk).
     */
    private fun formatTemplate(template: KtStringTemplateExpression, document: Document, project: Project): Int {
        val cssLanguage = Language.findLanguageByID("CSS") ?: return 0
        val text = template.text

        // Support both triple-quoted (raw) and single-quoted strings
        val quoteLen = if (text.startsWith("\"\"\"")) 3 else 1
        if (text.length <= quoteLen * 2) return 0

        // Step 1: Extract CSS content, replacing interpolations ($var, ${expr}) with
        // unique placeholders like "__ph0__", "__ph1__" etc. This ensures the CSS formatter
        // sees syntactically harmless identifiers instead of Kotlin interpolation syntax.
        val placeholders = mutableListOf<Pair<String, String>>()
        val cssBuilder = StringBuilder()
        for (entry in template.entries) {
            if (entry is KtLiteralStringTemplateEntry) {
                cssBuilder.append(entry.text)
            } else {
                val ph = "__ph${placeholders.size}__"
                placeholders.add(ph to entry.text)
                cssBuilder.append(ph)
            }
        }
        val sanitizedCss = cssBuilder.toString()

        // Step 2: Determine base indentation — for CSS properties this is the column
        // of the enclosing val/var declaration + 4 spaces (one indent level inside the property).
        val baseIndent = getBaseIndent(template, document)
        val indentStr = " ".repeat(baseIndent)

        // Step 3: Wrap in a CSS rule `.x { ... }` because standalone CSS declarations
        // (like `color: red;`) are not valid at the top level of a CSS file.
        // The formatter needs valid CSS to work correctly.
        val wrappedCss = ".x {\n$sanitizedCss\n}"
        val tempFile = PsiFileFactory.getInstance(project).createFileFromText("temp.css", cssLanguage, wrappedCss)
        CodeStyleManager.getInstance(project).reformatText(tempFile, 0, tempFile.textLength)

        // Step 4: Extract just the body content between the `{` and `}` braces.
        // This removes the `.x {` wrapper we added, leaving only the formatted declarations.
        val formatted = tempFile.text
        val bodyStart = formatted.indexOf('{') + 1
        val bodyEnd = formatted.lastIndexOf('}')
        if (bodyStart >= bodyEnd) return 0

        // Step 5: From the body, extract meaningful content lines (skip leading/trailing blanks)
        val bodyLines = formatted.substring(bodyStart, bodyEnd).lines()
        val firstNonBlank = bodyLines.indexOfFirst { it.isNotBlank() }
        val lastNonBlank = bodyLines.indexOfLast { it.isNotBlank() }
        if (firstNonBlank < 0) return 0

        val contentLines = bodyLines.subList(firstNonBlank, lastNonBlank + 1)

        // Step 6: Compute the minimum indentation in the formatted CSS output.
        // The CSS formatter adds indentation for being inside `.x { }` — we subtract
        // that and replace with our own base indentation from the Kotlin host.
        val cssBaseIndent = contentLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        // Step 7: Reindent each line — shift from CSS formatter's base to Kotlin host's base.
        // Blank lines become empty (no trailing whitespace).
        val reindentedContent = contentLines.map { line ->
            if (line.isBlank()) ""
            else {
                // Preserve relative indentation (nested selectors stay indented relative to base)
                val relativeIndent = (line.length - line.trimStart().length) - cssBaseIndent
                indentStr + " ".repeat(maxOf(0, relativeIndent)) + line.trimStart()
            }
        }

        // Step 8: Reassemble the string and restore interpolation placeholders
        var reindentedStr = reindentedContent.joinToString("\n")
        for ((ph, original) in placeholders) {
            reindentedStr = reindentedStr.replace(ph, original)
        }

        // Step 9: Build the final template content with proper structure:
        // """\n<reindented content>\n<baseIndent>"""
        val newContent = "\"\"\"\n" + reindentedStr + "\n" + indentStr + "\"\"\""
        if (newContent == text) return 0

        // Step 10: Replace in the document and return the length delta
        val docStart = template.textRange.startOffset
        val docEnd = template.textRange.endOffset
        document.replaceString(docStart, docEnd, newContent)
        return newContent.length - text.length
    }

    /**
     * Computes the base indentation for CSS content lines.
     *
     * Walks up the PSI tree to find the enclosing KtProperty (val/var declaration)
     * and returns its column offset + 4 (one indent level inside the property).
     * This ensures CSS declarations are indented one level deeper than the property
     * they belong to.
     *
     * Falls back to the minimum indentation found in the existing string content
     * if no enclosing property is found.
     */
    private fun getBaseIndent(template: KtStringTemplateExpression, document: Document): Int {
        var current: PsiElement? = template.parent
        while (current != null) {
            if (current is KtProperty) {
                val line = document.getLineNumber(current.textRange.startOffset)
                val lineStart = document.getLineStartOffset(line)
                // Property column + 4 spaces = one indent level inside the property
                return current.textRange.startOffset - lineStart + 4
            }
            current = current.parent
        }
        // Fallback: use the minimum existing indentation in the string content
        val content = template.text.let {
            val q = if (it.startsWith("\"\"\"")) 3 else 1
            it.substring(q, it.length - q)
        }
        return content.lines()
            .filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0
    }

    private fun isCssDelegateContext(element: KtStringTemplateExpression): Boolean {
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 8) {
            if (current is KtCallExpression) {
                val callee = current.calleeExpression as? KtNameReferenceExpression
                val name = callee?.getReferencedName()
                if (name == "Css" || name == "css") return true
            }
            if (current is KtClassBody) break
            current = current.parent
            depth++
        }
        return false
    }
}

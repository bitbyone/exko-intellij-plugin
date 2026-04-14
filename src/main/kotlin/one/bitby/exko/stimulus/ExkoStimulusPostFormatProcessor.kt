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

class ExkoStimulusPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, range: TextRange, settings: CodeStyleSettings): TextRange {
        return when (source.language.id) {
            "kotlin" -> processKotlinFile(source, range)
            "JavaScript" -> processInjectedJs(source)
            else -> range
        }
    }

    private fun processKotlinFile(source: PsiFile, range: TextRange): TextRange {
        val project = source.project
        val document = PsiDocumentManager.getInstance(project).getDocument(source) ?: return range

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

        templates.sortByDescending { it.textOffset }

        var delta = 0
        for (template in templates) {
            delta += formatTemplate(template, document, project)
        }
        if (delta != 0) {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        return TextRange(range.startOffset, range.endOffset + delta)
    }

    private fun processInjectedJs(source: PsiFile): TextRange {
        val project = source.project
        val injectionManager = InjectedLanguageManager.getInstance(project)
        if (!injectionManager.isInjectedFragment(source)) return source.textRange

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

    private fun formatTemplate(template: KtStringTemplateExpression, document: Document, project: Project): Int {
        val jsLanguage = Language.findLanguageByID("JavaScript") ?: return 0
        val text = template.text
        if (!text.startsWith("\"\"\"")) return 0
        val quoteLen = 3
        if (text.length <= quoteLen * 2) return 0

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

        val baseIndent = getBaseIndent(template, document)
        val indentStr = " ".repeat(baseIndent)

        val tempFile = PsiFileFactory.getInstance(project).createFileFromText("temp.js", jsLanguage, sanitizedJs)
        CodeStyleManager.getInstance(project).reformatText(tempFile, 0, tempFile.textLength)

        val formatted = tempFile.text
        val bodyLines = formatted.lines()
        val firstNonBlank = bodyLines.indexOfFirst { it.isNotBlank() }
        val lastNonBlank = bodyLines.indexOfLast { it.isNotBlank() }
        if (firstNonBlank < 0) return 0

        val contentLines = bodyLines.subList(firstNonBlank, lastNonBlank + 1)
        val jsBaseIndent = contentLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        val reindentedContent = contentLines.map { line ->
            if (line.isBlank()) ""
            else {
                val relativeIndent = (line.length - line.trimStart().length) - jsBaseIndent
                indentStr + " ".repeat(maxOf(0, relativeIndent)) + line.trimStart()
            }
        }

        var reindentedStr = reindentedContent.joinToString("\n")
        for ((ph, original) in placeholders) {
            reindentedStr = reindentedStr.replace(ph, original)
        }

        val newContent = "\"\"\"\n" + reindentedStr + "\n" + indentStr + "\"\"\""
        if (newContent == text) return 0

        val docStart = template.textRange.startOffset
        val docEnd = template.textRange.endOffset
        document.replaceString(docStart, docEnd, newContent)
        return newContent.length - text.length
    }

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

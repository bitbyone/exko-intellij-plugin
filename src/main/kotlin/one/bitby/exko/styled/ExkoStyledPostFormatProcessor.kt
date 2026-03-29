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

class ExkoStyledPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, range: TextRange, settings: CodeStyleSettings): TextRange {
        return when (source.language.id) {
            "kotlin" -> processKotlinFile(source, range)
            "CSS" -> processInjectedCss(source)
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
                    && isCssDelegateContext(element)
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

    private fun processInjectedCss(source: PsiFile): TextRange {
        val project = source.project
        val injectionManager = InjectedLanguageManager.getInstance(project)
        if (!injectionManager.isInjectedFragment(source)) return source.textRange

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

    private fun formatTemplate(template: KtStringTemplateExpression, document: Document, project: Project): Int {
        val cssLanguage = Language.findLanguageByID("CSS") ?: return 0
        val text = template.text
        val quoteLen = if (text.startsWith("\"\"\"")) 3 else 1
        if (text.length <= quoteLen * 2) return 0

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

        val baseIndent = getBaseIndent(template, document)
        val indentStr = " ".repeat(baseIndent)

        val wrappedCss = ".x {\n$sanitizedCss\n}"
        val tempFile = PsiFileFactory.getInstance(project).createFileFromText("temp.css", cssLanguage, wrappedCss)
        CodeStyleManager.getInstance(project).reformatText(tempFile, 0, tempFile.textLength)

        val formatted = tempFile.text
        val bodyStart = formatted.indexOf('{') + 1
        val bodyEnd = formatted.lastIndexOf('}')
        if (bodyStart >= bodyEnd) return 0

        val bodyLines = formatted.substring(bodyStart, bodyEnd).lines()
        val firstNonBlank = bodyLines.indexOfFirst { it.isNotBlank() }
        val lastNonBlank = bodyLines.indexOfLast { it.isNotBlank() }
        if (firstNonBlank < 0) return 0

        val contentLines = bodyLines.subList(firstNonBlank, lastNonBlank + 1)
        val cssBaseIndent = contentLines
            .filter { it.isNotBlank() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0

        val reindentedContent = contentLines.map { line ->
            if (line.isBlank()) ""
            else {
                val relativeIndent = (line.length - line.trimStart().length) - cssBaseIndent
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
        var current: PsiElement? = template.parent
        while (current != null) {
            if (current is KtProperty) {
                val line = document.getLineNumber(current.textRange.startOffset)
                val lineStart = document.getLineStartOffset(line)
                return current.textRange.startOffset - lineStart + 4
            }
            current = current.parent
        }
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

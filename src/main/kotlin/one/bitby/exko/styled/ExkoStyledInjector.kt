package one.bitby.exko.styled

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

class ExkoStyledInjector : MultiHostInjector {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is KtStringTemplateExpression) return
        if (!context.isValidHost) return
        if (!isCssDelegateContext(context)) return

        val cssLanguage = Language.findLanguageByID("CSS") ?: return
        val places = buildPlaces(context) ?: return

        registrar.startInjecting(cssLanguage)
        for (place in places) {
            registrar.addPlace(place.prefix, place.suffix, context as PsiLanguageInjectionHost, place.range)
        }
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> {
        return listOf(KtStringTemplateExpression::class.java)
    }

    private data class Place(val prefix: String?, val suffix: String?, val range: TextRange)

    private fun buildPlaces(element: KtStringTemplateExpression): List<Place>? {
        val entries = element.entries
        if (entries.isEmpty()) return null

        val places = mutableListOf<Place>()
        var rangeStart = -1
        var rangeEnd = -1
        var afterExpression = false

        for (entry in entries) {
            if (entry is KtLiteralStringTemplateEntry) {
                val entryRange = entry.textRangeInParent
                if (entryRange.isEmpty) continue
                if (rangeStart < 0) {
                    rangeStart = entryRange.startOffset
                }
                rangeEnd = entryRange.endOffset
            } else {
                if (rangeStart >= 0) {
                    val prefix = buildString {
                        if (places.isEmpty()) append(".x{")
                        if (afterExpression) append("0")
                    }
                    places.add(Place(prefix.ifEmpty { null }, null, TextRange(rangeStart, rangeEnd)))
                    rangeStart = -1
                }
                afterExpression = true
            }
        }

        if (rangeStart >= 0) {
            val prefix = buildString {
                if (places.isEmpty()) append(".x{")
                if (afterExpression) append("0")
            }
            places.add(Place(prefix.ifEmpty { null }, null, TextRange(rangeStart, rangeEnd)))
        }

        if (places.isEmpty()) return null

        val last = places.last()
        places[places.lastIndex] = Place(last.prefix, "}", last.range)

        return places
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

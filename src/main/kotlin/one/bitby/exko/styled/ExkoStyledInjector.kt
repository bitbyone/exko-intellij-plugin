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

/**
 * Injects CSS language into Kotlin raw string literals that appear inside
 * Css { ... } or css { ... } call contexts. This enables CSS syntax highlighting,
 * code completion, and error checking within the string.
 *
 * The injected CSS is wrapped in a virtual `.x{...}` rule context (via prefix/suffix)
 * because CSS declarations (properties) are only syntactically valid inside a rule block.
 * Without this wrapper, the CSS parser would not recognize the content as valid CSS.
 */
class ExkoStyledInjector : MultiHostInjector {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is KtStringTemplateExpression) return
        if (!context.isValidHost) return
        if (!isCssDelegateContext(context)) return

        val cssLanguage = Language.findLanguageByID("CSS") ?: return
        val places = buildPlaces(context) ?: return

        // Register the injection: each Place represents a contiguous range of literal text.
        // The first Place gets ".x{" prefix and last gets "}" suffix, wrapping the CSS
        // content in a valid rule block for the CSS parser.
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

    /**
     * Builds injection places by merging consecutive literal entries into single ranges.
     *
     * Kotlin PSI splits raw string content into many KtLiteralStringTemplateEntry nodes
     * (roughly one per line). Instead of creating one Place per entry (which would cause
     * issues like cursor staying on the same line on Enter), we merge consecutive literal
     * entries into one contiguous TextRange.
     *
     * When a string interpolation ($var or ${expr}) is encountered, we "break" the current
     * range and start a new one. The next Place gets prefix "0" as a placeholder for the
     * interpolated value so the CSS parser sees valid content across the gap.
     *
     * The first Place always gets ".x{" prefix (to open a CSS rule context).
     * The last Place always gets "}" suffix (to close the rule context).
     */
    private fun buildPlaces(element: KtStringTemplateExpression): List<Place>? {
        val entries = element.entries
        if (entries.isEmpty()) return null

        val places = mutableListOf<Place>()
        // Track the start and end of the current contiguous literal range
        var rangeStart = -1
        var rangeEnd = -1
        // Tracks whether we've just passed an interpolation expression
        var afterExpression = false

        for (entry in entries) {
            if (entry is KtLiteralStringTemplateEntry) {
                // Literal text — extend the current range (or start a new one)
                val entryRange = entry.textRangeInParent
                if (entryRange.isEmpty) continue
                if (rangeStart < 0) {
                    rangeStart = entryRange.startOffset
                }
                rangeEnd = entryRange.endOffset
            } else {
                // Interpolation expression ($var, ${expr}) — flush the accumulated range
                if (rangeStart >= 0) {
                    val prefix = buildString {
                        // First Place gets the CSS rule opener
                        if (places.isEmpty()) append(".x{")
                        // After interpolation, insert "0" as a value placeholder
                        if (afterExpression) append("0")
                    }
                    places.add(Place(prefix.ifEmpty { null }, null, TextRange(rangeStart, rangeEnd)))
                    rangeStart = -1
                }
                afterExpression = true
            }
        }

        // Flush the final accumulated range
        if (rangeStart >= 0) {
            val prefix = buildString {
                if (places.isEmpty()) append(".x{")
                if (afterExpression) append("0")
            }
            places.add(Place(prefix.ifEmpty { null }, null, TextRange(rangeStart, rangeEnd)))
        }

        if (places.isEmpty()) return null

        // Close the CSS rule on the last Place by adding "}" suffix
        val last = places.last()
        places[places.lastIndex] = Place(last.prefix, "}", last.range)

        return places
    }

    /**
     * Checks if the given string template is inside a Css { ... } or css { ... } call.
     * Walks up the PSI tree (max 8 levels to avoid expensive traversal) looking for
     * a KtCallExpression with callee name "Css" or "css". Stops at class body boundaries
     * to avoid false positives.
     */
    private fun isCssDelegateContext(element: KtStringTemplateExpression): Boolean {
        var current: PsiElement? = element.parent
        var depth = 0
        while (current != null && depth < 8) {
            if (current is KtCallExpression) {
                val callee = current.calleeExpression as? KtNameReferenceExpression
                val name = callee?.getReferencedName()
                if (name == "Css" || name == "css") return true
            }
            // Don't cross class body boundaries
            if (current is KtClassBody) break
            current = current.parent
            depth++
        }
        return false
    }
}

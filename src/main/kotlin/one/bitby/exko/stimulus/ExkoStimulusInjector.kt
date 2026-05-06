package one.bitby.exko.stimulus

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
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
 * Injects JavaScript language into Kotlin raw string literals that appear inside
 * StimulusController lambda/supertype contexts. This enables JS syntax highlighting,
 * code completion, and error checking within the string.
 */
class ExkoStimulusInjector : MultiHostInjector {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is KtStringTemplateExpression) return
        if (!context.isValidHost) return
        if (!isStimulusControllerContext(context)) return

        val jsLanguage = Language.findLanguageByID("JavaScript") ?: return
        val places = buildPlaces(context) ?: return

        // Register the injection: each Place represents a contiguous range of literal text
        // in the string that should be treated as JavaScript.
        registrar.startInjecting(jsLanguage)
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
     * range and start a new one after the interpolation. The next Place gets prefix "0"
     * which acts as a placeholder for the interpolated expression value — this ensures the
     * JS parser sees syntactically valid code across the gap.
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
                    val prefix = if (afterExpression) "0" else null
                    places.add(Place(prefix, null, TextRange(rangeStart, rangeEnd)))
                    rangeStart = -1
                }
                afterExpression = true
            }
        }

        // Flush the final accumulated range
        if (rangeStart >= 0) {
            val prefix = if (afterExpression) "0" else null
            places.add(Place(prefix, null, TextRange(rangeStart, rangeEnd)))
        }

        if (places.isEmpty()) return null
        return places
    }

    /**
     * Checks if the given string template is inside a StimulusController context.
     * Walks up the PSI tree to find the enclosing call expression and checks if it's
     * either a function call `StimulusController { ... }` or a supertype call entry
     * `class Foo : StimulusController({ ... })`.
     */
    private fun isStimulusControllerContext(element: KtStringTemplateExpression): Boolean {
        // The string must be inside a lambda expression
        val lambda = element.parentOfType(KtLambdaExpression::class.java) ?: return false
        val argHolder = lambda.parent

        // The lambda can be either a trailing lambda argument or a value argument in parens
        val call: PsiElement? = when (argHolder) {
            is KtLambdaArgument -> argHolder.parent
            is KtValueArgument -> (argHolder.parent as? KtValueArgumentList)?.parent
            else -> null
        }

        return when (call) {
            // Function call: StimulusController { """...""" }
            is KtCallExpression -> {
                val callee = call.calleeExpression as? KtNameReferenceExpression
                callee?.getReferencedName() == "StimulusController"
            }
            // Supertype: class MyController : StimulusController("""...""")
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

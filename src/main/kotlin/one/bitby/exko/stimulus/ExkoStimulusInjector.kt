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

class ExkoStimulusInjector : MultiHostInjector {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        if (context !is KtStringTemplateExpression) return
        if (!context.isValidHost) return
        if (!isStimulusControllerContext(context)) return

        val jsLanguage = Language.findLanguageByID("JavaScript") ?: return
        val places = buildPlaces(context) ?: return

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

    private fun buildPlaces(element: KtStringTemplateExpression): List<Place>? {
        val entries = element.entries
        if (entries.isEmpty()) return null

        val places = mutableListOf<Place>()
        var afterExpression = false

        for (entry in entries) {
            if (entry is KtLiteralStringTemplateEntry) {
                val range = entry.textRangeInParent
                if (range.isEmpty) continue

                val prefix = if (afterExpression) "0" else null
                places.add(Place(prefix, null, range))
                afterExpression = false
            } else {
                afterExpression = true
            }
        }

        if (places.isEmpty()) return null
        return places
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

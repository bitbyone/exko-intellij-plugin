package one.bitby.exko.ui

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Suppresses naming inspections for functions annotated with @io.exko.html.UI.
 *
 * UI component functions follow the convention of starting with an uppercase letter
 * (similar to Jetpack Compose @Composable). This suppressor prevents false-positive
 * naming warnings from both IntelliJ's built-in inspection and ktlint.
 */
class ExkoUiNamingSuppressor : InspectionSuppressor {

    private val suppressedInspections = setOf("FunctionName")

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in suppressedInspections) return false

        val function = element.parent as? KtNamedFunction ?: return false
        val name = function.name ?: return false
        if (name.first().isLowerCase()) return false

        return function.annotationEntries.any { annotation ->
            val shortName = annotation.shortName?.asString()
            shortName == "UI"
        }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
        return SuppressQuickFix.EMPTY_ARRAY
    }
}

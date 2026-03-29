package one.bitby.exko.styled

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtObjectDeclaration

class ExkoStyledInspectionSuppressor : InspectionSuppressor {

    private val suppressedInspections = setOf("MayBeConstant")

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in suppressedInspections) return false
        return isInsideStyledObject(element)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
        return SuppressQuickFix.EMPTY_ARRAY
    }

    private fun isInsideStyledObject(element: PsiElement): Boolean {
        var current: PsiElement? = element
        while (current != null) {
            if (current is KtObjectDeclaration) {
                for (entry in current.superTypeListEntries) {
                    val typeText = entry.typeReference?.text ?: continue
                    if (typeText == "Styled" || typeText.endsWith(".Styled")) return true
                }
            }
            current = current.parent
        }
        return false
    }
}

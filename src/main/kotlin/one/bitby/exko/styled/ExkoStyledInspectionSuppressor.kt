package one.bitby.exko.styled

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Suppresses specific Kotlin inspections inside Styled companion objects.
 *
 * For example, CSS property strings declared as `val bg = Css { """...""" }` would
 * normally trigger "MayBeConstant" inspection (suggesting to make them `const val`).
 * But these are NOT compile-time constants — they use delegates and runtime CSS injection.
 * This suppressor silences such false-positive warnings.
 */
class ExkoStyledInspectionSuppressor : InspectionSuppressor {

    // Set of inspection tool IDs to suppress inside Styled objects
    private val suppressedInspections = setOf("MayBeConstant")

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in suppressedInspections) return false
        return isInsideStyledObject(element)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
        return SuppressQuickFix.EMPTY_ARRAY
    }

    /**
     * Walks up the PSI tree to check if the element is inside a Kotlin object
     * that extends "Styled" (the base class for CSS module objects).
     */
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

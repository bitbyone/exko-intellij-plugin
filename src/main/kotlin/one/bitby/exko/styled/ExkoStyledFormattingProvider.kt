package one.bitby.exko.styled

import com.intellij.formatting.InjectedFormattingOptionsProvider
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * Controls how formatting is handled for injected CSS fragments.
 *
 * Returns false for shouldDelegateToTopLevel — this tells IntelliJ NOT to embed the
 * injected CSS block tree into the host (Kotlin) formatting model. Instead, CSS uses
 * its own independent formatting/indentation logic.
 *
 * The CSS injection includes a `.x{` prefix (from ExkoStyledInjector) that gives the
 * CSS formatter a valid rule context, so it can provide proper indentation for
 * declarations within the injected fragment.
 */
class ExkoStyledFormattingProvider : InjectedFormattingOptionsProvider {

    override fun shouldDelegateToTopLevel(file: PsiFile): Boolean? {
        val langMngr = InjectedLanguageManager.getInstance(file.project)
        val topLevelFile = langMngr.getTopLevelFile(file)
        if (
            file.language is CSSLanguage
            && topLevelFile.language is KotlinLanguage
            && InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
        ) {
            return false
        }
        // Return null for non-CSS files — no opinion, use default behavior
        return null
    }
}

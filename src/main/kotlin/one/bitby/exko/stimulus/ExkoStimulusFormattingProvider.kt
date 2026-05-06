package one.bitby.exko.stimulus

import com.intellij.formatting.InjectedFormattingOptionsProvider
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * Controls how formatting is handled for injected JavaScript fragments.
 *
 * Returns false for shouldDelegateToTopLevel — this tells IntelliJ NOT to embed the
 * injected JS block tree into the host (Kotlin) formatting model. Instead, JS uses
 * its own independent formatting/indentation logic.
 *
 * This is critical for correct Enter key behavior: if we delegated to the Kotlin
 * formatter, it wouldn't understand JS syntax and would return indent 0 for new lines.
 * By letting JS handle its own formatting, the JS language service provides proper
 * indentation based on block structure (functions, if/else, etc.).
 */
class ExkoStimulusFormattingProvider : InjectedFormattingOptionsProvider {

    override fun shouldDelegateToTopLevel(file: PsiFile): Boolean? {
        val langMngr = InjectedLanguageManager.getInstance(file.project)
        val topLevelFile = langMngr.getTopLevelFile(file)
        if (
            file.language is JavascriptLanguage
            && topLevelFile.language is KotlinLanguage
            && langMngr.isInjectedFragment(file)
        ) {
            return false
        }
        // Return null for non-JS files — no opinion, use default behavior
        return null
    }
}

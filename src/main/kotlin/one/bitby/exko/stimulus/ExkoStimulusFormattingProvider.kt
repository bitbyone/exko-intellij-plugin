package one.bitby.exko.stimulus

import com.intellij.formatting.InjectedFormattingOptionsProvider
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile

class ExkoStimulusFormattingProvider : InjectedFormattingOptionsProvider {

    override fun shouldDelegateToTopLevel(file: PsiFile): Boolean? {
        val jsLanguage = Language.findLanguageByID("JavaScript") ?: return null
        if (file.language == jsLanguage
            && InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
        ) {
            return true
        }
        return null
    }
}

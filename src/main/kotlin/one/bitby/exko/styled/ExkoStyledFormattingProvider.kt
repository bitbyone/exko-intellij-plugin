package one.bitby.exko.styled

import com.intellij.formatting.InjectedFormattingOptionsProvider
import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile

class ExkoStyledFormattingProvider : InjectedFormattingOptionsProvider {

    override fun shouldDelegateToTopLevel(file: PsiFile): Boolean? {
        val cssLanguage = Language.findLanguageByID("CSS") ?: return null
        if (file.language == cssLanguage
            && InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
        ) {
            return false
        }
        return null
    }
}

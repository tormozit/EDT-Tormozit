package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.xtext.ui.editor.contentassist.XtextContentAssistProcessor;

/**
 * Применяет патч к {@link SourceViewer} BSL-редактора:
 * <ul>
 *   <li>устанавливает символы-триггеры автооткрытия подсказки;</li>
 *   <li>устанавливает задержку автоактивации.</li>
 * </ul>
 *
 * <p>Использует рефлексию для доступа к приватному полю
 * {@code SourceViewer.fContentAssistant} — штатный API доступа отсутствует.
 */
public final class ContentAssistAutoOpenPatcher
{
    private static Field contentAssistantField;

    private ContentAssistAutoOpenPatcher() {}

    /**
     * Применяет патч к переданному {@code SourceViewer}.
     *
     * @return {@code true} если патч применён успешно
     */
    public static boolean applyPatch(SourceViewer sourceViewer, int timeout, String charset)
    {
        ContentAssistant contentAssist = getContentAssistant(sourceViewer);
        if (contentAssist == null)
            return false;

        IContentAssistProcessor processor =
            contentAssist.getContentAssistProcessor(IDocument.DEFAULT_CONTENT_TYPE);
        if (!(processor instanceof XtextContentAssistProcessor))
            return false;

        ((XtextContentAssistProcessor) processor)
            .setCompletionProposalAutoActivationCharacters(charset);
        contentAssist.setAutoActivationDelay(timeout);
        contentAssist.enableAutoActivation(true);
        return true;
    }

    // ---- Рефлексия ----

    private static ContentAssistant getContentAssistant(SourceViewer sourceViewer)
    {
        if (contentAssistantField == null && !initField())
            return null;

        try
        {
            return (ContentAssistant) contentAssistantField.get(sourceViewer);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static boolean initField()
    {
        try
        {
            Field field = SourceViewer.class.getDeclaredField("fContentAssistant"); //$NON-NLS-1$
            field.setAccessible(true);
            contentAssistantField = field;
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }
}

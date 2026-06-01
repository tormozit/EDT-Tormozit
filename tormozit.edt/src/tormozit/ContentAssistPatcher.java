package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalSorter;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.xtext.ui.editor.contentassist.XtextContentAssistProcessor;

/**
 * Применяет патч к {@link SourceViewer} BSL-редактора:
 * <ul>
 *   <li>устанавливает символы-триггеры и задержку автоактивации;</li>
 *   <li>оборачивает оригинальный процессор в {@link SmartContentAssistProcessor}
 *       (фильтрация + сортировка по премии);</li>
 *   <li>устанавливает {@link NeutralSorter} через публичный
 *       {@code ContentAssistant.setSorter()} — чтобы не перебить
 *       порядок из {@link SmartContentAssistProcessor}.</li>
 * </ul>
 */
public final class ContentAssistPatcher
{
    private static Field contentAssistantField; // SourceViewer.fContentAssistant
    private static Field fSorterField;          // ContentAssistant.fSorter — для проверки идемпотентности

    private ContentAssistPatcher() {}

    // -------------------------------------------------------------------------

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

        // Разворачиваем до оригинального Xtext-процессора (если уже обёрнут)
        IContentAssistProcessor xtext = unwrap(processor);
        if (!(xtext instanceof XtextContentAssistProcessor))
            return false;

        // 1. Символы автоактивации на Xtext-процессор
        ((XtextContentAssistProcessor) xtext)
            .setCompletionProposalAutoActivationCharacters(charset);

        // 2. Оборачиваем в SmartContentAssistProcessor (идемпотентно)
        if (!(processor instanceof SmartContentAssistProcessor))
        {
            contentAssist.setContentAssistProcessor(
                new SmartContentAssistProcessor(xtext, charset),
                IDocument.DEFAULT_CONTENT_TYPE);
        }

        // 3. Параметры автоактивации
        contentAssist.setAutoActivationDelay(timeout);
        contentAssist.enableAutoActivation(true);

        // 4. Нейтрализуем встроенный sorter (идемпотентно через чтение fSorter рефлексией)
        if (!isNeutralSorterInstalled(contentAssist))
            contentAssist.setSorter(NeutralSorter.INSTANCE);

        return true;
    }

    // -------------------------------------------------------------------------

    public static ContentAssistant getContentAssistant(SourceViewer sourceViewer)
    {
        if (contentAssistantField == null && !initContentAssistantField())
            return null;
        try
        {
            return (ContentAssistant) contentAssistantField.get(sourceViewer);
        }
        catch (Exception ignored) { return null; }
    }

    /** Разворачивает цепочку SmartContentAssistProcessor до оригинального процессора */
    private static IContentAssistProcessor unwrap(IContentAssistProcessor p)
    {
        while (p instanceof SmartContentAssistProcessor)
            p = ((SmartContentAssistProcessor) p).getDelegate();
        return p;
    }

    /** Проверяет, установлен ли уже NeutralSorter, читая fSorter рефлексией */
    private static boolean isNeutralSorterInstalled(ContentAssistant ca)
    {
        Field f = getSorterField(ca);
        if (f == null) return false;
        try
        {
            return f.get(ca) instanceof NeutralSorter;
        }
        catch (Exception ignored) { return false; }
    }

    private static Field getSorterField(ContentAssistant ca)
    {
        if (fSorterField != null) return fSorterField;
        try
        {
            Field f = ContentAssistant.class.getDeclaredField("fSorter"); //$NON-NLS-1$
            f.setAccessible(true);
            fSorterField = f;
        }
        catch (Exception ignored) {}
        return fSorterField;
    }

    private static boolean initContentAssistantField()
    {
        try
        {
            Field f = SourceViewer.class.getDeclaredField("fContentAssistant"); //$NON-NLS-1$
            f.setAccessible(true);
            contentAssistantField = f;
            return true;
        }
        catch (Exception ignored) { return false; }
    }

    // -------------------------------------------------------------------------
    // Нейтральный sorter: сохраняет порядок из SmartContentAssistProcessor
    // -------------------------------------------------------------------------

    /**
     * Реализует {@link ICompletionProposalSorter} — интерфейс,
     * который принимает {@code ContentAssistant.setSorter()}.
     * Возвращает 0 для любой пары: stable sort сохранит наш порядок.
     */
    static final class NeutralSorter implements ICompletionProposalSorter
    {
        static final NeutralSorter INSTANCE = new NeutralSorter();
        private NeutralSorter() {}

        @Override
        public int compare(ICompletionProposal p1, ICompletionProposal p2)
        {
            return 0;
        }
    }
}

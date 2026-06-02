package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.xtext.ui.editor.contentassist.XtextContentAssistProcessor;

public final class ContentAssistPatcher
{
    private static Field contentAssistantField;
    private static Field fProcessorsField;

    private ContentAssistPatcher() {}

    public static boolean applyPatch(SourceViewer sourceViewer, int timeout, String charset)
    {
        ContentAssistant contentAssist = getContentAssistant(sourceViewer);
        if (contentAssist == null) return false;

        IContentAssistProcessor current =
            contentAssist.getContentAssistProcessor(IDocument.DEFAULT_CONTENT_TYPE);
        if (current == null) return false;

        IContentAssistProcessor xtext = unwrap(current);
        if (!(xtext instanceof XtextContentAssistProcessor)) return false;

        ((XtextContentAssistProcessor) xtext)
            .setCompletionProposalAutoActivationCharacters(charset);

        SmartContentAssistProcessor wrapper;
        if (current instanceof SmartContentAssistProcessor) {
            wrapper = (SmartContentAssistProcessor) current;
        } else {
            wrapper = new SmartContentAssistProcessor(xtext, charset);
            contentAssist.setContentAssistProcessor(wrapper, IDocument.DEFAULT_CONTENT_TYPE);
            forceReplaceProcessor(contentAssist, IDocument.DEFAULT_CONTENT_TYPE, wrapper);
        }

        contentAssist.setAutoActivationDelay(timeout);
        contentAssist.enableAutoActivation(true);

        // Нейтрализуем штатный sorter — наш сортирует сам
        contentAssist.setSorter(new SmartCodeProposalSorter());

        // Перезапрос + подавление штатной раскраски
        ContentAssistSessionReloader.install(sourceViewer, contentAssist, wrapper);

        return true;
    }

    public static ContentAssistant getContentAssistant(SourceViewer sourceViewer)
    {
        if (contentAssistantField == null && !initContentAssistantField())
            return null;
        try {
            return (ContentAssistant) contentAssistantField.get(sourceViewer);
        } catch (Exception ignored) { return null; }
    }

    private static IContentAssistProcessor unwrap(IContentAssistProcessor p)
    {
        while (p instanceof SmartContentAssistProcessor)
            p = ((SmartContentAssistProcessor) p).getDelegate();
        return p;
    }

    private static void forceReplaceProcessor(ContentAssistant ca,
                                              String contentType,
                                              IContentAssistProcessor wrapper)
    {
        try {
            if (fProcessorsField == null) {
                fProcessorsField = ContentAssistant.class.getDeclaredField("fProcessors"); //$NON-NLS-1$
                fProcessorsField.setAccessible(true);
            }
            Object map = fProcessorsField.get(ca);
            if (!(map instanceof java.util.Map)) return;

            Object value = ((java.util.Map<?, ?>) map).get(contentType);
            if (value instanceof java.util.Set) {
                java.util.Set<?> set = (java.util.Set<?>) value;
                set.clear();
                ((java.util.Set) set).add(wrapper);
            } else {
                ((java.util.Map) map).put(contentType, wrapper);
            }
        } catch (Exception e) {
            Global.log("ContentAssistPatcher: forceReplaceProcessor failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static boolean initContentAssistantField()
    {
        try {
            Field f = SourceViewer.class.getDeclaredField("fContentAssistant"); //$NON-NLS-1$
            f.setAccessible(true);
            contentAssistantField = f;
            return true;
        } catch (Exception ignored) { return false; }
    }
}
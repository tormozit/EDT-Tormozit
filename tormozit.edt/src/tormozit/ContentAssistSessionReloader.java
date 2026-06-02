package tormozit;

import java.lang.reflect.Field;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Widget;

public final class ContentAssistSessionReloader
{
    private static final String DATA_KEY = "ContentAssistSessionReloader.installed"; //$NON-NLS-1$

    private final ContentAssistant assistant;
    private final SmartContentAssistProcessor processor;
    private volatile boolean sessionActive = false;
    private String lastFilter = "";

    public static void install(SourceViewer viewer, ContentAssistant assistant,
                               SmartContentAssistProcessor processor)
    {
        Widget w = viewer.getTextWidget();
        if (!(w instanceof Control)) return;
        Control control = (Control) w;
        if (control.getData(DATA_KEY) != null) return;

        new ContentAssistSessionReloader(viewer, assistant, processor);
        control.setData(DATA_KEY, Boolean.TRUE);
    }

    private ContentAssistSessionReloader(SourceViewer viewer, ContentAssistant assistant,
                                         SmartContentAssistProcessor processor)
    {
        this.assistant = assistant;
        this.processor = processor;

        assistant.addCompletionListener(new ICompletionListener() {
            @Override public void assistSessionStarted(ContentAssistEvent event) {
                sessionActive = true;
                lastFilter = "";
            }
            @Override public void assistSessionEnded(ContentAssistEvent event) {
                sessionActive = false;
                processor.invalidateCache();
            }
            @Override public void selectionChanged(
                org.eclipse.jface.text.contentassist.ICompletionProposal proposal,
                boolean smartToggle) {}
        });

        ((Control) viewer.getTextWidget()).addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e)
            {
                if (!sessionActive) return;
                if (!isFilterAction(e)) return;

                String filter = computeFilter(viewer);
                
                // Если фильтр стал пустым — сбрасываем кэш и штатный фильтр ДО перезапроса
                if (filter.isEmpty()) {
                    processor.invalidateCache();
                    SmartFilterTracker.setCurrentFilter("");
                    ContentAssistPopupHacker.resetFilter(assistant);
                    try {
                        assistant.showPossibleCompletions();
                    } catch (Exception ignored) {}
                    lastFilter = "";
                    return;
                }

                SmartFilterTracker.setCurrentFilter(filter);

                if (filter.equals(lastFilter)) return;
                lastFilter = filter;

                try {
                    ContentAssistPopupHacker.resetFilter(assistant);
                    assistant.showPossibleCompletions();
                } catch (Exception ignored) {}
            }
        });
    }

    private static String computeFilter(SourceViewer viewer)
    {
        try
        {
            IDocument doc = viewer.getDocument();
            int offset = viewer.getTextWidget().getCaretOffset();
            if (doc == null || offset <= 0) return "";
            int start = offset;
            while (start > 0)
            {
                char c = doc.getChar(start - 1);
                if (!(Character.isLetterOrDigit(c) || c == '_')) break;
                start--;
            }
            return (start < offset) ? doc.get(start, offset - start) : "";
        }
        catch (Exception e) { return ""; }
    }

    private static boolean isFilterAction(KeyEvent e)
    {
        return Character.isLetterOrDigit(e.character)
            || e.character == '_'
            || e.character == ' '
            || e.keyCode == SWT.BS
            || e.keyCode == SWT.DEL;
    }
    
    /**
     * Сбрасывает внутренний фильтр CompletionProposalPopup, чтобы Eclipse
     * не накладывала свою голубую подсветку поверх нашей.
     */
    public final class ContentAssistPopupHacker
    {
        private static Field popupField;          // ContentAssistant.fProposalPopup
        private static Field filterTextField;     // CompletionProposalPopup.fFilterText

        private ContentAssistPopupHacker() {}

        /**
         * Сбрасывает штатный фильтр popup в пустую строку.
         * Вызывать после showPossibleCompletions() — тогда Eclipse не найдёт
         * совпадений для подсветки, а наша подсветка из SmartCompletionProposal
         * останется единственной.
         */
        public static void resetFilter(ContentAssistant assistant)
        {
            try
            {
                if (popupField == null)
                {
                    popupField = ContentAssistant.class.getDeclaredField("fProposalPopup"); //$NON-NLS-1$
                    popupField.setAccessible(true);
                }
                Object popup = popupField.get(assistant);
                if (popup == null) return;

                if (filterTextField == null)
                {
                    // Ищем поле с фильтром в CompletionProposalPopup
                    Class<?> popupClass = popup.getClass();
                    for (String name : new String[]{"fFilterText", "fMessageText", "filterText"})
                    {
                        try
                        {
                            Field f = popupClass.getDeclaredField(name);
                            f.setAccessible(true);
                            filterTextField = f;
                            break;
                        }
                        catch (NoSuchFieldException ignored) {}
                    }
                }
                if (filterTextField == null) return;

                // Сбрасываем фильтр — Eclipse не найдёт совпадений для подсветки
                filterTextField.set(popup, ""); //$NON-NLS-1$

                // Также можно сбросить fComputedProposals, чтобы forceRecompute
                // Но обычно достаточно сброса фильтра
            }
            catch (Exception e)
            {
                Global.log("ContentAssistPopupHacker: " + e.getMessage()); //$NON-NLS-1$
            }
        }
    }
}
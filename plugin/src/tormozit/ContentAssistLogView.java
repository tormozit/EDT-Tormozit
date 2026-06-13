package tormozit;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.part.ViewPart;

/**
 * Общий отладочный журнал плагина (content assist, установщик и др.).
 */
public final class ContentAssistLogView extends ViewPart
{
    private StyledText logText;
    private final java.util.function.Consumer<String> logListener = this::onLogLine;
    private String findText = ""; //$NON-NLS-1$
    private int findPos = -1;
    private int findGeneration;

    @Override
    public void createPartControl(Composite parent)
    {
        logText = new StyledText(parent,
            SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        logText.setEditable(false);
        logText.setText(ComfortSettings.isDebugLogEnabled()
            ? ContentAssistLog.getFullText()
            : ""); //$NON-NLS-1$
        if (logText.getLineCount() > 0 && ComfortSettings.isLogAutoscroll())
            scrollToBottom();

        ContentAssistLog.addListener(logListener);
        attachFindKeyListener();

        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        Action autoScrollAction = new Action("Автопрокрутка", Action.AS_CHECK_BOX) { //$NON-NLS-1$
            @Override
            public void run()
            {
                ComfortSettings.setLogAutoscroll(isChecked());
                if (isChecked())
                    scrollToBottom();
            }
        };
        autoScrollAction.setToolTipText(
            "Автоматически прокручивать журнал к последней строке при появлении новых записей" //$NON-NLS-1$
                + Global.pluginSignForTooltip());
        autoScrollAction.setChecked(ComfortSettings.isLogAutoscroll());
        toolbar.add(autoScrollAction);
        toolbar.add(new Action("Найти") { //$NON-NLS-1$
            @Override
            public void run()
            {
                promptFind();
            }
        });
        toolbar.add(new Action("Очистить") { //$NON-NLS-1$
            @Override
            public void run()
            {
                ContentAssistLog.clear();
                if (logText != null && !logText.isDisposed())
                {
                    logText.setText(""); //$NON-NLS-1$
                }
            }
        });
        toolbar.update(true);
    }

    @Override
    public void setFocus()
    {
        if (logText != null && !logText.isDisposed())
            logText.setFocus();
    }

    @Override
    public void dispose()
    {
        ContentAssistLog.removeListener(logListener);
        super.dispose();
    }

    private void attachFindKeyListener()
    {
        Listener keyListener = this::onLogKeyDown;
        logText.addListener(SWT.KeyDown, keyListener);
        logText.addDisposeListener(e -> logText.removeListener(SWT.KeyDown, keyListener));
    }

    private void onLogKeyDown(Event event)
    {
        if ((event.stateMask & SWT.CTRL) != 0 && (event.keyCode == 'f' || event.keyCode == 'F'))
        {
            event.doit = false;
            promptFind();
            return;
        }
        if (event.keyCode == SWT.F3)
        {
            event.doit = false;
            findNext((event.stateMask & SWT.SHIFT) == 0);
        }
    }

    private void promptFind()
    {
        if (logText == null || logText.isDisposed())
            return;
        InputDialog dialog = new InputDialog(logText.getShell(),
            "Поиск в журнале", //$NON-NLS-1$
            "Текст для поиска" + Global.pluginSignForTooltip(), //$NON-NLS-1$
            findText,
            null);
        if (dialog.open() != InputDialog.OK)
            return;
        String value = dialog.getValue();
        if (value == null || value.isBlank())
            return;
        findText = value.trim();
        findGeneration++;
        findPos = -1;
        findNext(true);
    }

    private void findNext(boolean forward)
    {
        if (logText == null || logText.isDisposed())
            return;
        if (findText == null || findText.isBlank())
        {
            promptFind();
            return;
        }

        String haystack = logText.getText();
        if (haystack.isEmpty())
            return;

        String needle = findText.toLowerCase();
        String hayLower = haystack.toLowerCase();
        int generation = findGeneration;
        int start = forward ? findPos + 1 : findPos - 1;
        int idx = forward
            ? hayLower.indexOf(needle, Math.max(0, start))
            : hayLower.lastIndexOf(needle, start < 0 ? hayLower.length() : start);

        if (idx < 0)
            idx = forward ? hayLower.indexOf(needle, 0) : hayLower.lastIndexOf(needle);

        if (idx < 0 || generation != findGeneration)
            return;

        findPos = idx;
        logText.setSelection(idx, findText.length());
        logText.showSelection();
        highlightFindMatch(idx, findText.length());
    }

    private void highlightFindMatch(int start, int length)
    {
        Display display = logText.getDisplay();
        StyleRange range = new StyleRange();
        range.start = start;
        range.length = length;
        range.background = display.getSystemColor(SWT.COLOR_INFO_BACKGROUND);
        range.foreground = display.getSystemColor(SWT.COLOR_INFO_FOREGROUND);
        logText.setStyleRange(range);
    }

    private void onLogLine(String line)
    {
        if (logText == null || logText.isDisposed())
            return;
        if (line == null)
        {
            logText.setText(""); //$NON-NLS-1$
            return;
        }
        if (!ComfortSettings.isDebugLogEnabled())
            return;

        String existing = logText.getText();
        String next = existing.isEmpty() ? line : existing + "\n" + line; //$NON-NLS-1$
        logText.setText(next);
        applyHighlight(line, existing.length() + (existing.isEmpty() ? 0 : 1));
        if (ComfortSettings.isLogAutoscroll())
            scrollToBottom();
    }

    private void scrollToBottom()
    {
        if (logText == null || logText.isDisposed() || logText.getLineCount() <= 0)
            return;
        logText.setTopIndex(logText.getLineCount() - 1);
    }

    private void applyHighlight(String line, int lineOffset)
    {
        Display display = logText.getDisplay();
        Color keyColor = display.getSystemColor(SWT.COLOR_DARK_BLUE);
        highlightToken(line, lineOffset, "filterTrace", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "filter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "docFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "trackerFilter=\"", keyColor); //$NON-NLS-1$
        highlightToken(line, lineOffset, "MISMATCH", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_RED));
        highlightToken(line, lineOffset, "ERROR", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_RED));
        highlightToken(line, lineOffset, "[install]", //$NON-NLS-1$
            display.getSystemColor(SWT.COLOR_DARK_BLUE));
    }

    private void highlightToken(String line, int lineOffset, String token, Color color)
    {
        int idx = line.indexOf(token);
        if (idx < 0)
            return;
        StyleRange range = new StyleRange();
        range.start = lineOffset + idx;
        range.length = token.length();
        range.foreground = color;
        range.fontStyle = SWT.BOLD;
        logText.setStyleRange(range);
    }
}

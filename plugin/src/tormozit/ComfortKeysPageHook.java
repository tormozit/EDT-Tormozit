package tormozit;

import java.util.WeakHashMap;

import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * Подключает улучшения страницы «Клавиши» при любом способе открытия
 * (Общие → Клавиши, Параметры → Клавиши, ссылка из «Комфорт»).
 */
public final class ComfortKeysPageHook implements IStartup
{
    private static final int MAX_ATTEMPTS = 30;

    private static final int RETRY_MS = 100;

    private static final WeakHashMap<Shell, Boolean> pendingApplies =
            new WeakHashMap<>();

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> install(display));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (shell.isDisposed())
                return;
            if (!isPreferencesShell(shell))
                return;
            scheduleTryApplyOnce(display, shell);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static boolean isPreferencesShell(Shell shell)
    {
        if (findPreferenceDialog(shell) != null)
            return true;

        String title = shell.getText();
        if (title == null)
            return false;
        String lower = title.toLowerCase();
        return lower.contains("preferences") //$NON-NLS-1$
                || lower.contains("параметр") //$NON-NLS-1$
                || lower.contains("настройк"); //$NON-NLS-1$
    }

    private static void scheduleTryApply(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || attempt >= MAX_ATTEMPTS)
        {
            pendingApplies.remove(shell);
            return;
        }

        PreferenceDialog dialog = findPreferenceDialog(shell);
        if (dialog != null)
        {
            Object page = dialog.getSelectedPage();
            if (page instanceof IPreferencePage preferencePage
                    && ComfortKeysPreferences.isKeysPreferencePage(preferencePage))
            {
                if (ComfortKeysPreferences.isLocalConflictUiInstalled(preferencePage))
                {
                    pendingApplies.remove(shell);
                    return;
                }
                ComfortKeysPreferences.tryApplyEnhancements(preferencePage, null);
                if (ComfortKeysPreferences.isLocalConflictUiInstalled(preferencePage))
                {
                    pendingApplies.remove(shell);
                    return;
                }
            }
        }

        display.timerExec(RETRY_MS, () -> scheduleTryApply(display, shell, attempt + 1));
    }

    private static void scheduleTryApplyOnce(Display display, Shell shell)
    {
        synchronized (pendingApplies)
        {
            if (Boolean.TRUE.equals(pendingApplies.get(shell)))
                return;
            pendingApplies.put(shell, Boolean.TRUE);
        }
        scheduleTryApply(display, shell, 0);
    }

    private static PreferenceDialog findPreferenceDialog(Shell shell)
    {
        Shell current = shell;
        while (current != null && !current.isDisposed())
        {
            Object data = current.getData();
            if (data instanceof PreferenceDialog dialog)
                return dialog;
            if (current.getParent() instanceof Shell parent)
                current = parent;
            else
                break;
        }
        return null;
    }
}

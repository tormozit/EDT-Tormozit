package tormozit;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

/**
 * В диалоге «Выбор предопределенных данных» показывает полное имя объекта метаданных вместо штатного текста.
 */
public class PredefinedSelectionDialogHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.predefinedSelectionPatched"; //$NON-NLS-1$
    private static final String DIALOG_TITLE =
            "Выбор предопределенных данных"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.internal.md.ui.controls.value.PredefinedSelectionDialog"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isPredefinedSelectionShell(shell))
                return;
            schedulePatch(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isPredefinedSelectionShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && DIALOG_CLASS.equals(data.getClass().getName()))
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static void schedulePatch(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 8)
                schedulePatch(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog == null)
            dialog = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
            return false;

        Object input = Global.getField(dialog, "input"); //$NON-NLS-1$
        if (!(input instanceof EObject))
            return false;

        String fullName = GetRef.eObjectToFullName((EObject) input);
        if (fullName == null || fullName.isEmpty())
            return false;

        Global.invoke(dialog, "setMessage", fullName); //$NON-NLS-1$
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        return true;
    }
}

package tormozit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Патч диалога EDT «Выбор действия» при блокировке инфобазы: автоматический выбор
 * «Обновить динамически» по настройке из списка приложений и уведомление о переподключении ИР.
 */
public final class InfobaseSyncQuestionDialogHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.syncQuestionPatched"; //$NON-NLS-1$
    private static final String AUTO_TRIGGERED_KEY = "tormozit.syncQuestionAutoTriggered"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
        "InfobaseSynchonizationQuestionDialog"; //$NON-NLS-1$
    private static final String DIALOG_TITLE = "Выбор действия"; //$NON-NLS-1$
    private static final String BTN_DYNAMIC = "Обновить динамически"; //$NON-NLS-1$
    private static final String TOAST_MESSAGE =
        "БД обновлена динамически. Переподключить приложение ИР для обновления в нем метаданных?"; //$NON-NLS-1$

    private static final long PENDING_TIMEOUT_MS = 30L * 60_000;

    private static final AtomicReference<PendingDynamicUpdate> pendingAutoUpdate =
        new AtomicReference<>();

    private static volatile boolean jobListenerInstalled;

    private static final class PendingDynamicUpdate
    {
        final InfobaseReference infobase;
        final long startedMs;

        PendingDynamicUpdate(InfobaseReference infobase)
        {
            this.infobase = infobase;
            startedMs = System.currentTimeMillis();
        }

        boolean expired()
        {
            return System.currentTimeMillis() - startedMs > PENDING_TIMEOUT_MS;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        ensureJobListener();

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isSyncQuestionShell(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void ensureJobListener()
    {
        if (jobListenerInstalled)
            return;
        jobListenerInstalled = true;
        Job.getJobManager().addJobChangeListener(new IJobChangeListener()
        {
            @Override public void aboutToRun(IJobChangeEvent event) {}
            @Override public void awake(IJobChangeEvent event) {}
            @Override public void sleeping(IJobChangeEvent event) {}
            @Override public void running(IJobChangeEvent event) {}
            @Override public void scheduled(IJobChangeEvent event) {}

            @Override
            public void done(IJobChangeEvent event)
            {
                PendingDynamicUpdate pending = pendingAutoUpdate.get();
                if (pending == null)
                    return;
                if (pending.expired())
                {
                    pendingAutoUpdate.compareAndSet(pending, null);
                    return;
                }
                if (!isUpdateJob(event.getJob()))
                    return;
                IStatus result = event.getResult();
                if (result == null || !result.isOK())
                    return;
                if (!pendingAutoUpdate.compareAndSet(pending, null))
                    return;
                InfobaseReference ib = pending.infobase;
                Display.getDefault().asyncExec(() -> showReconnectToast(ib));
            }
        });
    }

    private static boolean isSyncQuestionShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && data.getClass().getName().contains(DIALOG_CLASS))
            return hasDynamicUpdateButton(shell);
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE) && hasDynamicUpdateButton(shell);
    }

    private static boolean hasDynamicUpdateButton(Shell shell)
    {
        return findButtonByText(shell, BTN_DYNAMIC) != null;
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;

        display.timerExec(attempt == 0 ? 0 : 100, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;

            Object dialog = shell.getData();
            Button btnDynamic = findButtonByText(shell, BTN_DYNAMIC);
            if (btnDynamic == null)
            {
                if (attempt < 20)
                    schedulePatchAttempt(display, shell, attempt + 1);
                return;
            }
            if (!isDialogContentReady(shell) && attempt < 15)
            {
                schedulePatchAttempt(display, shell, attempt + 1);
                return;
            }

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            patchDialog(display, shell, dialog, btnDynamic);
        });
    }

    private static void patchDialog(Display display, Shell shell, Object dialog, Button btnDynamic)
    {
        InfobaseReference infobase = resolveInfobase(dialog);
        if (infobase == null)
            Global.log("[DynamicAutoUpdate] infobase not resolved for sync question dialog"); //$NON-NLS-1$

        wrapDynamicButton(btnDynamic, infobase);

        if (infobase != null
            && IRApplication.getInstance().isDynamicAutoUpdate(infobase)
            && IRApplication.getInstance().isConnected(infobase)
            && shell.getData(AUTO_TRIGGERED_KEY) == null)
        {
            shell.setData(AUTO_TRIGGERED_KEY, Boolean.TRUE);
            final InfobaseReference ib = infobase;
            display.asyncExec(() ->
            {
                if (btnDynamic.isDisposed() || shell.isDisposed())
                    return;
                markAutoUpdatePending(ib);
                Event ev = new Event();
                ev.type = SWT.Selection;
                ev.widget = btnDynamic;
                btnDynamic.notifyListeners(SWT.Selection, ev);
            });
        }
    }

    /** Ждём, пока EDT отрисует текст ошибки и список сеансов. */
    private static boolean isDialogContentReady(Shell shell)
    {
        return findControlContainingText(shell, "Активны сеансы") != null //$NON-NLS-1$
            || findControlContainingText(shell, "активны сеансы") != null //$NON-NLS-1$
            || findControlContainingText(shell, "компьютер:") != null; //$NON-NLS-1$
    }

    private static Control findControlContainingText(Control root, String snippet)
    {
        if (root instanceof Label)
        {
            String text = ((Label) root).getText();
            if (text != null && text.contains(snippet))
                return root;
        }
        if (root instanceof org.eclipse.swt.widgets.Text)
        {
            String text = ((org.eclipse.swt.widgets.Text) root).getText();
            if (text != null && text.contains(snippet))
                return root;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Control found = findControlContainingText(child, snippet);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void wrapDynamicButton(Button btnDynamic, InfobaseReference infobase)
    {
        Listener[] original = btnDynamic.getListeners(SWT.Selection);
        for (Listener l : original)
            btnDynamic.removeListener(SWT.Selection, l);

        btnDynamic.addListener(SWT.Selection, event ->
        {
            if (infobase != null && shellMarkedAutoTriggered(btnDynamic.getShell()))
                markAutoUpdatePending(infobase);
            for (Listener l : original)
                l.handleEvent(event);
        });
    }

    private static boolean shellMarkedAutoTriggered(Shell shell)
    {
        return shell != null && shell.getData(AUTO_TRIGGERED_KEY) != null;
    }

    private static void markAutoUpdatePending(InfobaseReference infobase)
    {
        if (infobase == null)
            return;
        pendingAutoUpdate.set(new PendingDynamicUpdate(infobase));
        Global.log("[DynamicAutoUpdate] pending reconnect toast for infobase " //$NON-NLS-1$
            + IRApplication.extractInfobaseUuid(infobase));
    }

    private static void showReconnectToast(InfobaseReference infobase)
    {
        if (infobase == null)
            return;
        Global.log("[DynamicAutoUpdate] dynamic update finished, showing reconnect toast"); //$NON-NLS-1$
        ToastNotification.show(
            IRApplication.toastTitle(),
            TOAST_MESSAGE,
            30_000,
            () -> IRApplication.getInstance().reconnectInfobase(infobase),
            "Выполнить"); //$NON-NLS-1$
    }

    private static InfobaseReference resolveInfobase(Object dialog)
    {
        IRApplication ir = IRApplication.getInstance();
        List<InfobaseReference> connectedIr = ir.getConnectedInfobases();
        if (connectedIr.size() == 1)
            return connectedIr.get(0);

        List<InfobaseReference> allApps = listAllApplicationInfobases();
        if (allApps.isEmpty())
            return null;

        DesignerSessionPoolAccessor ssh = DesignerSessionPoolAccessor.getInstance();
        List<InfobaseReference> withDesigner = new ArrayList<>();
        for (InfobaseReference ib : allApps)
            if (ssh.isConnected(ib))
                withDesigner.add(ib);
        if (withDesigner.size() == 1)
            return withDesigner.get(0);

        String message = getDialogMessage(dialog);
        if (message != null && !message.isEmpty())
        {
            for (InfobaseReference ib : allApps)
            {
                String name = DesignerSessionPoolAccessor.nameOf(ib);
                if (name != null && !name.isEmpty() && message.contains(name))
                    return ib;
            }
        }

        if (allApps.size() == 1)
            return allApps.get(0);

        for (InfobaseReference ib : connectedIr)
        {
            String name = DesignerSessionPoolAccessor.nameOf(ib);
            if (name != null && !name.isEmpty() && message != null && message.contains(name))
                return ib;
        }
        return null;
    }

    private static List<InfobaseReference> listAllApplicationInfobases()
    {
        List<InfobaseReference> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BundleContext ctx = Global.ourContext();
        if (ctx == null)
            return result;
        ServiceReference<IApplicationManager> ref = ctx.getServiceReference(IApplicationManager.class);
        if (ref == null)
            return result;
        IApplicationManager mgr = ctx.getService(ref);
        try
        {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen())
                    continue;
                for (IApplication app : mgr.getApplications(project))
                {
                    if (!(app instanceof IInfobaseApplication))
                        continue;
                    InfobaseReference ib = ApplicationsViewHook.getInfobaseFromApplication(app);
                    if (ib == null)
                        continue;
                    String uuid = IRApplication.extractInfobaseUuid(ib);
                    if (!uuid.isEmpty() && seen.add(uuid))
                        result.add(ib);
                }
            }
        }
        catch (Exception e)
        {
            Global.log("listAllApplicationInfobases: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            ctx.ungetService(ref);
        }
        return result;
    }

    private static String getDialogMessage(Object dialog)
    {
        if (dialog == null)
            return ""; //$NON-NLS-1$
        Object presentation = Global.getField(dialog, "questionPresentation"); //$NON-NLS-1$
        if (presentation == null)
            return ""; //$NON-NLS-1$
        Object msg = Global.invoke(presentation, "getMessage"); //$NON-NLS-1$
        return msg instanceof String ? (String) msg : ""; //$NON-NLS-1$
    }

    private static Button findButtonByText(Control root, String text)
    {
        if (root instanceof Button)
        {
            Button b = (Button) root;
            if ((b.getStyle() & SWT.PUSH) != 0 && text.equals(b.getText()))
                return b;
        }
        if (root instanceof Composite)
        {
            for (Control child : ((Composite) root).getChildren())
            {
                Button found = findButtonByText(child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isUpdateJob(Job job)
    {
        if (job == null)
            return false;
        String name = job.getName();
        if (name != null)
        {
            String lower = name.toLowerCase();
            if (lower.contains("обновление") //$NON-NLS-1$
                || lower.contains("updating") //$NON-NLS-1$
                || lower.contains("update")) //$NON-NLS-1$
                return true;
        }
        String className = job.getClass().getName();
        return className.contains("DeployWithProgressOperation") //$NON-NLS-1$
            || className.contains("UpdateApplications"); //$NON-NLS-1$
    }
}

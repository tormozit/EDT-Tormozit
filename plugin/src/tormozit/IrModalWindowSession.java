package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sun.jna.platform.win32.WinDef.HWND;

/**
 * Псевдомодальный режим открытия окон ИР из EDT (аналог TurboConf
 * {@code Начать/ЗавершитьВызовВнешнегоОкнаАсинх} в режиме «Диалог»).
 * <p>
 * <b>Правила активации в псевдомодальном режиме</b> (монитор, edge-trigger + focus-guard):
 * <ol>
 * <li>Активировали окно ИР — сначала EDT, затем снова ИР ({@link WinWindowActivator#activateEdtThenWindowOnUiThread}),
 *     чтобы за модальным окном на заднем плане было видно EDT.</li>
 * <li>Активировали окно EDT — только ИР ({@link WinWindowActivator#activateWindowOnUiThread}),
 *     без повторной активации EDT. Focus-guard: пока {@code GetForegroundWindow} — EDT, redirect с throttle ~400 ms.</li>
 * </ol>
 */
final class IrModalWindowSession
{
    private static final long MONITOR_INTERVAL_MS = 50;
    /** Минимальный интервал redirect EDT→IR при смене дочернего HWND (набор текста). */
    private static final long EDT_TO_IR_REDIRECT_MIN_MS = 200;
    /** Минимальный интервал focus-guard, когда HWND EDT не меняется, а клавиатура в EDT. */
    private static final long FOCUS_GUARD_MIN_MS = 400;

    /**
     * Жёсткая блокировка EDT через {@code EnableWindow} и overlay-shell.
     * <p>
     * Отключено намеренно: реализация слишком агрессивна — на практике перестают
     * проходить клики не только в EDT, но и в окнах других приложений. Не включать,
     * пока не будет переработана (только HWND workbench, без глобальных побочных эффектов).
     * <p>
     * Псевдомодальность — «мягкий» режим: см. правила активации в javadoc класса.
     */
    private static final boolean BLOCK_EDT_INPUT = false;

    private final IRSession session;
    private final Pattern dialogTitlePattern;
    private final long waitForDialogMs;

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicBoolean dialogSeen = new AtomicBoolean(false);
    private final AtomicBoolean closeWaitLogged = new AtomicBoolean(false);

    private HWND mainHwnd;
    private HWND lastForegroundHwnd;
    private long lastEdtToIrRedirectMs;
    private WinWindowActivator.WindowState mainWindowState;
    private Thread monitorThread;

    private final List<Shell> overlayShells = new ArrayList<>();
    private final List<HWND> blockedWorkbenchHwnds = new ArrayList<>();

    private IrModalWindowSession(IRSession session, Pattern dialogTitlePattern, long waitForDialogMs)
    {
        this.session = session;
        this.dialogTitlePattern = dialogTitlePattern;
        this.waitForDialogMs = waitForDialogMs;
    }

    static IrModalWindowSession begin(IRSession session, Pattern dialogTitlePattern, long waitForDialogMs)
    {
        IrModalWindowSession modal = new IrModalWindowSession(session, dialogTitlePattern, waitForDialogMs);
        modal.doBegin();
        return modal;
    }

    private void doBegin()
    {
        if (!WinWindowActivator.isWindows() || session.pid <= 0)
        {
            IrModalWindowDebug.problem("модальный режим недоступен (не Windows или pid неизвестен)"); //$NON-NLS-1$
            return;
        }

        IrModalWindowDebug.step("begin", "pid=" + session.pid); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            ComBridge.setProperty(session.root, "Visible", true); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            IrModalWindowDebug.problem("Visible=true: " + e.getMessage()); //$NON-NLS-1$
        }

        mainHwnd = WinWindowActivator.resolveIrMainWindow(session);
        if (mainHwnd == null)
            IrModalWindowDebug.problem("главное окно ИР не найдено (MainWindowHandle)"); //$NON-NLS-1$
        else
        {
            session.cacheIrMainHwnd(mainHwnd);
            WinWindowActivator.prepareIrMainForModal(mainHwnd);
            WinWindowActivator.hideWindow(mainHwnd);
            IrModalWindowDebug.step("mainHidden", "hwnd=" + mainHwnd.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        startMonitor();
    }

    void end()
    {
        if (!ended.compareAndSet(false, true))
            return;

        active.set(false);
        IrModalWindowDebug.step("end", "pid=" + session.pid); //$NON-NLS-1$ //$NON-NLS-2$

        if (monitorThread != null)
        {
            try
            {
                monitorThread.interrupt();
                monitorThread.join(500);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            monitorThread = null;
        }

        if (mainWindowState != null)
        {
            WinWindowActivator.restoreWindowHidden(mainWindowState);
            mainWindowState = null;
        }
        else
        {
            IrModalWindowDebug.problem("end: нет сохранённого состояния главного окна"); //$NON-NLS-1$
        }

        if (BLOCK_EDT_INPUT)
            unblockEdtOnUiThread();

        try
        {
            ComBridge.setProperty(session.root, "Visible", false); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            IrModalWindowDebug.problem("Visible=false: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void startMonitor()
    {
        if (!WinWindowActivator.isWindows() || session.pid <= 0)
            return;

        monitorThread = new Thread(this::monitorLoop, "IrModalMonitor-" + session.pid); //$NON-NLS-1$
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void monitorLoop()
    {
        long waitDeadline = waitForDialogMs > 0 ? System.currentTimeMillis() + waitForDialogMs : 0;

        while (active.get() && !Thread.currentThread().isInterrupted())
        {
            try
            {
                int pid = (int) session.pid;
                List<WinWindowActivator.ProcessWindowInfo> windows =
                    WinWindowActivator.enumProcessWindows(pid);

                HWND currentMain = mainHwnd;
                if (currentMain == null)
                {
                    currentMain = WinWindowActivator.resolveIrMainWindow(session);
                    mainHwnd = currentMain;
                }

                List<HWND> dialogs = new ArrayList<>();
                for (WinWindowActivator.ProcessWindowInfo info : windows)
                {
                    if (!info.visible)
                        continue;
                    if (currentMain != null && WinWindowActivator.hwndEquals(info.hwnd, currentMain))
                        continue;
                    if (info.title.isEmpty())
                        continue;
                    if (dialogTitlePattern != null && !dialogTitlePattern.matcher(info.title).find())
                        continue;
                    dialogs.add(info.hwnd);
                }

                if (!dialogs.isEmpty())
                {
                    boolean firstSeen = !dialogSeen.get();
                    dialogSeen.set(true);
                    if (waitDeadline > 0)
                        waitDeadline = 0;

                    HWND topDialog = dialogs.get(dialogs.size() - 1);
                    HWND fg = com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();

                    if (firstSeen)
                    {
                        IrModalWindowDebug.step("dialogSeen", "диалог появился"); //$NON-NLS-1$ //$NON-NLS-2$
                        if (BLOCK_EDT_INPUT)
                            blockEdtOnUiThread();
                        if (currentMain == null)
                        {
                            currentMain = WinWindowActivator.resolveIrMainWindow(session);
                            mainHwnd = currentMain;
                            if (currentMain != null)
                                IrModalWindowDebug.step("mainResolved", "hwnd=" + currentMain.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        if (currentMain != null)
                        {
                            session.cacheIrMainHwnd(currentMain);
                            WinWindowActivator.prepareIrMainForModal(currentMain);
                            if (WinWindowActivator.isWindowVisible(currentMain))
                                WinWindowActivator.hideWindow(currentMain);
                            mainWindowState = WinWindowActivator.shrinkToTitleBar(currentMain);
                            if (mainWindowState == null)
                                IrModalWindowDebug.problem("не удалось сжать главное окно ИР"); //$NON-NLS-1$
                            else
                                IrModalWindowDebug.step("mainShrunk", "hwnd=" + currentMain.getPointer()); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        applyOpenRedirect(fg, topDialog, dialogs);
                        lastForegroundHwnd =
                            com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
                    }
                    else
                    {
                        applyFocusRedirect(fg, topDialog, dialogs);
                        lastForegroundHwnd = fg;
                    }
                }
                else if (dialogSeen.get() && closeWaitLogged.compareAndSet(false, true))
                {
                    IrModalWindowDebug.step("monitor", "диалоги закрыты — ждём возврата COM"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                else if (waitDeadline > 0 && System.currentTimeMillis() > waitDeadline)
                {
                    IrModalWindowDebug.step("monitor", "таймаут ожидания диалога"); //$NON-NLS-1$ //$NON-NLS-2$
                    waitDeadline = 0;
                }

                Thread.sleep(MONITOR_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e)
            {
                IrModalWindowDebug.problem("monitor: " + e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    /**
     * Первое появление диалога: без лишнего цикла EDT→IR, если 1С уже активировала окно.
     */
    private void applyOpenRedirect(HWND fg, HWND topDialog, List<HWND> dialogs)
    {
        if (topDialog == null)
            return;

        if (WinWindowActivator.isIrDialogForeground(fg, dialogs))
        {
            IrModalWindowDebug.step("redirectFocus", "open → skip (dialogFg)"); //$NON-NLS-1$ //$NON-NLS-2$
            WinWindowActivator.showEdtBehindIrDialog(topDialog);
        }
        else if (WinWindowActivator.isEdtForeground(fg))
        {
            IrModalWindowDebug.step("redirectFocus", "open → IR (edtWasFg)"); //$NON-NLS-1$ //$NON-NLS-2$
            WinWindowActivator.showEdtBehindIrDialog(topDialog);
            WinWindowActivator.activateWindowOnUiThread(topDialog);
        }
        else
        {
            IrModalWindowDebug.step("redirectFocus", "open → EDT → IR"); //$NON-NLS-1$ //$NON-NLS-2$
            WinWindowActivator.activateEdtThenWindowOnUiThread(topDialog);
        }
    }

    /**
     * Перенаправление клавиатурного фокуса (см. javadoc класса).
     * IR→EDT→IR — по переходу на ИР; EDT→IR — edge-trigger + focus-guard (только IR).
     */
    private void applyFocusRedirect(HWND fg, HWND topDialog, List<HWND> dialogs)
    {
        if (fg == null || topDialog == null)
            return;

        if (WinWindowActivator.isEdtForeground(fg))
        {
            if (WinWindowActivator.isIrDialogForeground(fg, dialogs))
                return;

            boolean lastEdt = WinWindowActivator.isEdtForeground(lastForegroundHwnd);
            boolean enteredEdt = !lastEdt;
            boolean edtTargetChanged = !hwndEquals(fg, lastForegroundHwnd);

            if (enteredEdt || edtTargetChanged)
            {
                String reason = enteredEdt ? "entered" : "edtHwnd"; //$NON-NLS-1$ //$NON-NLS-2$
                tryReturnFocusToIr(topDialog, "EDT → IR (" + reason + ")", EDT_TO_IR_REDIRECT_MIN_MS); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
                tryReturnFocusToIr(topDialog, "focusGuard → IR", FOCUS_GUARD_MIN_MS); //$NON-NLS-1$
            return;
        }

        boolean fgIr = WinWindowActivator.isIrDialogForeground(fg, dialogs);
        boolean lastIr = WinWindowActivator.isIrDialogForeground(lastForegroundHwnd, dialogs);

        // Прямой переход на ИР (не сразу после EDT→IR — там только activateWindowOnUiThread)
        if (fgIr && !lastIr && !hwndEquals(fg, lastForegroundHwnd))
        {
            long now = System.currentTimeMillis();
            if (now - lastEdtToIrRedirectMs < EDT_TO_IR_REDIRECT_MIN_MS)
                return;

            IrModalWindowDebug.step("redirectFocus", "IR → EDT → IR"); //$NON-NLS-1$ //$NON-NLS-2$
            WinWindowActivator.activateEdtThenWindowOnUiThread(topDialog);
        }
    }

    private void tryReturnFocusToIr(HWND topDialog, String logMessage, long minIntervalMs)
    {
        long now = System.currentTimeMillis();
        if (now - lastEdtToIrRedirectMs < minIntervalMs)
            return;

        IrModalWindowDebug.step("redirectFocus", logMessage); //$NON-NLS-1$
        WinWindowActivator.activateWindowOnUiThread(topDialog);
        lastEdtToIrRedirectMs = now;
    }

    private static boolean hwndEquals(HWND a, HWND b)
    {
        return WinWindowActivator.hwndEquals(a, b);
    }

    private void blockEdtOnUiThread()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Runnable block = () ->
        {
            overlayShells.clear();
            blockedWorkbenchHwnds.clear();

            Set<HWND> workbenchHwnds = WinWindowActivator.collectWorkbenchShells();
            for (HWND hwnd : workbenchHwnds)
            {
                WinWindowActivator.setWindowEnabled(hwnd, false);
                blockedWorkbenchHwnds.add(hwnd);
            }

            try
            {
                for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                {
                    Shell shell = window.getShell();
                    if (shell == null || shell.isDisposed())
                        continue;

                    Rectangle bounds = shell.getBounds();
                    Shell overlay = new Shell(shell, SWT.NO_TRIM | SWT.ON_TOP);
                    overlay.setBounds(bounds);
                    overlay.setAlpha(1);
                    overlay.setVisible(true);
                    overlay.setEnabled(true);
                    overlayShells.add(overlay);
                }
            }
            catch (Exception e)
            {
                IrModalWindowDebug.problem("overlay: " + e.getMessage()); //$NON-NLS-1$
            }
        };

        if (Display.getCurrent() == display)
            block.run();
        else
            display.syncExec(block);
    }

    private void unblockEdtOnUiThread()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Runnable unblock = () ->
        {
            for (Shell overlay : overlayShells)
            {
                if (overlay != null && !overlay.isDisposed())
                    overlay.dispose();
            }
            overlayShells.clear();

            for (HWND hwnd : blockedWorkbenchHwnds)
                WinWindowActivator.setWindowEnabled(hwnd, true);
            blockedWorkbenchHwnds.clear();
        };

        if (Display.getCurrent() == display)
            unblock.run();
        else
            display.syncExec(unblock);
    }
}

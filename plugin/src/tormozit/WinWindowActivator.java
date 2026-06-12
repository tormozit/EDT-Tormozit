package tormozit;

import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Shell;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

/**
 * Активация главного окна процесса Windows (User32): восстановление из свёрнутого состояния и вывод на передний план.
 */
public final class WinWindowActivator
{
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Win32: {@code (HWND)-1} */
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1));
    /** Win32: {@code (HWND)-2} */
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2));
    private static final int WS_EX_TOPMOST = 0x00000008;

    private WinWindowActivator() {}

    public static boolean isWindows()
    {
        return WINDOWS;
    }

    /**
     * Закрепляет SWT-shell поверх всех окон ОС (HWND_TOPMOST) или снимает закрепление.
     * На не-Windows платформах не выполняет действий.
     */
    public static void setShellAlwaysOnTop(Shell shell, boolean alwaysOnTop)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;

        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;

        HWND insertAfter = alwaysOnTop ? HWND_TOPMOST : HWND_NOTOPMOST;
        User32.INSTANCE.SetWindowPos(hwnd, insertAfter, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
    }

    /**
     * Снимает WS_EX_TOPMOST / HWND_TOPMOST у shell (hover с SWT.ON_TOP).
     * На не-Windows не выполняет действий.
     */
    public static void clearShellTopmost(Shell shell)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;
        HWND hwnd = hwndFromShell(shell);
        if (hwnd != null)
            clearTopmostStyle(hwnd);
    }

    /**
     * Держит popup поверх окна-владельца внутри приложения (Win32 owner), без HWND_TOPMOST.
     * Другие программы могут перекрыть инспектор; окна EDT над выбранным workbench-shell — нет.
     */
    public static void setShellAboveOwner(Shell shell, Shell ownerShell, boolean aboveOwner)
    {
        if (!WINDOWS || shell == null || shell.isDisposed())
            return;

        HWND hwnd = hwndFromShell(shell);
        if (hwnd == null)
            return;

        clearTopmostStyle(hwnd);

        HWND ownerHwnd = null;
        if (aboveOwner && ownerShell != null && !ownerShell.isDisposed())
            ownerHwnd = hwndFromShell(ownerShell);

        Pointer ownerPtr = ownerHwnd != null ? ownerHwnd.getPointer() : Pointer.NULL;
        User32.INSTANCE.SetWindowLongPtr(hwnd, WinUser.GWL_HWNDPARENT, ownerPtr);

        if (aboveOwner && ownerHwnd != null)
        {
            User32.INSTANCE.SetWindowPos(hwnd, new HWND(), 0, 0, 0, 0,
                WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
        }
    }

    private static void clearTopmostStyle(HWND hwnd)
    {
        int exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        if ((exStyle & WS_EX_TOPMOST) != 0)
        {
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle & ~WS_EX_TOPMOST);
        }
        User32.INSTANCE.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0,
            WinUser.SWP_NOMOVE | WinUser.SWP_NOSIZE | WinUser.SWP_NOACTIVATE);
    }

    private static HWND hwndFromShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Object handle = Global.getField(shell, "handle"); //$NON-NLS-1$
        if (!(handle instanceof Number number))
            return null;
        long hwndVal = number.longValue();
        if (hwndVal == 0)
            return null;
        return new HWND(Pointer.createConstant(hwndVal));
    }

    /**
     * Находит крупнейшее видимое top-level окно процесса и активирует его.
     *
     * @return {@code true}, если окно найдено и обработано
     */
    public static boolean activateMainWindow(long pid)
    {
        if (!WINDOWS || pid <= 0)
            return false;

        HWND hwnd = findMainWindow((int) pid);
        if (hwnd == null)
        {
            Global.log("WinWindowActivator: окно не найдено для PID=" + pid); //$NON-NLS-1$
            return false;
        }

        showAndActivate(hwnd);
        Global.log("WinWindowActivator: активировано окно PID=" + pid); //$NON-NLS-1$
        return true;
    }

    /**
     * Ожидает появления top-level окна процесса с заголовком, подходящим под {@code titlePattern}.
     *
     * @return {@code true}, если окно найдено и активировано
     */
    public static boolean waitForWindowTitle(long pid, Pattern titlePattern, long timeoutMs)
    {
        if (!WINDOWS || pid <= 0 || titlePattern == null || timeoutMs <= 0)
            return false;

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            HWND hwnd = findWindowByTitle((int) pid, titlePattern);
            if (hwnd != null)
            {
                showAndActivate(hwnd);
                return true;
            }
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static HWND findMainWindow(int pid)
    {
        final HWND[] best = { null };
        final int[] bestArea = { 0 };

        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
//            if (!User32.INSTANCE.IsWindowVisible(hwnd))
//                return true;

//            HWND owner = User32.INSTANCE.GetWindow(hwnd, new DWORD(WinUser.GW_OWNER));
//            if (owner != null && owner.getPointer() != null && !Pointer.NULL.equals(owner.getPointer()))
//                return true;

            char[] title = new char[512];
            if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
                return true;

            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
            if (windowPid.getValue() != pid)
                return true;

            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(hwnd, rect))
                return true;

            int area = Math.max(0, rect.right - rect.left) * Math.max(0, rect.bottom - rect.top);
            if (area > bestArea[0])
            {
                bestArea[0] = area;
                best[0] = hwnd;
            }
            return true;
        }, null);

        return best[0];
    }

    private static HWND findWindowByTitle(int pid, Pattern titlePattern)
    {
        final HWND[] found = { null };

        User32.INSTANCE.EnumWindows((hwnd, data) ->
        {
            char[] title = new char[512];
            if (User32.INSTANCE.GetWindowText(hwnd, title, title.length) == 0)
                return true;

            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, windowPid);
            if (windowPid.getValue() != pid)
                return true;

            String titleText = new String(title).trim();
            if (!titleText.isEmpty() && titlePattern.matcher(titleText).find())
            {
                found[0] = hwnd;
                return false;
            }
            return true;
        }, null);

        return found[0];
    }

    private static boolean isMinimized(HWND hwnd)
    {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        if (!User32.INSTANCE.GetWindowPlacement(hwnd, placement).booleanValue())
            return false;
        return placement.showCmd == WinUser.SW_SHOWMINIMIZED;
    }

    private static void showAndActivate(HWND hwnd)
    {
        if (isMinimized(hwnd))
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
        else
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);

        HWND fg = User32.INSTANCE.GetForegroundWindow();
        int fgThreadId = fg != null ? User32.INSTANCE.GetWindowThreadProcessId(fg, null) : 0;
        int targetThreadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, null);

        boolean attached = fgThreadId != 0 && targetThreadId != 0 && fgThreadId != targetThreadId;
        if (attached)
            User32.INSTANCE.AttachThreadInput(new DWORD(fgThreadId), new DWORD(targetThreadId), true);

        try
        {
            User32.INSTANCE.BringWindowToTop(hwnd);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        }
        finally
        {
            if (attached)
                User32.INSTANCE.AttachThreadInput(new DWORD(fgThreadId), new DWORD(targetThreadId), false);
        }
    }
}

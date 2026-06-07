package tormozit;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;

/**
 * Координатор замены UI палитры свойств: SWT renderer или собственный Comfort UI.
 */
final class PropertySheetComfortCoordinator
{
    enum UiMode
    {
        NONE,
        NATIVE_SWT,
        COMFORT_UI
    }

    private static final int QUICK_ATTEMPTS = 25;
    private static final int QUICK_DELAY_MS = 100;
    private static final int SLOW_DELAY_MS = 1_000;

    private static final Map<Object, PageState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetComfortCoordinator() {}

    static boolean ensureInstalled(Object page, SmartMatcher matcher)
    {
        if (page == null || !isPageAlive(page))
            return false;
        PageState state = STATES.computeIfAbsent(page, key -> new PageState());
        if (state.mode == UiMode.NATIVE_SWT)
            return true;
        if (state.mode == UiMode.COMFORT_UI)
        {
            if (PropertySheetComfortUi.isInstalled(page))
                return true;
            state.mode = UiMode.NONE;
        }
        // SWT renderer switch is not stable for MdPropertyPaletteComponent constructors.
        // Keep native EDT controls alive and mirror them instead.
        if (PropertySheetComfortUi.install(page, matcher))
        {
            state.mode = UiMode.COMFORT_UI;
            PropertySheetDebug.uiVerbose("uiReplacer mode=COMFORT_UI"); //$NON-NLS-1$
            return true;
        }
        if (state.attempt < QUICK_ATTEMPTS || state.attempt % 10 == 0)
            PropertySheetDebug.uiVerbose("uiReplacer WAIT install attempt=" + state.attempt); //$NON-NLS-1$
        return false;
    }

    static void scheduleRefresh(Object page, SmartMatcher matcher)
    {
        scheduleRefresh(page, matcher, false);
    }

    /** Принудительное обновление при смене источника (даже при том же фильтре). */
    static void scheduleSourceRefresh(Object page, SmartMatcher matcher)
    {
        scheduleRefresh(page, matcher, true);
    }

    private static void scheduleRefresh(Object page, SmartMatcher matcher, boolean force)
    {
        if (page == null || !isPageAlive(page))
            return;
        PageState state = STATES.computeIfAbsent(page, key -> new PageState());
        SmartMatcher next = matcher != null ? matcher : new SmartMatcher(""); //$NON-NLS-1$
        boolean installed = state.mode == UiMode.COMFORT_UI && PropertySheetComfortUi.isInstalled(page);
        if (!force && installed && state.pendingMatcher != null
                && samePattern(state.pendingMatcher.fullPattern, next.fullPattern)
                && state.pendingRunnable != null)
            return;
        state.pendingMatcher = next;
        state.attempt = 0;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        cancelTimer(state);
        state.pendingRunnable = () -> refreshNow(page);
        display.timerExec(QUICK_DELAY_MS, state.pendingRunnable);
    }

    static void cancelForPage(Object page)
    {
        if (page == null)
            return;
        PageState state = STATES.remove(page);
        if (state != null)
            cancelTimer(state);
        PropertySheetComfortUi.disposeForPage(page);
    }

    static void cancelAll()
    {
        synchronized (STATES)
        {
            for (PageState state : STATES.values())
                cancelTimer(state);
            for (Object page : STATES.keySet())
                PropertySheetComfortUi.disposeForPage(page);
            STATES.clear();
        }
    }

    static UiMode modeFor(Object page)
    {
        PageState state = STATES.get(page);
        return state != null ? state.mode : UiMode.NONE;
    }

    /** Синхронный refresh уже установленного Comfort UI (для live-sync с повторами). */
    static boolean refreshInstalled(Object page, SmartMatcher matcher)
    {
        if (page == null || !isPageAlive(page))
            return false;
        PageState state = STATES.get(page);
        if (state == null || state.mode != UiMode.COMFORT_UI || !PropertySheetComfortUi.isInstalled(page))
            return false;
        SmartMatcher active = matcher != null ? matcher : new SmartMatcher(""); //$NON-NLS-1$
        state.pendingMatcher = active;
        return PropertySheetComfortUi.refresh(page, active);
    }

    private static void refreshNow(Object page)
    {
        PageState state = STATES.get(page);
        if (state == null)
            return;
        if (!isPageAlive(page))
        {
            cancelForPage(page);
            return;
        }
        SmartMatcher matcher = state.pendingMatcher != null
                ? state.pendingMatcher : new SmartMatcher(""); //$NON-NLS-1$
        if (!ensureInstalled(page, matcher))
        {
            state.attempt++;
            retry(page, state);
            return;
        }
        if (state.mode == UiMode.NATIVE_SWT)
        {
            PropertySheetUiCoordinator.scheduleSync(page, matcher);
            state.attempt = 0;
            return;
        }
        if (state.mode == UiMode.COMFORT_UI)
        {
            PropertySheetComfortUi.refresh(page, matcher);
            state.attempt = 0;
            return;
        }
        state.attempt++;
        retry(page, state);
    }

    private static void retry(Object page, PageState state)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            cancelForPage(page);
            return;
        }
        cancelTimer(state);
        state.pendingRunnable = () -> refreshNow(page);
        int delay = state.attempt <= QUICK_ATTEMPTS ? QUICK_DELAY_MS : SLOW_DELAY_MS;
        display.timerExec(delay, state.pendingRunnable);
    }

    private static void cancelTimer(PageState state)
    {
        if (state == null || state.pendingRunnable == null)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(-1, state.pendingRunnable);
        state.pendingRunnable = null;
    }

    private static boolean isPageAlive(Object page)
    {
        if (page == null)
            return false;
        Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
        if (control instanceof org.eclipse.swt.widgets.Control)
            return !((org.eclipse.swt.widgets.Control) control).isDisposed();
        return true;
    }

    private static boolean samePattern(String a, String b)
    {
        if (a == null)
            a = ""; //$NON-NLS-1$
        if (b == null)
            b = ""; //$NON-NLS-1$
        return a.equals(b);
    }

    private static final class PageState
    {
        UiMode mode = UiMode.NONE;
        SmartMatcher pendingMatcher = new SmartMatcher(""); //$NON-NLS-1$
        Runnable pendingRunnable;
        int attempt;
    }
}

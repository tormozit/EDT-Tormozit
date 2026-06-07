package tormozit;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Синхронизация вкладки «Новая» при смене источника свойств (выделение, оповещения EDT).
 */
final class PropertySheetLiveSync
{
    private static final int DEBOUNCE_MS = 200;
    private static final int[] RETRY_DELAYS_MS = { 0, 200, 500, 1200, 2500 };

    private static final Map<Object, PageBinding> PAGES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<IWorkbenchWindow, WindowState> WINDOWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetLiveSync() {}

    static void install(Object page, IViewPart view)
    {
        if (page == null || view == null)
            return;
        IWorkbenchWindow window = view.getSite() != null ? view.getSite().getWorkbenchWindow() : null;
        if (window == null)
            return;

        PageBinding binding = PAGES.get(page);
        if (binding == null)
        {
            binding = new PageBinding(page, view, window);
            PAGES.put(page, binding);
            PropertySheetDebug.uiVerbose("liveSync INSTALL page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
        }
        else
        {
            binding.view = view;
            binding.window = window;
        }
        binding.lastSourceKey = PropertySheetSourceKey.fingerprint(page);
        ensureWindowListener(window);
    }

    static void remove(Object page)
    {
        if (page == null)
            return;
        PageBinding binding = PAGES.remove(page);
        if (binding != null)
        {
            cancelRetries(binding);
            PropertySheetDebug.uiVerbose("liveSync REMOVE page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
        }
    }

    /** Обновление при смене input страницы свойств (не при каждом focus/activate). */
    static void requestRefresh(Object page, IViewPart view)
    {
        requestRefresh(page, view, true);
    }

    private static void requestRefresh(Object page, IViewPart view, boolean force)
    {
        if (page == null)
            return;
        PageBinding binding = PAGES.get(page);
        if (binding == null && view != null)
            install(page, view);
        binding = PAGES.get(page);
        if (binding != null)
            scheduleSourceRefresh(binding, force);
    }

    private static void ensureWindowListener(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        synchronized (WINDOWS)
        {
            if (WINDOWS.containsKey(window))
                return;
            WindowState state = new WindowState(window);
            WINDOWS.put(window, state);
            window.getSelectionService().addPostSelectionListener(state.listener);
            PropertySheetDebug.uiVerbose("liveSync window listener " + PropertySheetDebug.safe(window)); //$NON-NLS-1$
        }
    }

    private static void scheduleForWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        WindowState state;
        synchronized (WINDOWS)
        {
            state = WINDOWS.get(window);
        }
        if (state == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (state.pending != null)
            display.timerExec(-1, state.pending);
        state.pending = () -> {
            state.pending = null;
            refreshPagesInWindow(window);
        };
        display.timerExec(DEBOUNCE_MS, state.pending);
    }

    private static void refreshPagesInWindow(IWorkbenchWindow window)
    {
        synchronized (PAGES)
        {
            for (Iterator<Map.Entry<Object, PageBinding>> it = PAGES.entrySet().iterator(); it.hasNext();)
            {
                Map.Entry<Object, PageBinding> entry = it.next();
                PageBinding binding = entry.getValue();
                if (binding == null || binding.window != window)
                    continue;
                if (!isBindingAlive(binding))
                {
                    cancelRetries(binding);
                    it.remove();
                    continue;
                }
                scheduleSourceRefresh(binding, false);
            }
        }
    }

    private static void scheduleSourceRefresh(PageBinding binding, boolean force)
    {
        if (binding == null || binding.page == null)
            return;
        Object page = binding.page;
        String sourceKey = PropertySheetSourceKey.fingerprint(page);
        if (PropertySheetComfortUi.isComfortPushInProgress(page)
                || PropertySheetComfortUi.isComfortRefreshSuppressed(page))
        {
            PropertySheetDebug.syncVerbose("liveSync DEFER push/suppress page=" //$NON-NLS-1$
                    + PropertySheetDebug.safe(page));
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.timerExec(300, () -> scheduleSourceRefresh(binding, force));
            return;
        }
        if (!force && sourceKey.equals(binding.lastSourceKey)
                && PropertySheetComfortUi.hasRows(page))
            return;

        binding.refreshSeq++;
        int seq = binding.refreshSeq;
        cancelRetries(binding);

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        SmartMatcher matcher = currentMatcher(binding.view, page);
        binding.retryRunnable = () -> runRefreshChain(binding, 0, seq, matcher);
        display.timerExec(0, binding.retryRunnable);
        PropertySheetDebug.uiVerbose("liveSync REFRESH page=" + PropertySheetDebug.safe(page) //$NON-NLS-1$
                + " source=" + PropertySheetDebug.quote(shortKey(sourceKey)) //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(matcher.fullPattern)); //$NON-NLS-1$
    }

    private static void runRefreshChain(PageBinding binding, int attempt, int seq, SmartMatcher matcher)
    {
        if (binding == null || binding.refreshSeq != seq || !isBindingAlive(binding))
            return;
        binding.retryRunnable = null;
        if (attempt == 0)
        {
            String key = PropertySheetSourceKey.fingerprint(binding.page);
            if (!key.equals(binding.lastSourceKey))
                PropertySheetSearchSupport.prepareComfortSync(binding.page);
        }
        PropertySheetDebug.sync("liveSync refresh attempt=" + attempt //$NON-NLS-1$
                + " page=" + PropertySheetDebug.safe(binding.page)); //$NON-NLS-1$
        boolean ok = PropertySheetComfortCoordinator.refreshInstalled(binding.page, matcher);
        if (ok)
            binding.lastSourceKey = PropertySheetSourceKey.fingerprint(binding.page);
        PropertySheetDebug.syncVerbose("liveSync refresh attempt=" + attempt + " ok=" + ok); //$NON-NLS-1$ //$NON-NLS-2$
        if (ok || attempt + 1 >= RETRY_DELAYS_MS.length)
            return;
        int nextDelay = RETRY_DELAYS_MS[attempt + 1] - RETRY_DELAYS_MS[attempt];
        if (nextDelay < 0)
            nextDelay = RETRY_DELAYS_MS[attempt + 1];
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        binding.retryRunnable = () -> runRefreshChain(binding, attempt + 1, seq, matcher);
        display.timerExec(nextDelay, binding.retryRunnable);
    }

    private static void cancelRetries(PageBinding binding)
    {
        if (binding == null || binding.retryRunnable == null)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(-1, binding.retryRunnable);
        binding.retryRunnable = null;
    }

    private static SmartMatcher currentMatcher(IViewPart view, Object page)
    {
        Object searchBox = Global.getField(page, "searchBox"); //$NON-NLS-1$
        if (searchBox == null)
            searchBox = Global.invoke(page, "getSearchBox"); //$NON-NLS-1$
        SearchBoxFilterAccess input = SearchBoxFilterAccess.resolveQuiet(view, searchBox);
        String pattern = input != null ? input.readPattern() : ""; //$NON-NLS-1$
        return new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
    }

    private static boolean isBindingAlive(PageBinding binding)
    {
        if (binding == null || binding.page == null)
            return false;
        Object control = Global.invoke(binding.page, "getControl"); //$NON-NLS-1$
        if (control instanceof org.eclipse.swt.widgets.Control)
            return !((org.eclipse.swt.widgets.Control) control).isDisposed();
        return true;
    }

    private static String shortKey(String sourceKey)
    {
        if (sourceKey == null || sourceKey.isEmpty())
            return ""; //$NON-NLS-1$
        if (sourceKey.length() <= 80)
            return sourceKey;
        return sourceKey.substring(0, 77) + "..."; //$NON-NLS-1$
    }

    private static boolean isPropertySheetPart(IWorkbenchPart part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.PROPERTIES_SHEET_ID.equals(id)
                || "org.eclipse.ui.views.PropertySheet".equals(id); //$NON-NLS-1$
    }

    private static final class PageBinding
    {
        final Object page;
        IViewPart view;
        IWorkbenchWindow window;
        String lastSourceKey = ""; //$NON-NLS-1$
        int refreshSeq;
        Runnable retryRunnable;

        PageBinding(Object page, IViewPart view, IWorkbenchWindow window)
        {
            this.page = page;
            this.view = view;
            this.window = window;
        }
    }

    private static final class WindowState
    {
        final IWorkbenchWindow window;
        final ISelectionListener listener;
        Runnable pending;

        WindowState(IWorkbenchWindow window)
        {
            this.window = window;
            this.listener = new ISelectionListener()
            {
                @Override
                public void selectionChanged(IWorkbenchPart part, ISelection selection)
                {
                    if (isPropertySheetPart(part))
                        return;
                    scheduleForWindow(WindowState.this.window);
                }
            };
        }
    }
}

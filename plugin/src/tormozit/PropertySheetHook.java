package tormozit;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Умный фильтр и замена UI в панели «Свойства»
 * ({@code org.eclipse.ui.views.properties.PropertySheet} → {@code MdPropertySheetPage}).
 *
 * <ul>
 *   <li>Smart-filter по имени/значению</li>
 *   <li>Замена LWT-палитры на SWT renderer или собственный Comfort UI</li>
 *   <li>Подсветка вхождений, выделение строки, контекстное меню копирования</li>
 * </ul>
 *
 * <p>Логирование: {@code -Dtormozit.propertySheet.debug=false} — отключить;
 * {@code verbose=true} — scan/ui; {@code trace=true} — resolve/feature.
 */
public final class PropertySheetHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.propertySheetPatched"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            IWorkbench wb = PlatformUI.getWorkbench();
            if (wb == null)
                return;
            PropertySheetDebug.log("[ui] debug flags: " + PropertySheetDebug.flags()); //$NON-NLS-1$
            PropertySheetDebug.uiVerbose("earlyStartup: install window listener"); //$NON-NLS-1$
            for (IWorkbenchWindow window : wb.getWorkbenchWindows())
                hookWindow(window);
            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)
                {
                    PropertySheetComfortCoordinator.cancelAll();
                    PropertySheetUiCoordinator.cancelAll();
                }
            });
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isPropertySheetView(view))
                    schedulePatch(view, 0, false);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)   { tryFromRef(ref, false); }
            @Override public void partVisible(IWorkbenchPartReference ref)  { tryFromRef(ref, false); }
            @Override public void partActivated(IWorkbenchPartReference ref){ tryFromRef(ref, false); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                {
                    Object propertyPage = resolvePropertySheetPage((IViewPart) part);
                    PropertySheetLiveSync.remove(propertyPage);
                    PropertySheetComfortCoordinator.cancelForPage(propertyPage);
                    PropertySheetUiCoordinator.cancelForView(part);
                }
            }
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r) { tryFromRef(r, true); }

            private void tryFromRef(IWorkbenchPartReference ref, boolean inputChanged)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (isPropertySheetView(part))
                    schedulePatch((IViewPart) part, 0, inputChanged);
            }
        });
    }

    private static boolean isPropertySheetView(Object part)
    {
        if (!(part instanceof IViewPart))
            return false;
        String id = ((IViewPart) part).getViewSite().getId();
        return Global.PROPERTIES_SHEET_ID.equals(id)
                || "org.eclipse.ui.views.PropertySheet".equals(id); //$NON-NLS-1$
    }

    private static void schedulePatch(IViewPart view, int attempt)
    {
        schedulePatch(view, attempt, false);
    }

    private static void schedulePatch(IViewPart view, int attempt, boolean inputChanged)
    {
        Display display = Display.getDefault();
        int delay = attempt == 0 ? 0 : 100;
        display.timerExec(delay, () -> {
            if (!tryPatch(view, attempt, inputChanged) && attempt < 25)
                schedulePatch(view, attempt + 1, inputChanged);
            else if (attempt >= 25)
                PropertySheetDebug.problem("tryPatch GIVE UP after 25 attempts"); //$NON-NLS-1$
        });
    }

    private static boolean tryPatch(IViewPart view, int attempt)
    {
        return tryPatch(view, attempt, false);
    }

    private static boolean tryPatch(IViewPart view, int attempt, boolean inputChanged)
    {
        if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
            PropertySheetDebug.uiVerbose("tryPatch #" + attempt); //$NON-NLS-1$

        Object page = resolvePropertySheetPage(view);
        if (page == null)
        {
            if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
                PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " WAIT: page=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        Object searchBox = Global.getField(page, "searchBox"); //$NON-NLS-1$
        if (searchBox == null)
        {
            if (PropertySheetComfortUi.isInstalled(page))
            {
                PropertySheetLiveSync.install(page, view);
                return true;
            }
            if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
                PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " WAIT: searchBox=null page=" //$NON-NLS-1$ //$NON-NLS-2$
                        + page.getClass().getSimpleName());
            return false;
        }
        if (searchBox instanceof Control && ((Control) searchBox).isDisposed())
            return false;

        if (Boolean.TRUE.equals(getPatchMarker(searchBox, page)))
        {
            PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " SKIP: already patched"); //$NON-NLS-1$ //$NON-NLS-2$
            PropertySheetLiveSync.install(page, view);
            if (!PropertySheetComfortUi.isInstalled(page))
            {
                SearchBoxFilterAccess searchInput = SearchBoxFilterAccess.resolve(view, searchBox);
                String pattern = searchInput != null ? searchInput.readPattern() : ""; //$NON-NLS-1$
                scheduleUiReplacerRetries(page, new SmartMatcher(pattern != null ? pattern : "")); //$NON-NLS-1$
            }
            else if (inputChanged)
                PropertySheetLiveSync.requestRefresh(page, view);
            return true;
        }

        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (palette == null || scene == null)
        {
            if (PropertySheetDebug.isTrace() && (attempt == 0 || attempt % 5 == 0))
                PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " WAIT: palette=" //$NON-NLS-1$ //$NON-NLS-2$
                        + PropertySheetDebug.safe(palette) + " scene=" + PropertySheetDebug.safe(scene)); //$NON-NLS-1$
            return false;
        }

        SearchBoxFilterAccess searchInput = SearchBoxFilterAccess.resolve(view, searchBox);
        if (searchInput == null)
        {
            PropertySheetDebug.problem("tryPatch #" + attempt + " FAIL: searchInput=null"); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }

        String initialPattern = searchInput.readPattern();
        PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " page=" + page.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " searchBox=" + searchBox.getClass().getSimpleName() //$NON-NLS-1$
                + " initialPattern=\"" + initialPattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$

        final Object propertyPage = page;
        final Runnable[] pending = { null };
        final SearchBoxFilterAccess input = searchInput;

        Object nativeListener = Global.invoke(searchBox, "getSearchListener"); //$NON-NLS-1$

        boolean listenerOk = searchInput.attachPatternListener(view, nativeListener, explicitPattern -> {
            Display d = Display.getDefault();
            if (pending[0] != null)
                d.timerExec(-1, pending[0]);
            pending[0] = () -> {
                String pattern = explicitPattern != null ? explicitPattern : input.readPattern();
                PropertySheetDebug.uiVerbose("modify pattern=\"" + pattern + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                PropertySheetSearchSupport.apply(propertyPage, pattern);
            };
            d.timerExec(150, pending[0]);
        });

        PropertySheetSearchSupport.apply(page, initialPattern, false);
        SmartMatcher initialMatcher = new SmartMatcher(initialPattern != null ? initialPattern : ""); //$NON-NLS-1$
        scheduleUiReplacerRetries(propertyPage, initialMatcher);

        Control focusControl = searchInput.focusControl();
        if (focusControl != null)
        {
            focusControl.addDisposeListener(e -> {
                if (pending[0] != null && !focusControl.getDisplay().isDisposed())
                    focusControl.getDisplay().timerExec(-1, pending[0]);
                PropertySheetComfortCoordinator.cancelForPage(propertyPage);
                PropertySheetUiCoordinator.cancelSync(propertyPage);
            });
        }
        if (searchBox instanceof Control)
        {
            Control searchControl = (Control) searchBox;
            searchControl.addDisposeListener(e -> {
                PropertySheetComfortCoordinator.cancelForPage(propertyPage);
                PropertySheetUiCoordinator.cancelSync(propertyPage);
            });
        }

        setPatchMarker(searchBox, page, Boolean.TRUE);
        PropertySheetLiveSync.install(propertyPage, view);
        PropertySheetDebug.uiVerbose("tryPatch #" + attempt + " PATCH OK listener=" + listenerOk); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }

    private static Object resolvePropertySheetPage(IViewPart view)
    {
        Object page = Global.invoke(view, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;

        Object pageBook = Global.invoke(view, "getPageBook"); //$NON-NLS-1$
        page = Global.invoke(pageBook, "getCurrentPage"); //$NON-NLS-1$
        if (isPropertySheetPage(page))
            return page;

        return null;
    }

    private static boolean isPropertySheetPage(Object page)
    {
        if (page == null)
            return false;
        String name = page.getClass().getName();
        return name.contains("PropertySheetPage"); //$NON-NLS-1$
    }

    private static Object getPatchMarker(Object searchBox, Object page)
    {
        if (searchBox instanceof Control)
            return ((Control) searchBox).getData(PATCHED_KEY);
        Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
        if (control instanceof Control)
            return ((Control) control).getData(PATCHED_KEY);
        return null;
    }

    private static void setPatchMarker(Object searchBox, Object page, Object value)
    {
        if (searchBox instanceof Control)
            ((Control) searchBox).setData(PATCHED_KEY, value);
        Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
        if (control instanceof Control)
            ((Control) control).setData(PATCHED_KEY, value);
    }

    /** Палитра часто появляется позже searchBox — повторяем установку Comfort UI. */
    private static void scheduleUiReplacerRetries(Object page, SmartMatcher matcher)
    {
        if (page == null)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        SmartMatcher active = matcher != null ? matcher : new SmartMatcher(""); //$NON-NLS-1$
        int[] delays = { 0, 120, 300, 700, 1500, 3000 };
        for (int delay : delays)
        {
            display.timerExec(delay, () -> {
                if (page == null)
                    return;
                Object control = Global.invoke(page, "getControl"); //$NON-NLS-1$
                if (control instanceof Control && ((Control) control).isDisposed())
                    return;
                if (PropertySheetComfortUi.hasRows(page))
                    return;
                PropertySheetComfortCoordinator.scheduleRefresh(page, active);
            });
        }
    }
}

package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;

/**
 * Координатор UI-доработок палитры свойств.
 * Новые возможности — отдельные {@link PropertySheetUiFeature}, регистрация в {@link #features(Object)}.
 */
final class PropertySheetUiCoordinator
{
    private static final int MAX_SYNC_ATTEMPTS = 8;
    private static final int SYNC_DELAY_MS = 80;

    private static final Map<Object, PageSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetUiCoordinator() {}

    static void scheduleSync(Object page, SmartMatcher matcher)
    {
        if (page == null || !isPageAlive(page))
            return;
        if (PropertySheetComfortCoordinator.modeFor(page) == PropertySheetComfortCoordinator.UiMode.COMFORT_UI)
            return;
        PageSession session = SESSIONS.computeIfAbsent(page, key -> new PageSession());
        session.pendingMatcher = matcher;
        session.syncAttempt = 0;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        cancelPendingTimer(session);
        session.pendingRunnable = () -> syncNow(page);
        PropertySheetDebug.uiVerbose("scheduleSync pattern=" + PropertySheetDebug.quote(matcher.fullPattern) //$NON-NLS-1$
                + " delay=" + SYNC_DELAY_MS + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        display.timerExec(SYNC_DELAY_MS, session.pendingRunnable);
    }

    /** Остановить отложенный sync для страницы (закрытие view / dispose). */
    static void cancelSync(Object page)
    {
        if (page == null)
            return;
        PageSession session = SESSIONS.remove(page);
        if (session == null)
            return;
        cancelPendingTimer(session);
        PropertySheetDebug.uiVerbose("sync CANCEL page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
    }

    /** Закрытие workbench window — снять все отложенные sync. */
    static void cancelAll()
    {
        synchronized (SESSIONS)
        {
            for (PageSession session : SESSIONS.values())
                cancelPendingTimer(session);
            SESSIONS.clear();
        }
        PropertySheetDebug.uiVerbose("sync CANCEL ALL"); //$NON-NLS-1$
    }

    /** Закрытие PropertySheet view — снять все «висящие» таймеры. */
    static void cancelForView(Object viewPart)
    {
        if (viewPart == null)
            return;
        Object page = Global.invoke(viewPart, "getCurrentPage"); //$NON-NLS-1$
        cancelSync(page);
        Object pageBook = Global.invoke(viewPart, "getPageBook"); //$NON-NLS-1$
        Object bookPage = pageBook != null ? Global.invoke(pageBook, "getCurrentPage") : null; //$NON-NLS-1$
        if (bookPage != null && bookPage != page)
            cancelSync(bookPage);
        purgeDeadSessions();
    }

    private static void purgeDeadSessions()
    {
        synchronized (SESSIONS)
        {
            SESSIONS.entrySet().removeIf(entry -> {
                if (isPageAlive(entry.getKey()))
                    return false;
                cancelPendingTimer(entry.getValue());
                PropertySheetDebug.uiVerbose("sync PURGE dead page=" + PropertySheetDebug.safe(entry.getKey())); //$NON-NLS-1$
                return true;
            });
        }
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

    private static void cancelPendingTimer(PageSession session)
    {
        if (session == null || session.pendingRunnable == null)
            return;
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.timerExec(-1, session.pendingRunnable);
        session.pendingRunnable = null;
    }

    private static void syncNow(Object page)
    {
        PageSession session = SESSIONS.get(page);
        if (session == null)
            return;
        if (!isPageAlive(page))
        {
            PropertySheetDebug.uiVerbose("sync STOP page disposed"); //$NON-NLS-1$
            cancelSync(page);
            return;
        }
        SmartMatcher matcher = session.pendingMatcher != null
                ? session.pendingMatcher : new SmartMatcher(""); //$NON-NLS-1$
        PropertySheetDebug.uiVerbose("syncNow attempt=" + session.syncAttempt //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(matcher.fullPattern)); //$NON-NLS-1$
        PropertySheetUiContext ctx = PropertySheetUiContext.build(page, matcher);
        if (ctx == null)
        {
            if (session.syncAttempt >= MAX_SYNC_ATTEMPTS)
            {
        PropertySheetDebug.uiProblem("sync GIVE UP context=null attempt=" + session.syncAttempt); //$NON-NLS-1$
                cancelSync(page);
                return;
            }
            PropertySheetDebug.uiVerbose("sync WAIT context=null attempt=" + session.syncAttempt //$NON-NLS-1$
                    + "/" + MAX_SYNC_ATTEMPTS); //$NON-NLS-1$
            retryLater(page, session);
            return;
        }
        if (ctx.rows.isEmpty() && session.syncAttempt < MAX_SYNC_ATTEMPTS)
        {
            PropertySheetDebug.uiVerbose("sync WAIT rows=0 attempt=" + session.syncAttempt //$NON-NLS-1$
                    + "/" + MAX_SYNC_ATTEMPTS); //$NON-NLS-1$
            retryLater(page, session);
            return;
        }
        if (ctx.rows.isEmpty())
        {
            PropertySheetDebug.uiProblem("sync GIVE UP rows=0 attempt=" + session.syncAttempt); //$NON-NLS-1$
            cancelSync(page);
            return;
        }
        int expected = PropertySheetPaletteScanner.expectedLabelRows(ctx.scene);
        if (expected > ctx.rows.size())
            PropertySheetDebug.uiProblem("sync partial rows=" + ctx.rows.size() + " expected=" + expected); //$NON-NLS-1$ //$NON-NLS-2$
        applySync(ctx, session);
        session.syncAttempt = 0;
        session.lastContext = ctx;
    }

    private static void applySync(PropertySheetUiContext ctx, PageSession session)
    {
        if (session.selectedName != null || session.selectedView != null)
            restoreSelection(ctx, session);
        int expected = PropertySheetPaletteScanner.expectedLabelRows(ctx.scene);
        PropertySheetDebug.uiVerbose("sync APPLY rows=" + ctx.rows.size() //$NON-NLS-1$
                + " expected=" + expected //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(ctx.matcher.fullPattern) //$NON-NLS-1$
                + " features=" + session.features.size()); //$NON-NLS-1$
        for (PropertySheetUiFeature feature : session.features)
            feature.refresh(ctx);
        if (ctx.selectedRow() != null)
        {
            session.selectedName = ctx.selectedRow().propertyName;
            session.selectedView = ctx.selectedRow().lwtView;
        }
    }

    private static void retryLater(Object page, PageSession session)
    {
        session.syncAttempt++;
        if (!isPageAlive(page) || session.syncAttempt > MAX_SYNC_ATTEMPTS)
        {
            cancelSync(page);
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            cancelSync(page);
            return;
        }
        cancelPendingTimer(session);
        session.pendingRunnable = () -> syncNow(page);
        display.timerExec(SYNC_DELAY_MS, session.pendingRunnable);
    }

    private static void restoreSelection(PropertySheetUiContext ctx, PageSession session)
    {
        if (session.selectedName == null && session.selectedView == null)
            return;
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (session.selectedView != null && session.selectedView == row.lwtView)
            {
                ctx.setSelectedRow(row);
                PropertySheetDebug.uiVerbose("restoreSelection byView " //$NON-NLS-1$
                        + PropertySheetDebug.safe(session.selectedView));
                return;
            }
            if (session.selectedName != null && session.selectedName.equals(row.propertyName))
            {
                ctx.setSelectedRow(row);
                PropertySheetDebug.uiVerbose("restoreSelection " + PropertySheetDebug.quote(session.selectedName)); //$NON-NLS-1$
                return;
            }
        }
        PropertySheetDebug.uiVerbose("restoreSelection MISS " + PropertySheetDebug.quote(session.selectedName)); //$NON-NLS-1$
    }

    /** Точка расширения: добавить feature для конкретной страницы или глобально. */
    static List<PropertySheetUiFeature> features(Object page)
    {
        PageSession session = SESSIONS.get(page);
        return session != null ? session.features : defaultFeatures();
    }

    static List<PropertySheetUiFeature> defaultFeatures()
    {
        List<PropertySheetUiFeature> list = new ArrayList<>();
        list.add(new PropertySheetMatchHighlightFeature());
        list.add(new PropertySheetNameCopyFeature());
        list.add(new PropertySheetRowSelectionFeature());
        return list;
    }

    static void rememberSelection(Object page, PropertySheetPaletteRow row)
    {
        if (page == null || row == null)
            return;
        PageSession session = SESSIONS.get(page);
        if (session != null)
        {
            session.selectedName = row.propertyName;
            session.selectedView = row.lwtView;
        }
    }

    static PropertySheetUiContext lastContext(Object page)
    {
        if (page == null)
            return null;
        PageSession session = SESSIONS.get(page);
        return session != null ? session.lastContext : null;
    }

    static Object pageForControl(org.eclipse.swt.widgets.Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        synchronized (SESSIONS)
        {
            for (Map.Entry<Object, PageSession> entry : SESSIONS.entrySet())
            {
                PropertySheetUiContext ctx = entry.getValue().lastContext;
                if (ctx == null)
                    continue;
                for (PropertySheetPaletteRow row : ctx.rows)
                {
                    org.eclipse.swt.widgets.Control target =
                            PropertySheetRowSelectionFeature.interactionTarget(row);
                    if (target != null && !target.isDisposed()
                            && (control == target
                                    || (target instanceof org.eclipse.swt.widgets.Composite
                                            && isDescendant(control,
                                                    (org.eclipse.swt.widgets.Composite) target))
                                    || (control instanceof org.eclipse.swt.widgets.Composite
                                            && isDescendant(target,
                                                    (org.eclipse.swt.widgets.Composite) control))))
                        return entry.getKey();
                }
                org.eclipse.swt.widgets.Composite root = PropertySheetUiContext.findPaletteRoot(entry.getKey());
                if (root != null && !root.isDisposed() && isDescendant(control, root))
                    return entry.getKey();
                if (!ctx.rows.isEmpty())
                {
                    org.eclipse.swt.widgets.Control target =
                            PropertySheetRowSelectionFeature.interactionTarget(ctx.rows.get(0));
                    if (target != null && !target.isDisposed() && target.getShell() == control.getShell())
                        return entry.getKey();
                }
            }
        }
        return null;
    }

    static void handleRowClick(Object page, PropertySheetPaletteRow row)
    {
        if (page == null || row == null)
            return;
        PageSession session = SESSIONS.get(page);
        if (session == null || session.rowSelection == null)
            return;
        PropertySheetUiContext ctx = session.lastContext;
        if (ctx == null)
            return;
        session.rowSelection.selectRow(ctx, row);
    }

    static void showRowContextMenu(Object page, PropertySheetPaletteRow row,
            org.eclipse.swt.widgets.Control widget, org.eclipse.swt.graphics.Point displayPoint)
    {
        if (page == null || row == null || widget == null || widget.isDisposed())
            return;
        PageSession session = SESSIONS.get(page);
        if (session == null || session.nameCopy == null)
            return;
        session.nameCopy.showContextMenu(row, widget, displayPoint);
    }

    private static boolean isDescendant(org.eclipse.swt.widgets.Control control,
            org.eclipse.swt.widgets.Composite ancestor)
    {
        if (control == null || ancestor == null || control.isDisposed() || ancestor.isDisposed())
            return false;
        if (control == ancestor)
            return true;
        for (org.eclipse.swt.widgets.Composite p = control.getParent();
                p != null && !p.isDisposed(); p = p.getParent())
        {
            if (p == ancestor)
                return true;
        }
        return false;
    }

    private static final class PageSession
    {
        final PropertySheetMatchHighlightFeature matchHighlight = new PropertySheetMatchHighlightFeature();
        final PropertySheetNameCopyFeature nameCopy = new PropertySheetNameCopyFeature();
        final PropertySheetRowSelectionFeature rowSelection = new PropertySheetRowSelectionFeature();
        final List<PropertySheetUiFeature> features = featuresOf(
                matchHighlight, nameCopy, rowSelection);
        SmartMatcher pendingMatcher = new SmartMatcher(""); //$NON-NLS-1$
        Runnable pendingRunnable;
        String selectedName;
        Object selectedView;
        int syncAttempt;
        PropertySheetUiContext lastContext;
    }

    private static List<PropertySheetUiFeature> featuresOf(PropertySheetUiFeature... items)
    {
        List<PropertySheetUiFeature> list = new ArrayList<>();
        Collections.addAll(list, items);
        return list;
    }
}

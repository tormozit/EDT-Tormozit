package tormozit;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

/**
 * Глобальный display-фильтр для кликов по LWT-палитре свойств.
 * SwtLightComposite не всегда доставляет MouseDown/MenuDetect в наш listener на host.
 */
final class PropertySheetMouseBridge
{
    private static volatile boolean installed;

    private PropertySheetMouseBridge() {}

    static void ensureInstalled(Display display)
    {
        if (display == null || display.isDisposed() || installed)
            return;
        display.addFilter(SWT.MouseDown, PropertySheetMouseBridge::handleMouseDown);
        display.addFilter(SWT.MenuDetect, PropertySheetMouseBridge::handleMenuDetect);
        installed = true;
        PropertySheetDebug.uiVerbose("mouseBridge installed"); //$NON-NLS-1$
    }

    private static void handleMouseDown(Event event)
    {
        if (event.button != 1 || !(event.widget instanceof Control))
            return;
        Control widget = (Control) event.widget;
        if (widget.isDisposed() || PropertySheetUiContext.isFilterAreaControl(widget))
            return;

        Object page = PropertySheetUiCoordinator.pageForControl(widget);
        if (page == null)
            return;

        Point display = widget.toDisplay(event.x, event.y);
        PropertySheetPaletteRow row = hitTestRendererLightLabel(page, widget, display);
        if (row == null)
            row = hitTestRow(page, display);
        if (row == null)
        {
            PropertySheetDebug.feature("mouseDown miss at=(" + display.x + "," + display.y + ") " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "widget=" + PropertySheetDebug.controlBrief(widget)); //$NON-NLS-1$
            return;
        }

        PropertySheetDebug.feature("mouseDown hit " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
        PropertySheetUiCoordinator.handleRowClick(page, row);
    }

    private static void handleMenuDetect(Event event)
    {
        if (!(event.widget instanceof Control))
            return;
        Control widget = (Control) event.widget;
        if (widget.isDisposed() || PropertySheetUiContext.isFilterAreaControl(widget))
            return;

        Object page = PropertySheetUiCoordinator.pageForControl(widget);
        if (page == null)
            return;

        Point display = event.x > 0 || event.y > 0
                ? new Point(event.x, event.y) : widget.getDisplay().getCursorLocation();
        PropertySheetPaletteRow row = hitTestRendererLightLabel(page, widget, display);
        if (row == null)
            row = hitTestRow(page, display);
        if (row == null)
        {
            PropertySheetDebug.feature("menuDetect miss widget=" + PropertySheetDebug.controlBrief(widget)); //$NON-NLS-1$
            return;
        }

        event.doit = false;
        PropertySheetDebug.feature("menuDetect hit " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
        PropertySheetUiCoordinator.showRowContextMenu(page, row, widget, display);
    }

    private static PropertySheetPaletteRow hitTestRendererLightLabel(Object page, Control widget, Point display)
    {
        if (page == null || widget == null || widget.isDisposed() || display == null)
            return null;
        PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
        if (ctx == null || ctx.scene == null)
            return null;
        Object renderer = Global.invoke(ctx.scene, "getRenderer"); //$NON-NLS-1$
        Object mapObj = Global.getField(renderer, "viewModelToView"); //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return null;
        List<Map.Entry<?, ?>> labelEntries = labelEntries((Map<?, ?>) mapObj);
        if (labelEntries.isEmpty())
            return null;

        int from = 0;
        int to = labelEntries.size();
        int[] slice = labelEntrySliceForWidget(page, widget, labelEntries.size());
        boolean scopedToWidget = slice != null;
        if (slice != null)
        {
            from = slice[0];
            to = slice[1];
        }

        Point local = widget.toControl(display);
        if (local.x < 0 || local.y < 0 || local.x >= widget.getSize().x || local.y >= widget.getSize().y)
            return null;
        if (local.x > widget.getSize().x / 2)
            return null;

        PropertySheetPaletteRow best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = from; i < to; i++)
        {
            Map.Entry<?, ?> entry = labelEntries.get(i);
            Object vm = entry.getKey();
            String name = textOfViewModel(vm);
            if (name.isEmpty())
                continue;
            Object view = entry.getValue();
            Object light = lightFromView(view);
            Object boundsObj = light != null ? Global.invoke(light, "getBounds") : null; //$NON-NLS-1$
            if (!(boundsObj instanceof Rectangle))
                continue;
            Rectangle bounds = (Rectangle) boundsObj;
            Point lightDisplay = lightDisplayOrigin(light);
            Point lightLocal = lightDisplay != null ? widget.toControl(lightDisplay) : null;
            if (lightLocal == null && scopedToWidget)
                lightLocal = new Point(bounds.x, bounds.y);
            if (lightLocal == null)
                continue;
            if (lightLocal.x < -8 || lightLocal.x > widget.getSize().x / 2)
                continue;
            Rectangle band = new Rectangle(0, Math.max(0, lightLocal.y - 4),
                    Math.max(1, widget.getSize().x), Math.max(24, bounds.height + 8));
            if (local.y < band.y || local.y >= band.y + band.height)
                continue;
            int dist = Math.abs(local.y - (lightLocal.y + Math.max(1, bounds.height / 2)));
            if (dist < bestDist)
            {
                Point origin = new Point(Math.max(0, lightLocal.x + 3),
                        Math.max(0, lightLocal.y + Math.max(0, (bounds.height - 13) / 2)));
                PropertySheetControlInterop.storeLwtRowGeometry(widget, view, name, origin, band);
                Composite rowComposite = widget instanceof Composite ? (Composite) widget : widget.getParent();
                best = new PropertySheetPaletteRow(widget, rowComposite,
                        PropertySheetUiContext.rowControls(rowComposite, widget), name, view);
                bestDist = dist;
            }
        }
        if (best != null)
            PropertySheetDebug.feature("directLightLabel hit " + PropertySheetDebug.quote(best.propertyName)); //$NON-NLS-1$
        return best;
    }

    private static List<Map.Entry<?, ?>> labelEntries(Map<?, ?> map)
    {
        List<Map.Entry<?, ?>> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            Object vm = entry.getKey();
            if (vm != null && vm.getClass().getName().contains("LabelViewModel")) //$NON-NLS-1$
                out.add(entry);
        }
        return out;
    }

    private static int[] labelEntrySliceForWidget(Object page, Control widget, int totalLabels)
    {
        if (page == null || widget == null || widget.isDisposed() || totalLabels <= 0)
            return null;
        Composite root = PropertySheetUiContext.findPaletteRoot(page);
        if (root == null || root.isDisposed())
            return null;
        List<Composite> hosts = PropertySheetControlInterop.collectLwtPaintHosts(root);
        if (hosts.isEmpty())
            return null;

        int start = 0;
        for (Composite host : hosts)
        {
            int capacity = lwtHostCapacity(host);
            int end = Math.min(totalLabels, start + capacity);
            if (sameOrRelated(widget, host))
            {
                PropertySheetDebug.feature("directLightLabel slice " + start + ".." + end //$NON-NLS-1$ //$NON-NLS-2$
                        + " host=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
                return new int[] { start, end };
            }
            start = end;
            if (start >= totalLabels)
                break;
        }
        return null;
    }

    private static int lwtHostCapacity(Control host)
    {
        if (host == null || host.isDisposed())
            return 1;
        return Math.max(1, Math.round(host.getSize().y / 30.0f));
    }

    private static boolean sameOrRelated(Control a, Control b)
    {
        if (a == null || b == null || a.isDisposed() || b.isDisposed())
            return false;
        if (a == b)
            return true;
        if (b instanceof Composite && isDescendant(a, (Composite) b))
            return true;
        if (a instanceof Composite && isDescendant(b, (Composite) a))
            return true;
        return false;
    }

    private static boolean isDescendant(Control control, Composite ancestor)
    {
        if (control == null || ancestor == null || control.isDisposed() || ancestor.isDisposed())
            return false;
        for (Composite p = control.getParent(); p != null && !p.isDisposed(); p = p.getParent())
        {
            if (p == ancestor)
                return true;
        }
        return false;
    }

    private static Object lightFromView(Object view)
    {
        if (view == null)
            return null;
        Object light = Global.getField(view, "lightControl"); //$NON-NLS-1$
        if (light == null)
            light = Global.getField(view, "lightLabel"); //$NON-NLS-1$
        if (light == null)
            light = Global.getField(view, "nativeControl"); //$NON-NLS-1$
        if (light == null)
            light = Global.invoke(view, "getNativeControl"); //$NON-NLS-1$
        return light;
    }

    private static Point lightDisplayOrigin(Object light)
    {
        if (light == null)
            return null;
        Object pt = Global.invoke(light, "toDisplay", Integer.valueOf(0), Integer.valueOf(0)); //$NON-NLS-1$
        if (pt instanceof Point)
            return (Point) pt;
        Object abs = Global.invoke(light, "getAbsoluteBounds"); //$NON-NLS-1$
        if (abs instanceof Rectangle)
        {
            Rectangle r = (Rectangle) abs;
            return new Point(r.x, r.y);
        }
        Object loc = Global.invoke(light, "getLocationInWindow"); //$NON-NLS-1$
        if (loc instanceof Point)
            return (Point) loc;
        return null;
    }

    private static String textOfViewModel(Object viewModel)
    {
        if (viewModel == null)
            return ""; //$NON-NLS-1$
        Object text = Global.invoke(viewModel, "getText"); //$NON-NLS-1$
        if (text instanceof String)
            return (String) text;
        return SmartTreeElementLabels.resolve(viewModel, null);
    }

    private static PropertySheetPaletteRow hitTestRow(Object page, Point display)
    {
        PropertySheetUiContext ctx = PropertySheetUiCoordinator.lastContext(page);
        if (ctx == null || ctx.rows.isEmpty())
            return null;

        PropertySheetPaletteRow best = null;
        int bestDist = Integer.MAX_VALUE;

        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            Control host = PropertySheetRowSelectionFeature.interactionTarget(row);
            if (host == null || host.isDisposed())
                continue;

            Point local = host.toControl(display);
            if (local.x < 0 || local.y < 0 || local.x >= host.getSize().x || local.y >= host.getSize().y)
                continue;

            if (PropertySheetControlInterop.isLwtPaintHost(host))
            {
                Rectangle band = PropertySheetControlInterop.lwtRowBand(host, row.propertyName);
                if (band == null)
                {
                    Point origin = PropertySheetControlInterop.lwtHighlightOrigin(host, row.propertyName);
                    int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, row.propertyName);
                    band = new Rectangle(0, Math.max(0, origin.y - 2), host.getSize().x, Math.max(4, bandH));
                }
                int bandTop = Math.max(0, band.y - 8);
                int bandBottom = bandTop + Math.max(12, band.height + 16);
                if (local.y < bandTop || local.y >= bandBottom)
                    continue;
                int dist = Math.abs(local.y - (bandTop + Math.max(4, band.height) / 2));
                if (dist < bestDist)
                {
                    bestDist = dist;
                    best = row;
                }
                continue;
            }
            else if (local.x >= host.getSize().x / 2)
            {
                continue;
            }

            int dist = Math.abs(local.y - host.getSize().y / 2);
            if (dist < bestDist)
            {
                bestDist = dist;
                best = row;
            }
        }
        return best;
    }

}

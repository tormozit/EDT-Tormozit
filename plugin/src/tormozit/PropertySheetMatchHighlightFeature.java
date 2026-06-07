package tormozit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

/**
 * Подсветка вхождений фильтра на имени свойства.
 * SWT {@link Label} — overlay; LWT field row — синий overlay с origin из LightLabel.
 */
final class PropertySheetMatchHighlightFeature implements PropertySheetUiFeature
{
    private static final String HOOK_KEY = "tormozit.ps.matchHighlight"; //$NON-NLS-1$
    private static final String LWT_HOOK_KEY = "tormozit.ps.lwtMatchHighlight"; //$NON-NLS-1$
    private static final String LWT_ROWS_KEY = "tormozit.ps.lwtMatchRows"; //$NON-NLS-1$

    private static final class LwtRowHighlight
    {
        SmartMatcher matcher;
        String text;
        String propertyName;
        Object light;
    }

    @Override
    public void refresh(PropertySheetUiContext ctx)
    {
        if (ctx == null)
            return;
        SmartMatcher matcher = ctx.matcher;
        purgeStaleHighlights(ctx);
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            Control host = highlightHost(row);
            if (matcher.isEmpty)
                clearHighlight(host, row.propertyName);
            else
                decorateName(host, matcher, row.propertyName);
        }
        PropertySheetDebug.feature("highlight rows=" + ctx.rows.size() //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(matcher.fullPattern)); //$NON-NLS-1$
    }

    private static Control highlightHost(PropertySheetPaletteRow row)
    {
        Control name = row.nameControl;
        if (row.lwtView != null && name != null && !name.isDisposed()
                && PropertySheetControlInterop.isLwtPaintHost(name))
            return name;
        if (name != null && !name.isDisposed() && hasDirectSwtNameText(name, row.propertyName))
            return name;
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
            return row.rowComposite;
        return name;
    }

    private static boolean hasDirectSwtNameText(Control control, String propertyName)
    {
        if (control == null || control.isDisposed() || propertyName == null || propertyName.isEmpty())
            return false;
        if (control instanceof Label)
            return true;
        String visible = PropertySheetControlInterop.controlText(control);
        return propertyName.equals(visible);
    }

    private static void decorateName(Control control, SmartMatcher matcher, String propertyName)
    {
        if (control == null || control.isDisposed())
            return;
        if (PropertySheetUiContext.isFilterAreaControl(control))
            return;

        String text = resolveDisplayText(control, propertyName);
        if (text.isEmpty() || !matcher.matches(text))
        {
            clearHighlight(control, propertyName);
            return;
        }

        if (PropertySheetControlInterop.isLwtPaintHost(control) && !hasDirectSwtNameText(control, propertyName))
            installLwtTextOverlay(control, matcher, text, propertyName);
        else
            installTextOverlay(control, matcher, text);
    }

    private static String resolveDisplayText(Control control, String propertyName)
    {
        if (PropertySheetControlInterop.isLwtPaintHost(control)
                && propertyName != null && !propertyName.isEmpty())
        {
            // Для LWT host controlText() часто возвращает текст значения ("Открыть" и т.п.),
            // а не имя свойства текущей строки.
            return propertyName;
        }
        if (control instanceof Label)
        {
            String labelText = ((Label) control).getText();
            if (labelText != null && !labelText.isEmpty())
                return labelText;
        }
        String visible = PropertySheetControlInterop.controlText(control);
        return visible.isEmpty() ? (propertyName != null ? propertyName : "") : visible; //$NON-NLS-1$
    }

    private static void installTextOverlay(Control control, SmartMatcher matcher, String text)
    {
        clearLwtHooks(control, null);
        if (skipIfSame(control, HOOK_KEY, matcher, text))
            return;

        if (control.getData(HOOK_KEY) == null)
        {
            control.addPaintListener(new PaintListener()
            {
                @Override
                public void paintControl(PaintEvent e)
                {
                    SmartMatcher active = activeMatcher(control, HOOK_KEY);
                    String drawn = activeText(control, HOOK_KEY);
                    if (active == null || drawn.isEmpty() || !active.matches(drawn))
                        return;
                    SmartMatchHighlight.paintTextMatchOverlay(e.gc, control, drawn, active);
                }
            });
            control.setData(HOOK_KEY, Boolean.TRUE);
        }
        storeMatcher(control, HOOK_KEY, matcher, text);
        control.redraw();
    }

    /** LWT: синий жирный overlay в позиции LightLabel (origin из scan). Несколько свойств на одном host. */
    private static void installLwtTextOverlay(Control host, SmartMatcher matcher, String text, String propertyName)
    {
        clearTextOverlayHooks(host);
        if (propertyName == null || propertyName.isEmpty())
            propertyName = text;

        @SuppressWarnings("unchecked")
        Map<String, LwtRowHighlight> rows = (Map<String, LwtRowHighlight>) host.getData(LWT_ROWS_KEY);
        if (rows == null)
        {
            rows = new HashMap<>();
            host.setData(LWT_ROWS_KEY, rows);
        }

        LwtRowHighlight existing = rows.get(propertyName);
        if (existing != null && matcher.fullPattern.equals(existing.matcher.fullPattern)
                && text.equals(existing.text))
            return;

        LwtRowHighlight entry = new LwtRowHighlight();
        entry.matcher = matcher;
        entry.text = text;
        entry.propertyName = propertyName;
        entry.light = PropertySheetControlInterop.rowLight(host, propertyName);
        rows.put(propertyName, entry);

        if (host.getData(LWT_HOOK_KEY) == null)
        {
            host.addPaintListener(new PaintListener()
            {
                @Override
                public void paintControl(PaintEvent e)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, LwtRowHighlight> activeRows =
                            (Map<String, LwtRowHighlight>) host.getData(LWT_ROWS_KEY);
                    if (activeRows == null || activeRows.isEmpty())
                        return;
                    for (LwtRowHighlight rh : activeRows.values())
                    {
                        if (rh.matcher == null || rh.matcher.isEmpty || !rh.matcher.matches(rh.text))
                            continue;
                        Point origin = resolveLwtHighlightOrigin(host, rh);
                        Rectangle band = PropertySheetControlInterop.lwtRowBand(host, rh.propertyName);
                        if (band != null)
                        {
                            if (origin.x < band.x)
                                origin.x = band.x;
                            if (rh.light == null)
                            {
                                int textHeight = PropertySheetControlInterop.lwtTextHeight(rh.light,
                                        host instanceof Composite ? (Composite) host : null);
                                if (origin.y < band.y - 2 || origin.y > band.y + band.height + 2)
                                    origin.y = band.y + Math.max(0, (band.height - textHeight) / 2);
                            }
                        }
                        Rectangle prevClip = e.gc.getClipping();
                        if (band != null)
                            e.gc.setClipping(band);
                        SmartMatchHighlight.paintLwtTextMatchOverlay(e.gc, host, rh.text, rh.matcher,
                                origin.x, origin.y, rh.light);
                        if (band != null)
                            e.gc.setClipping(prevClip);
                    }
                }
            });
            host.setData(LWT_HOOK_KEY, Boolean.TRUE);
        }
        host.redraw();
        Point origin = resolveLwtHighlightOrigin(host, entry);
        PropertySheetDebug.feature("highlight lwt " + PropertySheetDebug.quote(text) //$NON-NLS-1$
                + " origin=(" + origin.x + "," + origin.y + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " ctrl=" + PropertySheetDebug.controlBrief(host)); //$NON-NLS-1$
    }

    private static Point resolveLwtHighlightOrigin(Control host, LwtRowHighlight rh)
    {
        if (rh != null && rh.light != null && host instanceof Composite)
        {
            Point exact = PropertySheetControlInterop.lwtLabelDrawOrigin(rh.light, (Composite) host);
            if (exact != null)
                return exact;
        }
        return PropertySheetControlInterop.lwtHighlightOrigin(host,
                rh != null ? rh.propertyName : null);
    }

    private static void purgeStaleHighlights(PropertySheetUiContext ctx)
    {
        Set<Control> activeSwt = new HashSet<>();
        Map<Control, Set<String>> activeLwt = new HashMap<>();
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            Control host = highlightHost(row);
            if (host == null || host.isDisposed())
                continue;
            if (PropertySheetControlInterop.isLwtPaintHost(host))
                activeLwt.computeIfAbsent(host, k -> new HashSet<>()).add(row.propertyName);
            else
                activeSwt.add(host);
        }

        Composite root = PropertySheetUiContext.findPaletteRoot(ctx.page);
        if (root != null && !root.isDisposed())
            purgeStaleInComposite(root, activeSwt, activeLwt, ctx.matcher.isEmpty);
    }

    private static void purgeStaleInComposite(Composite composite, Set<Control> activeSwt,
            Map<Control, Set<String>> activeLwt, boolean filterEmpty)
    {
        if (composite == null || composite.isDisposed())
            return;
        purgeStaleOnControl(composite, activeSwt, activeLwt, filterEmpty);
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
                purgeStaleInComposite((Composite) child, activeSwt, activeLwt, filterEmpty);
            else
                purgeStaleOnControl(child, activeSwt, activeLwt, filterEmpty);
        }
    }

    private static void purgeStaleOnControl(Control control, Set<Control> activeSwt,
            Map<Control, Set<String>> activeLwt, boolean filterEmpty)
    {
        if (control == null || control.isDisposed())
            return;

        Object rowsObj = control.getData(LWT_ROWS_KEY);
        if (rowsObj instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, LwtRowHighlight> rows = (Map<String, LwtRowHighlight>) rowsObj;
            Set<String> allowed = activeLwt.get(control);
            if (filterEmpty || allowed == null)
                rows.clear();
            else
            {
                for (Iterator<String> it = rows.keySet().iterator(); it.hasNext();)
                {
                    if (!allowed.contains(it.next()))
                        it.remove();
                }
            }
            clearLegacyLwtKeys(control);
            control.redraw();
        }
        else if (Boolean.TRUE.equals(control.getData(LWT_HOOK_KEY)))
        {
            clearLegacyLwtKeys(control);
            control.redraw();
        }

        if (Boolean.TRUE.equals(control.getData(HOOK_KEY)) && !activeSwt.contains(control))
            clearTextOverlayHooks(control);
    }

    /** Старый формат (один matcher на host) — иначе ghost-подсветка от прежних listener. */
    private static void clearLegacyLwtKeys(Control control)
    {
        control.setData(LWT_HOOK_KEY + ".pattern", ""); //$NON-NLS-1$ //$NON-NLS-2$
        control.setData(LWT_HOOK_KEY + ".text", ""); //$NON-NLS-1$ //$NON-NLS-2$
        control.setData(LWT_HOOK_KEY + ".matcher", new SmartMatcher("")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean skipIfSame(Control control, String keyPrefix, SmartMatcher matcher, String text)
    {
        Object prevPattern = control.getData(keyPrefix + ".pattern"); //$NON-NLS-1$
        Object prevText = control.getData(keyPrefix + ".text"); //$NON-NLS-1$
        return matcher.fullPattern.equals(prevPattern) && text.equals(prevText);
    }

    private static SmartMatcher activeMatcher(Control control, String keyPrefix)
    {
        Object m = control.getData(keyPrefix + ".matcher"); //$NON-NLS-1$
        return m instanceof SmartMatcher ? (SmartMatcher) m : null;
    }

    private static String activeText(Control control, String keyPrefix)
    {
        Object stored = control.getData(keyPrefix + ".text"); //$NON-NLS-1$
        return stored instanceof String ? (String) stored : ""; //$NON-NLS-1$
    }

    private static void storeMatcher(Control control, String keyPrefix, SmartMatcher matcher, String text)
    {
        control.setData(keyPrefix + ".matcher", matcher); //$NON-NLS-1$
        control.setData(keyPrefix + ".pattern", matcher.fullPattern); //$NON-NLS-1$
        control.setData(keyPrefix + ".text", text); //$NON-NLS-1$
    }

    private static void clearHighlight(Control control, String propertyName)
    {
        if (control == null || control.isDisposed())
            return;
        clearTextOverlayHooks(control);
        clearLwtHooks(control, propertyName);
    }

    private static void clearTextOverlayHooks(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        control.setData(HOOK_KEY + ".pattern", ""); //$NON-NLS-1$ //$NON-NLS-2$
        control.setData(HOOK_KEY + ".matcher", new SmartMatcher("")); //$NON-NLS-1$ //$NON-NLS-2$
        control.setData(HOOK_KEY + ".text", ""); //$NON-NLS-1$ //$NON-NLS-2$
        control.redraw();
    }

    private static void clearLwtHooks(Control control, String propertyName)
    {
        if (control == null || control.isDisposed())
            return;
        Object rowsObj = control.getData(LWT_ROWS_KEY);
        if (rowsObj instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, ?> rows = (Map<String, ?>) rowsObj;
            if (propertyName != null && !propertyName.isEmpty())
                rows.remove(propertyName);
            else
                rows.clear();
            clearLegacyLwtKeys(control);
            control.redraw();
            return;
        }
        clearLegacyLwtKeys(control);
        control.redraw();
    }
}

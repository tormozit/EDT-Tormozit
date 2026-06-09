package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Выделение текущей строки по клику — через PaintListener (SWT и LWT).
 * Вместо {@code setBackground}, рисуем полупрозрачный фон поверх стандартной отрисовки.
 */
final class PropertySheetRowSelectionFeature implements PropertySheetUiFeature
{
    private static final String CLICK_HOOK_KEY = "tormozit.ps.rowSelectClick"; //$NON-NLS-1$
    private static final String PAINT_HOOK_KEY = "tormozit.ps.rowSelectPaint"; //$NON-NLS-1$
    /** Имя выделенного свойства для данного paint-хоста (несколько строк на одном LWT-host). */
    private static final String ACTIVE_PROP_KEY = "tormozit.ps.rowSelectProp"; //$NON-NLS-1$
    /** Сам объект PropertySheetPaletteRow — чтобы PaintListener знал строку для LWT-оригин. */
    private static final String ACTIVE_ROW_KEY = "tormozit.ps.rowSelectRow"; //$NON-NLS-1$

    private PropertySheetPaletteRow lastSelected;

    @Override
    public void refresh(PropertySheetUiContext ctx)
    {
        if (ctx == null)
            return;

        // Восстанавливаем сессионное выделение из ctx
        PropertySheetPaletteRow selected = ctx.selectedRow();
        if (selected != null && !selected.isAlive())
            selected = null;

        // При обновлении списка (фильтр/скролл) — снимаем прежню подсветку со старого хоста
        if (lastSelected != null && lastSelected != selected && lastSelected.isAlive())
            deactivate(lastSelected);

        // Устанавливаем paint-хуки + клик-хуки на всех строках
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            Control host = paintHost(row);
            if (host == null || host.isDisposed())
                continue;
            ensurePaintHook(host);
            if (!(row.lwtView != null && PropertySheetControlInterop.isLwtPaintHost(host)))
                ensureClickHook(host, row, ctx);
        }

        // Теперь активируем нужную строку
        if (selected != null)
            activate(selected);

        lastSelected = selected;
        PropertySheetDebug.feature("rowSelect rows=" + ctx.rows.size() //$NON-NLS-1$
                + " selected=" + PropertySheetDebug.quote(selected != null ? selected.propertyName : null)); //$NON-NLS-1$
    }

    static Control interactionTarget(PropertySheetPaletteRow row)
    {
        if (row == null)
            return null;
        if (row.lwtView != null && row.nameControl != null && !row.nameControl.isDisposed()
                && PropertySheetControlInterop.isLwtPaintHost(row.nameControl))
            return row.nameControl;
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
            return row.rowComposite;
        if (row.nameControl != null && !row.nameControl.isDisposed())
            return row.nameControl;
        return null;
    }

    // -----------------------------------------------------------------------
    // paint host: для LWT-хостов это rowComposite (фиксируем имя);  для SWT — rowComposite или nameControl
    // -----------------------------------------------------------------------

    private static Control paintHost(PropertySheetPaletteRow row)
    {
        return interactionTarget(row);
    }

    void selectRow(PropertySheetUiContext ctx, PropertySheetPaletteRow row)
    {
        if (ctx == null || row == null || !row.isAlive())
            return;
        if (lastSelected != null && lastSelected != row && lastSelected.isAlive())
            deactivate(lastSelected);
        ctx.setSelectedRow(row);
        PropertySheetUiCoordinator.rememberSelection(ctx.page, row);
        activate(row);
        lastSelected = row;
    }

    private void ensureClickHook(Control host, PropertySheetPaletteRow row, PropertySheetUiContext ctx)
    {
        if (Boolean.TRUE.equals(host.getData(CLICK_HOOK_KEY)))
            return;
        host.setData(CLICK_HOOK_KEY, Boolean.TRUE);
        host.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1 || !row.isAlive())
                    return;
                selectRow(ctx, row);
                PropertySheetDebug.feature("rowSelect click " //$NON-NLS-1$
                        + PropertySheetDebug.quote(row.propertyName));
            }
        });
    }

    private static void ensurePaintHook(Control host)
    {
        if (Boolean.TRUE.equals(host.getData(PAINT_HOOK_KEY)))
            return;
        host.setData(PAINT_HOOK_KEY, Boolean.TRUE);
        host.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                String activeName = (String) host.getData(ACTIVE_PROP_KEY);
                if (activeName == null || activeName.isEmpty())
                    return;
                PropertySheetPaletteRow row = (PropertySheetPaletteRow) host.getData(ACTIVE_ROW_KEY);
                if (row == null || !activeName.equals(row.propertyName))
                    return;
                drawSelectionBand(e.gc, host, row);
            }
        });
    }

    private static void activate(PropertySheetPaletteRow row)
    {
        Control host = paintHost(row);
        if (host == null || host.isDisposed())
            return;
        host.setData(ACTIVE_PROP_KEY, row.propertyName);
        host.setData(ACTIVE_ROW_KEY, row);
        host.redraw();
        if (host instanceof Composite)
            redrawChildren((Composite) host);
    }

    private static void deactivate(PropertySheetPaletteRow row)
    {
        Control host = paintHost(row);
        if (host == null || host.isDisposed())
            return;
        host.setData(ACTIVE_PROP_KEY, null);
        host.setData(ACTIVE_ROW_KEY, null);
        host.redraw();
        if (host instanceof Composite)
            redrawChildren((Composite) host);
    }

    private static void redrawChildren(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            child.redraw();
            if (child instanceof Composite)
                redrawChildren((Composite) child);
        }
    }

    /**
     * Рисуем полупрозрачный фон выделения поверх стандартной отрисовки контрола.
     * Alpha=80 (~31%) — видно но не глушит текст и редактор значения.
     * Для LWT-хостов рисуем только полосу одной строки (не весь контейнер).
     */
    private static void drawSelectionBand(GC gc, Control host, PropertySheetPaletteRow row)
    {
        int x = 0;
        int y = 0;
        int w = host.getSize().x;
        int h = host.getSize().y;

        if (PropertySheetControlInterop.isLwtPaintHost(host))
        {
            String propName = row != null ? row.propertyName : null;
            Rectangle band = PropertySheetControlInterop.lwtRowBand(host, propName);
            if (band != null)
            {
                // Точная геометрия от LightLabel.getBounds — придерживаемся её ±1px.
                y = Math.max(0, band.y - 1);
                h = Math.min(band.height + 2, host.getSize().y - y);
            }
            else
            {
                org.eclipse.swt.graphics.Point origin =
                        PropertySheetControlInterop.lwtHighlightOrigin(host, propName);
                int bandH = PropertySheetControlInterop.lwtRowBandHeight(host, propName);
                // Не позволяем полосе быть выше одной строки (~33px).
                bandH = Math.min(bandH, 36);
                y = Math.max(0, origin.y - 1);
                h = Math.min(Math.max(4, bandH + 2), host.getSize().y - y);
            }
        }

        if (w <= 0 || h <= 0)
            return;
        Color selColor = selectionBg(host.getDisplay());
        int alpha = gc.getAlpha();
        gc.setAlpha(80);
        gc.setBackground(selColor);
        gc.fillRectangle(x, y, w, h);
        gc.setAlpha(alpha);
    }

    private static Color selectionBg(Display display)
    {
        if (display == null || display.isDisposed())
            display = Display.getDefault();
        return display.getSystemColor(SWT.COLOR_LIST_SELECTION);
    }
}


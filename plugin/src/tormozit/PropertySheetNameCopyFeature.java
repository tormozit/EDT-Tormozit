package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Правый клик по строке свойства → контекстное меню с командой копирования имени.
 * Заменяет прежний левый клик-копирование, который конфликтовал с выделением строки.
 */
final class PropertySheetNameCopyFeature implements PropertySheetUiFeature
{
    private static final String HOOK_KEY = "tormozit.ps.nameCopyMenu"; //$NON-NLS-1$

    @Override
    public void refresh(PropertySheetUiContext ctx)
    {
        if (ctx == null)
            return;
        for (PropertySheetPaletteRow row : ctx.rows)
        {
            if (!row.isAlive())
                continue;
            Control target = PropertySheetRowSelectionFeature.interactionTarget(row);
            if (target == null || target.isDisposed())
                continue;
            if (row.lwtView != null && PropertySheetControlInterop.isLwtPaintHost(target))
                continue;
            if (Boolean.TRUE.equals(target.getData(HOOK_KEY)))
                continue;
            target.setData(HOOK_KEY, Boolean.TRUE);
            installContextMenu(target, row);
        }
        PropertySheetDebug.feature("nameCopyMenu hooked=" + ctx.rows.size()); //$NON-NLS-1$
    }

    void showContextMenu(PropertySheetPaletteRow row, Control widget, Point displayPoint)
    {
        if (row == null || widget == null || widget.isDisposed())
            return;
        String name = row.propertyName;
        if (name == null || name.isEmpty())
            name = PropertySheetControlInterop.controlText(widget);
        final String copyText = name != null ? name : ""; //$NON-NLS-1$

        Menu menu = new Menu(widget.getShell(), SWT.POP_UP);
        MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
        copyItem.setText("Копировать имя\tCtrl+C"); //$NON-NLS-1$
        if (copyText.isEmpty())
            copyItem.setEnabled(false);
        else
        {
            copyItem.addListener(SWT.Selection, e -> {
                PropertySheetDebug.feature("contextMenu copy " + PropertySheetDebug.quote(copyText)); //$NON-NLS-1$
                PropertySheetUiContext.copyToClipboard(widget, copyText);
                ToastNotification.show("Скопировано", copyText, 2_500); //$NON-NLS-1$
            });
        }
        Point at = displayPoint != null ? displayPoint : widget.getDisplay().getCursorLocation();
        menu.setLocation(at);
        menu.setVisible(true);
        menu.addListener(SWT.Hide, e -> {
            if (!menu.isDisposed())
                menu.dispose();
        });
    }

    private static void installContextMenu(Control target, PropertySheetPaletteRow row)
    {
        Menu menu = new Menu(target.getShell(), SWT.POP_UP);

        // Пересоздаём пункты при каждом показе — чтобы текст был актуальным.
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                // Очищаем старые пункты
                for (MenuItem item : menu.getItems())
                    item.dispose();

                String name = row.propertyName;
                if (name == null || name.isEmpty())
                    name = PropertySheetControlInterop.controlText(target);
                final String copyText = (name != null) ? name : ""; //$NON-NLS-1$

                MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
                copyItem.setText("Копировать имя\tCtrl+C"); //$NON-NLS-1$

                if (copyText.isEmpty())
                {
                    copyItem.setEnabled(false);
                    return;
                }

                copyItem.addListener(SWT.Selection, event -> {
                    PropertySheetDebug.feature("contextMenu copy " + PropertySheetDebug.quote(copyText)); //$NON-NLS-1$
                    PropertySheetUiContext.copyToClipboard(target, copyText);
                    ToastNotification.show("Скопировано", copyText, 2_500); //$NON-NLS-1$
                });
            }
        });

        target.setMenu(menu);
        target.addDisposeListener(e -> {
            if (!menu.isDisposed())
                menu.dispose();
        });
    }
}

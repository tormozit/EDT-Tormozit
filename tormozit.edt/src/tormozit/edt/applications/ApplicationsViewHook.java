package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
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
 * Хук панели «Приложения» EDT.
 *
 * <p>Добавляет в панель:
 * <ul>
 *   <li>Колонку «Начало сеанса конфигуратора» — рисуется через
 *       SWT {@code PaintItem}, чтобы <b>не трогать JFace-рендерер EDT</b>.
 *       Создание {@code TableViewerColumn} / {@code TreeViewerColumn} вызывает
 *       внутренний {@code clearLegacyRenderer()}, который ломает
 *       {@code OwnerDrawLabelProvider} EDT и делает список пустым.</li>
 *   <li>Кнопку «Отключить конфигуратор» в тулбар панели.</li>
 *   <li>Пункт «Отключить конфигуратор» в контекстное меню списка.</li>
 * </ul>
 *
 * <p>COM-подключения хранятся в {@link ComConnectionRegistry}. Подключение
 * конфигуратора выполняется EDT или пользователем через штатные средства;
 * этот хук только отображает дату начала сеанса и предоставляет команду
 * отключения.
 */
public class ApplicationsViewHook implements IStartup
{
    // ---- Константы ----

    private static final String APPLICATIONS_VIEW_CLASS =
        "com.e1c.g5.dt.internal.applications.ui.view.ApplicationsView"; //$NON-NLS-1$

    private static final String CMD_DISCONNECT = "Отключить конфигуратор"; //$NON-NLS-1$
    private static final String COL_TITLE      = "Начало сеанса конфигуратора"; //$NON-NLS-1$
    private static final int    COL_WIDTH      = 165;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"); //$NON-NLS-1$

    // -----------------------------------------------------------------------
    // IStartup
    // -----------------------------------------------------------------------

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench wb = PlatformUI.getWorkbench();

            for (IWorkbenchWindow w : wb.getWorkbenchWindows())
                hookWindow(w);

            wb.addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)     { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // -----------------------------------------------------------------------
    // Подключение к окну / панели
    // -----------------------------------------------------------------------

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IViewReference ref : page.getViewReferences())
            {
                IViewPart view = ref.getView(false);
                if (isApplicationsView(view))
                    hookView(view);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref.getPart(false);
                if (isApplicationsView(part))
                    // asyncExec: даём partControl завершить возможные отложенные init
                    Display.getDefault().asyncExec(() -> hookView(part));
            }

            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static boolean isApplicationsView(Object part)
    {
        return part != null &&
               APPLICATIONS_VIEW_CLASS.equals(part.getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Основная логика хука
    // -----------------------------------------------------------------------

    private void hookView(Object part)
    {
//        if (!(part instanceof IViewPart)) return;
//        IViewPart view = (IViewPart) part;
//
//        ColumnViewer viewer = findViewer(view);
//        if (viewer == null) return;
//
//        Control control = viewer.getControl();
//        if (control == null || control.isDisposed()) return;
//
//        addSessionColumn(control);
//        addToolbarButton(view, viewer, control);
//        addContextMenuItem(viewer, control);
//        registerRedrawOnRegistryChange(control);
    }

    // -----------------------------------------------------------------------
    // 1. Колонка «Начало сеанса конфигуратора» — чистый SWT, без JFace
    // -----------------------------------------------------------------------

    /**
     * Добавляет SWT-столбец и рисует дату через {@code SWT.PaintItem}.
     *
     * <p><b>Почему не TableViewerColumn?</b><br>
     * Конструктор {@code ViewerColumn} вызывает {@code viewer.clearLegacyRenderer()},
     * который уничтожает {@code OwnerDrawLabelProvider} EDT — список становится пустым.
     * Чистый SWT-столбец не трогает JFace-рендерер.
     */
    private void addSessionColumn(Control control)
    {
        addSessionColumnToTree((Tree) control);
    }

    private void addSessionColumnToTree(Tree tree)
    {
        TreeColumn col = new TreeColumn(tree, SWT.NONE);
        col.setText(COL_TITLE);
        col.setWidth(COL_WIDTH);
        col.setResizable(true);

        final int colIdx = tree.indexOf(col);

        Listener painter = event ->
        {
            if (event.index != colIdx) return;
            Object data = ((TreeItem) event.item).getData();
            paintDate(event.gc, event.x, event.y, event.height, extractKey(data));
        };
        tree.addListener(SWT.PaintItem, painter);
        tree.addDisposeListener(e -> tree.removeListener(SWT.PaintItem, painter));
    }

    /**
     * Рисует дату начала сеанса в ячейке. Текст вертикально центрируется.
     * Метод вызывается из {@code SWT.PaintItem} — только для нашего столбца.
     */
    private void paintDate(GC gc, int x, int y, int rowHeight, String key)
    {
        LocalDateTime dt = ComConnectionRegistry.getSessionStart(key);
        String text = DATE_FMT.format(dt);
        if (dt == null) text = "не подключено";
        int fontHeight = gc.getFontMetrics().getHeight();
        int textY      = y + Math.max(0, (rowHeight - fontHeight) / 2);
        // transparent=true — не перекрашиваем фон (важно для выделения)
        gc.drawString(text, x + 3, textY, true);
    }

    // -----------------------------------------------------------------------
    // 2. Кнопка в тулбаре панели
    // -----------------------------------------------------------------------

    private void addToolbarButton(IViewPart view, ColumnViewer viewer, Control control)
    {
        IActionBars actionBars = view.getViewSite().getActionBars();
        IToolBarManager toolbar = actionBars.getToolBarManager();

        Action action = new Action(CMD_DISCONNECT)
        {
            @Override
            public void run()
            {
                disconnectSelected(viewer);
                // Перерисовка инициируется слушателем реестра в registerRedrawOnRegistryChange
            }
        };
        action.setToolTipText(
            "Разорвать COM-подключение конфигуратора для выбранных баз"); //$NON-NLS-1$

        toolbar.add(new Separator());
        toolbar.add(action);
        actionBars.updateActionBars();
    }

    // -----------------------------------------------------------------------
    // 3. Пункт в контекстном меню
    // -----------------------------------------------------------------------

    private void addContextMenuItem(ColumnViewer viewer, Control control)
    {
        Menu menu = control.getMenu();
        if (menu == null)
        {
            menu = new Menu(control);
            control.setMenu(menu);
        }

        final Menu finalMenu = menu;

        MenuAdapter adapter = new MenuAdapter()
        {
            private final List<MenuItem> addedItems = new ArrayList<>(2);

            @Override
            public void menuShown(MenuEvent e)
            {
                IStructuredSelection sel =
                    (IStructuredSelection) viewer.getSelection();
                if (sel.isEmpty()) return;

                // Показываем пункт только если среди выбранных есть активные сеансы
                boolean anyConnected = sel.toList().stream()
                    .anyMatch(item -> ComConnectionRegistry.isConnected(extractKey(item)));
                if (!anyConnected) return;

                addedItems.add(new MenuItem(finalMenu, SWT.SEPARATOR));

                MenuItem item = new MenuItem(finalMenu, SWT.PUSH);
                item.setText(CMD_DISCONNECT);

                final IStructuredSelection capturedSel = sel;
                item.addSelectionListener(new SelectionAdapter()
                {
                    @Override
                    public void widgetSelected(SelectionEvent ev)
                    {
                        disconnectItems(capturedSel.toList());
                    }
                });
                addedItems.add(item);
            }

            @Override
            public void menuHidden(MenuEvent e)
            {
                // asyncExec: сначала доставляем widgetSelected, потом dispose
                Display display = ((Menu) e.widget).getDisplay();
                List<MenuItem> toDispose = new ArrayList<>(addedItems);
                addedItems.clear();
                display.asyncExec(() ->
                {
                    for (MenuItem mi : toDispose)
                        if (!mi.isDisposed()) mi.dispose();
                });
            }
        };

        menu.addMenuListener(adapter);
        control.addDisposeListener(
            e -> { if (!finalMenu.isDisposed()) finalMenu.removeMenuListener(adapter); });
    }

    // -----------------------------------------------------------------------
    // 4. Перерисовка при изменении реестра
    // -----------------------------------------------------------------------

    /**
     * Подписывается на изменения реестра и перерисовывает контрол через
     * {@code control.redraw()}. Этого достаточно для обновления нашего
     * SWT-столбца без «тяжёлого» {@code viewer.refresh()}.
     */
    private void registerRedrawOnRegistryChange(Control control)
    {
        Runnable refresher = () ->
        {
            Display d = Display.getDefault();
            if (d == null || d.isDisposed()) return;
            d.asyncExec(() ->
            {
                if (!control.isDisposed()) control.redraw();
            });
        };

        ComConnectionRegistry.addChangeListener(refresher);
        control.addDisposeListener(
            e -> ComConnectionRegistry.removeChangeListener(refresher));
    }

    // -----------------------------------------------------------------------
    // Действия «Отключить конфигуратор»
    // -----------------------------------------------------------------------

    private void disconnectSelected(ColumnViewer viewer)
    {
        IStructuredSelection sel = (IStructuredSelection) viewer.getSelection();
        if (!sel.isEmpty())
            disconnectItems(sel.toList());
    }

    private void disconnectItems(List<?> items)
    {
        for (Object item : items)
        {
            String key = extractKey(item);
            if (ComConnectionRegistry.isConnected(key))
                ComConnectionRegistry.disconnect(key);
                // disconnect() уведомляет слушателей → registerRedrawOnRegistryChange → redraw
        }
    }

    // -----------------------------------------------------------------------
    // Поиск ColumnViewer через рефлексию
    // -----------------------------------------------------------------------

    /**
     * Перебирает все поля класса вида и его суперклассов в поисках
     * первого {@link ColumnViewer}. Не зависит от имени поля в EDT.
     */
    private static ColumnViewer findViewer(Object view)
    {
        Class<?> cls = view.getClass();
        while (cls != null)
        {
            for (Field f : cls.getDeclaredFields())
            {
                if (!ColumnViewer.class.isAssignableFrom(f.getType())) continue;
                try
                {
                    f.setAccessible(true);
                    Object val = f.get(view);
                    if (val instanceof ColumnViewer)
                        return (ColumnViewer) val;
                }
                catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Извлечение ключа из элемента строки
    // -----------------------------------------------------------------------

    /**
     * Возвращает строковый ключ для элемента строки вьюера.
     * Пробует: {@code getName()} → {@code getTitle()} → {@code toString()}.
     */
    static String extractKey(Object item)
    {
        if (item == null) return ""; //$NON-NLS-1$

        String name = invokeString(item, "getName"); //$NON-NLS-1$
        if (name != null && !name.isEmpty()) return name;

        String title = invokeString(item, "getTitle"); //$NON-NLS-1$
        if (title != null && !title.isEmpty()) return title;

        return item.toString();
    }

    private static String invokeString(Object obj, String method)
    {
        try
        {
            Object r = obj.getClass().getMethod(method).invoke(obj);
            return r instanceof String ? (String) r : null;
        }
        catch (Exception ignored) { return null; }
    }
}

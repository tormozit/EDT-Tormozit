import org.eclipse.jface.viewers.*;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IStartup;
import java.lang.reflect.Field;
import java.util.regex.Pattern;

public class OpenMdObjectHook implements IStartup {
    private static final String PATCHED_KEY = "openmdobject_patched";

    @Override
    public void earlyStartup() {
//        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display) {
        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                Shell shell = (Shell) event.widget;
                Object dialog = shell.getData();
                if (dialog != null && dialog.getClass().getName().equals("com._1c.g5.v8.dt.md.ui.dialogs.OpenMdObjectSelectionDialog")) {
                    applyPatch(shell, dialog);
                }
            }
        };
        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void applyPatch(Shell shell, Object dialog) {
        if (shell.getData(PATCHED_KEY) != null) return;
        shell.setData(PATCHED_KEY, true);

        // 1. Делаем оригинальный фильтр "всегда пропускающим"
        try {
            Field filterField = dialog.getClass().getDeclaredField("filter");
            filterField.setAccessible(true);
            Object originalFilter = filterField.get(dialog); // MdObjectItemsFilter
            Field patternField = originalFilter.getClass().getDeclaredField("pattern");
            patternField.setAccessible(true);
            patternField.set(originalFilter, Pattern.compile(".*"));
            Field onlyInNamesField = originalFilter.getClass().getDeclaredField("onlyInNames");
            onlyInNamesField.setAccessible(true);
            onlyInNamesField.set(originalFilter, false);
        } catch (Exception e) {
            // Игнорируем – возможно, не получилось, но продолжим
        }

        // Получаем TableViewer (final)
        final TableViewer viewer = getValidTableViewer(dialog, shell);
        if (viewer == null)
            return;
        
        final Table table = viewer.getTable();

        Text filterText = findTextWidget(shell);
        if (filterText == null) 
            return;

        Object mdObjectsEngine = Global.getField(dialog, "mdObjectsEngine");
        if (mdObjectsEngine == null) 
            return;

        IBaseLabelProvider originalLp = viewer.getLabelProvider();
        final IStyledLabelProvider styledProvider;
        if (originalLp instanceof DelegatingStyledCellLabelProvider) {
            styledProvider = ((DelegatingStyledCellLabelProvider) originalLp).getStyledStringProvider();
        } else if (originalLp instanceof IStyledLabelProvider) {
            styledProvider = (IStyledLabelProvider) originalLp;
        } else {
            return;
        }
        if (styledProvider == null) return;

        OpenMdObjectFilter smartFilter = new OpenMdObjectFilter(mdObjectsEngine, dialog);
        smartFilter.setPattern(filterText.getText());
        OpenMdObjectLabelProviderWrapper wrapper = new OpenMdObjectLabelProviderWrapper(styledProvider, smartFilter.getMatcher());

        ILabelProvider textProvider = new ILabelProvider() {
            @Override public String getText(Object element) { 
                return styledProvider.getStyledText(element).getString(); }
            @Override public Image getImage(Object element) {
                return null; }
            @Override public void addListener(ILabelProviderListener listener) {}
            @Override public void dispose() {}
            @Override public boolean isLabelProperty(Object element, String property) { 
                return false; }
            @Override public void removeListener(ILabelProviderListener listener) {}
        };
        viewer.setComparator(new SmartOutlineComparator(smartFilter.getNamePremiumCache(), smartFilter.getParamPremiumCache(), textProvider));

        for (ViewerFilter f : viewer.getFilters()) viewer.removeFilter(f);
        viewer.addFilter(smartFilter);

        // Подмена LabelProvider – если оригинальный был DelegatingStyledCellLabelProvider, подменяем внутренний IStyledLabelProvider
        if (originalLp instanceof DelegatingStyledCellLabelProvider) {
            injectStyledStringProvider((DelegatingStyledCellLabelProvider) originalLp, wrapper);
        } else {
            viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(wrapper));
        }

        final Runnable[] pending = new Runnable[1];
        filterText.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                String pattern = filterText.getText();
                Display display = filterText.getDisplay();
                if (pending[0] != null) display.timerExec(-1, pending[0]);
                pending[0] = () -> {
                    if (table.isDisposed()) return;
                    table.setRedraw(false);
                    try {
                        smartFilter.setPattern(pattern);
                        wrapper.setPattern(pattern);
                        viewer.refresh();
                        selectFirstVisibleItem(table);
                    } finally {
                        table.setRedraw(true);
                    }
                };
                display.timerExec(150, pending[0]);
            }
        });

        installKeyboardNavigation(filterText, table);
    }
    
    private static void createAndSetAlwaysMatchFilter(Object dialog) {
        try {
            // Создаём динамический подкласс FilteredItemsSelectionDialog.ItemsFilter
            ClassLoader classLoader = dialog.getClass().getClassLoader();
            Class<?> itemsFilterClass = Class.forName("org.eclipse.ui.dialogs.FilteredItemsSelectionDialog$ItemsFilter");
            
            // Используем Java Proxy с интерфейсом, но ItemsFilter не имеет интерфейсов
            // Поэтому используем библиотеку javassist (есть в EDT)
            // или создаём через ReflectionFactory
            
            // Вместо этого – просто устанавливаем оригинальный фильтр в null?
            Field filterField = dialog.getClass().getDeclaredField("filter");
            filterField.setAccessible(true);
            filterField.set(dialog, null);
            
            System.out.println("[SmartMdObject] Set filter to null - content provider will add all items");
            
        } catch (Exception e) {
            System.err.println("[SmartMdObject] Failed to set filter to null: " + e.getMessage());
        }
    }
    
    private static TableViewer getValidTableViewer(Object dialog, Shell shell) {
        TableViewer viewer = getTableViewerFromDialog(dialog);
        if (viewer != null) return viewer;
        Table table = findTableWidget(shell);
        if (table != null) return new TableViewer(table);
        return null;
    }
    
    private static TableViewer getTableViewerFromDialog(Object dialog) {
        Class<?> clazz = dialog.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("fTableViewer");
                field.setAccessible(true);
                return (TableViewer) field.get(dialog);
            } catch (NoSuchFieldException e) {
                // ищем дальше
            } catch (Exception ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static void selectFirstVisibleItem(Table table) {
        if (table == null || table.isDisposed() || table.getItemCount() == 0) return;
        TableItem first = table.getItem(0);
        table.setSelection(first);
        table.showItem(first);
        Event event = new Event();
        event.widget = table;
        event.item = first;
        table.notifyListeners(SWT.Selection, event);
    }

    private static void installKeyboardNavigation(Text filterText, Table table) {
        Listener arrowFilter = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (event.widget == filterText && (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP ||
                        event.keyCode == SWT.PAGE_DOWN || event.keyCode == SWT.PAGE_UP)) {
                    navigateTable(table, event.keyCode);
                    event.type = SWT.None;
                }
            }
        };
        filterText.getDisplay().addFilter(SWT.KeyDown, arrowFilter);
        filterText.addDisposeListener(e -> {
            if (!filterText.getDisplay().isDisposed())
                filterText.getDisplay().removeFilter(SWT.KeyDown, arrowFilter);
        });
    }

    private static void navigateTable(Table table, int keyCode) {
        if (table == null || table.isDisposed() || table.getItemCount() == 0) return;
        int index = table.getSelectionIndex();
        int newIndex = index;
        int itemHeight = table.getItemHeight();
        int pageSteps = itemHeight > 0 ? table.getClientArea().height / itemHeight : 10;
        if (pageSteps <= 0) pageSteps = 10;

        if (index == -1) {
            newIndex = (keyCode == SWT.ARROW_UP || keyCode == SWT.PAGE_UP) ? table.getItemCount() - 1 : 0;
        } else {
            switch (keyCode) {
                case SWT.ARROW_DOWN: newIndex = index + 1; break;
                case SWT.ARROW_UP: newIndex = index - 1; break;
                case SWT.PAGE_DOWN: newIndex = Math.min(index + pageSteps, table.getItemCount() - 1); break;
                case SWT.PAGE_UP: newIndex = Math.max(index - pageSteps, 0); break;
            }
        }
        if (newIndex >= 0 && newIndex < table.getItemCount()) {
            TableItem item = table.getItem(newIndex);
            table.setSelection(item);
            table.showItem(item);
            Event event = new Event();
            event.widget = table;
            event.item = item;
            table.notifyListeners(SWT.Selection, event);
        }
    }

    
    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider, IStyledLabelProvider smartProvider) {
        Class<?> cls = provider.getClass();
        while (cls != null) {
            for (Field field : cls.getDeclaredFields()) {
                if (IStyledLabelProvider.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        field.set(provider, smartProvider);
                        return;
                    } catch (Exception ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static Text findTextWidget(Composite parent) {
        for (Control c : parent.getChildren()) {
            if (c instanceof Text) return (Text) c;
            if (c instanceof Composite) {
                Text res = findTextWidget((Composite) c);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static Table findTableWidget(Composite parent) {
        for (Control c : parent.getChildren()) {
            if (c instanceof Table) return (Table) c;
            if (c instanceof Composite) {
                Table res = findTableWidget((Composite) c);
                if (res != null) return res;
            }
        }
        return null;
    }
}
package tormozit;

import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;

import java.lang.reflect.Field;

import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;

public class OpenMdObjectHook implements IStartup {

    @Override
    public void earlyStartup()
    {
        Activator.getDefault().getInjector().injectMembers(this);
        Display.getDefault().asyncExec(() -> {
            install(Display.getDefault());
        });
    }

    private static final String PATCHED_KEY = "OpenMdObjectPatched";

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                Shell shell = (Shell) event.widget;
                if (shell.getData(PATCHED_KEY) != null) return;

                Object dialog = shell.getData();
                String dialogName = dialog != null ? dialog.getClass().getName() : "";
                String shellName = shell.getClass().getName();

                boolean isMdDialog = dialogName.contains("OpenMdObjectSelectionDialog")
                                  || shellName.contains("OpenMdObjectSelectionDialog");
                if (!isMdDialog) return;

                display.asyncExec(() -> {
                    if (!shell.isDisposed()) tryPatchDialog(shell, dialog);
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void tryPatchDialog(Shell shell, Object dialog) {
        try {
            if (dialog == null) return;

            // --- 1. Text фильтра ---
            Object patternControlObj = Global.invoke(dialog, "getPatternControl");
            if (!(patternControlObj instanceof Text)) {
                Global.log("OpenMdObjectHook: getPatternControl returned " + patternControlObj);
                return;
            }
            Text filterText = (Text) patternControlObj;

            // --- 2. Текущий LabelProvider ---
            Object currentBaseLpObj = Global.getField(dialog, "mdObjectLabelProvider");
            if (currentBaseLpObj == null) {
                currentBaseLpObj = Global.invoke(dialog, "getListLabelProvider");
            }
            if (!(currentBaseLpObj instanceof IBaseLabelProvider)) {
                Global.log("OpenMdObjectHook: labelProvider not found: " + currentBaseLpObj);
                return;
            }
            IBaseLabelProvider currentBaseLp = (IBaseLabelProvider) currentBaseLpObj;

            ILabelProvider currentLp = (ILabelProvider) currentBaseLp;
            IStyledLabelProvider currentStyled = (currentBaseLp instanceof IStyledLabelProvider)
                    ? (IStyledLabelProvider) currentBaseLp : null;
            ILabelDecorator currentDecorator = (currentBaseLp instanceof ILabelDecorator)
                    ? (ILabelDecorator) currentBaseLp : null;

            // --- 3. Умный LabelProvider ---
            OpenMdObjectLabelProvider smartLp = new OpenMdObjectLabelProvider(
                    currentLp, currentStyled, currentDecorator);
            smartLp.setPattern(filterText.getText());

            Global.invoke(dialog, "setListLabelProvider", smartLp);
            Global.invoke(dialog, "setListSelectionLabelDecorator", smartLp);

            // --- 4. Компаратор ---
            OpenMdObjectComparator comparator = new OpenMdObjectComparator(smartLp);
            comparator.setMatcher(new SmartMatcher(filterText.getText()));
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "itemsComparator", comparator);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "fItemsComparator", comparator);

            // --- 5. Фильтр ---
            org.eclipse.ui.dialogs.OpenMdObjectItemsFilter smartFilter =
                    new org.eclipse.ui.dialogs.OpenMdObjectItemsFilter(
                            (FilteredItemsSelectionDialog) dialog, smartLp, filterText.getText());

            // Подменяем filter в ОБОИХ классах:
            // - OpenMdObjectSelectionDialog.filter (подкласс) — для createFilter() и MdObjectLabelProvider
            // - FilteredItemsSelectionDialog.filter (суперкласс) — для applyFilter() и RefreshJob
            Global.setField(dialog, "filter", smartFilter);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);

            // --- 6. Удаляем стандартный modify listener EDT ---
            for (Listener l : filterText.getListeners(SWT.Modify)) {
                filterText.removeListener(SWT.Modify, l);
            }

            // --- 7. Debounce + applyFilter с восстановлением фильтра ---
            final Runnable[] pendingTask = new Runnable[1];
            final Display display = filterText.getDisplay();

            filterText.addModifyListener(e -> {
                String pattern = filterText.getText();
                if (pendingTask[0] != null) display.timerExec(-1, pendingTask[0]);

                pendingTask[0] = () -> {
                    if (filterText.isDisposed()) return;
                    smartFilter.setPattern(pattern);
                    smartLp.setPattern(pattern);
                    comparator.setMatcher(new SmartMatcher(pattern));

                    Global.invoke(dialog, "applyFilter");
                    // applyFilter → createFilter() → new MdObjectItemsFilter()
                    // applyFilter записывает результат в super.filter
                    // createFilter записывает результат в sub.filter
                    // Восстанавливаем оба поля на наш фильтр:
                    Global.setField(dialog, "filter", smartFilter);
                    setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);
                };
                display.timerExec(150, pendingTask[0]);
            });

            filterText.addDisposeListener(e -> {
                if (pendingTask[0] != null && !display.isDisposed()) {
                    display.timerExec(-1, pendingTask[0]);
                }
            });

            // Принудительный applyFilter при открытии
            Global.invoke(dialog, "applyFilter");
            Global.setField(dialog, "filter", smartFilter);
            setFieldExactClass(dialog, FilteredItemsSelectionDialog.class, "filter", smartFilter);

            shell.setData(PATCHED_KEY, Boolean.TRUE);
            Global.log("OpenMdObjectHook: patched successfully");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Устанавливает значение поля в КОНКРЕТНОМ классе (не поднимаясь по иерархии).
     * Нужно для FilteredItemsSelectionDialog.filter, т.к. OpenMdObjectSelectionDialog
     * объявляет своё поле filter, которое скрывает (shadows) поле суперкласса.
     */
    private static void setFieldExactClass(Object obj, Class<?> exactClass, String fieldName, Object value) {
        try {
            Field f = exactClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (NoSuchFieldException ignored) {
            // поле с таким именем в этом классе не найдено — нормально
        } catch (Exception e) {
            Global.log("OpenMdObjectHook: failed to set " + exactClass.getName() + "." + fieldName + ": " + e);
        }
    }
}
package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.core.commands.Category;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PreferencesUtil;

/**
 * Предфильтр страницы «Общие → Клавиши» по категории команд плагина.
 * Переход на страницу выполняется через {@link org.eclipse.ui.preferences.IWorkbenchPreferenceContainer#openPage}.
 */
public final class ComfortKeysPreferences
{
    /** Идентификатор страницы «Общие → Клавиши» ({@code org.eclipse.ui.preferencePages}). */
    public static final String KEYS_PREFERENCE_PAGE_ID =
            "org.eclipse.ui.preferencePages.Keys"; //$NON-NLS-1$

    /** Категория команд плагина ({@code plugin.xml}, {@code name="Комфорт"}). */
    public static final String COMMAND_CATEGORY_ID =
            "tormozit.commands.global"; //$NON-NLS-1$

    private static final String FALLBACK_CATEGORY_NAME = "Комфорт"; //$NON-NLS-1$

    private ComfortKeysPreferences()
    {
    }

    /**
     * Устанавливает фильтр «Комфорт» на уже открытой странице «Клавиши».
     * Публичного API Eclipse для этого нет — используется рефлексия.
     */
    public static void applyCategoryFilterAsync()
    {
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
            null,
            KEYS_PREFERENCE_PAGE_ID,
            null,
            null);
        scheduleCategoryFilter(dialog);
    }

    private static void scheduleCategoryFilter(PreferenceDialog dialog)
    {
        String filterText = resolveCategoryFilterText();
        Shell shell = dialog.getShell();
        if (shell == null || shell.isDisposed())
            return;
        Display display = shell.getDisplay();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            Object page = dialog.getSelectedPage();
            if (page instanceof IPreferencePage)
                applyCategoryFilter((IPreferencePage) page, filterText);
        });
    }

    private static String resolveCategoryFilterText()
    {
        ICommandService commandService =
            PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return FALLBACK_CATEGORY_NAME;
        Category category = commandService.getCategory(COMMAND_CATEGORY_ID);
        if (category == null)
            return FALLBACK_CATEGORY_NAME;
        try
        {
            String name = category.getName();
            if (name != null && !name.isBlank())
                return name;
        }
        catch (Exception ignored)
        {
            // категория ещё не определена в реестре команд
        }
        return FALLBACK_CATEGORY_NAME;
    }

    private static void applyCategoryFilter(IPreferencePage page, String filterText)
    {
        try
        {
            Field treeField = page.getClass().getDeclaredField("fFilteredTree"); //$NON-NLS-1$
            treeField.setAccessible(true);
            Object tree = treeField.get(page);
            if (!(tree instanceof FilteredTree))
                return;
            setFilterText((FilteredTree) tree, filterText);
        }
        catch (Exception ignored)
        {
            // внутренняя разметка Eclipse изменилась — страница откроется без предфильтра
        }
    }

    private static void setFilterText(FilteredTree tree, String text)
    {
        try
        {
            Method setFilterText = FilteredTree.class.getDeclaredMethod(
                "setFilterText", String.class); //$NON-NLS-1$
            setFilterText.setAccessible(true);
            setFilterText.invoke(tree, text);
            Method textChanged = FilteredTree.class.getDeclaredMethod("textChanged"); //$NON-NLS-1$
            textChanged.setAccessible(true);
            textChanged.invoke(tree);
        }
        catch (Exception ignored)
        {
            if (tree.getFilterControl() != null && !tree.getFilterControl().isDisposed())
                tree.getFilterControl().setText(text);
        }
    }
}

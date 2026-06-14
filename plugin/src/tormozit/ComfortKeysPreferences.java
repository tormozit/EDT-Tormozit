package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.commands.Category;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.internal.keys.model.BindingElement;
import org.eclipse.ui.internal.keys.model.CommonModel;
import org.eclipse.ui.internal.keys.model.ConflictModel;
import org.eclipse.ui.internal.keys.model.KeyController;

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

    /** Заголовок блока про глобальные горячие клавиши на странице «Комфорт». */
    public static final String GLOBAL_KEYS_SECTION_TITLE =
            "Глобальные горячие клавиши"; //$NON-NLS-1$

    /**
     * Пояснение: строка «Редактор формы» в Keys — автозеркало привязки «В окнах».
     */
    public static final String GLOBAL_KEYS_HINT =
            "Настраивайте только «В окнах». «Редактор формы» с тем же сочетанием — автозеркало плагина. "
            + "Не редактируйте (U/CU — не отдельная настройка)."; //$NON-NLS-1$

    private static final String FALLBACK_CATEGORY_NAME = "Комфорт"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_UI_KEY =
            "tormozit.comfort.keys.localConflictUi"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_TOOLBAR_KEY =
            "tormozit.comfort.keys.localConflictToolbar"; //$NON-NLS-1$

    private static final String LOCAL_CONFLICT_TABS_KEY =
            "tormozit.comfort.keys.localConflictTabs"; //$NON-NLS-1$

    private static final String HIDDEN_CONFLICT_HEADER_KEY =
            "tormozit.comfort.keys.hiddenConflictHeader"; //$NON-NLS-1$

    private ComfortKeysPreferences()
    {
    }

    /**
     * Фильтр «Комфорт» на уже открытой странице «Клавиши»
     * (после {@code IWorkbenchPreferenceContainer.openPage}).
     */
    public static void scheduleApplyEnhancements(IPreferencePageContainer container)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> scheduleApplyEnhancementsDelayed(container, 0));
    }

    /**
     * Устанавливает фильтр «Комфорт» на странице «Клавиши» в отдельном диалоге.
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

    /** Подключает UI и опционально фильтр «Комфорт» (filterText=null — без фильтра). */
    public static void tryApplyEnhancements(IPreferencePage page, String filterText)
    {
        if (!isKeysPreferencePage(page))
            return;
        applyEnhancements(page, filterText);
    }

    static boolean isKeysPreferencePage(IPreferencePage page)
    {
        if (page == null)
            return false;
        String className = page.getClass().getName();
        if (className.contains("KeysPreferencePage")) //$NON-NLS-1$
            return true;
        return resolveKeyController(page) != null && resolveConflictViewer(page) != null;
    }

    static boolean isLocalConflictUiInstalled(IPreferencePage page)
    {
        TableViewer viewer = resolveConflictViewer(page);
        if (viewer == null)
            return false;
        Table table = viewer.getTable();
        return table != null && !table.isDisposed()
                && (table.getData(LOCAL_CONFLICT_UI_KEY) instanceof LocalConflictUi
                        || Boolean.TRUE.equals(table.getData(LOCAL_CONFLICT_TABS_KEY)));
    }

    private static void scheduleApplyEnhancementsDelayed(
            IPreferencePageContainer container, int attempt)
    {
        IPreferencePage page = findKeysPageFromContainer(container);
        if (page != null)
        {
            tryApplyEnhancements(page, resolveCategoryFilterText());
            if (isLocalConflictUiInstalled(page))
                return;
        }
        if (attempt >= 30)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.timerExec(100, () -> scheduleApplyEnhancementsDelayed(container, attempt + 1));
    }

    private static IPreferencePage findKeysPageFromContainer(IPreferencePageContainer container)
    {
        if (container == null)
            return null;
        try
        {
            Method getSelectedPage = container.getClass().getMethod(
                "getSelectedPage"); //$NON-NLS-1$
            Object page = getSelectedPage.invoke(container);
            if (page instanceof IPreferencePage preferencePage
                    && isKeysPreferencePage(preferencePage))
                return preferencePage;
        }
        catch (Exception ignored)
        {
            // контейнер без getSelectedPage — пробуем поля
        }
        try
        {
            for (Field field : container.getClass().getDeclaredFields())
            {
                field.setAccessible(true);
                Object value = field.get(container);
                if (value instanceof PreferenceDialog dialog)
                {
                    Object page = dialog.getSelectedPage();
                    if (page instanceof IPreferencePage preferencePage
                            && isKeysPreferencePage(preferencePage))
                        return preferencePage;
                }
            }
        }
        catch (Exception ignored)
        {
            // внутренняя разметка Eclipse изменилась
        }
        return null;
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
            if (page instanceof IPreferencePage preferencePage)
                tryApplyEnhancements(preferencePage, filterText);
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

    private static void applyEnhancements(IPreferencePage page, String filterText)
    {
        removeMirrorBindingHint(page);
        FilteredTree tree = resolveFilteredTree(page);
        if (filterText != null && tree != null)
            setFilterText(tree, filterText);
        LocalConflictUi.install(page);
    }

    private static FilteredTree resolveFilteredTree(IPreferencePage page)
    {
        Object tree = resolvePageField(page, "fFilteredTree"); //$NON-NLS-1$
        if (tree instanceof FilteredTree filteredTree)
            return filteredTree;
        return null;
    }

    private static KeyController resolveKeyController(IPreferencePage page)
    {
        Object value = resolvePageField(page, "keyController"); //$NON-NLS-1$
        if (value instanceof KeyController controller)
            return controller;
        return null;
    }

    private static TableViewer resolveConflictViewer(IPreferencePage page)
    {
        Object value = resolvePageField(page, "conflictViewer"); //$NON-NLS-1$
        if (value instanceof TableViewer viewer)
            return viewer;
        return null;
    }

    private static Object resolvePageField(IPreferencePage page, String fieldName)
    {
        if (page == null)
            return null;
        Class<?> type = page.getClass();
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(page);
            }
            catch (Exception ignored)
            {
                // поле в суперклассе или переименовано
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void removeMirrorBindingHint(IPreferencePage page)
    {
        FilteredTree tree = resolveFilteredTree(page);
        if (tree == null || tree.isDisposed())
            return;

        Composite parent = tree.getParent();
        if (parent == null || parent.isDisposed())
            return;

        for (Control child : parent.getChildren())
        {
            if (child.isDisposed() || !(child instanceof Label label))
                continue;
            if (GLOBAL_KEYS_HINT.equals(label.getText()))
                label.dispose();
        }
        parent.layout(true, true);
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

    /**
     * Автоанализ локальных пересечений: вкладки «Глобальные» / «Локальные» в блоке конфликтов Keys.
     */
    private static final class LocalConflictUi
    {
        private static final String ANALYSIS_JOB_NAME =
                "Локальные пересечения клавиш"; //$NON-NLS-1$

        private static final String TAB_GLOBAL_BASE = "Конфликты глобальные"; //$NON-NLS-1$

        private static final String TAB_LOCAL_BASE = "Локальные"; //$NON-NLS-1$

        private static final String COLUMN_COMMAND = "Команда"; //$NON-NLS-1$

        private static final String COLUMN_WHEN = "Когда"; //$NON-NLS-1$

        static void install(IPreferencePage page)
        {
            if (page == null)
                return;

            KeyController keyController = ComfortKeysPreferences.resolveKeyController(page);
            TableViewer conflictViewer = ComfortKeysPreferences.resolveConflictViewer(page);
            if (keyController == null || conflictViewer == null)
                return;

            Table table = conflictViewer.getTable();
            if (table == null || table.isDisposed())
                return;

            synchronized (table)
            {
                if (table.getData(LOCAL_CONFLICT_UI_KEY) instanceof LocalConflictUi)
                    return;
                if (Boolean.TRUE.equals(table.getData(LOCAL_CONFLICT_TABS_KEY)))
                    return;

                Composite parent = table.getParent();
                if (parent != null && !parent.isDisposed())
                    removeDuplicateToolbars(parent, table);

                LocalConflictUi hook = new LocalConflictUi(page, keyController, conflictViewer);
                hook.installUi(table);
                table.setData(LOCAL_CONFLICT_UI_KEY, hook);
            }
        }

        private static void removeDuplicateToolbars(Composite parent, Table table)
        {
            for (Control child : parent.getChildren())
            {
                if (child == table || child.isDisposed())
                    continue;
                if (!Boolean.TRUE.equals(child.getData(LOCAL_CONFLICT_TOOLBAR_KEY)))
                    continue;
                child.dispose();
            }
        }

        /** Скрывает подпись «Конфликты:» (не dispose — Eclipse обновляет её через lambda$11). */
        private void hideConflictsGroupHeader()
        {
            if (tabFolder == null || tabFolder.isDisposed())
                return;

            Composite tabParent = tabFolder.getParent();
            if (tabParent != null && !tabParent.isDisposed())
                hideConflictLabelDirectlyAbove(tabParent, tabFolder);

            Composite rightDataArea = resolvePageComposite(page, "rightDataArea"); //$NON-NLS-1$
            if (rightDataArea != null)
                hideStaticConflictHeaderLabels(rightDataArea);

            tightenDataAreaLayout(page);

            Composite relayoutRoot = tabFolder.getParent();
            while (relayoutRoot != null && !(relayoutRoot instanceof Shell))
            {
                relayoutRoot.layout(true, true);
                relayoutRoot = relayoutRoot.getParent();
            }
        }

        private static Composite resolvePageComposite(IPreferencePage page, String fieldName)
        {
            Object value = ComfortKeysPreferences.resolvePageField(page, fieldName);
            if (value instanceof Composite composite)
                return composite;
            return null;
        }

        private static void hideConflictLabelDirectlyAbove(Composite parent, Control below)
        {
            Control previous = null;
            for (Control child : parent.getChildren())
            {
                if (child == below)
                {
                    if (previous != null && !previous.isDisposed()
                            && (previous instanceof Label || previous instanceof CLabel))
                        hideControl(previous);
                    return;
                }
                if (!child.isDisposed())
                    previous = child;
            }
        }

        private static void hideStaticConflictHeaderLabels(Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (child instanceof CTabFolder)
                    continue;
                if (isStaticConflictsHeader(child))
                    hideControl(child);
                else if (child instanceof Composite nested)
                    hideStaticConflictHeaderLabels(nested);
            }
        }

        private static void hideControl(Control control)
        {
            if (control == null || control.isDisposed())
                return;
            if (Boolean.TRUE.equals(control.getData(HIDDEN_CONFLICT_HEADER_KEY)))
                return;
            control.setData(HIDDEN_CONFLICT_HEADER_KEY, Boolean.TRUE);
            control.setVisible(false);
            Object layoutData = control.getLayoutData();
            if (layoutData instanceof GridData gd)
            {
                gd.exclude = true;
                gd.heightHint = 0;
                gd.verticalIndent = 0;
                gd.horizontalIndent = 0;
                control.setLayoutData(gd);
            }
        }

        private static void tightenDataAreaLayout(IPreferencePage page)
        {
            Composite dataArea = resolvePageComposite(page, "dataArea"); //$NON-NLS-1$
            if (dataArea == null || dataArea.isDisposed())
                return;

            Object layoutData = dataArea.getLayoutData();
            if (layoutData instanceof GridData gd)
            {
                gd.verticalIndent = 0;
                gd.verticalAlignment = SWT.FILL;
            }
        }

        private static boolean isStaticConflictsHeader(Control control)
        {
            return isStaticConflictsHeaderText(readControlText(control));
        }

        private static boolean isStaticConflictsHeaderText(String text)
        {
            if (text == null)
                return false;
            String trimmed = text.strip().replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (trimmed.endsWith(":")) //$NON-NLS-1$
                trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
            String lower = trimmed.toLowerCase();
            return "conflicts".equals(lower) || "конфликты".equals(lower); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static String readControlText(Control control)
        {
            if (control instanceof Label label)
                return label.getText();
            if (control instanceof CLabel clabel)
                return clabel.getText();
            return null;
        }

        private static GridData copyTableGridData(Table table)
        {
            Object layoutData = table.getLayoutData();
            if (layoutData instanceof GridData source)
            {
                GridData gd = new GridData(source.horizontalAlignment, source.verticalAlignment,
                        source.grabExcessHorizontalSpace, true,
                        source.horizontalSpan, source.verticalSpan);
                gd.minimumHeight = Math.max(80, source.minimumHeight);
                gd.widthHint = source.widthHint;
                gd.heightHint = source.heightHint;
                return gd;
            }
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.minimumHeight = 80;
            return gd;
        }

        private final IPreferencePage page;
        private final KeyController keyController;
        private final TableViewer conflictViewer;

        private CTabFolder tabFolder;
        private CTabItem globalTab;
        private CTabItem localTab;
        private TableViewer localConflictViewer;

        private List<Object> eclipseConflicts = Collections.emptyList();
        private List<ComfortKeysLocalConflictRow> localRows = Collections.emptyList();

        private Job analysisJob;
        private boolean disposed;

        private final IPropertyChangeListener keyControllerListener = this::onKeyControllerChange;

        private LocalConflictUi(
                IPreferencePage page,
                KeyController keyController,
                TableViewer conflictViewer)
        {
            this.page = page;
            this.keyController = keyController;
            this.conflictViewer = conflictViewer;
        }

        private void installUi(Table table)
        {
            Composite parent = table.getParent();
            if (parent == null || parent.isDisposed())
                return;

            GridData tabFolderGd = copyTableGridData(table);

            tabFolder = new CTabFolder(parent, SWT.FLAT);
            tabFolder.setLayoutData(tabFolderGd);
            tabFolder.setData(LOCAL_CONFLICT_TABS_KEY, Boolean.TRUE);
            tabFolder.moveAbove(table);

            globalTab = new CTabItem(tabFolder, SWT.NONE);
            Composite globalComposite = new Composite(tabFolder, SWT.NONE);
            globalComposite.setLayout(new GridLayout(1, false));
            globalTab.setControl(globalComposite);

            table.setParent(globalComposite);
            GridData tableGd = new GridData(SWT.FILL, SWT.FILL, true, true);
            table.setLayoutData(tableGd);

            localTab = new CTabItem(tabFolder, SWT.NONE);
            Composite localComposite = new Composite(tabFolder, SWT.NONE);
            localComposite.setLayout(new GridLayout(1, false));
            localTab.setControl(localComposite);
            localConflictViewer = createLocalConflictViewer(localComposite, table);

            tabFolder.setSelection(globalTab);

            hideConflictsGroupHeader();

            keyController.addPropertyChangeListener(keyControllerListener);

            table.addListener(SWT.Dispose, e -> dispose());
            tabFolder.addListener(SWT.Dispose, e -> dispose());

            captureEclipseConflicts();
            refreshLocalViewer();
            updateTabTitles();
            scheduleAutoAnalysis();

            parent.layout(true, true);
        }

        private TableViewer createLocalConflictViewer(Composite parent, Table referenceTable)
        {
            int style = referenceTable.getStyle();
            Table localTable = new Table(parent, style);
            localTable.setHeaderVisible(true);
            localTable.setLinesVisible(referenceTable.getLinesVisible());
            localTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            TableColumn commandColumn = new TableColumn(localTable, SWT.NONE);
            commandColumn.setText(COLUMN_COMMAND);
            TableColumn whenColumn = new TableColumn(localTable, SWT.NONE);
            whenColumn.setText(COLUMN_WHEN);

            if (referenceTable.getColumnCount() >= 2)
            {
                commandColumn.setWidth(referenceTable.getColumn(0).getWidth());
                whenColumn.setWidth(referenceTable.getColumn(1).getWidth());
            }
            else
            {
                commandColumn.setWidth(200);
                whenColumn.setWidth(280);
            }

            TableViewer viewer = new TableViewer(localTable);
            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setLabelProvider(new LocalConflictLabelProvider());
            viewer.setInput(Collections.emptyList());
            return viewer;
        }

        private void onKeyControllerChange(PropertyChangeEvent event)
        {
            if (disposed)
                return;

            Object source = event.getSource();
            String property = event.getProperty();

            if (source == keyController.getConflictModel())
            {
                if (ConflictModel.PROP_CONFLICTS.equals(property)
                        || ConflictModel.PROP_CONFLICTS_ADD.equals(property)
                        || ConflictModel.PROP_CONFLICTS_REMOVE.equals(property))
                {
                    scheduleRefreshFromEclipse();
                }
            }
            else if (source == keyController.getBindingModel()
                    && CommonModel.PROP_SELECTED_ELEMENT.equals(property))
            {
                scheduleAutoAnalysis();
            }
        }

        private void scheduleRefreshFromEclipse()
        {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            display.asyncExec(() -> {
                if (disposed)
                    return;
                captureEclipseConflicts();
                updateTabTitles();
            });
        }

        private void captureEclipseConflicts()
        {
            Object input = conflictViewer.getInput();
            if (!(input instanceof Collection<?> collection))
            {
                eclipseConflicts = Collections.emptyList();
                return;
            }
            List<Object> items = new ArrayList<>(collection.size());
            for (Object item : collection)
            {
                if (item instanceof BindingElement)
                    items.add(item);
            }
            eclipseConflicts = items;
        }

        private void clearLocalRows()
        {
            localRows = Collections.emptyList();
            refreshLocalViewer();
        }

        private void refreshLocalViewer()
        {
            if (disposed || localConflictViewer == null)
                return;
            Table localTable = localConflictViewer.getTable();
            if (localTable == null || localTable.isDisposed())
                return;
            localConflictViewer.setInput(localRows);
            updateTabTitles();
        }

        private void updateTabTitles()
        {
            if (tabFolder == null || tabFolder.isDisposed())
                return;
            if (globalTab != null && !globalTab.isDisposed())
                globalTab.setText(TAB_GLOBAL_BASE + " (" + eclipseConflicts.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            if (localTab != null && !localTab.isDisposed())
                localTab.setText(TAB_LOCAL_BASE + " (" + localRows.size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void scheduleAutoAnalysis()
        {
            if (disposed)
                return;

            BindingElement selected = getSelectedBinding();
            if (selected == null || selected.getTrigger() == null)
            {
                clearLocalRows();
                return;
            }

            if (analysisJob != null)
                analysisJob.cancel();

            final BindingElement selectedBinding = selected;
            analysisJob = new Job(ANALYSIS_JOB_NAME)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    List<ComfortKeysLocalConflictRow> rows =
                            ComfortKeysLocalConflictAnalyzer.analyze(
                                    keyController, selectedBinding, monitor);

                    Display display = Display.getDefault();
                    if (display == null || display.isDisposed())
                        return Status.CANCEL_STATUS;

                    display.asyncExec(() -> {
                        if (disposed)
                            return;
                        if (getSelectedBinding() != selectedBinding)
                            return;
                        localRows = rows;
                        refreshLocalViewer();
                    });
                    return monitor.isCanceled()
                            ? Status.CANCEL_STATUS
                            : Status.OK_STATUS;
                }
            };
            analysisJob.setSystem(true);
            analysisJob.setUser(false);
            analysisJob.schedule();
        }

        private BindingElement getSelectedBinding()
        {
            Object selected = keyController.getBindingModel().getSelectedElement();
            if (selected instanceof BindingElement bindingElement)
                return bindingElement;
            return null;
        }

        private void dispose()
        {
            if (disposed)
                return;
            disposed = true;
            if (analysisJob != null)
                analysisJob.cancel();
            keyController.removePropertyChangeListener(keyControllerListener);
            Table table = conflictViewer.getTable();
            if (table != null && !table.isDisposed())
                table.setData(LOCAL_CONFLICT_UI_KEY, null);
            if (tabFolder != null && !tabFolder.isDisposed())
                tabFolder.setData(LOCAL_CONFLICT_TABS_KEY, null);
        }
    }

    private static final class LocalConflictLabelProvider implements ITableLabelProvider
    {
        @Override
        public Image getColumnImage(Object element, int columnIndex)
        {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex)
        {
            if (!(element instanceof ComfortKeysLocalConflictRow row))
                return ""; //$NON-NLS-1$
            return columnIndex == 0
                    ? row.commandColumnText()
                    : row.contextColumnText();
        }

        @Override
        public void addListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
            // read-only provider
        }

        @Override
        public void dispose()
        {
            // nothing
        }

        @Override
        public boolean isLabelProperty(Object element, String property)
        {
            return false;
        }

        @Override
        public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener listener)
        {
            // read-only provider
        }
    }
}

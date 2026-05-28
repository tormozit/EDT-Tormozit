import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;

/**
 * Перехватчик открытия окна Quick Outline для замены стандартного поиска на "Умный".
 */
public class SmartOutlineHook implements IStartup {

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            install(Display.getDefault());
        });
    }
    
    private static final String PATCHED_KEY = "tormozit.outlinePatched";

    public static void install(Display display) {
        if (display == null || display.isDisposed()) return;

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell)) return;
                
                Shell shell = (Shell) event.widget;
                if (shell.getData(PATCHED_KEY) != null) return;

                display.asyncExec(() -> {
                    if (!shell.isDisposed()) {
                        tryPatchOutline(shell);
                    }
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static void tryPatchOutline(Shell shell) {
        Text filterText = findTextWidget(shell);
        Tree treeWidget = findTreeWidget(shell);

        if (filterText == null || treeWidget == null) return;

        TreeViewer viewer = findTreeViewer(treeWidget, shell);
        if (viewer == null || viewer.getContentProvider() == null) return;

        String lpName = viewer.getLabelProvider() != null ? viewer.getLabelProvider().getClass().getName() : "";
        String cpName = viewer.getContentProvider() != null ? viewer.getContentProvider().getClass().getName() : "";
        String shellName = shell.getClass().getName();
        Object dialog = shell.getData();
        String dialogName = dialog != null ? dialog.getClass().getName() : "";
        
        boolean isOutline = lpName.contains("Outline") || cpName.contains("Outline") 
                         || shellName.contains("Outline") || dialogName.contains("Outline");
        
        if (!isOutline) return;

        shell.setData(PATCHED_KEY, Boolean.TRUE);

        applySmartSearch(viewer, filterText);
    }

    private static TreeViewer findTreeViewer(Tree treeWidget, Shell shell) {
        Composite parent = treeWidget.getParent();
        while (parent != null) {
            Object viewer = Global.invoke(parent, "getTreeViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

            viewer = Global.invoke(parent, "getViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

            for (String fieldName : new String[]{"treeViewer", "viewer", "fTreeViewer", "bslTreeViewer"}) {
                Object fViewer = Global.getField(parent, fieldName);
                if (fViewer instanceof TreeViewer) return (TreeViewer) fViewer;
            }

            if (parent == shell) break;
            parent = parent.getParent();
        }

        Object dialog = shell.getData();
        if (dialog != null) {
            for (String fieldName : new String[]{"treeViewer", "viewer", "fTreeViewer", "outlineViewer"}) {
                Object viewer = Global.getField(dialog, fieldName);
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
            }
            Object viewer = Global.invoke(dialog, "getTreeViewer");
            if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
        }

        int[] vitalEvents = { SWT.Selection, SWT.Expand, SWT.Collapse };
        for (int eventType : vitalEvents) {
            for (Listener listener : treeWidget.getListeners(eventType)) {
                Object outer = Global.getField(listener, "this$0");
                if (outer instanceof TreeViewer) return (TreeViewer) outer;

                Object viewer = Global.getField(listener, "viewer");
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;

                viewer = Global.getField(listener, "treeViewer");
                if (viewer instanceof TreeViewer) return (TreeViewer) viewer;
            }
        }
        return null;
    }

    private static void applySmartSearch(TreeViewer viewer, Text filterText) {
        for (ViewerFilter filter : viewer.getFilters()) {
            viewer.removeFilter(filter);
        }

        IBaseLabelProvider rawLp = viewer.getLabelProvider();
        ILabelProvider baseLp = createLabelProviderAdapter(rawLp);
        
        SmartOutlineFilter smartFilter = new SmartOutlineFilter(baseLp);
        smartFilter.setPattern(filterText.getText());
        
        IStyledLabelProvider innerStyledLp = null;
        if (rawLp instanceof DelegatingStyledCellLabelProvider) {
            innerStyledLp = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
        } else if (rawLp instanceof IStyledLabelProvider) {
            innerStyledLp = (IStyledLabelProvider) rawLp;
        }

        final SmartOutlineLabelProvider finalSmartLabelProvider;

        if (innerStyledLp != null) {
            SmartOutlineLabelProvider smartLabelProvider = new SmartOutlineLabelProvider(innerStyledLp);
            smartLabelProvider.setPattern(filterText.getText());
            
            if (rawLp instanceof DelegatingStyledCellLabelProvider) {
                injectStyledStringProvider((DelegatingStyledCellLabelProvider) rawLp, smartLabelProvider);
            } else {
                viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(smartLabelProvider));
            }
            finalSmartLabelProvider = smartLabelProvider;
        } else {
            finalSmartLabelProvider = null;
        }

        viewer.addFilter(smartFilter);
         viewer.setComparator(new SmartOutlineComparator(
             smartFilter.getNamePremiumCache(), 
             smartFilter.getParamPremiumCache(), 
             baseLp
         ));
        // Модификация текста (живой фильтр)
        filterText.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {            
                String pattern = filterText.getText();

                smartFilter.refreshPattern(pattern);

                if (finalSmartLabelProvider != null) {
                    finalSmartLabelProvider.setPattern(pattern);
                }

                viewer.refresh();

                if (!pattern.trim().isEmpty()) {
                    TreeItem firstMatch = findFirstMatchingItem(viewer.getTree().getItems(), pattern);
                    if (firstMatch != null && !firstMatch.isDisposed()) {
                        viewer.getTree().setSelection(firstMatch);
                        
                        Event selectionEvent = new Event();
                        selectionEvent.item = firstMatch;
                        viewer.getTree().notifyListeners(SWT.Selection, selectionEvent);
                    }
                }
            }
        });

        // Внедряем обработку клавиш Down и Up для мгновенной навигации
        filterText.addListener(SWT.KeyDown, new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP) {
                    Tree tree = viewer.getTree();
                    TreeItem[] selection = tree.getSelection();
                    TreeItem targetItem = null;

                    if (selection.length == 0) {
                        if (tree.getItemCount() > 0) {
                            targetItem = tree.getItem(0);
                        }
                    } else {
                        TreeItem current = selection[0];
                        if (event.keyCode == SWT.ARROW_DOWN) {
                            targetItem = getNextVisibleItem(tree, current);
                        } else {
                            targetItem = getPreviousVisibleItem(tree, current);
                        }
                    }

                    if (targetItem != null && !targetItem.isDisposed()) {
                        tree.setSelection(targetItem);
                        tree.showItem(targetItem);
                        
                        // Посылаем сигнал выбора строки в 1C:EDT
                        Event selectionEvent = new Event();
                        selectionEvent.item = targetItem;
                        tree.notifyListeners(SWT.Selection, selectionEvent);
                        
                        // doIt = false останавливает дефолтную SWT-передачу фокуса
                        event.doit = false;
                    }
                }
            }
        });
    }

    // --- Методы точного расчета следующего/предыдущего видимого элемента в Tree ---

    private static TreeItem getNextVisibleItem(Tree tree, TreeItem item) {
        if (item.getExpanded() && item.getItemCount() > 0) {
            return item.getItem(0);
        }
        return getNextSiblingOrParentSibling(tree, item);
    }

    private static TreeItem getNextSiblingOrParentSibling(Tree tree, TreeItem item) {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = (parent == null) ? tree.getItems() : parent.getItems();
        
        int index = indexOfItem(siblings, item);
        if (index >= 0 && index < siblings.length - 1) {
            return siblings[index + 1];
        }
        
        if (parent != null) {
            return getNextSiblingOrParentSibling(tree, parent);
        }
        return null;
    }

    private static TreeItem getPreviousVisibleItem(Tree tree, TreeItem item) {
        TreeItem parent = item.getParentItem();
        TreeItem[] siblings = (parent == null) ? tree.getItems() : parent.getItems();
        
        int index = indexOfItem(siblings, item);
        if (index > 0) {
            TreeItem prevSibling = siblings[index - 1];
            return getLastVisibleLeaf(prevSibling);
        }
        return parent; // Если брать выше первого ребенка — попадем на родителя
    }

    private static TreeItem getLastVisibleLeaf(TreeItem item) {
        if (item.getExpanded() && item.getItemCount() > 0) {
            return getLastVisibleLeaf(item.getItem(item.getItemCount() - 1));
        }
        return item;
    }

    private static int indexOfItem(TreeItem[] items, TreeItem item) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == item) return i;
        }
        return -1;
    }

    private static TreeItem findFirstMatchingItem(TreeItem[] items, String pattern) {
        if (items == null || items.length == 0) return null;
        if (pattern == null || pattern.isEmpty()) return items[0];

        String lowerPattern = pattern.toLowerCase();
        for (TreeItem item : items) {
            String text = item.getText() != null ? item.getText().toLowerCase() : "";
            
            if (text.contains(lowerPattern) || item.getItemCount() == 0) {
                return item;
            }
            
            if (item.getItemCount() > 0) {
                TreeItem childMatch = findFirstMatchingItem(item.getItems(), pattern);
                if (childMatch != null) {
                    return childMatch;
                }
            }
        }
        return items[0];
    }
    
    private static ILabelProvider createLabelProviderAdapter(IBaseLabelProvider rawLp) {
        return new ILabelProvider() {
            @Override
            public String getText(Object element) {
                if (rawLp instanceof ILabelProvider) {
                    return ((ILabelProvider) rawLp).getText(element);
                }
                if (rawLp instanceof DelegatingStyledCellLabelProvider) {
                    IStyledLabelProvider styledProvider = ((DelegatingStyledCellLabelProvider) rawLp).getStyledStringProvider();
                    if (styledProvider != null) {
                        org.eclipse.jface.viewers.StyledString ss = styledProvider.getStyledText(element);
                        return ss != null ? ss.getString() : "";
                    }
                }
                Object text = Global.invoke(rawLp, "getText", element);
                if (text instanceof String) return (String) text;
                return element != null ? element.toString() : "";
            }

            @Override
            public org.eclipse.swt.graphics.Image getImage(Object element) {
                if (rawLp instanceof ILabelProvider) {
                    return ((ILabelProvider) rawLp).getImage(element);
                }
                Object img = Global.invoke(rawLp, "getImage", element);
                if (img instanceof org.eclipse.swt.graphics.Image) return (org.eclipse.swt.graphics.Image) img;
                return null;
            }

            @Override public void addListener(org.eclipse.jface.viewers.ILabelProviderListener l) { if (rawLp != null) rawLp.addListener(l); }
            @Override public void dispose() { if (rawLp != null) rawLp.dispose(); }
            @Override public boolean isLabelProperty(Object e, String p) { return rawLp != null && rawLp.isLabelProperty(e, p); }
            @Override public void removeListener(org.eclipse.jface.viewers.ILabelProviderListener l) { if (rawLp != null) rawLp.removeListener(l); }
        };
    }
    
    private static void injectStyledStringProvider(DelegatingStyledCellLabelProvider provider, IStyledLabelProvider smartProvider) {
        Class<?> cls = provider.getClass();
        while (cls != null) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
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
        for (Control control : parent.getChildren()) {
            if (control instanceof Text) return (Text) control;
            if (control instanceof Composite) {
                Text result = findTextWidget((Composite) control);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static Tree findTreeWidget(Composite parent) {
        for (Control control : parent.getChildren()) {
            if (control instanceof Tree) return (Tree) control;
            if (control instanceof Composite) {
                Tree result = findTreeWidget((Composite) control);
                if (result != null) return result;
            }
        }
        return null;
    }
}
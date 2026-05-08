package tormozit.edt.compare.open_object.menu;

import java.lang.reflect.Field;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;

import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Динамический вклад в контекстное меню дерева сравнения.
 *
 * Eclipse вызывает getContributionItems() непосредственно перед каждым показом
 * контекстного меню — именно это гарантирует учёт роли текущей строки в дереве.
 *
 * Логика:
 *   - Если выбранная строка является MatchedObjectsComparisonNode (объект конфигурации)
 *     → возвращаем CommandContributionItem с командой openObject
 *   - Для любой другой строки (свойство, лист дерева и т.п.)
 *     → возвращаем пустой массив, пункт меню не появляется вовсе
 */
public class OpenObjectContribution extends CompoundContributionItem {

    private static final String EDITOR_ID = "com._1c.g5.v8.dt.compare.ui.editor";
    private static final String COMMAND_ID = "tormozit.edt.compare.open_object.openObject";

    @Override
    protected IContributionItem[] getContributionItems() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            return empty();

        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null || !EDITOR_ID.equals(editor.getSite().getId()))
            return empty();

        if (getSelectedMatchedNode(editor) == null)
            return empty();

        CommandContributionItemParameter p = new CommandContributionItemParameter(
                window, null, COMMAND_ID, CommandContributionItem.STYLE_PUSH);
        p.label   = "Открыть объект";
        p.tooltip = "Открыть выбранный объект (F4)";
        return new IContributionItem[] { new CommandContributionItem(p) };
    }

    // ---- Получение выбранного узла (аналогично OpenObjectHandler) ----

    private MatchedObjectsComparisonNode getSelectedMatchedNode(IEditorPart editor) {
        Object view = getField(editor, "comparisonView");
        if (!(view instanceof DtComparisonView))
            return null;

        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null)
            return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer");
        if (viewer == null)
            return null;

        Object sel = invokeNoArg(viewer, "getSelection");
        if (!(sel instanceof IStructuredSelection))
            return null;

        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null)
            return null;

        // Обычный случай: element — «частичный» узел, оборачивающий ComparisonNode
        try {
            Object node = invokeNoArg(element, "retrieveComparisonNode");
            if (node instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) node;
        } catch (Exception ignored) {}

        // Фоллбэк: element уже является MatchedObjectsComparisonNode
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;

        return null;
    }

    // ---- Утилиты рефлексии ----

    private Object getField(Object obj, String name) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Object invokeNoArg(Object o, String name) {
        if (o == null) return null;
        try {
            return o.getClass().getMethod(name).invoke(o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static IContributionItem[] empty() {
        return new IContributionItem[0];
    }
}

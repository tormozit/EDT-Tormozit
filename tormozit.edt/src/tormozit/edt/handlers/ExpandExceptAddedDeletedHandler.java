package tormozit.edt.handlers;

import java.lang.reflect.Field;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.ui.IEditorPart;

import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Разворачивает дерево сравнения EDT, пропуская добавленные/удалённые объекты.
 *
 * <p>Режимы ({@link ExpandMode}):
 * <ul>
 *   <li>{@code toBothElement} — раскрывает всё, пропуская добавленные/удалённые
 *       (узлы с одной стороной сравнения).</li>
 *   <li>{@code toObject} — раскрывает до уровня объектов конфигурации
 *       (до первого {@link MatchedObjectsComparisonNode} с ненулевым ID),
 *       не углубляясь внутрь объектов.</li>
 * </ul>
 *
 * <h3>Оптимизации</h3>
 * <ul>
 *   <li>{@code setRedraw(false)} на время всей операции — SWT не перерисовывает
 *       дерево после каждого {@code expandToLevel}, итоговая перерисовка одна.</li>
 *   <li>{@code isObject()} не вызывает {@code getEObject()} (BM-транзакция),
 *       а проверяет только наличие ID в {@link MatchedObjectsComparisonNode}.</li>
 * </ul>
 */
public class ExpandExceptAddedDeletedHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        return null;
    }

    public static Object expand(IEditorPart editor, ExpandMode mode)
    {
        AbstractTreeViewer viewer = getTreeViewer(editor);
        if (viewer == null)
            return null;
        ITreeContentProvider cp =
            (ITreeContentProvider) viewer.getContentProvider();
        if (cp == null)
            return null;
        ISelection selection = viewer.getSelection();
        viewer.collapseAll();
        for (Object root : cp.getElements(viewer.getInput()))
            expandSelectively(viewer, cp, root, mode);
        if (selection != null && !selection.isEmpty())
            viewer.setSelection(selection, true);
        return null;
    }

    /**
     * Рекурсивно раскрывает узел с учётом режима.
     */
    private static void expandSelectively(AbstractTreeViewer viewer,
            ITreeContentProvider cp, Object element, ExpandMode mode)
    {
        if (false 
            || !cp.hasChildren(element)
            || mode == ExpandMode.toBothElement && isAddedOrDeleted(element)
            || mode == ExpandMode.toObject && isObject(element))
       {
            viewer.expandToLevel(element, 0); 
            return;       
       }
//        viewer.expandToLevel(element, 1);
        for (Object child : cp.getChildren(element))
            expandSelectively(viewer, cp, child, mode);
    }

    /**
     * Возвращает {@code true}, если элемент является узлом объекта конфигурации
     * (имеет хотя бы один ненулевой BM-идентификатор в {@link MatchedObjectsComparisonNode}).
     *
     * <p><b>Не вызывает {@code getEObject()} и не открывает BM-транзакцию</b> —
     * только проверяет поля узла, что на порядок быстрее.
     */
    private static boolean isObject(Object element)
    {
        MatchedObjectsComparisonNode node = extractMatchedNode(element);
        if (node == null)
            return false;

        Long mainId  = node.getMainObjectId();
        Long otherId = node.getOtherObjectId();
        return (mainId  != null && mainId  != -1L)
            || (otherId != null && otherId != -1L);
    }

    /**
     * Возвращает {@code true}, если объект присутствует только в одной стороне
     * сравнения (добавлен или удалён).
     */
    private static boolean isAddedOrDeleted(Object element)
    {
        MatchedObjectsComparisonNode node = extractMatchedNode(element);
        return !(node == null || node.getNodeSide()== null);
    }

    /**
     * Извлекает {@link MatchedObjectsComparisonNode} из обёртки элемента дерева,
     * или {@code null} если элемент не является таким узлом.
     */
    private static MatchedObjectsComparisonNode extractMatchedNode(Object element)
    {
        Object raw = invokeNoArg(element, "retrieveComparisonNode"); //$NON-NLS-1$
        if (raw instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) raw;
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }

    // ---- Получение TreeViewer из редактора ----

    private static AbstractTreeViewer getTreeViewer(IEditorPart editor)
    {
        Object view = getField(editor, "comparisonView"); //$NON-NLS-1$
        if (!(view instanceof DtComparisonView))
            return null;

        Object treeControl = ((DtComparisonView) view).getTreeControl();
        if (treeControl == null)
            return null;

        Object viewer = invokeNoArg(treeControl, "getTreeViewer"); //$NON-NLS-1$
        return (viewer instanceof AbstractTreeViewer)
            ? (AbstractTreeViewer) viewer : null;
    }

    // ---- Утилиты рефлексии ----

    static Object getField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored)
            {
                cls = cls.getSuperclass();
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    static Object invokeNoArg(Object o, String name)
    {
        if (o == null)
            return null;
        try
        {
            return o.getClass().getMethod(name).invoke(o);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}

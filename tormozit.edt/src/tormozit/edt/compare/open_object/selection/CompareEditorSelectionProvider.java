package tormozit.edt.compare.open_object.selection;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorPart;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;

import tormozit.edt.compare.open_object.handlers.OpenObjectHandler;

/**
 * ISelectionProvider для редактора сравнения, транслирующий узел дерева
 * (MatchedObjectsComparisonNode) в соответствующий EObject.
 *
 * <p>Устанавливается через {@code editor.getSite().setSelectionProvider()} и
 * подписывается непосредственно на тот же tree viewer, что и редактор EDT.
 * Каждый раз, когда пользователь выбирает объект конфигурации в дереве
 * сравнения, этот провайдер публикует resolved {@link EObject} через
 * стандартный механизм {@link org.eclipse.ui.ISelectionService} рабочей
 * среды Eclipse.
 *
 * <p>Благодаря этому любая панель, подписанная на
 * {@link org.eclipse.ui.ISelectionService} (панели «История», «Ошибки
 * конфигурации» и другие), автоматически обновляет свой отбор при
 * активации объекта в дереве сравнения конфигураций.
 *
 * <p>Если выбранный элемент не является {@link MatchedObjectsComparisonNode}
 * (например, свойство объекта) или EObject не удалось получить — публикуется
 * пустое выделение {@link StructuredSelection#EMPTY}.
 */
public class CompareEditorSelectionProvider
        implements ISelectionProvider, ISelectionChangedListener
{
    private final IEditorPart editor;
    private final List<ISelectionChangedListener> listeners = new ArrayList<>();
    private ISelection currentSelection = StructuredSelection.EMPTY;

    /**
     * @param editor редактор сравнения EDT
     */
    public CompareEditorSelectionProvider(IEditorPart editor)
    {
        this.editor = editor;
    }

    /**
     * Подключает tree viewer к провайдеру.
     * Вызывается асинхронно из {@code hookEditor()} — после того, как EDT
     * создаёт {@code comparisonView} и tree viewer становится доступным.
     * К этому моменту workbench уже подключился к провайдеру через
     * {@code addSelectionChangedListener} (в {@code partActivated}),
     * поэтому события дерева будут корректно транслироваться в EObject
     * и доставляться через {@code ISelectionService} всем подписанным панелям.
     */
    public void setTreeViewer(AbstractTreeViewer treeViewer)
    {
        if (treeViewer != null)
            treeViewer.addSelectionChangedListener(this);
    }

    // ---- ISelectionProvider ----

    @Override
    public void addSelectionChangedListener(ISelectionChangedListener listener)
    {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    @Override
    public void removeSelectionChangedListener(ISelectionChangedListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public ISelection getSelection()
    {
        return currentSelection;
    }

    @Override
    public void setSelection(ISelection selection)
    {
        // Внешние вызовы setSelection не переносим на дерево,
        // т.к. не управляем им напрямую. Просто публикуем как есть.
        currentSelection = (selection != null) ? selection : StructuredSelection.EMPTY;
        fireSelectionChanged(currentSelection);
    }

    // ---- ISelectionChangedListener (слушаем tree viewer напрямую) ----

    @Override
    public void selectionChanged(SelectionChangedEvent event)
    {
        EObject eObject = resolveEObject(event.getSelection());
        currentSelection = (eObject != null)
                ? new StructuredSelection(eObject)
                : StructuredSelection.EMPTY;
        fireSelectionChanged(currentSelection);
    }

    // ---- Внутренние утилиты ----

    /**
     * Транслирует выделение дерева в EObject:
     * element → MatchedObjectsComparisonNode → bmId → EObject.
     * Возвращает {@code null}, если элемент не является объектом конфигурации
     * или EObject не удалось получить через сессию сравнения.
     */
    private EObject resolveEObject(ISelection selection)
    {
        if (!(selection instanceof IStructuredSelection))
            return null;

        Object element = ((IStructuredSelection) selection).getFirstElement();
        if (element == null)
            return null;

        MatchedObjectsComparisonNode matchedNode = resolveMatchedNode(element);
        if (matchedNode == null)
            return null;

        IComparisonSession session = OpenObjectHandler.getSession(editor);
        if (session == null)
            return null;

        Long bmId = matchedNode.getMainObjectId();
        if (bmId == null || bmId == -1L)
            bmId = matchedNode.getOtherObjectId();
        if (bmId == null || bmId == -1L)
            return null;

        return OpenObjectHandler.getEObject(session, bmId, matchedNode);
    }

    /**
     * Разворачивает произвольный элемент дерева в
     * {@link MatchedObjectsComparisonNode}, поддерживая:
     * <ul>
     *   <li>PartialMatchedObjectComparisonNode через рефлексию
     *       {@code retrieveComparisonNode()}</li>
     *   <li>прямое приведение типа</li>
     * </ul>
     */
    private static MatchedObjectsComparisonNode resolveMatchedNode(Object element)
    {
        try
        {
            Object node = element.getClass()
                    .getMethod("retrieveComparisonNode") //$NON-NLS-1$
                    .invoke(element);
            if (node instanceof MatchedObjectsComparisonNode)
                return (MatchedObjectsComparisonNode) node;
        }
        catch (Exception ignored)
        {
        }
        if (element instanceof MatchedObjectsComparisonNode)
            return (MatchedObjectsComparisonNode) element;
        return null;
    }

    private void fireSelectionChanged(ISelection selection)
    {
        if (listeners.isEmpty())
            return;
        SelectionChangedEvent event = new SelectionChangedEvent(this, selection);
        for (ISelectionChangedListener l : new ArrayList<>(listeners))
        {
            try
            {
                l.selectionChanged(event);
            }
            catch (Exception ignored)
            {
            }
        }
    }
}

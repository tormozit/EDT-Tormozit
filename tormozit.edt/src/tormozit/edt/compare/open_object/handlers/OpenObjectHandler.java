package tormozit.edt.compare.open_object.handlers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.datasource.IActiveComparisonDataSource;
import com._1c.g5.v8.dt.compare.datasource.IComparisonDataSource;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;

/**
 * Открывает объект конфигурации выбранный в дереве сравнения EDT.
 *
 * Алгоритм:
 * 1. Получаем IComparisonSession из поля comparisonArtifactsList редактора
 * 2. Получаем MatchedObjectsComparisonNode из comparisonView
 * 3. Берём mainObjectId (bmId) из узла
 * 4. Получаем EObject через IActiveComparisonDataSource.getObjectById()
 * 5. Открываем через OpenHelper
 */
public class OpenObjectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        openObject(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }

    /**
     * Основная логика вынесена в static, чтобы её можно было вызывать
     * напрямую с захваченным IEditorPart — без зависимости
     * от фокуса / activeContexts / activeWhen.
     */
    public static void openObject(IEditorPart editor, Shell shell) {
        if (editor == null) return;

        // Шаг 1: IComparisonSession
        IComparisonSession session = getSession(editor);
        if (session == null) {
            showError(shell, "Не удалось получить сессию сравнения.");
            return;
        }

        // Шаг 2: Выбранный узел дерева
        MatchedObjectsComparisonNode matchedNode = getSelectedMatchedNode(editor);
        if (matchedNode == null) {
            showError(shell, "Выберите объект в дереве сравнения.\n"
                + "(Выбранный элемент должен быть объектом конфигурации, не свойством)");
            return;
        }

        // Шаг 3: bmId главной стороны
        Long bmId = matchedNode.getMainObjectId();
        if (bmId == null || bmId == -1L) {
            // Объект есть только в другой стороне — берём оттуда
            bmId = matchedNode.getOtherObjectId();
        }
        if (bmId == null || bmId == -1L) {
            showError(shell, "Объект не найден ни в одной из сторон сравнения.");
            return;
        }

        // Шаг 4: EObject через IActiveComparisonDataSource
        EObject eObject = getEObject(session, bmId, matchedNode);
        if (eObject == null) {
            showError(shell, "Не удалось получить объект по идентификатору: " + bmId);
            return;
        }

        // Шаг 5: Открываем редактор
        openInEditor(eObject, editor, shell);
    }

    public static IComparisonSession getSession(IEditorPart editor)
    {
        // Из comparisonArtifactsList
        Object list = getField(editor, "comparisonArtifactsList");
        if (list instanceof List) {
            for (Object artifact : (List<?>) list) {
                Object session = invokeNoArg(artifact, "getSession");
                if (session instanceof IComparisonSession) {
                    return (IComparisonSession) session;
                }
            }
        }
        // Fallback: из root
        Object root = getField(editor, "root");
        if (root != null) {
            Object session = invokeNoArg(root, "getComparisonSession");
            if (session instanceof IComparisonSession) return (IComparisonSession) session;
        }
        return null;
    }

    private static MatchedObjectsComparisonNode getSelectedMatchedNode(IEditorPart editor) {
        // Через comparisonView -> treeViewer -> selection
        Object view = getField(editor, "comparisonView");
        if (view instanceof DtComparisonView) {
            Object treeControl = ((DtComparisonView) view).getTreeControl();
            if (treeControl != null) {
                Object viewer = invokeNoArg(treeControl, "getTreeViewer");
                if (viewer != null)
                {
                    Object sel = invokeNoArg(viewer, "getSelection");
                    if (sel instanceof IStructuredSelection)
                    {
                        Object partialNode = ((IStructuredSelection)sel).getFirstElement();
                        ComparisonNode comparisonNode = null;
                        try
                        {
                            comparisonNode = (ComparisonNode)invokeNoArg(partialNode, "retrieveComparisonNode");
                        }
                        catch (Exception ignored)
                        {
                        }
                        return (MatchedObjectsComparisonNode)comparisonNode;
                    }
                }
            }
        }
        return null;
    }

    public static EObject getEObject(IComparisonSession session, long bmId,
            MatchedObjectsComparisonNode node) {

        // Определяем сторону: MAIN если mainObjectId есть, иначе OTHER
        ComparisonSide side = (node.getMainObjectId() != null && node.getMainObjectId() != -1L)
            ? ComparisonSide.MAIN
            : ComparisonSide.OTHER;

        IComparisonDataSource dataSource = session.getDataSource(side);
        if (dataSource instanceof IActiveComparisonDataSource) {
            EObject obj = ((IActiveComparisonDataSource) dataSource).getObjectById(bmId);
            if (obj != null) return obj;
        }

        // Попробуем другую сторону
        ComparisonSide otherSide = (side == ComparisonSide.MAIN)
            ? ComparisonSide.OTHER : ComparisonSide.MAIN;
        IComparisonDataSource otherSource = session.getDataSource(otherSide);
        if (otherSource instanceof IActiveComparisonDataSource) {
            Long otherId = (otherSide == ComparisonSide.MAIN)
                ? node.getMainObjectId() : node.getOtherObjectId();
            if (otherId != null && otherId != -1L) {
                return ((IActiveComparisonDataSource) otherSource).getObjectById(otherId);
            }
        }
        return null;
    }

    private static void openInEditor(EObject eObject, IEditorPart editor, Shell shell) {
        // Используем OpenHelper из EDT UI
        try {
            Class<?> cls = Class.forName("com._1c.g5.v8.dt.ui.util.OpenHelper");
            Object helper = cls.getConstructor().newInstance();
            // openEditor(EObject) — основной метод
            for (Method m : cls.getMethods()) {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(eObject.getClass())) {
                    Object result = m.invoke(helper, eObject);
                    if (result != null) return;
                }
            }
            // openEditor(EObject, IWorkbenchPage)
            for (Method m : cls.getMethods()) {
                if (!"openEditor".equals(m.getName()) || m.getParameterCount() != 2) continue;
                try {
                    m.invoke(helper, eObject, editor.getSite().getPage());
                    return;
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException e) {
            showError(shell, "OpenHelper не найден: " + e.getMessage());
            return;
            } catch (Exception e) {
            showError(shell, "Ошибка OpenHelper: " + e.getMessage());
            return;
        }

        showError(shell, "Объект найден, но редактор не открылся.\nТип: "
            + eObject.getClass().getName());
    }

    // ---- Utility ----

    private static Object getField(Object obj, String name) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    private static Object invokeNoArg(Object o, String name) {
        if (o == null) return null;
        try { return o.getClass().getMethod(name).invoke(o); }
        catch (Exception ignored) { return null; }
    }

    private static void showError(Shell shell, String msg) {
        try {
            MessageDialog.openInformation(shell, "Открыть объект", msg);
        } catch (Exception ignored) {}
    }
}

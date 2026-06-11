package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.Notifier;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.ChoiceParameterItem;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.ChoiceParametersViewModel;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.MdAefPackage;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;

/**
 * В диалоге «Редактирование параметров выбора» автоматически выставляет тип значения
 * при выборе имени параметра ({@code Отбор.*} / {@code Filter.*}).
 */
public class ChoiceParametersHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.choiceParamsPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.choiceParamsSession"; //$NON-NLS-1$
    private static final String DIALOG_TITLE =
            "\u0420\u0435\u0434\u0430\u043a\u0442\u0438\u0440\u043e\u0432\u0430\u043d\u0438\u0435 \u043f\u0430\u0440\u0430\u043c\u0435\u0442\u0440\u043e\u0432 \u0432\u044b\u0431\u043e\u0440\u0430"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.components.choiceparameters.ChoiceParametersDialog"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isChoiceParametersShell(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isChoiceParametersShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && DIALOG_CLASS.equals(data.getClass().getName()))
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 12)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog == null)
            dialog = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
            return false;

        ChoiceParametersViewModel viewModel =
                (ChoiceParametersViewModel) Global.getField(dialog, "viewModel"); //$NON-NLS-1$
        IV8Project v8Project = (IV8Project) Global.getField(dialog, "v8Project"); //$NON-NLS-1$
        ColumnViewer viewer = (ColumnViewer) Global.getField(dialog, "viewer"); //$NON-NLS-1$
        if (viewModel == null || v8Project == null || viewer == null)
            return false;

        Map<String, Field> fieldMap = ChoiceParameterFieldResolver.buildMap(viewModel, v8Project);
        if (fieldMap.isEmpty())
            return false;

        PatchSession session = new PatchSession(viewModel, viewer, fieldMap);
        session.install();
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        shell.setData(SESSION_KEY, session);
        shell.addDisposeListener(e -> session.dispose());
        ChoiceParametersDebug.log("PATCH OK fields=" + fieldMap.size()); //$NON-NLS-1$
        return true;
    }

    private static final class PatchSession
    {
        private final ChoiceParametersViewModel viewModel;
        private final ColumnViewer viewer;
        private final Map<String, Field> fieldMap;
        private final ChoiceParameterValueFactory valueFactory = new ChoiceParameterValueFactory();
        private final List<Adapter> itemAdapters = new ArrayList<>();
        private final EContentAdapter viewModelAdapter;

        PatchSession(ChoiceParametersViewModel viewModel, ColumnViewer viewer,
                Map<String, Field> fieldMap)
        {
            this.viewModel = viewModel;
            this.viewer = viewer;
            this.fieldMap = fieldMap;
            viewModelAdapter = new EContentAdapter()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    super.notifyChanged(notification);
                    if (notification.getFeature() == MdAefPackage.Literals.CHOICE_PARAMETERS_VIEW_MODEL__ITEMS
                            && notification.getEventType() == Notification.ADD)
                    {
                        Object newItem = notification.getNewValue();
                        if (newItem instanceof ChoiceParameterItem)
                            attachItemAdapter((ChoiceParameterItem) newItem);
                    }
                }
            };
        }

        void install()
        {
            EObject viewModelObject = asEObject(viewModel);
            if (viewModelObject == null)
                return;
            viewModelObject.eAdapters().add(viewModelAdapter);
            for (ChoiceParameterItem item : viewModel.getItems())
                attachItemAdapter(item);
        }

        void dispose()
        {
            EObject viewModelObject = asEObject(viewModel);
            if (viewModelObject != null)
                viewModelObject.eAdapters().remove(viewModelAdapter);
            for (Adapter adapter : itemAdapters)
            {
                Notifier notifier = adapter.getTarget();
                if (notifier != null)
                    notifier.eAdapters().remove(adapter);
            }
            itemAdapters.clear();
        }

        private void attachItemAdapter(ChoiceParameterItem item)
        {
            EObject itemObject = asEObject(item);
            if (itemObject == null)
                return;

            Adapter adapter = new AdapterImpl()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    if (notification.getEventType() != Notification.SET)
                        return;
                    Object feature = notification.getFeature();
                    if (!(feature instanceof org.eclipse.emf.ecore.EStructuralFeature))
                        return;
                    if (!"text".equals(((org.eclipse.emf.ecore.EStructuralFeature) feature).getName())) //$NON-NLS-1$
                        return;
                    applyTypeForName(item, (String) notification.getNewValue());
                }

                @Override
                public boolean isAdapterForType(Object type)
                {
                    return type == ChoiceParameterItem.class;
                }
            };
            itemObject.eAdapters().add(adapter);
            itemAdapters.add(adapter);
            applyTypeForName(item, getItemText(item));
        }

        private static EObject asEObject(Object model)
        {
            return model instanceof EObject ? (EObject) model : null;
        }

        /** {@code getText()} объявлен в {@code ItemViewModel} (aef2), не в {@link ChoiceParameterItem}. */
        private static String getItemText(ChoiceParameterItem item)
        {
            Object text = Global.invoke(item, "getText"); //$NON-NLS-1$
            return text instanceof String ? (String) text : ""; //$NON-NLS-1$
        }

        private void applyTypeForName(ChoiceParameterItem item, String name)
        {
            if (name == null || name.isEmpty())
                return;

            Field field = fieldMap.get(name);
            if (field == null)
            {
                ChoiceParametersDebug.log("skip unknown name " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            TypeItem typeItem = valueFactory.pickType(field);
            if (typeItem == null)
            {
                ChoiceParametersDebug.log("skip no type for " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            Value current = item.getValue();
            if (current != null && valueFactory.sameValueType(current, typeItem))
            {
                ChoiceParametersDebug.log("keep " + ChoiceParametersDebug.quote(name) //$NON-NLS-1$
                        + " value=" + current.getClass().getSimpleName()); //$NON-NLS-1$
                return;
            }

            Value newValue = valueFactory.createDefault(typeItem);
            if (newValue == null)
            {
                ChoiceParametersDebug.log("reset FAIL createDefault " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            item.setValue(newValue);
            refreshItem(item);
            ChoiceParametersDebug.log("reset " + ChoiceParametersDebug.quote(name) //$NON-NLS-1$
                    + " -> " + newValue.getClass().getSimpleName()); //$NON-NLS-1$
        }

        private void refreshItem(ChoiceParameterItem item)
        {
            Display display = viewer.getControl().getDisplay();
            if (display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (!viewer.getControl().isDisposed())
                    viewer.refresh(item, true);
            });
        }
    }
}

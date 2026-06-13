package tormozit;

import java.lang.reflect.Constructor;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Хелперы открытия popup-инспектора EDT для {@link DebugInspectorHook} (hover «Инспектировать»).
 */
public final class BslInspectSupport
{
    private static final String BUNDLE_DEBUG_UI = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$
    private static final String CLASS_INSPECT_POPUP =
        "com._1c.g5.v8.dt.internal.debug.ui.dialogs.PendingAwareInspectPopupDialog"; //$NON-NLS-1$
    private static final String CMD_INSPECT_EDT = "com._1c.g5.v8.dt.debug.ui.commands.Inspect"; //$NON-NLS-1$

    private BslInspectSupport() {}

    static void openInspectPopup(
        Shell parent,
        Point anchor,
        IWatchExpression watch,
        IBslStackFrame frame,
        IDebugMonitoringManager monitoringManager)
    {
        if (parent == null || parent.isDisposed() || watch == null || frame == null)
            return;
        try
        {
            Class<?> dialogClass = loadDebugUiClass(CLASS_INSPECT_POPUP);
            Constructor<?> ctor = dialogClass.getConstructor(
                Shell.class, Point.class, String.class, IWatchExpression.class, IDebugMonitoringManager.class);
            Object dialog = ctor.newInstance(parent, anchor, CMD_INSPECT_EDT, watch, monitoringManager);
            watch.setExpressionContext(frame);
            Global.invoke(dialog, "open"); //$NON-NLS-1$
        }
        catch (Exception ignored)
        {
            // Ошибки логирует DebugInspectorHook
        }
    }

    static IBslStackFrame resolveInspectStackFrame(IEditorPart editor)
    {
        IProject project = null;
        if (editor instanceof BslXtextEditor bsl)
        {
            IDtProject dtProject = DebugIRHandler.getDtProjectFromBslEditor(bsl);
            if (dtProject != null)
                project = dtProject.getWorkspaceProject();
        }
        IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(project);
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        return frame;
    }

    static IWatchExpression newWatchExpression(String exprText)
    {
        if (exprText == null || exprText.isBlank())
            return null;
        return DebugPlugin.getDefault().getExpressionManager().newWatchExpression(exprText);
    }

    static IWatchExpression toWatchExpression(Object element)
    {
        if (element instanceof IWatchExpression watch)
            return watch;
        if (element instanceof IBslVariable variable)
        {
            try
            {
                String expr = variable.toWatchExpression();
                return newWatchExpression(expr);
            }
            catch (Exception ignored)
            {
                return null;
            }
        }
        return null;
    }

    static String watchExpressionText(IWatchExpression watch)
    {
        if (watch instanceof IExpression expression)
        {
            String text = expression.getExpressionText();
            if (text != null && !text.isBlank())
                return text.trim();
        }
        return ""; //$NON-NLS-1$
    }

    private static Class<?> loadDebugUiClass(String className) throws ClassNotFoundException
    {
        Bundle bundle = Platform.getBundle(BUNDLE_DEBUG_UI);
        if (bundle == null)
            throw new ClassNotFoundException(className + " (bundle " + BUNDLE_DEBUG_UI + " not installed)"); //$NON-NLS-1$ //$NON-NLS-2$
        if (bundle.getState() != Bundle.ACTIVE)
        {
            try
            {
                bundle.start(Bundle.START_TRANSIENT);
            }
            catch (Exception ignored)
            {
                // bundle activation optional
            }
        }
        return bundle.loadClass(className);
    }
}

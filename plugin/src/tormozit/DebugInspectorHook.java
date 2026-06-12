package tormozit;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.core.model.IWatchExpression;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Патч окон инспектора отладки (F9 / hover): флажок «Обновлять», «Инспектировать» (hover) и кнопка закрытия.
 * Независимое окно (F9) всегда закреплено (без авто-закрытия по клику вне окна).
 */
public final class DebugInspectorHook implements IStartup
{
    private static volatile boolean filtersInstalled;

    private static final String PATCHED_KEY = "tormozit.debugInspectorPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.debugInspectorSession"; //$NON-NLS-1$
    private static final String COMFORT_HEADER_KEY = "tormozit.inspectorComfortHeader"; //$NON-NLS-1$
    private static final String DETECT_LOG_KEY = "tormozit.debugInspectorDetectLog"; //$NON-NLS-1$

    private static final String WINDOW_DATA_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$

    private static final String CLASS_INSPECT_POPUP =
        "com._1c.g5.v8.dt.internal.debug.ui.dialogs.PendingAwareInspectPopupDialog"; //$NON-NLS-1$
    private static final String CLASS_HOVER_DIALOG =
        "com._1c.g5.v8.dt.internal.debug.ui.hover.DebugElementInformationControlCreator$ExpressionInformationControl$DebugExpressionInformationControl"; //$NON-NLS-1$
    private static final String CLASS_DEBUG_ELEMENT_DIALOG =
        "com._1c.g5.v8.dt.internal.debug.ui.hover.DebugElementDialog"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_RU = "Фактический тип"; //$NON-NLS-1$
    private static final String COLUMN_MARKER_EN = "Actual type"; //$NON-NLS-1$
    /** Подъём блока «флажок / меню» в hover-шапке. */
    private static final int HEADER_LIFT_PX = -6;
    /** Дополнительный подъём «Инспектировать» и × в hover (флажок не трогаем). */
    private static final int HOVER_HEADER_LIFT_EXTRA_PX = -3;

    private static final String BUNDLE_DEBUG_UI = "com._1c.g5.v8.dt.debug.ui"; //$NON-NLS-1$

    /** Штатная команда EDT «Инспектировать» в контекстном меню BSL-редактора (Ctrl+F9). */
    private static final String CMD_INSPECT = "com._1c.g5.v8.dt.debug.ui.commands.Inspect"; //$NON-NLS-1$

    /** Отвязанный от редактора hover-инспектор (флажок «Обновлять» снят). */
    private static final Map<Object, PinnedHoverLink> PINNED_HOVER_BY_MANAGER = new ConcurrentHashMap<>();

    /** Последний живой hover-shell редактора (для снятия дубликатов). */
    private static final Map<Object, Shell> ACTIVE_HOVER_SHELL_BY_MANAGER = new ConcurrentHashMap<>();

    private record PinnedHoverLink(
        Shell shell,
        Object textHoverManager,
        Object pinnedInfoControl,
        Object savedManagerIc,
        Object savedReplacerIc,
        Object blockerIc,
        Boolean savedProcessMouseHover,
        Boolean savedManagerEnabled) {}

    @Override
    public void earlyStartup()
    {
        ensureInstalled();
    }

    public static void ensureInstalled()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (display.getThread() == Thread.currentThread())
            install(display);
        else
            display.asyncExec(() -> install(display));
    }

    private static synchronized void install(Display display)
    {
        if (display == null || display.isDisposed() || filtersInstalled)
            return;
        filtersInstalled = true;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell))
                return;
            if (!mightBeInspectorShell(shell))
                return;
            logInspectorDetectOnce(shell, event.type);
            if (shouldSuppressDuplicateHoverShell(shell))
            {
                suppressHoverShell(shell);
                return;
            }
            Object sessionObj = shell.getData(SESSION_KEY);
            if (sessionObj instanceof InspectorPatchSession session)
                scheduleSessionRefresh(display, shell, session);
            else
                schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
        DebugInspectorDebug.log("install Show/Activate filters"); //$NON-NLS-1$
    }

    private static void scheduleSessionRefresh(Display display, Shell shell, InspectorPatchSession session)
    {
        display.timerExec(0, () ->
        {
            if (!shell.isDisposed())
                session.refresh();
        });
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed())
            return;
        if (shouldSuppressDuplicateHoverShell(shell))
        {
            suppressHoverShell(shell);
            return;
        }
        if (attempt >= 6 && shouldAbortTransientHoverShell(shell))
        {
            suppressHoverShell(shell);
            return;
        }
        int delay = attempt == 0 ? 0 : attempt < 8 ? 50 : 100;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed())
                return;
            DebugInspectorDebug.step("patch", "try a=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$
            if (tryPatch(shell, attempt))
                return;
            if (attempt < 24)
                schedulePatchAttempt(display, shell, attempt + 1);
            else
            {
                InspectorTargets failedTargets = resolveTargets(shell);
                traceResolveDiagnostics(shell, attempt, failedTargets, null, null);
                DebugInspectorDebug.problem("patch failed after retries shell=\"" //$NON-NLS-1$
                    + shell.getText() + "\" tree=" + hasInspectorTableMarker(shell)); //$NON-NLS-1$
            }
        });
    }

    private static boolean tryPatch(Shell shell, int attempt)
    {
        synchronized (shell)
        {
            return tryPatchLocked(shell, attempt);
        }
    }

    private static boolean tryPatchLocked(Shell shell, int attempt)
    {
        if (shouldSuppressDuplicateHoverShell(shell))
        {
            suppressHoverShell(shell);
            return true;
        }
        if (isShellBlockedByOtherPin(shell))
        {
            suppressHoverShell(shell);
            return true;
        }
        if (attempt >= 6 && shouldAbortTransientHoverShell(shell))
        {
            suppressHoverShell(shell);
            return true;
        }

        InspectorTargets targets = resolveTargets(shell);
        Object hoverManager = resolveHoverManagerForShell(shell, targets);
        cleanupHoverManagerBlockers(hoverManager);
        if (isBlockerInfoControl(targets.infoControl) || isBlockerShell(shell))
        {
            DebugInspectorDebug.step("hover", //$NON-NLS-1$
                "reject blocker shell=" + shell //$NON-NLS-1$
                    + " infoCtrl=" + DebugInspectorDebug.cn(targets.infoControl)); //$NON-NLS-1$
            clearHoverManagerInfoControls(hoverManager);
            targets = resolveTargets(shell);
            if (isBlockerInfoControl(targets.infoControl) || isBlockerShell(shell))
            {
                suppressHoverShell(shell);
                return true;
            }
        }
        if (Boolean.TRUE.equals(shell.getData(PATCHED_KEY)))
        {
            Object manager = resolveHoverManagerForShell(shell, targets);
            Shell active = manager != null ? ACTIVE_HOVER_SHELL_BY_MANAGER.get(manager) : null;
            if (active != null && active != shell && !active.isDisposed())
            {
                DebugInspectorDebug.step("hover", "suppress superseded shell active=" + active); //$NON-NLS-1$ //$NON-NLS-2$
                suppressHoverShell(shell);
                return true;
            }
        }

        InspectorPatchSession existing = (InspectorPatchSession) shell.getData(SESSION_KEY);
        final InspectorPatchSession session;
        if (existing == null)
        {
            session = new InspectorPatchSession(shell, targets);
            shell.addDisposeListener(e -> session.dispose());
            shell.setData(SESSION_KEY, session);
            DebugInspectorDebug.step("session", "new a=" + attempt); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            session = existing;
            session.updateTargets(targets);
        }

        session.installTreeEnhancements();

        targets = resolveTargets(shell);
        session.updateTargets(targets);

        ToolBar menuBar = resolveToolBar(targets.dialog, shell);
        if (menuBar == null || menuBar.isDisposed())
        {
            traceResolveDiagnostics(shell, attempt, targets, menuBar, "miss=menuBar"); //$NON-NLS-1$
            return false;
        }

        if (!isPatchTarget(targets.dialog))
        {
            traceResolveDiagnostics(shell, attempt, targets, menuBar, "miss=dialog"); //$NON-NLS-1$
            return false;
        }

        traceResolveDiagnostics(shell, attempt, targets, menuBar, "pre-header"); //$NON-NLS-1$

        boolean headerOk = session.installHeaderControls(menuBar);
        session.scheduleHeaderMaintenance(menuBar);
        session.applyInspectorModeForTargets();

        if (headerOk && session.isHeaderLive())
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            session.registerHoverShellAfterPatch();
            DebugInspectorDebug.step("PATCH OK", //$NON-NLS-1$
                "a=" + attempt //$NON-NLS-1$
                    + " dialog=" + targets.dialog.getClass().getSimpleName() //$NON-NLS-1$
                    + " hover=" + (targets.infoControl != null) //$NON-NLS-1$
                    + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
            return true;
        }

        DebugInspectorDebug.step("header", //$NON-NLS-1$
            "miss a=" + attempt //$NON-NLS-1$
                + " headerOk=" + headerOk //$NON-NLS-1$
                + " installed=" + session.isHeaderInstalled() //$NON-NLS-1$
                + " live=" + session.isHeaderLive() //$NON-NLS-1$
                + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
        return false;
    }

    private static boolean mightBeInspectorShell(Shell shell)
    {
        if (isWorkbenchShell(shell))
            return false;
        if (Boolean.TRUE.equals(shell.getData(PinnedHoverBlockerInformationControl.SHELL_MARKER_KEY)))
            return false;
        if (resolveElementDialog(shell, null) != null)
            return true;
        if (isInspectorShellData(shell.getData()) || isInspectorShellData(shell.getData(WINDOW_DATA_KEY)))
            return true;
        if (findHoverBindingForShell(shell) != null)
            return true;
        return hasInspectorTableMarker(shell);
    }

    private static boolean isWorkbenchShell(Shell shell)
    {
        Object layout = shell.getLayout();
        if (layout != null && layout.getClass().getName().contains("TrimmedPartLayout")) //$NON-NLS-1$
            return true;
        String title = shell.getText();
        return title != null && title.contains("Eclipse SDK"); //$NON-NLS-1$
    }

    private static InspectorTargets resolveTargets(Shell shell)
    {
        HoverBinding binding = findHoverBindingForShell(shell);
        Object infoControl = binding != null ? binding.infoControl() : null;
        Object hoverManager = binding != null ? binding.textHoverManager() : null;
        Object dialog = resolveElementDialog(shell, infoControl);
        if (!isElementDialog(dialog))
            dialog = findElementDialogByShellMatch(shell);
        if (!isPatchTarget(dialog))
            dialog = findElementDialogByTreeShell(shell);
        if (!isPatchTarget(dialog))
            dialog = resolveHoverInspectProxy(shell, infoControl);
        if (!isPatchTarget(dialog) && isHoverInspectControl(infoControl) && hasInspectorTableMarker(shell))
            dialog = infoControl;
        return new InspectorTargets(dialog, infoControl, hoverManager);
    }

    /** Диалог с деревом и toolBar (DebugElementDialog / PendingAwareInspectPopupDialog). */
    private static Object resolveElementDialog(Shell shell, Object infoControl)
    {
        Object dialog = unwrapToElementDialog(shell.getData());
        if (isElementDialog(dialog))
            return dialog;
        dialog = unwrapToElementDialog(shell.getData(WINDOW_DATA_KEY));
        if (isElementDialog(dialog))
            return dialog;
        dialog = unwrapToElementDialog(infoControl);
        if (isElementDialog(dialog))
            return dialog;

        for (Shell walk = shell; walk != null && !walk.isDisposed(); walk = parentShellOf(walk))
        {
            dialog = unwrapToElementDialog(walk.getData(WINDOW_DATA_KEY));
            if (isElementDialog(dialog))
                return dialog;
            dialog = unwrapToElementDialog(walk.getData());
            if (isElementDialog(dialog))
                return dialog;
        }

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree != null && PlatformUI.isWorkbenchRunning())
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    for (IEditorPart editor : page.getEditors())
                    {
                        dialog = dialogFromEditorTree(editor, tree);
                        if (isElementDialog(dialog))
                            return dialog;
                    }
                }
            }
        }
        Object hoverProxy = resolveHoverInspectProxy(shell, infoControl);
        if (hoverProxy != null)
            return hoverProxy;
        return null;
    }

    /** Hover-инспектор без {@code debugElementDialog}: UI в {@code ExpressionInformationControl}. */
    private static Object resolveHoverInspectProxy(Shell shell, Object infoControl)
    {
        if (!isHoverInspectControl(infoControl) || shell == null || shell.isDisposed())
            return null;
        if (!infoControlShellEquals(infoControl, shell))
            return null;
        if (!hasInspectorTableMarker(shell))
            return null;
        return infoControl;
    }

    private static boolean isHoverInspectControl(Object data)
    {
        if (data == null)
            return false;
        return data.getClass().getName().contains("ExpressionInformationControl"); //$NON-NLS-1$
    }

    private static boolean isPatchTarget(Object data)
    {
        return isElementDialog(data) || isHoverInspectControl(data);
    }

    private static Object unwrapToElementDialog(Object data)
    {
        if (data == null)
            return null;
        if (isElementDialog(data))
            return data;
        String name = data.getClass().getName();
        if (!name.contains("ExpressionInformationControl")) //$NON-NLS-1$
            return null;
        Object elementDialog = Global.getField(data, "debugElementDialog"); //$NON-NLS-1$
        if (isElementDialog(elementDialog))
            return elementDialog;
        elementDialog = Global.invoke(data, "getDebugElementDialog"); //$NON-NLS-1$
        return isElementDialog(elementDialog) ? elementDialog : null;
    }

    private static boolean isElementDialog(Object data)
    {
        if (data == null)
            return false;
        String name = data.getClass().getName();
        if (name.contains("ExpressionInformationControl")) //$NON-NLS-1$
            return false;
        return CLASS_INSPECT_POPUP.equals(name) || CLASS_DEBUG_ELEMENT_DIALOG.equals(name);
    }

    private static Object dialogFromEditorTree(IEditorPart editor, Tree tree)
    {
        ISourceViewer viewer = sourceViewer(editor);
        if (viewer == null)
            return null;
        Object hoverManager = Global.getField(viewer, "fTextHoverManager"); //$NON-NLS-1$
        if (hoverManager == null)
            return null;
        Object replacer = Global.getField(hoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        Object ric = replacer != null ? Global.getField(replacer, "fInformationControl") : null; //$NON-NLS-1$
        Object ic = Global.getField(hoverManager, "fInformationControl"); //$NON-NLS-1$
        for (Object candidate : new Object[] { ic, ric })
        {
            Object dialog = unwrapToElementDialog(candidate);
            if (dialog != null && treeFromDialog(dialog) == tree)
                return dialog;
        }
        return null;
    }

    private static Tree treeFromDialog(Object dialog)
    {
        Object tree = Global.invoke(dialog, "getTree"); //$NON-NLS-1$
        if (tree instanceof Tree t && !t.isDisposed())
            return t;
        tree = Global.getField(dialog, "tree"); //$NON-NLS-1$
        return tree instanceof Tree t && !t.isDisposed() ? t : null;
    }

    private static ISourceViewer sourceViewer(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            return bsl.getInternalSourceViewer();
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage page = granular.getActivePageInstance();
            if (page instanceof DtGranularEditorXtextEditorPage<?> xtext)
            {
                IEditorPart embedded = xtext.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return bsl.getInternalSourceViewer();
            }
        }
        return null;
    }

    private static boolean isInspectorShellData(Object data)
    {
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return CLASS_INSPECT_POPUP.equals(name) || CLASS_HOVER_DIALOG.equals(name)
            || CLASS_DEBUG_ELEMENT_DIALOG.equals(name)
            || name.contains("ExpressionInformationControl"); //$NON-NLS-1$
    }

    private static ToolBar resolveToolBar(Object dialog, Shell shell)
    {
        if (isElementDialog(dialog))
        {
            ToolBar menuBar = (ToolBar) Global.getField(dialog, "toolBar"); //$NON-NLS-1$
            if (menuBar != null && !menuBar.isDisposed())
                return menuBar;

            Object titleObj = Global.getField(dialog, "titleAreaComposite"); //$NON-NLS-1$
            if (titleObj instanceof Composite title && !title.isDisposed())
            {
                ToolBar inTitle = findToolBarInControls(title);
                if (inTitle != null)
                    return inTitle;
            }
        }

        if (shell != null && !shell.isDisposed())
        {
            Composite titleFromTree = resolveTitleAreaFromTree(shell);
            if (titleFromTree != null)
            {
                ToolBar inTitle = findToolBarInControls(titleFromTree);
                if (inTitle != null)
                    return inTitle;
            }
        }
        return null;
    }

    private static Object findElementDialogByShellMatch(Shell shell)
    {
        if (!PlatformUI.isWorkbenchRunning() || shell == null || shell.isDisposed())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    Object dialog = findElementDialogInEditorShellMatch(editor, shell);
                    if (isElementDialog(dialog))
                        return dialog;
                }
            }
        }
        return null;
    }

    private static Object findElementDialogByTreeShell(Shell shell)
    {
        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null || !PlatformUI.isWorkbenchRunning())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    Object dialog = dialogFromEditorTree(editor, tree);
                    if (isElementDialog(dialog))
                        return dialog;
                }
            }
        }
        return null;
    }

    private static Object findElementDialogInEditorShellMatch(IEditorPart editor, Shell shell)
    {
        if (editor instanceof BslXtextEditor bsl)
            return findElementDialogInSourceViewerShellMatch(bsl.getInternalSourceViewer(), shell);
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return findElementDialogInSourceViewerShellMatch(bsl.getInternalSourceViewer(), shell);
            }
        }
        return null;
    }

    private static Object findElementDialogInSourceViewerShellMatch(ISourceViewer viewer, Shell shell)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return null;
        Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;

        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
        {
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            Object dialog = unwrapToElementDialog(replacerControl);
            if (dialogShellEquals(dialog, shell))
                return dialog;
        }

        Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        Object dialog = unwrapToElementDialog(infoControl);
        if (dialogShellEquals(dialog, shell))
            return dialog;
        return null;
    }

    private static boolean dialogShellEquals(Object dialog, Shell shell)
    {
        if (!isElementDialog(dialog) || shell == null || shell.isDisposed())
            return false;
        Object dialogShell = Global.invoke(dialog, "getShell"); //$NON-NLS-1$
        return dialogShell == shell;
    }

    private static Shell parentShellOf(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        Composite parent = shell.getParent();
        while (parent != null && !(parent instanceof Shell))
            parent = parent.getParent();
        return parent instanceof Shell s ? s : null;
    }

    private static Composite resolveTitleAreaFromTree(Shell shell)
    {
        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        Composite dialogArea = tree.getParent();
        if (dialogArea == null)
            return null;
        Composite main = dialogArea.getParent();
        if (main != null && !main.isDisposed())
        {
            for (Control child : main.getChildren())
            {
                if (child instanceof Composite composite && findToolBarInControls(composite) != null)
                    return composite;
            }
            ToolBar bar = findToolBarInControls(main);
            if (bar != null)
                return InspectorPatchSession.findTitleArea(bar);
        }
        for (Composite parent = dialogArea; parent != null && parent != (Composite) shell; parent = parent.getParent())
        {
            if (parent.getLayout() instanceof GridLayout)
            {
                ToolBar bar = findToolBarInControls(parent);
                if (bar != null)
                    return InspectorPatchSession.findTitleArea(bar);
            }
        }
        return null;
    }

    private static ToolBar findToolBarInControls(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof ToolBar toolBar)
            return toolBar;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                ToolBar found = findToolBarInControls(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingForShell(Shell shell)
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    HoverBinding binding = findHoverBindingInEditor(editor, shell);
                    if (binding != null)
                        return binding;
                }
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingInEditor(IEditorPart editor, Shell shell)
    {
        if (editor instanceof BslXtextEditor bsl)
            return findHoverBindingInSourceViewer(bsl.getInternalSourceViewer(), shell);
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return findHoverBindingInSourceViewer(bsl.getInternalSourceViewer(), shell);
            }
        }
        return null;
    }

    private static HoverBinding findHoverBindingInSourceViewer(ISourceViewer viewer, Shell shell)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return null;
        Object textHoverManager = Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
        if (textHoverManager == null)
            return null;

        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        Object dialogOnShell = resolveElementDialog(shell, null);

        if (replacer != null)
        {
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (!isBlockerInfoControl(replacerControl) && infoControlShellEquals(replacerControl, shell))
                return new HoverBinding(replacerControl, replacer, textHoverManager);
        }

        Object infoControl = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        if (infoControl == null)
            return null;

        boolean shellMatch = infoControlShellEquals(infoControl, shell);
        if (!shellMatch && dialogOnShell != null)
        {
            Object debugDialog = Global.getField(infoControl, "debugElementDialog"); //$NON-NLS-1$
            shellMatch = debugDialog == dialogOnShell;
        }
        if (!shellMatch)
            return null;

        Object activeControl = infoControl;
        Object closerOwner = textHoverManager;
        if (replacer != null)
        {
            closerOwner = replacer;
            Object replacerControl = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (replacerControl != null && !isBlockerInfoControl(replacerControl))
                activeControl = replacerControl;
        }

        return new HoverBinding(activeControl, closerOwner, textHoverManager);
    }

    private static IEditorPart findEditorForHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return null;
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorPart editor : page.getEditors())
                {
                    if (findHoverBindingInEditor(editor, shell) != null)
                        return resolveBslEditorFromPart(editor);
                }
            }
        }
        return null;
    }

    private static IEditorPart resolveBslEditorFromPart(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            return bsl;
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return bsl;
            }
        }
        return editor;
    }

    private static IBslStackFrame resolveInspectStackFrame(IEditorPart editor)
    {
        IProject project = null;
        if (editor instanceof BslXtextEditor bsl)
        {
            var dtProject = DebugIRHandler.getDtProjectFromBslEditor(bsl);
            if (dtProject != null)
                project = dtProject.getWorkspaceProject();
        }
        IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(project);
        if (frame == null)
            frame = DebugSessionHelper.findSuspendedStackFrame(null);
        return frame;
    }

    private static Object resolveHoverMonitoringManager(Object infoControl)
    {
        if (infoControl != null)
        {
            Object mm = Global.getField(infoControl, "monitoringManager"); //$NON-NLS-1$
            if (mm != null)
                return mm;
        }
        return Global.getServiceByClass(IDebugMonitoringManager.class);
    }

    private static IWatchExpression resolveHoverRootWatchExpression(Shell shell, Object infoControl)
    {
        Object element = resolveHoverDebugElement(infoControl);
        IWatchExpression watch = toWatchExpression(element);
        if (watch != null)
            return watch;

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        TreeItem[] roots = tree.getItems();
        if (roots.length == 0)
            return null;
        return toWatchExpression(roots[0].getData());
    }

    private static Object resolveHoverDebugElement(Object infoControl)
    {
        if (infoControl == null)
            return null;
        Object dialog = Global.getField(infoControl, "debugElementDialog"); //$NON-NLS-1$
        if (dialog == null)
            return null;
        Object element = Global.invoke(dialog, "getElement"); //$NON-NLS-1$
        if (element != null)
            return element;
        return Global.getField(dialog, "element"); //$NON-NLS-1$
    }

    private static IWatchExpression toWatchExpression(Object element)
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

    private static IWatchExpression newWatchExpression(String exprText)
    {
        if (exprText == null || exprText.isBlank())
            return null;
        return DebugPlugin.getDefault().getExpressionManager().newWatchExpression(exprText);
    }

    private static String resolveHoverRootExpressionText(Shell shell, Object infoControl)
    {
        IWatchExpression watch = resolveHoverRootWatchExpression(shell, infoControl);
        String text = watchExpressionText(watch);
        if (text != null)
            return text;

        Tree tree = findTreeWithInspectorColumns(shell);
        if (tree == null)
            return null;
        TreeItem[] roots = tree.getItems();
        if (roots.length == 0)
            return null;
        TreeItem root = roots[0];
        text = root.getText(0);
        if (text != null && !text.isBlank())
            return text.trim();
        return null;
    }

    private static String watchExpressionText(IWatchExpression watch)
    {
        if (watch instanceof IExpression expression)
        {
            String text = expression.getExpressionText();
            if (text != null && !text.isBlank())
                return text.trim();
        }
        return null;
    }

    private static Point resolveInspectPopupAnchor(Shell hoverShell, IEditorPart editor)
    {
        if (hoverShell != null && !hoverShell.isDisposed())
        {
            Rectangle bounds = hoverShell.getBounds();
            return new Point(bounds.x + bounds.width / 2, bounds.y + 20);
        }
        StyledText styledText = styledTextFromEditor(editor);
        if (styledText != null && !styledText.isDisposed())
        {
            Point range = styledText.getSelectionRange();
            int mid = range.x + range.y / 2;
            Point loc = styledText.getLocationAtOffset(mid);
            return styledText.toDisplay(loc);
        }
        return new Point(100, 100);
    }

    private static StyledText styledTextFromEditor(IEditorPart editor)
    {
        if (editor == null)
            return null;
        ITextViewer viewer = editor.getAdapter(ITextViewer.class);
        if (viewer != null)
            return viewer.getTextWidget();
        if (editor instanceof BslXtextEditor bsl)
            return bsl.getInternalSourceViewer().getTextWidget();
        return null;
    }

    private static void openStandaloneInspectFromHover(Shell hoverShell, IEditorPart editor, Object infoControl)
    {
        IWatchExpression watch = resolveHoverRootWatchExpression(hoverShell, infoControl);
        String exprText = watchExpressionText(watch);
        if (exprText == null)
            exprText = resolveHoverRootExpressionText(hoverShell, infoControl);
        if (watch == null && (exprText == null || exprText.isBlank()))
        {
            DebugInspectorDebug.problem("inspect: root expression not found"); //$NON-NLS-1$
            return;
        }
        if (watch == null)
            watch = newWatchExpression(exprText);

        IBslStackFrame frame = resolveInspectStackFrame(editor);
        if (frame == null)
        {
            DebugInspectorDebug.problem("inspect: no suspended frame"); //$NON-NLS-1$
            return;
        }

        Object monitoringManager = resolveHoverMonitoringManager(infoControl);
        Shell parent = hoverShell;
        if (editor != null && editor.getSite() != null)
            parent = editor.getSite().getShell();
        else if (parent == null || parent.isDisposed())
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            parent = window != null ? window.getShell() : null;
        }
        if (parent == null || parent.isDisposed())
        {
            DebugInspectorDebug.problem("inspect: no parent shell"); //$NON-NLS-1$
            return;
        }

        Point anchor = resolveInspectPopupAnchor(hoverShell, editor);
        try
        {
            Class<?> dialogClass = loadDebugUiClass(CLASS_INSPECT_POPUP);
            Constructor<?> ctor = dialogClass.getConstructor(
                Shell.class, Point.class, String.class, IWatchExpression.class, IDebugMonitoringManager.class);
            Object dialog = ctor.newInstance(parent, anchor, CMD_INSPECT, watch, monitoringManager);
            watch.setExpressionContext(frame);
            Global.invoke(dialog, "open"); //$NON-NLS-1$
            DebugInspectorDebug.step("inspect", "opened expr=\"" //$NON-NLS-1$ //$NON-NLS-2$
                + (exprText != null ? exprText : watchExpressionText(watch)) + "\""); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            DebugInspectorDebug.problem("inspect open: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /** Internal-класс EDT: только через classloader bundle debug.ui, не Class.forName. */
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
            catch (Exception e)
            {
                DebugInspectorDebug.problem("debug.ui bundle start: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return bundle.loadClass(className);
    }

    private static Object findTextHoverManagerFromActiveEditor()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null)
            return null;
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return null;
        return getTextHoverManagerFromEditor(page.getActiveEditor());
    }

    private static Object getTextHoverManagerFromEditor(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
            return getTextHoverManagerFromSourceViewer(bsl.getInternalSourceViewer());
        if (editor instanceof DtGranularEditor<?> granular)
        {
            IFormPage activePage = granular.getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage)
            {
                IEditorPart embedded = xtextPage.getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor bsl)
                    return getTextHoverManagerFromSourceViewer(bsl.getInternalSourceViewer());
            }
        }
        return null;
    }

    private static Object getTextHoverManagerFromSourceViewer(ISourceViewer viewer)
    {
        if (!(viewer instanceof SourceViewer sourceViewer))
            return null;
        return Global.getField(sourceViewer, "fTextHoverManager"); //$NON-NLS-1$
    }

    private static boolean infoControlShellEquals(Object infoControl, Shell shell)
    {
        if (infoControl == null)
            return false;
        Object controlShell = Global.invoke(infoControl, "getShell"); //$NON-NLS-1$
        return controlShell == shell;
    }

    private static boolean hasInspectorTableMarker(Shell shell)
    {
        return findTreeWithInspectorColumns(shell) != null;
    }

    private static Tree findTreeWithInspectorColumns(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof Tree tree && treeHasInspectorColumns(tree))
            return tree;
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Tree found = findTreeWithInspectorColumns(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean treeHasInspectorColumns(Tree tree)
    {
        TreeColumn[] columns = tree.getColumns();
        if (columns == null || columns.length < 3)
            return false;
        for (TreeColumn column : columns)
        {
            String text = column.getText();
            if (text != null && (text.contains(COLUMN_MARKER_RU) || text.contains(COLUMN_MARKER_EN)))
                return true;
        }
        return false;
    }

    private record HoverBinding(Object infoControl, Object closerOwner, Object textHoverManager) {}

    private record InspectorTargets(Object dialog, Object infoControl, Object hoverManager) {}

    private static final class InspectorPatchSession
    {
        private static final NoOpInformationControlCloser NOOP_CLOSER = new NoOpInformationControlCloser();

        private final Shell shell;
        private InspectorTargets targets;

        private Button autoCloseCheckbox;
        private Button inspectButton;
        private ToolBar closeToolBar;
        private Composite rightArea;
        private ToolBar menuBarRef;
        private Composite titleAreaRef;
        private Listener keepDeactivateOffListener;
        private Listener hoverAutoCloseGuardListener;
        private Listener headerMaintainListener;
        private Listener shellPinListener;
        private Listener hoverPinVisibleListener;
        private Listener hoverPinCloseListener;
        private Listener hoverPinMoveListener;
        private boolean hoverPinDisposeAllowed;
        private boolean pinnedShellMovedByUser;
        private Object savedTextHoverCloser;
        private Object savedReplacerCloser;
        private int hoverGuardGeneration;
        private boolean hoverPollingScheduled;
        private boolean hoverDisableLogged;
        private boolean hoverClosersSwizzled;
        private boolean hoverIcListenersCleared;
        private Listener savedHoverDeactivationListener;
        private Listener savedHoverActivationListener;
        private Object innerDebugDialog;
        private IDebugEventSetListener savedDebugEventsListener;
        private boolean innerDebugEventsSuspended;
        private boolean shellPinnedOnTop;
        private boolean headerGuardInstalled;
        private Listener savedFShellListener;
        private Listener pinnedFShellListenerProxy;
        private Listener savedDeactivateListener;
        private Listener pinnedDeactivateListenerProxy;
        private MouseMoveListener savedMouseMoveListener;
        private Control savedMouseMoveSubject;
        private Object savedViewportListener;
        private Listener pinnedOutsideMouseFilter;
        private boolean hoverEditorReleased;
        private DebugInspectorTreeEnhancement treeEnhancement;

        InspectorPatchSession(Shell shell, InspectorTargets targets)
        {
            this.shell = shell;
            this.targets = targets;
        }

        void updateTargets(InspectorTargets fresh)
        {
            if (fresh == null)
                return;
            Object dialog = fresh.dialog;
            if (!isPatchTarget(dialog))
                dialog = resolveElementDialog(shell, fresh.infoControl);
            if (isPatchTarget(dialog))
            {
                Object infoControl = fresh.infoControl;
                Object hoverManager = fresh.hoverManager;
                PinnedHoverLink pin = findPinnedLinkForShell(shell);
                if (pin != null)
                {
                    if (infoControl == null)
                        infoControl = pin.pinnedInfoControl();
                    if (hoverManager == null)
                        hoverManager = pin.textHoverManager();
                }
                targets = new InspectorTargets(dialog, infoControl, hoverManager);
            }
        }

        void refresh()
        {
            if (shell.isDisposed())
                return;
            updateTargets(resolveTargets(shell));
            ToolBar menuBar = resolveToolBar(targets.dialog, shell);
            if (!isPatchTarget(targets.dialog))
            {
                DebugInspectorDebug.step("refresh", "dialog=null → retry patch"); //$NON-NLS-1$
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            if (menuBar == null || menuBar.isDisposed())
            {
                DebugInspectorDebug.step("refresh", "menuBar=null → invalidate"); //$NON-NLS-1$ //$NON-NLS-2$
                traceResolveDiagnostics(shell, -1, targets, menuBar, "refresh"); //$NON-NLS-1$
                invalidateSession();
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            if (!installHeaderControls(menuBar))
            {
                DebugInspectorDebug.problem("refresh: header install failed"); //$NON-NLS-1$
                invalidateSession();
                schedulePatchAttempt(shell.getDisplay(), shell, 0);
                return;
            }
            applyInspectorModeForTargets();
            installTreeEnhancements();
            scheduleHeaderMaintenance(menuBar);
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            registerHoverShellAfterPatch();
            DebugInspectorDebug.step("refresh", "OK dialog=" + targets.dialog.getClass().getSimpleName() //$NON-NLS-1$ //$NON-NLS-2$
                + " menu=" + describeToolBar(menuBar));
        }

        boolean isHoverSession()
        {
            return isHoverMode();
        }

        private void registerHoverShellAfterPatch()
        {
            if (!isHoverSession())
                return;
            Object manager = resolveTextHoverManager();
            if (manager == null)
                manager = targets.hoverManager;
            if (manager == null)
                return;
            Shell previous = ACTIVE_HOVER_SHELL_BY_MANAGER.get(manager);
            if (previous != null && previous.isDisposed())
                ACTIVE_HOVER_SHELL_BY_MANAGER.remove(manager);
                if (isHoverFollowEnabled())
                {
                    previous = ACTIVE_HOVER_SHELL_BY_MANAGER.get(manager);
                    if (previous != null && previous != shell && !previous.isDisposed()
                        && !isPinnedShell(previous)
                        && Boolean.TRUE.equals(previous.getData(PATCHED_KEY)))
                    {
                        DebugInspectorDebug.step("hover", "retire previous shell=" + previous); //$NON-NLS-1$ //$NON-NLS-2$
                        suppressHoverShell(previous);
                    }
                }
            ACTIVE_HOVER_SHELL_BY_MANAGER.put(manager, shell);
        }

        void requestClose()
        {
            if (shell.isDisposed())
                return;
            PinnedHoverLink pin = findPinnedLinkForShell(shell);
            Object icToDispose = pin != null ? pin.pinnedInfoControl() : null;
            Object textHoverManager = resolveTextHoverManager();
            if (textHoverManager == null)
                textHoverManager = findTextHoverManagerFromActiveEditor();
            hoverPinDisposeAllowed = true;
            removePinnedOutsideMouseFilter();
            removeHoverPinCloseGuard();
            removeHoverPinVisibleGuard();
            removeHoverPinMoveGuard();
            removeKeepDeactivateOffListener();
            removeHoverAutoCloseGuard();
            removeShellPinMaintenance();
            cancelHoverAutoClosePolling();
            clearPinnedHoverForClose(pin, icToDispose != null ? icToDispose : targets.infoControl);
            savedDebugEventsListener = null;
            innerDebugEventsSuspended = false;
            innerDebugDialog = null;
            restoreHoverAutoClose();

            Object ic = icToDispose;
            if (ic == null)
                ic = targets.infoControl;
            if (isHoverInspectControl(ic) && textHoverManager != null && !isInfoControlDisposed(ic))
            {
                hoverEditorReleased = true;
                relinkHoverInfoControlForDispose(textHoverManager, ic);
                disposeHoverViaManager(textHoverManager);
                releaseHoverManagerForEditor(textHoverManager);
                restoreShellOnTop(false);
                dropActiveHoverShell(shell);
                scheduleHoverManagerReady(textHoverManager);
                return;
            }
            releaseHoverManagerForEditor(textHoverManager);
            restoreShellOnTop(false);
            dropActiveHoverShell(shell);

            if (isHoverInspectControl(ic))
            {
                Global.invoke(ic, "dispose"); //$NON-NLS-1$
                scheduleHoverManagerReady(textHoverManager);
                return;
            }
            if (isHoverInspectControl(targets.dialog))
            {
                Global.invoke(targets.dialog, "dispose"); //$NON-NLS-1$
                return;
            }
            if (targets.infoControl != null)
                Global.invoke(targets.infoControl, "setVisible", Boolean.FALSE); //$NON-NLS-1$
            else if (isElementDialog(targets.dialog))
                Global.invoke(targets.dialog, "close"); //$NON-NLS-1$
            else if (!shell.isDisposed())
                shell.dispose();
        }

        private void invalidateSession()
        {
            DebugInspectorDebug.step("session", "invalidate"); //$NON-NLS-1$ //$NON-NLS-2$
            clearPatchState();
            if (!shell.isDisposed())
                shell.setData(SESSION_KEY, null);
        }

        private void clearPatchState()
        {
            shell.setData(PATCHED_KEY, null);
            treeEnhancement = null;
        }

        void scheduleHeaderMaintenance(ToolBar menuBar)
        {
            if (shell.isDisposed() || menuBar.isDisposed())
                return;
            Display display = shell.getDisplay();
            for (int delay : new int[] { 50, 150, 400, 800 })
            {
                display.timerExec(delay, () ->
                {
                    if (!shell.isDisposed() && !menuBar.isDisposed())
                        maintainHeaderControls(menuBar);
                    if (!shell.isDisposed())
                        installTreeEnhancements();
                });
            }
        }

        boolean maintainHeaderControls(ToolBar menuBar)
        {
            if (menuBar == null || menuBar.isDisposed())
                return false;
            if (!isHeaderInstalled())
            {
                if (!installHeaderControls(menuBar))
                    return false;
                applyInspectorModeForTargets();
                return true;
            }

            Composite titleArea = findTitleArea(menuBar);
            if (titleArea == null || titleArea.isDisposed())
                return false;

            if (rightArea != null && !rightArea.isDisposed())
            {
                rightArea.setVisible(true);
                if (autoCloseCheckbox != null && !autoCloseCheckbox.isDisposed())
                {
                    autoCloseCheckbox.setVisible(true);
                    configureHeaderCheckbox();
                }
                if (inspectButton != null && !inspectButton.isDisposed())
                {
                    inspectButton.setVisible(true);
                    configureInspectButton();
                }
            }
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setVisible(true);
            syncHeaderBackground(titleArea);
            titleArea.layout(true, true);
            return true;
        }

        private boolean isHeaderInstalled()
        {
            if (closeToolBar == null || closeToolBar.isDisposed())
                return false;
            if (!isHoverMode())
                return true;
            return rightArea != null && !rightArea.isDisposed()
                && autoCloseCheckbox != null && !autoCloseCheckbox.isDisposed()
                && inspectButton != null && !inspectButton.isDisposed();
        }

        boolean isHeaderLive()
        {
            return isHeaderInstalled();
        }

        boolean installHeaderControls(ToolBar menuBar)
        {
            if (isHeaderInstalled() && menuBarRef == menuBar)
                return maintainHeaderControls(menuBar);

            Composite titleArea = findTitleArea(menuBar);
            if (titleArea == null || titleArea.isDisposed())
            {
                DebugInspectorDebug.problem("installHeader: titleArea not found"); //$NON-NLS-1$
                return false;
            }

            ToolBar oldMenuBarRef = menuBarRef;
            Composite oldTitleAreaRef = titleAreaRef;

            DebugInspectorDebug.step("header", "install start menu=" + describeToolBar(menuBar)); //$NON-NLS-1$ //$NON-NLS-2$
            removeOrphanComfortControls(titleArea, menuBar);

            Color titleBg = titleArea.getBackground();
            Object menuBarLayout = menuBar.getLayoutData();

            try
            {
                boolean ok = installHeaderControlsInTitleArea(titleArea, menuBar, titleBg, menuBarLayout);
                if (ok && oldMenuBarRef != null && oldMenuBarRef != menuBar
                    && oldTitleAreaRef != null && oldTitleAreaRef != titleArea
                    && !oldTitleAreaRef.isDisposed())
                {
                    DebugInspectorDebug.step("header", //$NON-NLS-1$
                        "menuBar changed old=" + describeToolBar(oldMenuBarRef) //$NON-NLS-1$
                            + " new=" + describeToolBar(menuBar)); //$NON-NLS-1$
                    removeOrphanComfortControls(oldTitleAreaRef, oldMenuBarRef);
                }
                return ok;
            }
            catch (RuntimeException e)
            {
                DebugInspectorDebug.problem("installHeader: " + e.getMessage()); //$NON-NLS-1$
                return false;
            }
        }

        private boolean installHeaderControlsInTitleArea(
            Composite titleArea, ToolBar menuBar, Color titleBg, Object menuBarLayout)
        {
            ensureMenuBarInTitleArea(titleArea, menuBar, menuBarLayout);

            if (isHoverMode())
            {
                rightArea = new Composite(titleArea, SWT.NONE);
                markComfortHeader(rightArea);
                GridLayout rightGrid = new GridLayout(2, false);
                rightGrid.marginWidth = 0;
                rightGrid.marginHeight = 0;
                rightGrid.horizontalSpacing = 4;
                rightArea.setLayout(rightGrid);
                rightArea.setBackground(titleBg);
                rightArea.setLayoutData(rightAreaGridData());

                autoCloseCheckbox = new Button(rightArea, SWT.CHECK);
                markComfortHeader(autoCloseCheckbox);
                autoCloseCheckbox.setBackground(titleBg);
                autoCloseCheckbox.setLayoutData(checkboxInRightAreaGridData());
                configureHeaderCheckbox();
                autoCloseCheckbox.setSelection(ComfortSettings.isDebugInspectorHoverUpdate());
                autoCloseCheckbox.addListener(SWT.Selection, e ->
                {
                    boolean enabled = autoCloseCheckbox.getSelection();
                    ComfortSettings.setDebugInspectorHoverUpdate(enabled);
                    applyInspectorMode(enabled);
                });

                inspectButton = new Button(rightArea, SWT.PUSH);
                markComfortHeader(inspectButton);
                inspectButton.setBackground(titleBg);
                inspectButton.setLayoutData(inspectButtonGridData());
                configureInspectButton();
                inspectButton.addListener(SWT.Selection, e -> runInspectFromHover());
            }

            closeToolBar = new ToolBar(titleArea, SWT.FLAT | SWT.RIGHT);
            markComfortHeader(closeToolBar);
            closeToolBar.setBackground(titleBg);
            closeToolBar.setLayoutData(closeButtonGridData(isHoverMode()));
            ToolItem closeItem = new ToolItem(closeToolBar, SWT.PUSH);
            closeItem.setText("\u2715"); //$NON-NLS-1$
            closeItem.setToolTipText(
                "Закрыть окно инспектора" //$NON-NLS-1$
                    + Global.pluginSignForTooltip());
            closeItem.addListener(SWT.Selection, e -> closeInspector());

            if (menuBar.getParent() == titleArea)
            {
                if (rightArea != null && !rightArea.isDisposed())
                    rightArea.moveAbove(menuBar);
                closeToolBar.moveBelow(menuBar);
            }

            if (titleArea.getLayout() instanceof GridLayout gridLayout)
                gridLayout.numColumns = Math.max(gridLayout.numColumns, 4);

            menuBarRef = menuBar;
            if (rightArea != null && !rightArea.isDisposed())
                rightArea.setVisible(true);
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setVisible(true);
            titleAreaRef = titleArea;
            installHeaderGuard(titleArea);
            titleArea.layout(true, true);
            installTreeEnhancements();
            DebugInspectorDebug.step("header", //$NON-NLS-1$
                "installed rightArea=" + (rightArea != null ? controlPath(rightArea) : "null") //$NON-NLS-1$ //$NON-NLS-2$
                    + " menu=" + describeToolBar(menuBar)); //$NON-NLS-1$
            return true;
        }

        void installTreeEnhancements()
        {
            if (treeEnhancement != null && treeEnhancement.isAttached())
                return;
            treeEnhancement = DebugInspectorTreeEnhancement.install(targets.dialog, shell);
            if (treeEnhancement == null)
                DebugInspectorDebug.step("tree", "install failed dialog=" //$NON-NLS-1$ //$NON-NLS-2$
                    + DebugInspectorDebug.cn(targets.dialog));
        }

        private static void ensureMenuBarInTitleArea(
            Composite titleArea, ToolBar menuBar, Object menuBarLayout)
        {
            if (menuBar == null || menuBar.isDisposed() || titleArea == null || titleArea.isDisposed())
                return;
            if (menuBar.getParent() == titleArea)
                return;
            Control parent = menuBar.getParent();
            if (parent instanceof Composite composite && isComfortHeader(composite))
            {
                menuBar.setParent(titleArea);
                if (menuBarLayout instanceof GridData gd)
                    menuBar.setLayoutData(gd);
            }
        }

        private static Composite findTitleArea(ToolBar menuBar)
        {
            for (Composite parent = menuBar.getParent(); parent != null; parent = parent.getParent())
            {
                if (parent.getLayout() instanceof GridLayout)
                    return parent;
            }
            return menuBar.getParent();
        }

        private void removeOrphanComfortControls(Composite titleArea, ToolBar menuBar)
        {
            autoCloseCheckbox = null;
            inspectButton = null;
            closeToolBar = null;
            rightArea = null;
            menuBarRef = null;
            int removed = 0;
            for (Control child : titleArea.getChildren())
            {
                if (!isComfortHeader(child))
                    continue;
                if (child instanceof ToolBar bar && menuBar != null && bar == menuBar)
                    continue;
                removed++;
                if (child instanceof Composite composite && menuBar != null && !menuBar.isDisposed())
                {
                    for (Control grand : composite.getChildren())
                    {
                        if (grand == menuBar)
                        {
                            Object layoutData = composite.getLayoutData();
                            menuBar.setParent(titleArea);
                            if (layoutData instanceof GridData gd)
                                menuBar.setLayoutData(gd);
                            break;
                        }
                    }
                }
                child.dispose();
            }
            if (removed > 0)
                DebugInspectorDebug.step("header", "removed orphans=" + removed); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void syncHeaderBackground(Composite titleArea)
        {
            if (titleArea == null || titleArea.isDisposed())
                return;
            Color titleBg = titleArea.getBackground();
            if (autoCloseCheckbox != null && !autoCloseCheckbox.isDisposed())
                autoCloseCheckbox.setBackground(titleBg);
            if (inspectButton != null && !inspectButton.isDisposed())
                inspectButton.setBackground(titleBg);
            if (closeToolBar != null && !closeToolBar.isDisposed())
                closeToolBar.setBackground(titleBg);
            if (rightArea != null && !rightArea.isDisposed())
                rightArea.setBackground(titleBg);
        }

        private void installHeaderGuard(Composite titleArea)
        {
            if (headerGuardInstalled)
                return;
            headerGuardInstalled = true;
            headerMaintainListener = e ->
            {
                if (shell.isDisposed())
                    return;
                if (e.type == SWT.Move && isHoverMode() && !isHoverFollowEnabled())
                    return;
                ToolBar menuBar = resolveToolBar(targets.dialog, shell);
                if (menuBar != null && !menuBar.isDisposed())
                    maintainHeaderControls(menuBar);
            };
            titleArea.addListener(SWT.Resize, headerMaintainListener);
            shell.addListener(SWT.Resize, headerMaintainListener);
            shell.addListener(SWT.Move, headerMaintainListener);
            shell.addListener(SWT.MouseUp, headerMaintainListener);
        }

        private void removeHeaderGuard()
        {
            if (!headerGuardInstalled)
                return;
            if (titleAreaRef != null && !titleAreaRef.isDisposed() && headerMaintainListener != null)
                titleAreaRef.removeListener(SWT.Resize, headerMaintainListener);
            if (!shell.isDisposed() && headerMaintainListener != null)
            {
                shell.removeListener(SWT.Resize, headerMaintainListener);
                shell.removeListener(SWT.Move, headerMaintainListener);
                shell.removeListener(SWT.MouseUp, headerMaintainListener);
            }
            headerGuardInstalled = false;
            headerMaintainListener = null;
        }

        void applyInspectorModeForTargets()
        {
            if (!isHoverMode())
            {
                applyInspectorMode(false);
                return;
            }
            if (autoCloseCheckbox == null || autoCloseCheckbox.isDisposed())
                applyInspectorMode(ComfortSettings.isDebugInspectorHoverUpdate());
            else
                applyInspectorMode(autoCloseCheckbox.getSelection());
        }

        private void configureHeaderCheckbox()
        {
            if (autoCloseCheckbox == null || autoCloseCheckbox.isDisposed() || !isHoverMode())
                return;
            autoCloseCheckbox.setText("Обновлять"); //$NON-NLS-1$
            autoCloseCheckbox.setToolTipText(
                "Обновлять окно при наведении в редакторе и закрывать по сигналам редактора" //$NON-NLS-1$
                    + Global.pluginSignForTooltip());
        }

        private void configureInspectButton()
        {
            if (inspectButton == null || inspectButton.isDisposed() || !isHoverMode())
                return;
            inspectButton.setText("Инспектировать"); //$NON-NLS-1$
            inspectButton.setToolTipText(
                "Открыть инспектор с выражением из hover-окна" //$NON-NLS-1$
                    + Global.pluginSignForTooltip());
            inspectButton.setEnabled(true);
        }

        private void runInspectFromHover()
        {
            IEditorPart editor = findEditorForHoverShell(shell);
            if (editor == null)
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null && window.getActivePage() != null)
                    editor = resolveBslEditorFromPart(window.getActivePage().getActiveEditor());
            }
            Object infoControl = targets != null ? targets.infoControl : null;
            Display display = Display.getDefault();
            if (display == null || display.isDisposed())
                return;
            IEditorPart editorFinal = editor;
            display.asyncExec(() -> openStandaloneInspectFromHover(shell, editorFinal, infoControl));
        }

        private boolean isHoverMode()
        {
            return isHoverInspectControl(targets.dialog) || targets.infoControl != null;
        }

        private boolean isHoverFollowEnabled()
        {
            return isHoverMode()
                && autoCloseCheckbox != null
                && !autoCloseCheckbox.isDisposed()
                && autoCloseCheckbox.getSelection();
        }

        void applyInspectorMode(boolean enabled)
        {
            if (shell.isDisposed())
                return;
            if (!isPatchTarget(targets.dialog))
            {
                Object dialog = resolveElementDialog(shell, targets.infoControl);
                if (isPatchTarget(dialog))
                    targets = new InspectorTargets(dialog, targets.infoControl, targets.hoverManager);
            }
            if (!isPatchTarget(targets.dialog))
            {
                DebugInspectorDebug.step("inspector", "skip dialog=null enabled=" + enabled); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }

            if (isHoverMode())
                applyHoverFollow(enabled);
            else
                applyStandaloneAutoClose(enabled);
        }

        private void applyHoverFollow(boolean follow)
        {
            if (follow)
            {
                removePinnedOutsideMouseFilter();
                removeHoverPinCloseGuard();
                removeHoverPinVisibleGuard();
                removeHoverPinMoveGuard();
                removeKeepDeactivateOffListener();
                restoreHoverEditorLink();
                restoreShellOnTop(false);
                restoreHoverAutoClose();
                DebugInspectorDebug.step("hover", "follow ON"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                if (isOtherShellPinned())
                {
                    DebugInspectorDebug.step("hover", "follow OFF skip other pinned"); //$NON-NLS-1$ //$NON-NLS-2$
                    scheduleSuppressHoverShell(shell);
                    return;
                }
                if (!isAlreadyPinnedHere())
                {
                    pinnedShellMovedByUser = false;
                    restoreShellOnTop(true);
                }
                installHoverPinCloseGuard();
                installHoverPinVisibleGuard();
                installHoverPinMoveGuard();
                installKeepDeactivateOffListener();
                installPinnedOutsideMouseFilter();
                if (isAlreadyPinnedHere())
                {
                    ensureHoverManagerSuspendedForShell();
                    disableHoverAutoClose();
                    ensureHoverPinnedDeactivateOff();
                    DebugInspectorDebug.step("hover", "follow OFF keep pin"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                disableHoverAutoClose();
                ensureHoverPinnedDeactivateOff();
                detachHoverFromEditor();
                disableHoverAutoClose();
                ensureHoverPinnedDeactivateOff();
                DebugInspectorDebug.step("hover", "follow OFF (pinned)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private List<Object> collectInfoControlCandidates()
        {
            Set<Object> seen = new LinkedHashSet<>();
            PinnedHoverLink pin = findPinnedLinkForShell(shell);
            if (pin != null)
            {
                addInfoControlCandidate(seen, pin.pinnedInfoControl());
                addInfoControlCandidate(seen, pin.savedManagerIc());
                addInfoControlCandidate(seen, pin.savedReplacerIc());
                Object manager = pin.textHoverManager();
                addInfoControlCandidate(seen, Global.getField(manager, "fInformationControl")); //$NON-NLS-1$
                Object replacer = Global.getField(manager, "fInformationControlReplacer"); //$NON-NLS-1$
                if (replacer != null)
                    addInfoControlCandidate(seen, Global.getField(replacer, "fInformationControl")); //$NON-NLS-1$
            }
            addInfoControlCandidate(seen, targets.infoControl);
            addInfoControlCandidate(seen, targets.dialog);
            HoverBinding binding = findHoverBindingForShell(shell);
            if (binding != null)
                addInfoControlCandidate(seen, binding.infoControl());
            Object manager = resolveTextHoverManager();
            if (manager != null)
            {
                Object replacer = Global.getField(manager, "fInformationControlReplacer"); //$NON-NLS-1$
                addInfoControlCandidate(seen, readSavedHoverInfoControl(manager, replacer, true));
                if (replacer != null)
                    addInfoControlCandidate(seen, readSavedHoverInfoControl(manager, replacer, false));
            }
            return new ArrayList<>(seen);
        }

        private static void addInfoControlCandidate(Set<Object> seen, Object candidate)
        {
            if (candidate != null && !(candidate instanceof PinnedHoverBlockerInformationControl))
                seen.add(candidate);
        }

        private Object resolvePrimaryInfoControlForShell()
        {
            for (Object candidate : collectInfoControlCandidates())
            {
                if (infoControlShellEquals(candidate, shell))
                    return candidate;
                Object inner = Global.getField(candidate, "debugElementDialog"); //$NON-NLS-1$
                if (inner != null && infoControlShellEquals(inner, shell))
                    return candidate;
            }
            return null;
        }

        private boolean isAlreadyPinnedHere()
        {
            PinnedHoverLink owned = findPinnedLinkForShell(shell);
            return owned != null && !owned.shell().isDisposed();
        }

        private void ensureHoverManagerSuspendedForShell()
        {
            PinnedHoverLink owned = findPinnedLinkForShell(shell);
            if (owned != null)
                suspendHoverManager(owned.textHoverManager());
        }

        private Object resolvePinnedHoverInfoControl()
        {
            Object primary = resolvePrimaryInfoControlForShell();
            if (primary != null)
                return primary;
            PinnedHoverLink pin = findPinnedLinkForShell(shell);
            if (pin != null && pin.pinnedInfoControl() != null)
                return pin.pinnedInfoControl();
            if (isHoverInspectControl(targets.dialog))
                return targets.dialog;
            if (isHoverInspectControl(targets.infoControl))
                return targets.infoControl;
            return null;
        }

        private void ensureHoverPinnedDeactivateOff()
        {
            if (isElementDialog(targets.dialog))
            {
                Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
            }
            for (Object ic : collectInfoControlCandidates())
                suspendInnerDebugElementDialog(ic);
            swizzleDeactivateListenerForPin();
            swizzleFShellListenerForPin();
            Object manager = resolveTextHoverManager();
            if (manager != null)
                suspendHoverEditorInteractionForPin(manager);
        }

        private void swizzleDeactivateListenerForPin()
        {
            if (pinnedDeactivateListenerProxy != null || shell.isDisposed())
                return;
            for (Object ic : collectInfoControlCandidates())
            {
                Object current = Global.getField(ic, "deactivationListener"); //$NON-NLS-1$
                if (!(current instanceof Listener listener) || listener == pinnedDeactivateListenerProxy)
                    continue;
                savedDeactivateListener = listener;
                shell.removeListener(SWT.Deactivate, listener);
                break;
            }
            if (savedDeactivateListener == null)
                return;

            Listener original = savedDeactivateListener;
            pinnedDeactivateListenerProxy = e ->
            {
                if (shouldBlockPinnedClose())
                    return;
                original.handleEvent(e);
            };
            shell.addListener(SWT.Deactivate, pinnedDeactivateListenerProxy);
            for (Object ic : collectInfoControlCandidates())
                Global.setField(ic, "deactivationListener", pinnedDeactivateListenerProxy); //$NON-NLS-1$
            DebugInspectorDebug.step("hover", "deactivationListener swizzled"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void restoreDeactivateListenerForPin()
        {
            if (pinnedDeactivateListenerProxy != null && !shell.isDisposed())
                shell.removeListener(SWT.Deactivate, pinnedDeactivateListenerProxy);
            if (savedDeactivateListener != null && !shell.isDisposed())
            {
                shell.addListener(SWT.Deactivate, savedDeactivateListener);
                for (Object ic : collectInfoControlCandidates())
                    Global.setField(ic, "deactivationListener", savedDeactivateListener); //$NON-NLS-1$
                DebugInspectorDebug.step("hover", "deactivationListener restored"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            savedDeactivateListener = null;
            pinnedDeactivateListenerProxy = null;
        }

        private void swizzleFShellListenerForPin()
        {
            if (pinnedFShellListenerProxy != null || shell.isDisposed())
                return;
            for (Object ic : collectInfoControlCandidates())
            {
                Object current = Global.getField(ic, "fShellListener"); //$NON-NLS-1$
                if (!(current instanceof Listener listener) || listener == pinnedFShellListenerProxy)
                    continue;
                savedFShellListener = listener;
                shell.removeListener(SWT.Deactivate, listener);
                shell.removeListener(SWT.Activate, listener);
                break;
            }
            if (savedFShellListener == null)
                return;

            Listener original = savedFShellListener;
            pinnedFShellListenerProxy = e ->
            {
                if (e.type == SWT.Deactivate && shouldBlockPinnedClose())
                    return;
                original.handleEvent(e);
            }; //$NON-NLS-1$
            shell.addListener(SWT.Deactivate, pinnedFShellListenerProxy);
            shell.addListener(SWT.Activate, pinnedFShellListenerProxy);
            for (Object ic : collectInfoControlCandidates())
                Global.setField(ic, "fShellListener", pinnedFShellListenerProxy); //$NON-NLS-1$
            DebugInspectorDebug.step("hover", "fShellListener swizzled"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void restoreFShellListenerForPin()
        {
            if (pinnedFShellListenerProxy != null && !shell.isDisposed())
            {
                shell.removeListener(SWT.Deactivate, pinnedFShellListenerProxy);
                shell.removeListener(SWT.Activate, pinnedFShellListenerProxy);
            }
            if (savedFShellListener != null && !shell.isDisposed())
            {
                shell.addListener(SWT.Deactivate, savedFShellListener);
                shell.addListener(SWT.Activate, savedFShellListener);
                for (Object ic : collectInfoControlCandidates())
                    Global.setField(ic, "fShellListener", savedFShellListener); //$NON-NLS-1$
                DebugInspectorDebug.step("hover", "fShellListener restored"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            savedFShellListener = null;
            pinnedFShellListenerProxy = null;
        }

        private Control resolveHoverSubjectControl(Object textHoverManager)
        {
            Object subject = Global.getField(textHoverManager, "fSubjectControl"); //$NON-NLS-1$
            if (subject instanceof Control control && !control.isDisposed())
                return control;
            Object viewer = Global.getField(textHoverManager, "fTextViewer"); //$NON-NLS-1$
            if (viewer instanceof TextViewer textViewer)
            {
                StyledText widget = textViewer.getTextWidget();
                if (widget != null && !widget.isDisposed())
                    return widget;
            }
            return null;
        }

        private void stopAllInformationControlClosers(Object textHoverManager)
        {
            if (textHoverManager == null)
                return;
            stopInformationControlCloser(textHoverManager);
            Object current = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
            if (current != null && !(current instanceof NoOpInformationControlCloser))
                Global.invoke(current, "stop"); //$NON-NLS-1$
            if (savedTextHoverCloser != null)
                Global.invoke(savedTextHoverCloser, "stop"); //$NON-NLS-1$
            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
            {
                stopInformationControlCloser(replacer);
                Object replacerCloser = Global.getField(replacer, "fInformationControlCloser"); //$NON-NLS-1$
                if (replacerCloser != null && !(replacerCloser instanceof NoOpInformationControlCloser))
                    Global.invoke(replacerCloser, "stop"); //$NON-NLS-1$
            }
            if (savedReplacerCloser != null)
                Global.invoke(savedReplacerCloser, "stop"); //$NON-NLS-1$
            HoverBinding binding = findHoverBindingForShell(shell);
            if (binding != null && binding.closerOwner() != null)
                stopInformationControlCloser(binding.closerOwner());
        }

        private void suspendHoverEditorInteractionForPin(Object textHoverManager)
        {
            if (textHoverManager == null)
                return;
            suspendHoverManager(textHoverManager);
            stopAllInformationControlClosers(textHoverManager);

            Object stopper = Global.getField(textHoverManager, "fStopper"); //$NON-NLS-1$
            if (stopper != null)
                Global.invoke(stopper, "stop"); //$NON-NLS-1$
            Object tracker = Global.getField(textHoverManager, "fMouseTracker"); //$NON-NLS-1$
            if (tracker != null)
                Global.invoke(tracker, "stop"); //$NON-NLS-1$

            if (savedMouseMoveListener == null)
            {
                Object listener = Global.getField(textHoverManager, "fMouseMoveListener"); //$NON-NLS-1$
                Control subject = resolveHoverSubjectControl(textHoverManager);
                if (subject instanceof StyledText styledText && !styledText.isDisposed()
                    && listener instanceof MouseMoveListener mouseMoveListener)
                {
                    styledText.removeMouseMoveListener(mouseMoveListener);
                    savedMouseMoveSubject = styledText;
                    savedMouseMoveListener = mouseMoveListener;
                    DebugInspectorDebug.step("hover", "editor mouseMove detached"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            if (savedViewportListener == null)
            {
                Object viewportListener = Global.getField(textHoverManager, "fViewportListener"); //$NON-NLS-1$
                if (viewportListener != null)
                {
                    Global.invoke(textHoverManager, "removeViewportListener", viewportListener); //$NON-NLS-1$
                    savedViewportListener = viewportListener;
                    DebugInspectorDebug.step("hover", "editor viewport detached"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        private void restoreHoverEditorInteractionForPin()
        {
            if (savedMouseMoveSubject instanceof StyledText styledText && !styledText.isDisposed()
                && savedMouseMoveListener != null)
            {
                styledText.addMouseMoveListener(savedMouseMoveListener);
                DebugInspectorDebug.step("hover", "editor mouseMove restored"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            savedMouseMoveSubject = null;
            savedMouseMoveListener = null;
            if (savedViewportListener != null)
            {
                Object manager = resolveTextHoverManager();
                if (manager != null)
                    Global.invoke(manager, "addViewportListener", savedViewportListener); //$NON-NLS-1$
                savedViewportListener = null;
                DebugInspectorDebug.step("hover", "editor viewport restored"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private boolean isControlInPinnedShell(Control control)
        {
            for (Control walk = control; walk != null; walk = walk.getParent())
            {
                if (walk == shell)
                    return true;
            }
            return false;
        }

        private void reinforcePinnedHoverGuards()
        {
            if (!shouldBlockPinnedClose())
                return;
            Object manager = resolveTextHoverManager();
            if (manager == null)
            {
                PinnedHoverLink pin = findPinnedLinkForShell(shell);
                if (pin != null)
                    manager = pin.textHoverManager();
            }
            if (manager != null)
            {
                stopAllInformationControlClosers(manager);
                if (!hoverClosersSwizzled)
                    swizzleHoverClosers(manager);
                suspendHoverEditorInteractionForPin(manager);
            }
            swizzleDeactivateListenerForPin();
            swizzleFShellListenerForPin();
        }

        private void installPinnedOutsideMouseFilter()
        {
            if (pinnedOutsideMouseFilter != null || shell.isDisposed())
                return;
            Display display = shell.getDisplay();
            pinnedOutsideMouseFilter = e ->
            {
                if (!shouldBlockPinnedClose() || shell.isDisposed())
                    return;
                if (!(e.widget instanceof Control control) || isControlInPinnedShell(control))
                    return;
                reinforcePinnedHoverGuards();
            };
            display.addFilter(SWT.MouseDown, pinnedOutsideMouseFilter);
            display.addFilter(SWT.MouseUp, pinnedOutsideMouseFilter);
            DebugInspectorDebug.step("hover", "outside mouse filter installed"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void removePinnedOutsideMouseFilter()
        {
            if (pinnedOutsideMouseFilter == null)
                return;
            Display display = shell.isDisposed() ? null : shell.getDisplay();
            if (display != null && !display.isDisposed())
            {
                display.removeFilter(SWT.MouseDown, pinnedOutsideMouseFilter);
                display.removeFilter(SWT.MouseUp, pinnedOutsideMouseFilter);
            }
            pinnedOutsideMouseFilter = null;
        }

        private static boolean isInnerHoverDebugDialog(Object data)
        {
            if (data == null)
                return false;
            String name = data.getClass().getName();
            return CLASS_HOVER_DIALOG.equals(name) || CLASS_DEBUG_ELEMENT_DIALOG.equals(name);
        }

        private Object resolveInnerDebugElementDialog(Object infoControl)
        {
            if (infoControl == null)
                return null;
            Object inner = Global.getField(infoControl, "debugElementDialog"); //$NON-NLS-1$
            if (isInnerHoverDebugDialog(inner) || isElementDialog(inner))
                return inner;
            return null;
        }

        private void suspendInnerDebugElementDialog(Object infoControl)
        {
            Object inner = resolveInnerDebugElementDialog(infoControl);
            if (inner == null)
                return;
            innerDebugDialog = inner;
            Global.setField(inner, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
            Global.setField(inner, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
            if (innerDebugEventsSuspended)
                return;
            Object listener = Global.getField(inner, "debugEventsListener"); //$NON-NLS-1$
            if (listener instanceof IDebugEventSetListener debugListener)
            {
                DebugPlugin.getDefault().removeDebugEventListener(debugListener);
                savedDebugEventsListener = debugListener;
                innerDebugEventsSuspended = true;
                DebugInspectorDebug.step("hover", "inner debug events suspended"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                DebugInspectorDebug.step("hover", //$NON-NLS-1$
                    "inner debug listener missing inner=" + DebugInspectorDebug.cn(inner)); //$NON-NLS-1$
            }
        }

        private void restoreInnerDebugElementDialog()
        {
            if (!innerDebugEventsSuspended || savedDebugEventsListener == null)
                return;
            DebugPlugin.getDefault().addDebugEventListener(savedDebugEventsListener);
            savedDebugEventsListener = null;
            innerDebugEventsSuspended = false;
            innerDebugDialog = null;
            DebugInspectorDebug.step("hover", "inner debug events restored"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void removeHoverIcAutoCloseListeners(Object infoControl)
        {
            if (infoControl == null || shell.isDisposed())
                return;

            boolean removed = false;
            for (String fieldName : new String[] { "deactivationListener", "activationListener" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object listener = Global.getField(infoControl, fieldName);
                if (!(listener instanceof Listener l))
                    continue;
                int type = "deactivationListener".equals(fieldName) ? SWT.Deactivate : SWT.Activate; //$NON-NLS-1$
                shell.removeListener(type, l);
                Global.setField(infoControl, fieldName, null);
                if ("deactivationListener".equals(fieldName)) //$NON-NLS-1$
                    savedHoverDeactivationListener = l;
                else
                    savedHoverActivationListener = l;
                removed = true;
            }

            if (removed)
            {
                hoverIcListenersCleared = true;
                DebugInspectorDebug.step("hover", //$NON-NLS-1$
                    "ic listeners cleared deactiv=" + (savedHoverDeactivationListener != null) //$NON-NLS-1$
                        + " activ=" + (savedHoverActivationListener != null)); //$NON-NLS-1$
            }
        }

        private void restoreHoverIcAutoCloseListeners(Object infoControl)
        {
            if (!hoverIcListenersCleared || infoControl == null || shell.isDisposed())
                return;
            if (savedHoverDeactivationListener != null)
            {
                shell.addListener(SWT.Deactivate, savedHoverDeactivationListener);
                Global.setField(infoControl, "deactivationListener", savedHoverDeactivationListener); //$NON-NLS-1$
            }
            if (savedHoverActivationListener != null)
            {
                shell.addListener(SWT.Activate, savedHoverActivationListener);
                Global.setField(infoControl, "activationListener", savedHoverActivationListener); //$NON-NLS-1$
            }
            savedHoverDeactivationListener = null;
            savedHoverActivationListener = null;
            hoverIcListenersCleared = false;
        }

        private boolean shouldBlockPinnedClose()
        {
            return isHoverMode()
                && !isHoverFollowEnabled()
                && !hoverPinDisposeAllowed;
        }

        private boolean isOtherShellPinned()
        {
            if (findPinnedLinkForShell(shell) != null)
                return false;
            Object manager = resolveTextHoverManager();
            for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
            {
                if (link.shell().isDisposed() || link.shell() == shell)
                    continue;
                if (manager != null && link.textHoverManager() != manager)
                    continue;
                return true;
            }
            return false;
        }

        private void applyStandaloneAutoClose(boolean autoClose)
        {
            if (autoClose)
            {
                removeKeepDeactivateOffListener();
                restoreShellOnTop(false);
                restoreHoverAutoClose();
                DebugInspectorDebug.step("standalone", "close ON"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                if (isElementDialog(targets.dialog))
                {
                    Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                    Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                    installKeepDeactivateOffListener();
                }
                restoreShellOnTop(true);
                DebugInspectorDebug.step("standalone", "close OFF (pinned)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        private Object resolveTextHoverManager()
        {
            if (targets.hoverManager != null)
                return targets.hoverManager;
            HoverBinding binding = findHoverBindingForShell(shell);
            return binding != null ? binding.textHoverManager() : null;
        }

        private void detachHoverFromEditor()
        {
            Object textHoverManager = resolveTextHoverManager();
            if (textHoverManager == null)
            {
                DebugInspectorDebug.step("hover", "detach skip manager=null"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }

            PinnedHoverLink existing = PINNED_HOVER_BY_MANAGER.get(textHoverManager);
            if (existing != null)
            {
                if (!existing.shell().isDisposed() && existing.shell() != shell)
                {
                    DebugInspectorDebug.step("hover", //$NON-NLS-1$
                        "detach skip other pinned=" + existing.shell()); //$NON-NLS-1$
                    scheduleSuppressHoverShell(shell);
                    return;
                }
                if (!existing.shell().isDisposed() && existing.shell() == shell)
                {
                    DebugInspectorDebug.step("hover", "detach skip already pinned"); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                if (existing.shell().isDisposed())
                {
                    if (existing.blockerIc() instanceof PinnedHoverBlockerInformationControl oldBlocker)
                        oldBlocker.dispose();
                    PINNED_HOVER_BY_MANAGER.remove(textHoverManager);
                }
            }

            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            Object savedIc = readSavedHoverInfoControl(textHoverManager, replacer, true);
            Object savedRic = replacer != null
                ? readSavedHoverInfoControl(textHoverManager, replacer, false)
                : null;
            Object pinnedInfoControl = resolvePrimaryInfoControlForShell();
            if (pinnedInfoControl == null)
                pinnedInfoControl = targets.infoControl != null ? targets.infoControl : savedIc;
            if (pinnedInfoControl == null || pinnedInfoControl instanceof PinnedHoverBlockerInformationControl)
                pinnedInfoControl = savedRic;
            if (pinnedInfoControl instanceof PinnedHoverBlockerInformationControl)
                pinnedInfoControl = null;

            Boolean savedProcessMouseHover = readBooleanField(textHoverManager, "fProcessMouseHoverEvent"); //$NON-NLS-1$
            Boolean savedManagerEnabled = readBooleanField(textHoverManager, "fEnabled"); //$NON-NLS-1$
            suspendHoverManager(textHoverManager);

            PinnedHoverBlockerInformationControl blocker =
                new PinnedHoverBlockerInformationControl(shell.getDisplay());
            PINNED_HOVER_BY_MANAGER.put(textHoverManager,
                new PinnedHoverLink(shell, textHoverManager, pinnedInfoControl, savedIc, savedRic, blocker,
                    savedProcessMouseHover, savedManagerEnabled));
            Global.setField(textHoverManager, "fInformationControl", blocker); //$NON-NLS-1$
            if (replacer != null)
                Global.setField(replacer, "fInformationControl", blocker); //$NON-NLS-1$
            DebugInspectorDebug.step("hover", "detach manager=" + textHoverManager.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static Object readSavedHoverInfoControl(
            Object textHoverManager, Object replacer, boolean fromManager)
        {
            Object current = fromManager
                ? Global.getField(textHoverManager, "fInformationControl") //$NON-NLS-1$
                : Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (current != null && !(current instanceof PinnedHoverBlockerInformationControl))
                return current;
            PinnedHoverLink link = PINNED_HOVER_BY_MANAGER.get(textHoverManager);
            if (link == null)
                return null;
            Object saved = fromManager ? link.savedManagerIc() : link.savedReplacerIc();
            if (saved != null && !(saved instanceof PinnedHoverBlockerInformationControl))
                return saved;
            return link.pinnedInfoControl();
        }

        private void restoreHoverEditorLink()
        {
            PinnedHoverLink owned = findPinnedLinkForShell(shell);
            Object textHoverManager = owned != null
                ? owned.textHoverManager()
                : resolveTextHoverManager();
            if (textHoverManager == null)
                return;
            PinnedHoverLink link = PINNED_HOVER_BY_MANAGER.remove(textHoverManager);
            if (link == null)
                return;
            Object restoredManagerIc = link.savedManagerIc();
            if (restoredManagerIc != null && !(restoredManagerIc instanceof PinnedHoverBlockerInformationControl))
                Global.setField(textHoverManager, "fInformationControl", restoredManagerIc); //$NON-NLS-1$
            else
                Global.setField(textHoverManager, "fInformationControl", null); //$NON-NLS-1$
            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
            {
                Object restoredReplacerIc = link.savedReplacerIc();
                if (restoredReplacerIc == null || restoredReplacerIc instanceof PinnedHoverBlockerInformationControl)
                    restoredReplacerIc = restoredManagerIc;
                if (restoredReplacerIc != null && !(restoredReplacerIc instanceof PinnedHoverBlockerInformationControl))
                    Global.setField(replacer, "fInformationControl", restoredReplacerIc); //$NON-NLS-1$
                else
                    Global.setField(replacer, "fInformationControl", null); //$NON-NLS-1$
            }
            if (link.blockerIc() instanceof PinnedHoverBlockerInformationControl blocker)
                blocker.dispose();
            resumeHoverManager(link);
        }

        private void clearPinnedHoverIfOwned()
        {
            PinnedHoverLink owned = findPinnedLinkForShell(shell);
            if (owned != null)
                clearPinnedHoverManager(owned);
        }

        private void clearPinnedHoverForClose(PinnedHoverLink owned, Object preferredIc)
        {
            if (owned == null)
                return;
            Object textHoverManager = owned.textHoverManager();
            PINNED_HOVER_BY_MANAGER.remove(textHoverManager);
            if (owned.blockerIc() instanceof PinnedHoverBlockerInformationControl blocker)
                blocker.dispose();
            Object ic = preferredIc != null ? preferredIc : owned.pinnedInfoControl();
            relinkHoverInfoControlForDispose(textHoverManager, ic);
        }

        private void scheduleHoverManagerReady(Object textHoverManager)
        {
            Display display = shell.getDisplay();
            if (display == null || display.isDisposed())
                return;
            final Object managerRef = textHoverManager;
            display.asyncExec(() ->
            {
                Object manager = managerRef;
                if (manager == null)
                    manager = findTextHoverManagerFromActiveEditor();
                cleanupHoverManagerBlockers(manager);
            });
        }

        private void installKeepDeactivateOffListener()
        {
            if (keepDeactivateOffListener != null)
                return;
            keepDeactivateOffListener = (Event e) ->
            {
                if (e.widget != shell || shell.isDisposed())
                    return;
                if (isElementDialog(targets.dialog))
                {
                    Global.setField(targets.dialog, "listenToDeactivate", Boolean.FALSE); //$NON-NLS-1$
                    Global.setField(targets.dialog, "listenToParentDeactivate", Boolean.FALSE); //$NON-NLS-1$
                }
                if (isHoverMode() && !isHoverFollowEnabled())
                    reinforcePinnedHoverGuards();
            };
            shell.addListener(SWT.Activate, keepDeactivateOffListener);
            shell.addListener(SWT.Deactivate, keepDeactivateOffListener);
        }

        private void removeKeepDeactivateOffListener()
        {
            if (keepDeactivateOffListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Activate, keepDeactivateOffListener);
            shell.removeListener(SWT.Deactivate, keepDeactivateOffListener);
            keepDeactivateOffListener = null;
        }

        private void restoreShellOnTop(boolean pinOnTop)
        {
            shellPinnedOnTop = pinOnTop;
            if (pinOnTop)
            {
                boolean firstPinMaintenance = shellPinListener == null;
                applyShellPinNow();
                installShellPinMaintenance();
                if (firstPinMaintenance)
                    scheduleShellPinRetries();
            }
            else
            {
                removeShellPinMaintenance();
                WinWindowActivator.setShellAboveOwner(shell, null, false);
            }
        }

        private void applyShellPinNow()
        {
            if (shell.isDisposed() || !shellPinnedOnTop)
                return;
            WinWindowActivator.clearShellTopmost(shell);
            if (pinnedShellMovedByUser)
                return;
            WinWindowActivator.setShellAboveOwner(shell, resolveOwnerShell(), true);
        }

        private void installShellPinMaintenance()
        {
            if (shellPinListener != null)
                return;
            shellPinListener = e ->
            {
                if (!shell.isDisposed() && shellPinnedOnTop && !pinnedShellMovedByUser)
                    applyShellPinNow();
            };
            shell.addListener(SWT.Show, shellPinListener);
            shell.addListener(SWT.Activate, shellPinListener);
        }

        private void scheduleShellPinRetries()
        {
            Display display = shell.getDisplay();
            for (int delay : new int[] { 0, 50, 150, 400, 800 })
            {
                display.timerExec(delay, () ->
                {
                    if (!shell.isDisposed() && shellPinnedOnTop && !pinnedShellMovedByUser)
                        applyShellPinNow();
                });
            }
        }

        private void removeShellPinMaintenance()
        {
            if (shellPinListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Show, shellPinListener);
            shell.removeListener(SWT.Activate, shellPinListener);
            shellPinListener = null;
        }

        private Shell resolveOwnerShell()
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null)
                {
                    IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                    if (windows != null && windows.length > 0)
                        window = windows[0];
                }
                if (window != null)
                {
                    Shell workbenchShell = window.getShell();
                    if (workbenchShell != null && !workbenchShell.isDisposed())
                        return workbenchShell;
                }
            }
            catch (RuntimeException ignored)
            {
                // workbench ещё не поднят
            }
            Shell active = shell.getDisplay().getActiveShell();
            if (active != null && !active.isDisposed() && active != shell)
                return active;
            return null;
        }

        private void disableHoverAutoClose()
        {
            if (!ensureHoverAutoCloseDisabled())
                return;
            installHoverAutoCloseGuard();
            scheduleHoverAutoClosePollingOnce();
            if (!hoverDisableLogged)
            {
                hoverDisableLogged = true;
                HoverBinding binding = findHoverBindingForShell(shell);
                DebugInspectorDebug.step("hover", //$NON-NLS-1$
                    "closer swizzled replacer=" //$NON-NLS-1$
                        + (binding != null && binding.closerOwner() != binding.textHoverManager()));
            }
        }

        private boolean ensureHoverAutoCloseDisabled()
        {
            PinnedHoverLink pin = findPinnedLinkForShell(shell);
            HoverBinding binding = findHoverBindingForShell(shell);
            List<Object> infoControls = collectInfoControlCandidates();
            Object textHoverManager = pin != null
                ? pin.textHoverManager()
                : binding != null
                    ? binding.textHoverManager()
                    : findTextHoverManagerFromActiveEditor();
            if (infoControls.isEmpty() && textHoverManager == null)
                return false;

            if (textHoverManager != null)
            {
                stopAllInformationControlClosers(textHoverManager);
                swizzleHoverClosers(textHoverManager);
                suspendHoverEditorInteractionForPin(textHoverManager);
            }
            if (binding != null && binding.closerOwner() != null
                && binding.closerOwner() != textHoverManager)
                swizzleCloserOnOwner(binding.closerOwner());
            return true;
        }

        private void swizzleCloserOnOwner(Object closerOwner)
        {
            stopInformationControlCloser(closerOwner);
            Object currentCloser = Global.getField(closerOwner, "fInformationControlCloser"); //$NON-NLS-1$
            if (!(currentCloser instanceof NoOpInformationControlCloser))
            {
                if (savedReplacerCloser == null)
                    savedReplacerCloser = currentCloser;
                Global.setField(closerOwner, "fInformationControlCloser", NOOP_CLOSER); //$NON-NLS-1$
            }
            hoverClosersSwizzled = true;
        }

        private Object resolveHoverInfoControl(HoverBinding binding)
        {
            if (binding != null)
                return binding.infoControl();
            if (targets.infoControl != null)
                return targets.infoControl;
            return null;
        }

        private void swizzleHoverClosers(Object textHoverManager)
        {
            stopAllInformationControlClosers(textHoverManager);
            Object currentCloser = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
            if (!(currentCloser instanceof NoOpInformationControlCloser))
            {
                if (savedTextHoverCloser == null)
                    savedTextHoverCloser = currentCloser;
                Global.setField(textHoverManager, "fInformationControlCloser", NOOP_CLOSER); //$NON-NLS-1$
            }

            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
            {
                stopInformationControlCloser(replacer);
                Object replacerCloser = Global.getField(replacer, "fInformationControlCloser"); //$NON-NLS-1$
                if (!(replacerCloser instanceof NoOpInformationControlCloser))
                {
                    if (savedReplacerCloser == null)
                        savedReplacerCloser = replacerCloser;
                    Global.setField(replacer, "fInformationControlCloser", NOOP_CLOSER); //$NON-NLS-1$
                }
            }
            hoverClosersSwizzled = true;
        }

        private void installHoverAutoCloseGuard()
        {
            if (hoverAutoCloseGuardListener != null)
                return;
            hoverAutoCloseGuardListener = e ->
            {
                if (e.widget == shell && !shell.isDisposed() && isHoverMode() && !isHoverFollowEnabled())
                    ensureHoverAutoCloseDisabled();
            };
            shell.addListener(SWT.Show, hoverAutoCloseGuardListener);
            shell.addListener(SWT.Activate, hoverAutoCloseGuardListener);
        }

        private void installHoverPinVisibleGuard()
        {
            if (hoverPinVisibleListener != null)
                return;
            hoverPinVisibleListener = e ->
            {
                if (!isHoverMode() || isHoverFollowEnabled() || hoverPinDisposeAllowed
                    || shell.isDisposed() || shell.getVisible())
                    return;
                Display display = shell.getDisplay();
                if (display == null || display.isDisposed())
                    return;
                display.asyncExec(() ->
                {
                    if (!shell.isDisposed() && isHoverMode() && !isHoverFollowEnabled()
                        && !hoverPinDisposeAllowed)
                        shell.setVisible(true);
                });
            };
            shell.addListener(SWT.Hide, hoverPinVisibleListener);
        }

        private void installHoverPinMoveGuard()
        {
            if (hoverPinMoveListener != null)
                return;
            hoverPinMoveListener = e ->
            {
                if (!isHoverMode() || isHoverFollowEnabled() || shell.isDisposed())
                    return;
                if (!pinnedShellMovedByUser)
                    DebugInspectorDebug.step("hover", "pin shell moved by user"); //$NON-NLS-1$ //$NON-NLS-2$
                pinnedShellMovedByUser = true;
                reinforcePinnedHoverGuards();
            };
            shell.addListener(SWT.Move, hoverPinMoveListener);
        }

        private void removeHoverPinMoveGuard()
        {
            if (hoverPinMoveListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Move, hoverPinMoveListener);
            hoverPinMoveListener = null;
            pinnedShellMovedByUser = false;
        }

        private void installHoverPinCloseGuard()
        {
            if (hoverPinCloseListener != null)
                return;
            hoverPinCloseListener = e ->
            {
                if (shouldBlockPinnedClose())
                {
                    e.doit = false;
                    DebugInspectorDebug.step("hover", "pin close blocked"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            };
            shell.addListener(SWT.Close, hoverPinCloseListener);
        }

        private void removeHoverPinCloseGuard()
        {
            if (hoverPinCloseListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Close, hoverPinCloseListener);
            hoverPinCloseListener = null;
        }

        private void removeHoverPinVisibleGuard()
        {
            if (hoverPinVisibleListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Hide, hoverPinVisibleListener);
            hoverPinVisibleListener = null;
        }

        private PinnedHoverLink findPinnedLinkForShell(Shell target)
        {
            pruneDisposedPinnedHovers();
            for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
            {
                if (link.shell() == target)
                    return link;
            }
            return null;
        }

        private void removeHoverAutoCloseGuard()
        {
            if (hoverAutoCloseGuardListener == null || shell.isDisposed())
                return;
            shell.removeListener(SWT.Show, hoverAutoCloseGuardListener);
            shell.removeListener(SWT.Activate, hoverAutoCloseGuardListener);
            hoverAutoCloseGuardListener = null;
            cancelHoverAutoClosePolling();
        }

        private void cancelHoverAutoClosePolling()
        {
            hoverGuardGeneration++;
            hoverPollingScheduled = false;
        }

        private void scheduleHoverAutoClosePollingOnce()
        {
            if (hoverPollingScheduled || shell.isDisposed())
                return;
            hoverPollingScheduled = true;
            int generation = hoverGuardGeneration;
            Display display = shell.getDisplay();
            for (int delay : new int[] { 100, 300, 600, 1000, 2000, 4000, 6000 })
            {
                display.timerExec(delay, () ->
                {
                    if (generation != hoverGuardGeneration || shell.isDisposed())
                        return;
                    if (isHoverMode() && !isHoverFollowEnabled())
                        ensureHoverAutoCloseDisabled();
                });
            }
        }

        private static void stopInformationControlCloser(Object manager)
        {
            if (manager == null)
                return;
            Object closer = Global.getField(manager, "fInformationControlCloser"); //$NON-NLS-1$
            if (closer != null && !(closer instanceof NoOpInformationControlCloser))
                Global.invoke(closer, "stop"); //$NON-NLS-1$
        }

        private void restoreHoverAutoClose()
        {
            HoverBinding binding = findHoverBindingForShell(shell);
            Object infoControl = resolveHoverInfoControl(binding);
            Object textHoverManager = binding != null
                ? binding.textHoverManager()
                : findTextHoverManagerFromActiveEditor();
            if (textHoverManager == null)
                textHoverManager = resolveTextHoverManager();

            removeHoverAutoCloseGuard();

            restoreFShellListenerForPin();
            restoreDeactivateListenerForPin();
            restoreHoverEditorInteractionForPin();
            Object pinnedIc = resolvePinnedHoverInfoControl();
            if (pinnedIc == null)
                pinnedIc = infoControl;
            restoreHoverIcAutoCloseListeners(pinnedIc);
            restoreInnerDebugElementDialog();
            forceRestoreHoverClosers(textHoverManager);
        }

        private void forceRestoreHoverClosers(Object textHoverManager)
        {
            if (textHoverManager == null)
                return;
            Object managerCloser = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
            if (savedTextHoverCloser != null)
            {
                Global.setField(textHoverManager, "fInformationControlCloser", savedTextHoverCloser); //$NON-NLS-1$
                savedTextHoverCloser = null;
            }
            else if (managerCloser instanceof NoOpInformationControlCloser)
                recreateHoverManagerCloser(textHoverManager, textHoverManager);

            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
            {
                Object replacerCloser = Global.getField(replacer, "fInformationControlCloser"); //$NON-NLS-1$
                if (savedReplacerCloser != null)
                {
                    Global.setField(replacer, "fInformationControlCloser", savedReplacerCloser); //$NON-NLS-1$
                    savedReplacerCloser = null;
                }
                else if (replacerCloser instanceof NoOpInformationControlCloser)
                    recreateHoverManagerCloser(replacer, textHoverManager);
            }
            hoverClosersSwizzled = false;
            hoverDisableLogged = false;
            Object check = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
            if (!(check instanceof NoOpInformationControlCloser))
                DebugInspectorDebug.step("hover", "closers restored"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        private void releaseHoverManagerForEditor(Object textHoverManager)
        {
            if (textHoverManager == null)
                textHoverManager = resolveTextHoverManager();
            if (textHoverManager == null)
                textHoverManager = findTextHoverManagerFromActiveEditor();
            if (textHoverManager == null)
                return;

            Object managerCloser = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
            if (managerCloser instanceof NoOpInformationControlCloser || savedTextHoverCloser != null)
                forceRestoreHoverClosers(textHoverManager);

            Object ic = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
            if (ic instanceof PinnedHoverBlockerInformationControl || isBlockerInfoControl(ic))
                clearHoverManagerInfoControls(textHoverManager);
            else if (ic != null && isInfoControlDisposed(ic))
                clearHoverManagerInfoControls(textHoverManager);

            restartHoverManagerAfterClose(textHoverManager);
            hoverEditorReleased = true;
        }

        private void closeInspector()
        {
            requestClose();
        }

        void dispose()
        {
            cancelHoverAutoClosePolling();
            boolean explicitClose = hoverPinDisposeAllowed;
            if (explicitClose)
            {
                if (!hoverEditorReleased)
                {
                    clearPinnedHoverIfOwned();
                    restoreHoverAutoClose();
                    releaseHoverManagerForEditor(resolveTextHoverManager());
                }
                restoreShellOnTop(false);
                DebugInspectorDebug.step("hover", "pin released explicit"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                PinnedHoverLink owned = findPinnedLinkForShell(shell);
                if (owned != null)
                {
                    clearPinnedHoverManager(owned);
                    DebugInspectorDebug.step("hover", "pin shell lost accidental"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            removeHeaderGuard();
            removePinnedOutsideMouseFilter();
            removeKeepDeactivateOffListener();
            removeHoverAutoCloseGuard();
            removeHoverPinCloseGuard();
            removeHoverPinVisibleGuard();
            removeHoverPinMoveGuard();
            removeKeepDeactivateOffListener();
            removeShellPinMaintenance();
            if (treeEnhancement != null)
            {
                treeEnhancement.dispose();
                treeEnhancement = null;
            }
            dropActiveHoverShell(shell);
            if (!shell.isDisposed())
            {
                shell.setData(PATCHED_KEY, null);
                shell.setData(SESSION_KEY, null);
            }
        }
    }

    private static void dropActiveHoverShell(Shell shell)
    {
        if (shell == null)
            return;
        ACTIVE_HOVER_SHELL_BY_MANAGER.entrySet().removeIf(e -> e.getValue() == shell);
    }

    private static void logInspectorDetectOnce(Shell shell, int eventType)
    {
        if (shell.getData(DETECT_LOG_KEY) != null)
            return;
        shell.setData(DETECT_LOG_KEY, Boolean.TRUE);
        String evt = eventType == SWT.Show ? "Show" //$NON-NLS-1$
            : eventType == SWT.Activate ? "Activate" : String.valueOf(eventType); //$NON-NLS-1$
        DebugInspectorDebug.step("detect", //$NON-NLS-1$
            "evt=" + evt + " shell=\"" + shell.getText() //$NON-NLS-1$ //$NON-NLS-2$
                + "\" reason=" + detectInspectorShellReason(shell)); //$NON-NLS-1$
    }

    private static String detectInspectorShellReason(Shell shell)
    {
        if (resolveElementDialog(shell, null) != null)
            return "elementDialog"; //$NON-NLS-1$
        if (isInspectorShellData(shell.getData()))
            return "shellData"; //$NON-NLS-1$
        if (isInspectorShellData(shell.getData(WINDOW_DATA_KEY)))
            return "windowData"; //$NON-NLS-1$
        if (findHoverBindingForShell(shell) != null)
            return "hoverBinding"; //$NON-NLS-1$
        if (hasInspectorTableMarker(shell))
            return "inspectorTree"; //$NON-NLS-1$
        return "?"; //$NON-NLS-1$
    }

    private static Boolean readBooleanField(Object target, String fieldName)
    {
        Object value = Global.getField(target, fieldName);
        if (value instanceof Boolean bool)
            return bool;
        return null;
    }

    private static void suspendHoverManager(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Global.setField(textHoverManager, "fProcessMouseHoverEvent", Boolean.FALSE); //$NON-NLS-1$
        Global.setField(textHoverManager, "fEnabled", Boolean.FALSE); //$NON-NLS-1$
    }

    private static void resumeHoverManager(PinnedHoverLink link)
    {
        if (link == null)
            return;
        Object textHoverManager = link.textHoverManager();
        if (textHoverManager == null)
            return;
        if (link.savedProcessMouseHover() != null)
            Global.setField(textHoverManager, "fProcessMouseHoverEvent", link.savedProcessMouseHover()); //$NON-NLS-1$
        if (link.savedManagerEnabled() != null)
            Global.setField(textHoverManager, "fEnabled", link.savedManagerEnabled()); //$NON-NLS-1$
    }

    private static void pruneDisposedPinnedHovers()
    {
        for (var it = PINNED_HOVER_BY_MANAGER.entrySet().iterator(); it.hasNext();)
        {
            PinnedHoverLink link = it.next().getValue();
            if (link == null || link.shell().isDisposed())
            {
                if (link != null)
                    releasePinnedHoverResources(link);
                it.remove();
            }
        }
    }

    private static void recreateHoverManagerCloser(Object closerHost, Object textHoverManager)
    {
        if (closerHost == null)
            return;
        Object manager = textHoverManager != null ? textHoverManager : closerHost;
        try
        {
            for (Class<?> walk = manager.getClass(); walk != null; walk = walk.getSuperclass())
            {
                for (Class<?> inner : walk.getDeclaredClasses())
                {
                    if (!"Closer".equals(inner.getSimpleName())) //$NON-NLS-1$
                        continue;
                    if (inner.getName().contains("IInformationControlCloser")) //$NON-NLS-1$
                        continue;
                    Object closer = inner.getDeclaredConstructor(walk).newInstance(manager);
                    Global.setField(closerHost, "fInformationControlCloser", closer); //$NON-NLS-1$
                    DebugInspectorDebug.step("hover", //$NON-NLS-1$
                        "closer recreated host=" + closerHost.getClass().getSimpleName()); //$NON-NLS-1$
                    return;
                }
            }
        }
        catch (ReflectiveOperationException e)
        {
            DebugInspectorDebug.problem("recreate closer: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void ensureManagerClosersNotNoOp(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object managerCloser = Global.getField(textHoverManager, "fInformationControlCloser"); //$NON-NLS-1$
        if (managerCloser instanceof NoOpInformationControlCloser)
            recreateHoverManagerCloser(textHoverManager, textHoverManager);
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
        {
            Object replacerCloser = Global.getField(replacer, "fInformationControlCloser"); //$NON-NLS-1$
            if (replacerCloser instanceof NoOpInformationControlCloser)
                recreateHoverManagerCloser(replacer, textHoverManager);
        }
    }

    private static void clearPinnedHoverManager(PinnedHoverLink link)
    {
        if (link == null)
            return;
        Object textHoverManager = link.textHoverManager();
        PINNED_HOVER_BY_MANAGER.remove(textHoverManager);
        releasePinnedHoverResources(link);
    }

    private static void releasePinnedHoverResources(PinnedHoverLink link)
    {
        if (link == null)
            return;
        Object textHoverManager = link.textHoverManager();
        if (textHoverManager != null)
        {
            Global.setField(textHoverManager, "fInformationControl", null); //$NON-NLS-1$
            Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
            if (replacer != null)
                Global.setField(replacer, "fInformationControl", null); //$NON-NLS-1$
        }
        if (link.blockerIc() instanceof PinnedHoverBlockerInformationControl blocker)
            blocker.dispose();
        resumeHoverManager(link);
    }

    private static boolean isInfoControlDisposed(Object infoControl)
    {
        if (infoControl == null)
            return true;
        Object icShell = Global.invoke(infoControl, "getShell"); //$NON-NLS-1$
        return icShell instanceof Shell shell && shell.isDisposed();
    }

    private static void relinkHoverInfoControlForDispose(Object textHoverManager, Object infoControl)
    {
        if (textHoverManager == null || infoControl == null
            || infoControl instanceof PinnedHoverBlockerInformationControl
            || isInfoControlDisposed(infoControl))
            return;
        Global.setField(textHoverManager, "fInformationControl", infoControl); //$NON-NLS-1$
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
            Global.setField(replacer, "fInformationControl", infoControl); //$NON-NLS-1$
    }

    private static void disposeHoverViaManager(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object ic = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        if (ic == null || ic instanceof PinnedHoverBlockerInformationControl)
        {
            DebugInspectorDebug.step("hover", "disposeViaManager skip ic=" + DebugInspectorDebug.cn(ic)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (Global.invokeVoid(textHoverManager, "disposeInformationControl")) //$NON-NLS-1$
            DebugInspectorDebug.step("hover", "disposeViaManager OK ic=" + ic.getClass().getSimpleName()); //$NON-NLS-1$ //$NON-NLS-2$
        else
            DebugInspectorDebug.problem("disposeViaManager failed"); //$NON-NLS-1$
    }

    private static void releaseHoverWidgetToken(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object viewer = Global.getField(textHoverManager, "fTextViewer"); //$NON-NLS-1$
        if (viewer == null)
            return;
        if (Global.invokeVoid(viewer, "releaseWidgetToken", textHoverManager)) //$NON-NLS-1$
            DebugInspectorDebug.step("hover", "widgetToken released"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void resetHoverPresenterThread(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object thread = Global.getField(textHoverManager, "fThread"); //$NON-NLS-1$
        if (thread instanceof Thread presenter && presenter.isAlive())
            presenter.interrupt();
        Global.setField(textHoverManager, "fThread", null); //$NON-NLS-1$
    }

    private static void startInformationControlCloser(Object closerHost)
    {
        if (closerHost == null)
            return;
        Object closer = Global.getField(closerHost, "fInformationControlCloser"); //$NON-NLS-1$
        if (closer != null && !(closer instanceof NoOpInformationControlCloser))
            Global.invoke(closer, "start"); //$NON-NLS-1$
    }

    private static Control resolveHoverSubjectControlStatic(Object textHoverManager)
    {
        if (textHoverManager == null)
            return null;
        Object subject = Global.getField(textHoverManager, "fSubjectControl"); //$NON-NLS-1$
        if (subject instanceof Control control && !control.isDisposed())
            return control;
        Object viewer = Global.getField(textHoverManager, "fTextViewer"); //$NON-NLS-1$
        if (viewer instanceof TextViewer textViewer)
        {
            StyledText widget = textViewer.getTextWidget();
            if (widget != null && !widget.isDisposed())
                return widget;
        }
        return null;
    }

    private static void ensureHoverMouseMoveListener(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object listener = Global.getField(textHoverManager, "fMouseMoveListener"); //$NON-NLS-1$
        if (!(listener instanceof MouseMoveListener mouseMoveListener))
            return;
        Control subject = resolveHoverSubjectControlStatic(textHoverManager);
        if (!(subject instanceof StyledText styledText) || styledText.isDisposed())
            return;
        styledText.removeMouseMoveListener(mouseMoveListener);
        styledText.addMouseMoveListener(mouseMoveListener);
    }

    private static void enableHoverManagerFlags(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Global.setField(textHoverManager, "fEnabled", Boolean.TRUE); //$NON-NLS-1$
        Global.setField(textHoverManager, "fProcessMouseHoverEvent", Boolean.TRUE); //$NON-NLS-1$
    }

    private static void enableHoverManagerForEditor(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        enableHoverManagerFlags(textHoverManager);
        ensureManagerClosersNotNoOp(textHoverManager);
        startInformationControlCloser(textHoverManager);
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
            startInformationControlCloser(replacer);
        Object stopper = Global.getField(textHoverManager, "fStopper"); //$NON-NLS-1$
        if (stopper != null)
            Global.invoke(stopper, "start"); //$NON-NLS-1$
        Object tracker = Global.getField(textHoverManager, "fMouseTracker"); //$NON-NLS-1$
        if (tracker != null)
            Global.invoke(tracker, "start"); //$NON-NLS-1$
    }

    private static void reinstallHoverManagerSubject(Object textHoverManager)
    {
        Control subject = resolveHoverSubjectControlStatic(textHoverManager);
        if (subject == null || subject.isDisposed())
            return;
        Global.invokeVoid(textHoverManager, "install", subject); //$NON-NLS-1$
    }

    private static void restartHoverManagerAfterClose(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        resetHoverPresenterThread(textHoverManager);
        releaseHoverWidgetToken(textHoverManager);
        clearHoverManagerInfoControls(textHoverManager);
        reinstallHoverManagerSubject(textHoverManager);
        enableHoverManagerForEditor(textHoverManager);
        ensureHoverMouseMoveListener(textHoverManager);
        DebugInspectorDebug.step("hover", "manager released after close closer=" //$NON-NLS-1$ //$NON-NLS-2$
            + DebugInspectorDebug.cn(Global.getField(textHoverManager, "fInformationControlCloser"))); //$NON-NLS-1$
    }

    private static void cleanupHoverManagerBlockers(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        pruneDisposedPinnedHovers();
        PinnedHoverLink live = PINNED_HOVER_BY_MANAGER.get(textHoverManager);
        if (live != null && !live.shell().isDisposed())
            return;

        Object ic = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        Object ric = replacer != null ? Global.getField(replacer, "fInformationControl") : null; //$NON-NLS-1$
        boolean hasBlocker = ic instanceof PinnedHoverBlockerInformationControl
            || ric instanceof PinnedHoverBlockerInformationControl;
        if (hasBlocker)
        {
            DebugInspectorDebug.step("hover", "cleanup orphan blocker"); //$NON-NLS-1$ //$NON-NLS-2$
            if (live != null)
                clearPinnedHoverManager(live);
            else
                clearHoverManagerInfoControls(textHoverManager);
            enableHoverManagerFlags(textHoverManager);
            return;
        }
        if (ic != null && isHoverInspectControl(ic))
        {
            Object icShell = Global.invoke(ic, "getShell"); //$NON-NLS-1$
            if (icShell instanceof Shell shell && shell.isDisposed())
            {
                DebugInspectorDebug.step("hover", "cleanup disposed ic on manager"); //$NON-NLS-1$ //$NON-NLS-2$
                clearHoverManagerInfoControls(textHoverManager);
                enableHoverManagerFlags(textHoverManager);
            }
            return;
        }
        Object enabled = Global.getField(textHoverManager, "fEnabled"); //$NON-NLS-1$
        Object process = Global.getField(textHoverManager, "fProcessMouseHoverEvent"); //$NON-NLS-1$
        if (Boolean.FALSE.equals(enabled) || Boolean.FALSE.equals(process))
            enableHoverManagerFlags(textHoverManager);
    }

    private static void clearHoverManagerInfoControls(Object textHoverManager)
    {
        if (textHoverManager == null)
            return;
        Object ic = Global.getField(textHoverManager, "fInformationControl"); //$NON-NLS-1$
        if (ic instanceof PinnedHoverBlockerInformationControl blocker)
            blocker.dispose();
        Global.setField(textHoverManager, "fInformationControl", null); //$NON-NLS-1$
        Object replacer = Global.getField(textHoverManager, "fInformationControlReplacer"); //$NON-NLS-1$
        if (replacer != null)
        {
            Object ric = Global.getField(replacer, "fInformationControl"); //$NON-NLS-1$
            if (ric instanceof PinnedHoverBlockerInformationControl replacerBlocker)
                replacerBlocker.dispose();
            Global.setField(replacer, "fInformationControl", null); //$NON-NLS-1$
        }
    }

    private static void scheduleSuppressHoverShell(Shell shell)
    {
        suppressHoverShell(shell);
    }

    private static boolean shouldAbortTransientHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        if (hasInspectorTableMarker(shell) || isPatchTarget(resolveElementDialog(shell, null)))
            return false;
        return findHoverBindingForShell(shell) != null;
    }

    private static boolean isPinnedShell(Shell shell)
    {
        if (shell == null || shell.isDisposed())
            return false;
        for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
        {
            if (!link.shell().isDisposed() && link.shell() == shell)
                return true;
        }
        return false;
    }

    private static boolean isBlockerShell(Shell shell)
    {
        return shell != null && !shell.isDisposed()
            && Boolean.TRUE.equals(shell.getData(PinnedHoverBlockerInformationControl.SHELL_MARKER_KEY));
    }

    private static boolean isBlockerInfoControl(Object infoControl)
    {
        return infoControl instanceof PinnedHoverBlockerInformationControl;
    }

    private static void requestCloseHoverShell(Shell target)
    {
        if (target == null || target.isDisposed())
            return;
        Object sessionObj = target.getData(SESSION_KEY);
        if (sessionObj instanceof InspectorPatchSession session)
            session.requestClose();
        else
            suppressHoverShell(target);
    }

    private static boolean isShellBlockedByOtherPin(Shell shell)
    {
        if (shell == null || shell.isDisposed() || isStandaloneInspectorShell(shell))
            return false;
        pruneDisposedPinnedHovers();
        if (PINNED_HOVER_BY_MANAGER.isEmpty())
            return false;
        Object incomingManager = resolveHoverManagerForShell(shell, resolveTargets(shell));
        for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
        {
            if (link.shell().isDisposed() || link.shell() == shell)
                continue;
            if (incomingManager != null && link.textHoverManager() != incomingManager)
                continue;
            DebugInspectorDebug.step("hover", //$NON-NLS-1$
                "block other pin incoming=" + shell + " pinned=" + link.shell()); //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        return false;
    }

    private static boolean isStandaloneInspectorShell(Shell shell)
    {
        Object dialog = resolveElementDialog(shell, null);
        return isElementDialog(dialog) && !isHoverInspectControl(dialog);
    }

    private static PinnedHoverLink findBlockingPinnedHover(Shell incoming)
    {
        if (!mightBeInspectorShell(incoming))
            return null;
        Object incomingManager = resolveHoverManagerForShell(incoming, resolveTargets(incoming));
        for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
        {
            if (link == null || link.shell().isDisposed())
                continue;
            if (link.shell() == incoming)
                return null;
            if (incomingManager != null && link.textHoverManager() != incomingManager)
                continue;
            return link;
        }
        return null;
    }

    private static boolean shouldSuppressDuplicateHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed() || isStandaloneInspectorShell(shell))
            return false;
        pruneDisposedPinnedHovers();
        PinnedHoverLink blocking = findBlockingPinnedHover(shell);
        if (blocking != null)
        {
            DebugInspectorDebug.step("hover", //$NON-NLS-1$
                "suppress duplicate shell reason=pinned pinned=" + blocking.shell()); //$NON-NLS-1$
            return true;
        }
        return false;
    }

    private static Object resolveHoverManagerForShell(Shell shell, InspectorTargets targets)
    {
        if (targets != null && targets.hoverManager != null)
            return targets.hoverManager;
        HoverBinding binding = findHoverBindingForShell(shell);
        return binding != null ? binding.textHoverManager() : null;
    }

    private static void suppressHoverShell(Shell shell)
    {
        if (shell == null || shell.isDisposed() || isPinnedShell(shell))
            return;
        shell.setVisible(false);
        HoverBinding binding = findHoverBindingForShell(shell);
        Object infoControl = binding != null ? binding.infoControl() : null;
        if (infoControl != null && !(infoControl instanceof PinnedHoverBlockerInformationControl))
        {
            Object icShell = Global.invoke(infoControl, "getShell"); //$NON-NLS-1$
            if (icShell == shell && !isPinnedShell(shell))
                Global.invoke(infoControl, "setVisible", Boolean.FALSE); //$NON-NLS-1$
        }
        if (!shell.isDisposed() && !isPinnedShell(shell) && !sharesPinnedInfoControl(shell))
            shell.dispose();
    }

    private static boolean sharesPinnedInfoControl(Shell shell)
    {
        HoverBinding binding = findHoverBindingForShell(shell);
        if (binding == null)
            return false;
        Object infoControl = binding.infoControl();
        if (infoControl == null)
            return false;
        for (PinnedHoverLink link : PINNED_HOVER_BY_MANAGER.values())
        {
            if (link.shell().isDisposed())
                continue;
            if (infoControl == link.pinnedInfoControl())
                return true;
        }
        return false;
    }

    private static void markComfortHeader(Control control)
    {
        control.setData(COMFORT_HEADER_KEY, Boolean.TRUE);
    }

    private static boolean isComfortHeader(Control control)
    {
        return control != null && !control.isDisposed()
            && Boolean.TRUE.equals(control.getData(COMFORT_HEADER_KEY));
    }

    /** Ячейка для блока «флажок / Инспектировать»; флажок на прежней высоте. */
    private static GridData rightAreaGridData()
    {
        GridData gd = new GridData(SWT.END, SWT.CENTER, false, false);
        gd.verticalIndent = HEADER_LIFT_PX;
        return gd;
    }

    private static GridData checkboxInRightAreaGridData()
    {
        return new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
    }

    private static GridData inspectButtonGridData()
    {
        GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        gd.verticalIndent = HOVER_HEADER_LIFT_EXTRA_PX;
        return gd;
    }

    private static GridData closeButtonGridData(boolean hover)
    {
        GridData gd = new GridData(SWT.END, SWT.CENTER, false, false);
        gd.verticalIndent = HEADER_LIFT_PX + (hover ? HOVER_HEADER_LIFT_EXTRA_PX : 0);
        return gd;
    }

    private static String describeToolBar(ToolBar bar)
    {
        if (bar == null)
            return "null"; //$NON-NLS-1$
        if (bar.isDisposed())
            return "disposed"; //$NON-NLS-1$
        return "items=" + bar.getItemCount() + " path=" + controlPath(bar); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String controlPath(Control control)
    {
        if (control == null || control.isDisposed())
            return "disposed"; //$NON-NLS-1$
        StringBuilder path = new StringBuilder(control.getClass().getSimpleName());
        for (Composite parent = control.getParent(); parent != null; parent = parent.getParent())
        {
            path.insert(0, parent.getClass().getSimpleName() + '/');
            if (parent instanceof Shell)
                break;
        }
        return path.toString();
    }

    private static String describeDialogStep(String label, Object data)
    {
        if (data == null)
            return label + "=null"; //$NON-NLS-1$
        if (isElementDialog(data))
            return label + '=' + data.getClass().getSimpleName();
        if (isHoverInspectControl(data))
            return label + "=hover:" + data.getClass().getSimpleName(); //$NON-NLS-1$
        String name = data.getClass().getName();
        if (name.contains("ExpressionInformationControl")) //$NON-NLS-1$
        {
            Object inner = Global.getField(data, "debugElementDialog"); //$NON-NLS-1$
            if (!isElementDialog(inner))
                inner = Global.invoke(data, "getDebugElementDialog"); //$NON-NLS-1$
            if (isElementDialog(inner))
                return label + "→" + inner.getClass().getSimpleName(); //$NON-NLS-1$
            return label + "=wrap:" + data.getClass().getSimpleName() //$NON-NLS-1$
                + "(inner=" + DebugInspectorDebug.cn(inner) + ')'; //$NON-NLS-1$
        }
        return label + "=reject:" + data.getClass().getSimpleName(); //$NON-NLS-1$
    }

    private static void traceResolveDiagnostics(
        Shell shell, int attempt, InspectorTargets targets, ToolBar menuBar, String headerNote)
    {
        StringBuilder msg = new StringBuilder();
        msg.append("a=").append(attempt);
        msg.append(" shell=\"").append(shell.getText()).append('"');
        msg.append(" tree=").append(hasInspectorTableMarker(shell));
        msg.append(' ').append(describeDialogStep("shellData", shell.getData())); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("windowData", shell.getData(WINDOW_DATA_KEY))); //$NON-NLS-1$
        if (targets.infoControl != null)
            msg.append(' ').append(describeDialogStep("infoCtrl", targets.infoControl)); //$NON-NLS-1$
        msg.append(" hoverBind=").append(findHoverBindingForShell(shell) != null); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("resolve", resolveElementDialog(shell, targets.infoControl))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("shellMatch", findElementDialogByShellMatch(shell))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("treeMatch", findElementDialogByTreeShell(shell))); //$NON-NLS-1$
        msg.append(' ').append(describeDialogStep("targets", targets.dialog)); //$NON-NLS-1$
        if (isElementDialog(targets.dialog))
        {
            ToolBar fromField = (ToolBar) Global.getField(targets.dialog, "toolBar"); //$NON-NLS-1$
            msg.append(" dialog.toolBar=").append(fromField != null && !fromField.isDisposed() ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Object titleObj = Global.getField(targets.dialog, "titleAreaComposite"); //$NON-NLS-1$
            msg.append(" titleArea=").append(DebugInspectorDebug.cn(titleObj)); //$NON-NLS-1$
            if (titleObj instanceof Composite title && !title.isDisposed())
            {
                ToolBar inTitle = findToolBarInControls(title);
                msg.append(" title.toolBar=").append(inTitle != null ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        else if (hasInspectorTableMarker(shell))
        {
            Composite titleFromTree = resolveTitleAreaFromTree(shell);
            msg.append(" titleFromTree=").append(titleFromTree != null ? "ok" : "null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        msg.append(" menu=").append(describeToolBar(menuBar)); //$NON-NLS-1$
        if (headerNote != null && !headerNote.isEmpty())
            msg.append(' ').append(headerNote);
        DebugInspectorDebug.step("resolve", msg.toString()); //$NON-NLS-1$
    }
}

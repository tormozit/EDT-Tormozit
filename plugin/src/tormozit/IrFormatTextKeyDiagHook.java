package tormozit;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Пассивная диагностика Alt+Shift+F на уровне {@link Display} (раньше виджета).
 * Не меняет {@code event.doit} и не перехватывает клавишу.
 */
public final class IrFormatTextKeyDiagHook implements IStartup
{
    private static final String FORCE_RETURN_COMMAND_ID =
            "org.eclipse.jdt.debug.ui.commands.ForceReturn"; //$NON-NLS-1$

    private static final String XTEXT_FORMAT_COMMAND_ID =
            "org.eclipse.xtext.ui.FormatAction"; //$NON-NLS-1$

    private static boolean installed;
    private static boolean executionListenerInstalled;
    private static boolean hookEnsurerInstalled;

    private static long lastAltFProbeAt;

    @Override
    public void earlyStartup()
    {
        if (!IrFormatTextDebug.isKeyDiagnosticEnabled())
            return;

        Display display = Display.getDefault();
        if (display == null)
            return;
        display.asyncExec(IrFormatTextKeyDiagHook::installDiagnostics);
    }

    private static void installDiagnostics()
    {
        installDisplayFilter();
        installExecutionListener();
        installHookEnsurer();
    }

    private static void installDisplayFilter()
    {
        if (installed)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        Listener filter = IrFormatTextKeyDiagHook::onDisplayKeyDown;
        display.addFilter(SWT.KeyDown, filter);
        installed = true;

        if (Global.isLogEnabled())
        {
            IrFormatTextDebug.logComfortKeyDownHooksRegistered();
            IrFormatTextDebug.log("diag armed: display filter"); //$NON-NLS-1$
        }
    }

    private static void installExecutionListener()
    {
        if (executionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;

        ICommandService commandService =
                PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;

        commandService.addExecutionListener(executionListener);
        executionListenerInstalled = true;

        if (Global.isLogEnabled())
            IrFormatTextDebug.log("diag armed: execution listener"); //$NON-NLS-1$
    }

    private static void installHookEnsurer()
    {
        if (hookEnsurerInstalled || PlatformUI.getWorkbench() == null)
            return;

        IWindowListener windowListener = new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override public void windowActivated(IWorkbenchWindow window) {}
            @Override public void windowDeactivated(IWorkbenchWindow window) {}
            @Override public void windowClosed(IWorkbenchWindow window) {}
        };
        PlatformUI.getWorkbench().addWindowListener(windowListener);
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            hookWindow(window);

        hookEnsurerInstalled = true;
        if (Global.isLogEnabled())
            IrFormatTextDebug.log("diag armed: hook ensurer"); //$NON-NLS-1$
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null)
                    ensureEmbeddedHooksForEditor(editor);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference editorRef)
                {
                    IEditorPart editor = editorRef.getEditor(false);
                    if (editor != null)
                        ensureEmbeddedHooksForEditor(editor);
                }
            }

            @Override
            public void partActivated(IWorkbenchPartReference ref)
            {
                if (ref instanceof IEditorReference editorRef)
                {
                    IEditorPart editor = editorRef.getEditor(false);
                    if (editor != null)
                        ensureEmbeddedHooksForEditor(editor);
                }
            }

            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
        });
    }

    private static void ensureEmbeddedHooksForEditor(IEditorPart editor)
    {
        BslXtextEditor bsl = GetRef.getActiveBslEditor(editor);
        if (bsl == null)
            return;
        StyledText textWidget = resolveStyledText(bsl);
        if (textWidget == null)
            return;
        BSLEditorMenuHook.ensureEmbeddedHooks(bsl, textWidget);
    }

    private static StyledText resolveStyledText(BslXtextEditor editor)
    {
        try
        {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            if (viewer instanceof SourceViewer sourceViewer)
            {
                StyledText textWidget = sourceViewer.getTextWidget();
                if (textWidget != null && !textWidget.isDisposed())
                    return textWidget;
            }
        }
        catch (Exception ignored)
        {
            // null ниже
        }
        return null;
    }

    private static final IExecutionListener executionListener = new IExecutionListener()
    {
        @Override
        public void preExecute(String commandId, ExecutionEvent event)
        {
            if (!IrFormatTextDebug.isKeyDiagnosticEnabled() || !isDiagCommand(commandId))
                return;

            BslXtextEditor editor = resolveBslFromWorkbench();
            IrFormatTextDebug.logBindingSnapshot("execution", commandId, editor); //$NON-NLS-1$
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception)
        {
            if (!IrFormatTextDebug.isKeyDiagnosticEnabled() || !isDiagCommand(commandId))
                return;
            IrFormatTextDebug.logBindingSnapshot("notHandled", commandId, resolveBslFromWorkbench()); //$NON-NLS-1$
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception)
        {
            if (!IrFormatTextDebug.isKeyDiagnosticEnabled() || !isDiagCommand(commandId))
                return;
            IrFormatTextDebug.logBindingSnapshot("postExecuteFailure", commandId, resolveBslFromWorkbench()); //$NON-NLS-1$
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue)
        {
            if (!IrFormatTextDebug.isKeyDiagnosticEnabled() || !isDiagCommand(commandId))
                return;
            IrFormatTextDebug.logBindingSnapshot("postExecuteSuccess", commandId, resolveBslFromWorkbench()); //$NON-NLS-1$
        }
    };

    private static boolean isDiagCommand(String commandId)
    {
        return IrFormatTextCommandHandler.COMMAND_ID.equals(commandId)
                || FORCE_RETURN_COMMAND_ID.equals(commandId)
                || XTEXT_FORMAT_COMMAND_ID.equals(commandId);
    }

    private static void onDisplayKeyDown(Event event)
    {
        if (!IrFormatTextDebug.isKeyDiagnosticEnabled() || event.type != SWT.KeyDown)
            return;

        BslXtextEditor bslEditor = resolveBslFromWorkbench();

        if (IrFormatTextDebug.looksLikeAltShiftF(event))
        {
            IrFormatTextDebug.logFullRuntimeReport("display", event, bslEditor); //$NON-NLS-1$
            return;
        }

        // Alt+F в BSL: SHIFT иногда отсутствует в stateMask (RU/Windows).
        if (bslEditor != null && IrFormatTextDebug.isFKey(event) && (event.stateMask & SWT.ALT) != 0)
        {
            long now = System.currentTimeMillis();
            if (now - lastAltFProbeAt >= 400L)
            {
                lastAltFProbeAt = now;
                IrFormatTextDebug.logFullRuntimeReport("display-altF", event, bslEditor); //$NON-NLS-1$
            }
            return;
        }

        // Probe: что SWT реально доставляет (если полный report не сработал)
        if (IrFormatTextDebug.isFKey(event))
            IrFormatTextDebug.step("keyProbe F-key", IrFormatTextDebug.formatKeyEvent(event)); //$NON-NLS-1$
        if (IrFormatTextDebug.hasAltShiftModifiers(event))
            IrFormatTextDebug.step("keyProbe alt+shift", IrFormatTextDebug.formatKeyEvent(event)); //$NON-NLS-1$
    }

    private static BslXtextEditor resolveBslFromWorkbench()
    {
        try
        {
            if (PlatformUI.getWorkbench() == null)
                return null;
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;
            IEditorPart editor = page.getActiveEditor();
            if (editor == null)
                return null;
            return GetRef.getActiveBslEditor(editor);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }
}

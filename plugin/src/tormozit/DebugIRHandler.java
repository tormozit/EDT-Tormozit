package tormozit;

import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;

/**
 * Обработчик команды «Отладить ИР» в контекстном меню BSL-редактора.
 * Порт {@code ОтладитьОбъект()} из RDT.txt.
 */
public final class DebugIRHandler
{
    private static final String REQUIRE_DEFERRED_DEBUG = "Ложь"; //$NON-NLS-1$
    private static final String IR_CACHE_OS_PID_EXPR = "ирКэш.ИдентификаторПроцессаОСЛкс()"; //$NON-NLS-1$
    private static final long EVAL_TIMEOUT_MS = 1_000;
    private static final long IR_WINDOW_WAIT_MS = 2_000;
    private static final Pattern IR_TOOL_WINDOW_TITLE =
        Pattern.compile("^(Консоль|Исследователь|Табличный|Дерево|Таблица)"); //$NON-NLS-1$

    private DebugIRHandler() {}

    public static boolean isApplicable(BslXtextEditor editor)
    {
        IProject project = getWorkspaceProject(editor);
        return project != null && DebugSessionHelper.isDebugSuspended(project);
    }

    public static void debugObject(BslXtextEditor editor)
    {
        IDtProject dtProject = getDtProjectFromBslEditor(editor);
        if (dtProject == null)
            return;

        IProject wsProject = dtProject.getWorkspaceProject();
        IRSession irSession = IRApplication.getSession(dtProject);
        if (irSession == null || irSession.executor == null)
            return;

        String ref = GetRef.buildExtendedRef(editor, false);
        String selectedText = getSelectedText(editor);
        irSession.syncCodeEditorToIR(editor);

        irSession.executor.submit(() ->
        {
            try
            {
                ensureCodeEditor(irSession);
                ComBridge.invoke(irSession.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$
                String debugContext = ComBridge.toString(
                    ComBridge.invoke(irSession.codeEditor, "ВычисляемыйКонтекстОтладчика")); //$NON-NLS-1$
                if (debugContext == null || debugContext.isBlank())
                {
                    toast("Отладить ИР",
                        "Команда применима только при остановке отладки в редакторе модуля"); //$NON-NLS-1$
                    return;
                }
                String textCall = debugContext.split("\\*")[0] + "От"; //$NON-NLS-1$ //$NON-NLS-2$
                String moduleRef = ComBridge.toString(
                    ComBridge.invoke(irSession.codeEditor, "СсылкаСтрокиМодуля", null, false)); //$NON-NLS-1$
                String exprText = selectedText;
                if (exprText == null || exprText.isBlank())
                    exprText = ComBridge.toString(ComBridge.getProperty(irSession.codeEditor, "мКонтекст")); //$NON-NLS-1$
                if (exprText == null || exprText.isBlank())
                    return;
                if (isHierarchicalLogicalExpression(exprText))
                {
                    handleHierarchicalExpression(irSession, wsProject, exprText, ref);
                    return;
                }
                String expression = exprText.startsWith(textCall) ? exprText : buildDebugExpression(textCall, exprText, moduleRef);
                boolean isThickClient = DebugSessionHelper.isThickClientDebug(wsProject);
                long thickPid = 0;
                if (isThickClient)
                    thickPid = evaluateThickClientOsPid(wsProject);
                DebugSessionHelper.EvalResult evalResult = evaluateOnUiThread(wsProject, expression);
                if (thickPid > 0)
                    if (WinWindowActivator.waitForWindowTitle(thickPid, IR_TOOL_WINDOW_TITLE, IR_WINDOW_WAIT_MS))
                        WinWindowActivator.activateMainWindow(thickPid);
                if (evalResult != null && evalResult.hasValue()
                    && evalResult.valueText.toLowerCase(Locale.ROOT).contains("открыть объект для отладки")) //$NON-NLS-1$
                {
                    Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                    ComBridge.invoke(irClient, "ОтладитьОтложенныйОбъектЛкс", evalResult.valueText); //$NON-NLS-1$
                    irSession.showWindow();
                    return;
                }
                if (evalResult == null || !evalResult.hasValue())
                {
                    toast("Отладить ИР",
                        "Не дождались снимка объекта. Либо повторите команду после его появления, "
                            + "либо активируйте окно отлаживаемого приложения.", //$NON-NLS-1$
                        5_000);
                }
            }
            catch (Exception e)
            {
                Global.log("DebugIRHandler: " + e.getMessage()); //$NON-NLS-1$
                toast("Отладить ИР", "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static void handleHierarchicalExpression(
        IRSession irSession, IProject wsProject, String exprText, String ref) throws Exception
    {
        Object irCommon = irSession.getModule("ирОбщий"); //$NON-NLS-1$
        String wrapped = ComBridge.toString(
            ComBridge.invoke(irCommon, "ТекстВВыражениеВстроенногоЯзыкаЛкс", exprText)); //$NON-NLS-1$
        String evalExpr = "ирОбщий.РасшифровкаИерархическогоЛогическогоВыраженияЛкс(" + wrapped + ")"; //$NON-NLS-1$ //$NON-NLS-2$

        DebugSessionHelper.EvalResult evalResult = evaluateOnUiThread(wsProject, evalExpr);
        if (evalResult == null || !evalResult.hasValue())
            return;

        irSession.showWindow();
        irSession.openTextEditor(evalResult.valueText, ref != null ? ref : ""); //$NON-NLS-1$
    }

    private static String buildDebugExpression(String textCall, String exprText, String moduleRef)
    {
        String commaSuffix = exprText.contains(",") ? ",," : ",,,"; //$NON-NLS-1$ //$NON-NLS-2$
        return textCall + "(" + exprText + commaSuffix + " " + REQUIRE_DEFERRED_DEBUG //$NON-NLS-1$ //$NON-NLS-2$
            + ",,,, \"" + moduleRef + "\")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isHierarchicalLogicalExpression(String exprText)
    {
        if (exprText == null || !exprText.contains("\n") && !exprText.contains("\r")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;

        String firstLine = exprText.split("\\R", 2)[0].strip(); //$NON-NLS-1$
        if (firstLine.startsWith("(")) //$NON-NLS-1$
            firstLine = firstLine.substring(1).stripLeading();
        firstLine = firstLine.toLowerCase(Locale.ROOT);
        return "истина".equals(firstLine) || "ложь".equals(firstLine); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static long evaluateThickClientOsPid(IProject project)
    {
        return parseIntFromEval(evaluateOnUiThread(project, IR_CACHE_OS_PID_EXPR));
    }

    private static long parseIntFromEval(DebugSessionHelper.EvalResult result)
    {
        if (result == null || !result.hasValue())
            return 0L;

        String text = result.valueText.strip();
        if (text.isEmpty())
            return 0L;

        String numeric = stripIntegerGroupSeparators(text);
        if (numeric.isEmpty() || "+".equals(numeric)) //$NON-NLS-1$
            return 0L;
        if ("-".equals(numeric)) //$NON-NLS-1$
            return 0L;

        try
        {
            return Long.parseLong(numeric);
        }
        catch (NumberFormatException e)
        {
            Global.log("DebugIRHandler.parseIntFromEval: " + text); //$NON-NLS-1$
            return 0L;
        }
    }

    private static String stripIntegerGroupSeparators(String text)
    {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == ',' || c == '\'' || Character.isWhitespace(c))
                continue;
            if (c == '\u00A0' || c == '\u202F' || c == '\u2009') // NBSP, narrow NBSP, thin space
                continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static DebugSessionHelper.EvalResult evaluateOnUiThread(IProject project, String expression)
    {
        final DebugSessionHelper.EvalResult[] box = new DebugSessionHelper.EvalResult[1];
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return new DebugSessionHelper.EvalResult(null, null, "no display"); //$NON-NLS-1$

        display.syncExec(() ->
        {
            IBslStackFrame frame = DebugSessionHelper.findSuspendedStackFrame(project);
            box[0] = DebugSessionHelper.evaluateExpression(frame, expression, EVAL_TIMEOUT_MS);
        });
        return box[0];
    }

    private static void ensureCodeEditor(IRSession irSession)
    {
        if (irSession.codeEditor != null)
            return;
        Object irCache = irSession.getModule("ирКэш"); //$NON-NLS-1$
        irSession.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
    }

    private static String getSelectedText(BslXtextEditor editor)
    {
        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer == null)
            return ""; //$NON-NLS-1$
        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return ""; //$NON-NLS-1$
        if (textSel.getLength() == 0)
            return ""; //$NON-NLS-1$
        return textSel.getText();
    }

    private static IProject getWorkspaceProject(BslXtextEditor editor)
    {
        IDtProject dt = getDtProjectFromBslEditor(editor);
        return dt != null ? dt.getWorkspaceProject() : null;
    }

    private static IDtProject getDtProjectFromBslEditor(BslXtextEditor editor)
    {
        Object p = Global.getField(editor, "project"); //$NON-NLS-1$
        if (p instanceof IDtProject)
            return (IDtProject) p;

        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input == null)
                return null;

            IFile file = input.getAdapter(IFile.class);
            if (file == null)
                return null;

            IProject iProject = file.getProject();
            IV8ProjectManager projectManager =
                (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
            if (projectManager == null)
                return null;

            Object result = Global.invoke(projectManager, "getDtProject", iProject); //$NON-NLS-1$
            if (result instanceof IDtProject)
                return (IDtProject) result;

            Object allProjects = Global.call(projectManager, "getProjects"); //$NON-NLS-1$
            if (allProjects instanceof Iterable<?>)
            {
                for (Object proj : (Iterable<?>) allProjects)
                {
                    IDtProject candidate = toDtProject(proj);
                    if (candidate != null && iProject.equals(candidate.getWorkspaceProject()))
                        return candidate;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("DebugIRHandler.getDtProjectFromBslEditor: " + e); //$NON-NLS-1$
        }
        return null;
    }

    private static IDtProject toDtProject(Object proj)
    {
        if (proj instanceof IDtProject)
            return (IDtProject) proj;
        if (proj instanceof IV8Project)
        {
            Object dt = Global.call(proj, "getDtProject"); //$NON-NLS-1$
            if (dt instanceof IDtProject)
                return (IDtProject) dt;
        }
        return null;
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }
}

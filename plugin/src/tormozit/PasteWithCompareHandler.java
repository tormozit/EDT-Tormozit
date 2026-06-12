package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Команда «Вставить со сравнением»: модальное сравнение выделения с буфером обмена.
 */
public class PasteWithCompareHandler extends AbstractHandler
{
    @Override
    public void setEnabled(Object evaluationContext)
    {
        boolean enabled = false;
        if (evaluationContext instanceof IEvaluationContext context)
        {
            try
            {
                Object part = context.getVariable(ISources.ACTIVE_PART_NAME);
                Object editor = context.getVariable(ISources.ACTIVE_EDITOR_NAME);
                Object shell = context.getVariable(ISources.ACTIVE_SHELL_NAME);

                IWorkbenchPart workbenchPart = part instanceof IWorkbenchPart wp ? wp : null;
                IEditorPart editorPart = editor instanceof IEditorPart ep ? ep : null;
                if (workbenchPart == null && editorPart instanceof IWorkbenchPart wp)
                    workbenchPart = wp;

                TextEditorSupport.Context ctx =
                    TextEditorSupport.resolveContext(workbenchPart, editorPart);
                if (ctx != null && ctx.editable)
                {
                    org.eclipse.swt.widgets.Shell activeShell =
                        shell instanceof org.eclipse.swt.widgets.Shell s ? s : null;
                    enabled = TextEditorSupport.clipboardHasText(activeShell);
                }
            }
            catch (Exception ignored)
            {
                // команда остаётся недоступной
            }
        }
        setBaseEnabled(enabled);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        PasteWithCompareActions.run(
            HandlerUtil.getActiveShell(event),
            HandlerUtil.getActivePart(event),
            HandlerUtil.getActiveEditor(event));
        return null;
    }
}

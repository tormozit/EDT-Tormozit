package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Команда «Быстрая схема модуля» — делегирует штатному Quick Outline EDT
 * ({@code org.eclipse.xtext.ui.editor.outline.QuickOutline}).
 */
public class ModuleOutlineHandler extends AbstractHandler
{
    private static final String TAG = "ModuleOutline"; //$NON-NLS-1$

    /** Идентификатор команды плагина ({@code plugin.xml}). */
    public static final String COMMAND_ID = "tormozit.ModuleOutline"; //$NON-NLS-1$

    private static final String QUICK_OUTLINE_COMMAND_ID =
            "org.eclipse.xtext.ui.editor.outline.QuickOutline"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
            return null;

        IHandlerService handlerService = window.getService(IHandlerService.class);
        if (handlerService == null)
            return null;

        try
        {
            return handlerService.executeCommand(QUICK_OUTLINE_COMMAND_ID, null);
        }
        catch (ExecutionException e)
        {
            Global.logError(TAG, "execute QuickOutline", e); //$NON-NLS-1$
            throw e;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "execute QuickOutline", e); //$NON-NLS-1$
            throw new ExecutionException("Quick Outline недоступен", e); //$NON-NLS-1$
        }
    }
}

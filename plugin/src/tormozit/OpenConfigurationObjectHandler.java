package tormozit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Команда «Открыть объект конфигурации» — делегирует штатному диалогу EDT
 * ({@code com._1c.g5.v8.dt.md.ui.openMdObjectDialog}).
 */
public class OpenConfigurationObjectHandler extends AbstractHandler
{
    private static final String TAG = "OpenConfigurationObject"; //$NON-NLS-1$

    /** Идентификатор команды плагина ({@code plugin.xml}). */
    public static final String COMMAND_ID = "tormozit.OpenConfigurationObject"; //$NON-NLS-1$

    private static final String EDT_COMMAND_ID =
            "com._1c.g5.v8.dt.md.ui.openMdObjectDialog"; //$NON-NLS-1$

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
            return handlerService.executeCommand(EDT_COMMAND_ID, null);
        }
        catch (ExecutionException e)
        {
            Global.logError(TAG, "execute openMdObjectDialog", e); //$NON-NLS-1$
            throw e;
        }
        catch (Exception e)
        {
            Global.logError(TAG, "execute openMdObjectDialog", e); //$NON-NLS-1$
            throw new ExecutionException("Открыть объект конфигурации недоступно", e); //$NON-NLS-1$
        }
    }
}

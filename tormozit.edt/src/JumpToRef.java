import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Display;

public class JumpToRef extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        // 1. Безопасное получение активного окна (если нужно показать диалог/тост)
        // Получать окно через HandlerUtil надежнее, чем через Display.getDefault().getActiveShell()
        final Shell activeShell = HandlerUtil.getActiveShell(event);
        EclipseToastNotification.show("котик", "мяу");
        // Метод execute должен возвращать null согласно спецификации API
        return null; 
    }
}
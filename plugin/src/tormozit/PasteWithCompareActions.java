package tormozit;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.swt.widgets.Shell;

/**
 * Общая логика команды «Вставить со сравнением» для handler и контекстного меню.
 */
public final class PasteWithCompareActions
{
    private static final String TITLE = "Вставить со сравнением"; //$NON-NLS-1$

    private PasteWithCompareActions()
    {
    }

    public static void run(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        run(shell, TextEditorSupport.resolveContext(part, editorPart));
    }

    public static void run(Shell shell, TextEditorSupport.Context ctx)
    {
        if (ctx == null)
        {
            ToastNotification.show(TITLE,
                "Откройте текстовый редактор с фокусом в поле текста.", 4000);
            return;
        }

        if (!ctx.editable)
        {
            ToastNotification.show(TITLE,
                "Редактор доступен только для чтения.", 4000);
            return;
        }

        String clipboardText = TextEditorSupport.readClipboardText(shell);
        if (clipboardText == null || clipboardText.isEmpty())
        {
            ToastNotification.show(TITLE,
                "Буфер обмена пуст или не содержит текста.", 4000);
            return;
        }

        try
        {
            StringFragmentCompareInput input = new StringFragmentCompareInput(
                ctx.selectedText,
                clipboardText,
                ctx.compareViewerType);

            String newText = input.openDialog();
            if (newText == null)
                return;

            TextEditorSupport.replaceSelectionAndSelect(ctx, newText);
        }
        catch (Exception e)
        {
            Global.log("PasteWithCompareActions.run: " + e); //$NON-NLS-1$
            ToastNotification.show(TITLE,
                "Не удалось открыть сравнение: " + e.getMessage(), 5000);
        }
    }

    public static boolean isAvailable(Shell shell, IWorkbenchPart part, IEditorPart editorPart)
    {
        return isAvailable(shell, TextEditorSupport.resolveContext(part, editorPart));
    }

    public static boolean isAvailable(Shell shell, TextEditorSupport.Context ctx)
    {
        return ctx != null
            && ctx.editable
            && TextEditorSupport.clipboardHasText(shell);
    }
}

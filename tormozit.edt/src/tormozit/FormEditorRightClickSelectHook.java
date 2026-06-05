package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IStartup;

/**
 * Классическое поведение правого клика в области предпросмотра формы (WYSIWYG):
 * на {@link SWT#MouseDown} сначала посылает синтетический левый клик
 * (чтобы EDT выбрал элемент формы под курсором), а затем платформа штатно
 * открывает контекстное меню.
 *
 * <p>Область предпросмотра реализована классом
 * {@code com._1c.g5.v8.dt.form.internal.presentation.wysiwyg.WysiwygNativeComposite} (наследник
 * {@link Composite}), который используется как {@code wysiwygViewer} внутри
 * {@code com._1c.g5.v8.dt.form.ui.editor.FormEditorPage}.
 *
 * <p>Логика зеркалит {@link TextEditorRightClickCaretHook}: глобальный
 * display-фильтр без слежки за жизненным циклом редактора.
 */
public class FormEditorRightClickSelectHook implements IStartup
{
    /** Простое имя класса wysiwyg-области предпросмотра форм. */
    private static final String WYSIWYG_CLASS = "WysiwygNativeComposite"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseDown, FormEditorRightClickSelectHook::handleMouseDown);
    }

    private static void handleMouseDown(Event e)
    {
        if (e.button != 3)
            return;
        if (!(e.widget instanceof Composite))
            return;
        if (!WYSIWYG_CLASS.equals(e.widget.getClass().getSimpleName()))
            return;

        simulateLeftClick((Composite) e.widget, e.x, e.y);
    }

    /**
     * Посылает синтетическую пару MouseDown/MouseUp левой кнопки на виджет,
     * чтобы EDT выбрал элемент формы под курсором до открытия контекстного меню.
     */
    private static void simulateLeftClick(Composite widget, int x, int y)
    {
        if (widget.isDisposed())
            return;

        Event down = new Event();
        down.type   = SWT.MouseDown;
        down.button = 1;
        down.x      = x;
        down.y      = y;
        down.count  = 1;
        widget.notifyListeners(SWT.MouseDown, down);

        Event up = new Event();
        up.type   = SWT.MouseUp;
        up.button = 1;
        up.x      = x;
        up.y      = y;
        up.count  = 1;
        widget.notifyListeners(SWT.MouseUp, up);
    }
}

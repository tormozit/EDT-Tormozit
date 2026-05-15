package tormozit.edt.applications;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Всплывающее уведомление в стиле TurboConf.
 *
 * <h3>Потокобезопасность</h3>
 * {@link #show} использует {@code syncExec} если вызов идёт из не-UI потока —
 * это гарантирует что метод ВСЕГДА возвращает реальный {@link Shell}, который
 * можно сохранить и закрыть через {@link #close}.
 *
 * <h3>Жизненный цикл «подключение ИР»</h3>
 * <pre>
 *   Shell toast = show("Подключение", "...", 60_000);
 *   try {
 *       // ... COM connect (до 60 с) ...
 *   } finally {
 *       close(toast);   // закрывается при любом исходе
 *   }
 * </pre>
 */
public final class EclipseToastNotification
{
    private static final int WIDTH   = 380;
    private static final int PADDING = 12;

    private EclipseToastNotification() {}

    // -----------------------------------------------------------------------
    // Публичный API
    // -----------------------------------------------------------------------

    /**
     * Показывает уведомление.
     *
     * <p>Всегда возвращает {@link Shell} (не null при живом Display), даже если
     * вызван из фонового потока — для этого используется {@code syncExec}.
     *
     * @param title      заголовок (жирный)
     * @param message    текст
     * @param durationMs авто-скрытие в мс; 0 = не скрывать автоматически
     * @return Shell уведомления — сохраните для последующего {@link #close}
     */
    public static Shell show(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return null;

        Shell[] result = { null };

        if (display.getThread() == Thread.currentThread())
        {
            result[0] = createShell(display, title, message, durationMs);
        }
        else
        {
            // syncExec блокирует текущий поток до создания Shell —
            // гарантируем ненулевой результат из фонового потока
            display.syncExec(() -> result[0] = createShell(display, title, message, durationMs));
        }

        return result[0];
    }

    /**
     * Закрывает уведомление досрочно (при любом исходе операции).
     * Безопасен из любого потока и при null/disposed shell.
     */
    public static void close(Shell shell)
    {
        if (shell == null) return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return;

        if (display.getThread() == Thread.currentThread())
        {
            if (!shell.isDisposed()) shell.dispose();
        }
        else
        {
            display.asyncExec(() -> { if (!shell.isDisposed()) shell.dispose(); });
        }
    }

    // -----------------------------------------------------------------------
    // Создание окна
    // -----------------------------------------------------------------------

    private static Shell createShell(Display display, String title,
                                     String message, int durationMs)
    {
        Shell shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth  = PADDING;
        layout.marginHeight = PADDING;
        layout.verticalSpacing = 4;
        shell.setLayout(layout);
        shell.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));

        // Заголовок
        if (title != null && !title.isEmpty())
        {
            Label lbl = new Label(shell, SWT.WRAP);
            lbl.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            lbl.setText(title);
            lbl.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
            lbl.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
            FontData[] fd = lbl.getFont().getFontData();
            fd[0].setStyle(SWT.BOLD);
            Font bold = new Font(display, fd);
            lbl.setFont(bold);
            lbl.addDisposeListener(e -> bold.dispose());
        }

        // Текст
        if (message != null && !message.isEmpty())
        {
            Label lbl = new Label(shell, SWT.WRAP);
            GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
            gd.widthHint = WIDTH - 2 * PADDING;
            lbl.setLayoutData(gd);
            lbl.setText(message);
            lbl.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
            lbl.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
        }

        shell.pack();
        Point size = shell.getSize();
        if (size.x < WIDTH) shell.setSize(WIDTH, size.y);
        size = shell.getSize();

        // Правый нижний угол экрана
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        shell.setLocation(screen.x + screen.width - size.x - 16,
                          screen.y + screen.height - size.y - 48);

        shell.setVisible(true);

        // Клик закрывает
        shell.addListener(SWT.MouseDown, e -> { if (!shell.isDisposed()) shell.dispose(); });

        // Авто-скрытие
        if (durationMs > 0)
            display.timerExec(durationMs, () -> { if (!shell.isDisposed()) shell.dispose(); });

        return shell;
    }
}

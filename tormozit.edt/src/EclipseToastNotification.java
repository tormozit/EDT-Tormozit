
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;

public final class EclipseToastNotification
{
    // =======================================================================
    // НАСТРОЙКИ АНИМАЦИИ И ВНЕШНЕГО ВИДА
    // =======================================================================
    private static final int SLIDE_IN_DURATION_MS = 500;
    private static final int FADE_OUT_DURATION_MS = 2000;
    private static final int ANIMATION_STEP_MS    = 40;

    private static final int WIDTH          = 380;
    private static final int PADDING        = 12;
    private static final int GAP_BETWEEN    = 8;   // зазор между тостами
    private static final int EDGE_GAP       = 16;  // отступ от правого края и от таскбара
    private static final int MIN_TOP_MARGIN = 40;  // запас от верха экрана до начала нового слоя

    // =======================================================================
    // РЕЕСТР АКТИВНЫХ ТОСТОВ (читается/пишется только из display-потока)
    // =======================================================================
    private static final List<ToastEntry> activeToasts = new CopyOnWriteArrayList<>();

    private static final class ToastEntry
    {
        final Shell shell;
        final int   y;      // целевая Y верхней границы (конечная позиция после анимации)
        final int   height;

        ToastEntry(Shell s, int y, int h) { shell = s; this.y = y; this.height = h; }
    }

    private EclipseToastNotification() {}

    // =======================================================================
    // ПУБЛИЧНЫЙ API
    // =======================================================================

    public static Shell show(String title, String message)
    {
        return show(title, message, 4000, null, null);
    }

    public static Shell show(String title, String message, int durationMs)
    {
        return show(title, message, durationMs, null, null);
    }

    /**
     * Показывает всплывающее уведомление.
     * <p>Тосты стекуются снизу вверх (от верхней границы таскбара).
     * Когда места не хватает (новый тост не вписывается выше MIN_TOP_MARGIN),
     * начинается новый слой — тосты снова идут от таскбара вверх,
     * визуально перекрывая тосты предыдущего слоя.
     *
     * @param title       заголовок (null = не отображать)
     * @param message     основной текст
     * @param durationMs  время показа в мс до начала затухания
     * @param actionLabel текст гиперссылки «Выполнить» (null = не отображать)
     * @param action      действие при клике на гиперссылку (null = не отображать);
     *                    произвольные параметры передаются через замыкание лямбды
     */
    public static Shell show(String title, String message, int durationMs,
                              String actionLabel, Runnable action)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) return null;
        Shell[] holder = new Shell[1];

        display.syncExec(() ->
        {
            Shell shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
            holder[0] = shell;

            GridLayout layout      = new GridLayout(1, false);
            layout.marginWidth     = PADDING;
            layout.marginHeight    = PADDING;
            layout.verticalSpacing = 4;
            shell.setLayout(layout);
            shell.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));

            // --- Заголовок ---
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

            // --- Сообщение ---
            if (message != null && !message.isEmpty())
            {
                Label lbl = new Label(shell, SWT.WRAP);
                GridData gd   = new GridData(SWT.FILL, SWT.TOP, true, false);
                gd.widthHint  = WIDTH - 2 * PADDING;
                lbl.setLayoutData(gd);
                lbl.setText(message);
                lbl.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
                lbl.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
            }

            // --- Гиперссылка «Выполнить» (опционально) ---
            final Link actionLink;
            if (actionLabel != null && !actionLabel.isEmpty() && action != null)
            {
                actionLink = new Link(shell, SWT.NONE);
                actionLink.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, true, false));
                actionLink.setText("<a>" + actionLabel + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
                actionLink.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
                actionLink.addListener(SWT.Selection, e ->
                {
                    if (!shell.isDisposed()) shell.dispose();
                    display.asyncExec(action::run);
                });
            }
            else
            {
                actionLink = null;
            }

            shell.pack();
            Point size = shell.getSize();
            if (size.x < WIDTH) shell.setSize(WIDTH, size.y);
            final Point finalSize = shell.getSize();

            // --- Вычисляем позицию с учётом уже активных тостов ---
            Rectangle ca = display.getPrimaryMonitor().getClientArea();
            int targetX  = ca.x + ca.width  - finalSize.x - EDGE_GAP;
            int targetY  = findSlotTopY(ca, finalSize.y);

            // Регистрируем ДО показа, чтобы следующие тосты учитывали нашу позицию
            ToastEntry entry = new ToastEntry(shell, targetY, finalSize.y);
            activeToasts.add(entry);
            shell.addDisposeListener(e -> activeToasts.remove(entry));

            int startY = targetY + 30;
            shell.setLocation(targetX, startY);
            shell.setAlpha(0);
            shell.setVisible(true);

            // --- 1. Анимация появления ---
            int slideSteps = Math.max(1, SLIDE_IN_DURATION_MS / ANIMATION_STEP_MS);
            for (int i = 0; i <= slideSteps; i++)
            {
                final int step = i;
                display.timerExec(step * ANIMATION_STEP_MS, () ->
                {
                    if (!shell.isDisposed())
                    {
                        shell.setLocation(targetX,
                            startY - (startY - targetY) * step / slideSteps);
                        shell.setAlpha(255 * step / slideSteps);
                    }
                });
            }

            // --- 2. Ховер ---
            final boolean[] isHovered    = { false };
            final boolean[] isFading     = { false };
            final int[]     currentAlpha = { 255 };
            final int[]     remainingMs  = { durationMs };

            Listener hoverListener = e ->
            {
                if      (e.type == SWT.MouseEnter) { isHovered[0] = true; }
                else if (e.type == SWT.MouseExit
                         && !shell.getBounds().contains(display.getCursorLocation()))
                         { isHovered[0] = false; }
            };
            addListenerToAll(shell, actionLink, SWT.MouseEnter, hoverListener);
            addListenerToAll(shell, actionLink, SWT.MouseExit,  hoverListener);

            // --- 3. Клик по фону → копируемый диалог ---
            Listener clickListener = e ->
            {
                if (!shell.isDisposed())
                {
                    shell.dispose();
                    display.asyncExec(() -> openCopyableDialog(title, message));
                }
            };
            shell.addListener(SWT.MouseDown, clickListener);
            for (Control child : shell.getChildren())
                if (!(child instanceof Link))
                    child.addListener(SWT.MouseDown, clickListener);

            // --- 4. Таймер удержания + затухание ---
            Runnable[] loop = new Runnable[1];
            loop[0] = () ->
            {
                if (shell.isDisposed()) return;

                if (isHovered[0])
                {
                    if (isFading[0] || currentAlpha[0] < 255)
                    {
                        shell.setAlpha(255);
                        currentAlpha[0] = 255;
                        isFading[0]     = false;
                    }
                    remainingMs[0] = durationMs;
                    display.timerExec(100, loop[0]);
                    return;
                }

                if (remainingMs[0] > 0)
                {
                    remainingMs[0] -= 100;
                    display.timerExec(100, loop[0]);
                }
                else
                {
                    isFading[0]     = true;
                    int decrement   = Math.max(1, 255 * ANIMATION_STEP_MS / FADE_OUT_DURATION_MS);
                    currentAlpha[0] = Math.max(0, currentAlpha[0] - decrement);
                    shell.setAlpha(currentAlpha[0]);
                    if (currentAlpha[0] > 0)
                        display.timerExec(ANIMATION_STEP_MS, loop[0]);
                    else
                        shell.dispose();
                }
            };
            display.timerExec(SLIDE_IN_DURATION_MS + 50, loop[0]);
        });

        return holder[0];
    }

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

    // =======================================================================
    // АЛГОРИТМ РАЗМЕЩЕНИЯ: стек снизу вверх, при переполнении — новый слой
    // =======================================================================

    /**
     * Возвращает Y-координату верхней границы нового тоста (в координатах экрана).
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Опорная точка — верхняя граница таскбара
     *       ({@code clientArea.y + clientArea.height - EDGE_GAP}).</li>
     *   <li>Для каждого активного тоста (от нижнего к верхнему): если кандидат
     *       перекрывает его — поднимаем кандидата выше этого тоста.</li>
     *   <li>Если после обхода всех тостов кандидат выходит за {@code MIN_TOP_MARGIN}
     *       от верха экрана — начинаем новый слой: возвращаемся к опорной точке
     *       (тост ляжет поверх тостов предыдущего слоя).</li>
     * </ol>
     */
    private static int findSlotTopY(Rectangle clientArea, int toastHeight)
    {
        int clientBottom = clientArea.y + clientArea.height - EDGE_GAP;
        int minTopY      = clientArea.y + MIN_TOP_MARGIN;

        // Активные тосты по убыванию нижней границы (самый нижний — первый)
        List<int[]> slots = activeToasts.stream()
            .filter(e -> !e.shell.isDisposed())
            .map(e -> new int[]{ e.y, e.y + e.height }) // [topY, bottomY]
            .sorted(Comparator.<int[], Integer>comparing(s -> s[1]).reversed())
            .collect(Collectors.toList());

        int candidateBottom = clientBottom;

        for (int[] slot : slots)
        {
            // Если кандидат [candidateBottom-toastHeight .. candidateBottom] задевает слот
            if (candidateBottom - toastHeight < slot[1] + GAP_BETWEEN)
                candidateBottom = slot[0] - GAP_BETWEEN; // поднимаем выше этого тоста
        }

        int candidateTop = candidateBottom - toastHeight;

        // Нет места → новый слой: снова от таскбара (перекрываем тосты прошлого слоя)
        if (candidateTop < minTopY)
            candidateTop = clientBottom - toastHeight;

        return candidateTop;
    }

    // =======================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =======================================================================

    /**
     * Добавляет listener ко всем дочерним виджетам.
     * Для Link ховер нужен тоже, поэтому исключений нет.
     * MouseDown на Link не добавляем — вызывающий код делает это явно.
     */
    private static void addListenerToAll(Shell shell, Link skipLink,
                                          int eventType, Listener listener)
    {
        shell.addListener(eventType, listener);
        for (Control child : shell.getChildren())
            child.addListener(eventType, listener);
    }

    private static void openCopyableDialog(String title, String message)
    {
        Display display = Display.getDefault();
        Shell dialog = new Shell(display, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText(title != null && !title.isEmpty() ? title : "Текст уведомления"); //$NON-NLS-1$
        dialog.setLayout(new GridLayout(1, false));

        Text textWidget = new Text(dialog,
            SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        textWidget.setText(message != null ? message : ""); //$NON-NLS-1$
        GridData gd   = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint  = 480;
        gd.heightHint = 220;
        textWidget.setLayoutData(gd);

        Button btnClose = new Button(dialog, SWT.PUSH);
        btnClose.setText("Закрыть"); //$NON-NLS-1$
        btnClose.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        btnClose.addListener(SWT.Selection, e -> dialog.dispose());

        dialog.pack();
        Rectangle screen = display.getPrimaryMonitor().getBounds();
        Rectangle db     = dialog.getBounds();
        dialog.setLocation(screen.x + (screen.width  - db.width)  / 2,
                           screen.y + (screen.height - db.height) / 2);
        dialog.open();
    }
}

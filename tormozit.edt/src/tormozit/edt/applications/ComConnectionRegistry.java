package tormozit.edt.applications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Реестр COM-подключений к серверу автоматизации 1С (V8x.Application).
 *
 * <p>Порт функций {@code ПодключениеИР()} и {@code ЗакрытьПриложениеИР()} из RDT.os.
 *
 * <h3>Тост-уведомление</h3>
 * Тост создаётся внутри {@link #doConnect} (а не в {@link #connect}) через {@link EclipseToastNotification#show},
 * который использует {@code syncExec} — гарантированно возвращает {@link Shell}.
 * В блоке {@code finally} всегда вызывается {@link EclipseToastNotification#close} —
 * тост закрывается при любом исходе (успех, ошибка, исключение).
 */
public final class ComConnectionRegistry
{
    // ---- Синглтон ----
    private static final ComConnectionRegistry INSTANCE = new ComConnectionRegistry();
    public static ComConnectionRegistry getInstance() { return INSTANCE; }
    private ComConnectionRegistry() {}

    // -----------------------------------------------------------------------
    // Состояние сессии
    // -----------------------------------------------------------------------

    public enum State { IDLE, CONNECTING, CONNECTED, ERROR }

    public static final class ComSession
    {
        public final State         state;
        public final LocalDateTime startTime;
        public final long          pid;
        final Object               dispatch;
        final String               appTitle;

        ComSession(State state, LocalDateTime startTime, long pid,
                   Object dispatch, String appTitle)
        {
            this.state     = state;
            this.startTime = startTime;
            this.pid       = pid;
            this.dispatch  = dispatch;
            this.appTitle  = appTitle;
        }

        static ComSession connecting()
        {
            return new ComSession(State.CONNECTING, LocalDateTime.now(), 0, null, ""); //$NON-NLS-1$
        }
    }

    private final Map<Object, ComSession> sessions        = new ConcurrentHashMap<>();
    private final List<Runnable>          changeListeners = new CopyOnWriteArrayList<>();

    // -----------------------------------------------------------------------
    // Публичный API
    // -----------------------------------------------------------------------

    public boolean isConnected(Object key)
    {
        ComSession s = sessions.get(key);
        return s != null && s.state == State.CONNECTED;
    }

    public boolean isConnecting(Object key)
    {
        ComSession s = sessions.get(key);
        return s != null && s.state == State.CONNECTING;
    }

    public State getState(Object key)
    {
        ComSession s = sessions.get(key);
        return s != null ? s.state : State.IDLE;
    }

    public LocalDateTime getSessionStart(Object key)
    {
        ComSession s = sessions.get(key);
        return (s != null && s.state == State.CONNECTED) ? s.startTime : null;
    }

    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }

    // -----------------------------------------------------------------------
    // Подключение
    // -----------------------------------------------------------------------

    /**
     * Запускает асинхронное подключение.
     * Возвращает управление немедленно; колонка сразу покажет «подключение».
     */
    public void connect(Object key, String connectionString,
                        String platformVersion, String appLabel)
    {
        if (sessions.containsKey(key))
        {
            State st = sessions.get(key).state;
            if (st == State.CONNECTING || st == State.CONNECTED) return;
        }

        sessions.put(key, ComSession.connecting());
        notifyListeners();

        // Асинхронно — тост создаётся внутри doConnect с гарантированным close()
        CompletableFuture.runAsync(
            () -> doConnect(key, connectionString, platformVersion, appLabel));
    }

    /**
     * Весь процесс подключения выполняется здесь, в фоновом потоке.
     *
     * <p>Тост показывается в самом начале через {@code syncExec} — возвращает Shell.
     * {@code finally} гарантирует закрытие тоста при любом исходе.
     */
    private void doConnect(Object key, String connectionString,
                           String platformVersion, String appLabel)
    {
        // Тост «Подключается» — создаём через syncExec, всегда получаем Shell
        Shell connectingToast = EclipseToastNotification.show(
            "Подключение",
            "Подключается приложение ИР. Закрыть его можно командой «Отключить приложение ИР».",
            60_000);

        try
        {
            doConnectInternal(key, connectionString, platformVersion, appLabel);
        }
        finally
        {
            // Закрываем тост при любом исходе — успех, ошибка, исключение
            EclipseToastNotification.close(connectingToast);
        }
    }

    private void doConnectInternal(Object key, String connectionString,
                                   String platformVersion, String appLabel)
    {
        String className                = ComConnectionRegistry.buildComClassName(platformVersion);
        String connectionStringNoPass   = removePassword(connectionString);
        log("COM-класс: " + className + ", БД: " + connectionStringNoPass); //$NON-NLS-1$

        Object comDispatch      = null;
        boolean success         = false;
        String descriptionOnError = ""; //$NON-NLS-1$
        long momentStart        = System.currentTimeMillis();
        long pid                = 0;

        // До 2 попыток — аналог цикла «Для НомерПопытки = 1 По 2»
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                long startMs = System.currentTimeMillis();
                comDispatch = ComJacobBridge.createComObject(className);

                // Ищем PID нового процесса 1cv8.exe -Embedding
                pid = WmiProcessHelper.findNewEmbeddingPid(startMs);

                boolean connected = ComJacobBridge.connect(comDispatch, connectionString);
                if (connected)
                {
                    success = true;
                    break;
                }
                descriptionOnError = "Connect() вернул false"; //$NON-NLS-1$
                comDispatch = null;
            }
            catch (Exception e)
            {
                descriptionOnError = e.getMessage() != null ? e.getMessage() : e.toString();
                comDispatch = null;

                String errLow = descriptionOnError.toLowerCase();
                if (errLow.contains("пароль") || errLow.contains("password") //$NON-NLS-1$ //$NON-NLS-2$
                        || errLow.contains("не идентифицирован")) //$NON-NLS-1$
                {
                    showError("Неверное имя или пароль.\n" + descriptionOnError); //$NON-NLS-1$
                    sessions.remove(key);
                    notifyListeners();
                    return;
                }
                if (errLow.contains("0x800706be")) //$NON-NLS-1$
                {
                    showError("Ошибка инициации приложения. Подробности в Error Log."); //$NON-NLS-1$
                    sessions.remove(key);
                    notifyListeners();
                    return;
                }

                if (attempt == 2)
                {
                    String msg = "Ошибка подключения ИР " + connectionStringNoPass //$NON-NLS-1$
                        + " через " + className + ":\n" + descriptionOnError; //$NON-NLS-1$ //$NON-NLS-2$
                    showError(msg);
                    log(msg);
                    if (!descriptionOnError.contains("Ошибка разделенного доступа")) //$NON-NLS-1$
                        EclipseToastNotification.show("Ошибка COM", //$NON-NLS-1$
                            "Если ошибка связана с COM, зарегистрируйте класс " //$NON-NLS-1$
                            + className + " (/RegServer -CurrentUser).", 8_000); //$NON-NLS-1$
                    sessions.remove(key);
                    notifyListeners();
                    return;
                }
                log("Попытка " + attempt + " неудача: " + descriptionOnError + ". Повтор..."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        if (!success || comDispatch == null)
        {
            sessions.remove(key);
            notifyListeners();
            return;
        }

        long duration = (System.currentTimeMillis() - momentStart) / 1000;
        String title = "ИР - " + connectionStringNoPass.split(";")[0]; //$NON-NLS-1$ //$NON-NLS-2$

        // Устанавливаем заголовок окна
        try
        {
            try
            {
                Object clientApp = ComJacobBridge.getProperty(comDispatch, "КлиентскоеПриложение"); //$NON-NLS-1$
                ComJacobBridge.invoke(clientApp, "УстановитьЗаголовок", title); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                ComJacobBridge.invoke(comDispatch, "УстановитьЗаголовокСистемы", title); //$NON-NLS-1$
            }
        }
        catch (Exception ignored) {}

        // Visible = false — работаем в фоне
        try { ComJacobBridge.setProperty(comDispatch, "Visible", false); } //$NON-NLS-1$
        catch (Exception ignored) {}

        final long   finalPid      = pid;
        final Object finalDispatch = comDispatch;
        sessions.put(key, new ComSession(
            State.CONNECTED, LocalDateTime.now(), finalPid, finalDispatch, title));
        notifyListeners();

        EclipseToastNotification.show(
            "ИР подключено", //$NON-NLS-1$
            title + " подключено за " + duration + " сек", //$NON-NLS-1$ //$NON-NLS-2$
            3_000);
        log("Подключено: " + title + " за " + duration + " с, PID=" + finalPid); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // -----------------------------------------------------------------------
    // Отключение
    // -----------------------------------------------------------------------

    public void disconnect(Object key)
    {
        ComSession session = sessions.get(key);
        if (session == null || session.state == State.IDLE) return;
        CompletableFuture.runAsync(() -> doDisconnect(key, session));
    }

    private void doDisconnect(Object key, ComSession session)
    {
        boolean killProcess = false;

        if (session.dispatch != null)
        {
            try
            {
                ComJacobBridge.invoke(session.dispatch, "ЗавершитьРаботуСистемы", false); //$NON-NLS-1$
                log("ЗавершитьРаботуСистемы(false) — OK"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                log("ЗавершитьРаботуСистемы ошибка → убиваем процесс: " + e.getMessage()); //$NON-NLS-1$
                killProcess = true;
            }
        }
        else
        {
            killProcess = true;
        }

        if (killProcess && session.pid > 0)
        {
            WmiProcessHelper.killByPid(session.pid);
            EclipseToastNotification.show("ИР отключено", //$NON-NLS-1$
                "Процесс приложения ИР завершён принудительно.", 2_000); //$NON-NLS-1$
        }
        else
        {
            EclipseToastNotification.show("ИР отключено", //$NON-NLS-1$
                "Приложение ИР завершено. Отключение от базы займёт несколько секунд.", 3_000); //$NON-NLS-1$
        }

        sessions.remove(key);
        notifyListeners();
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    /** "8.5.1.1343" → "V85.Application" */
    public static String buildComClassName(String platformVersion)
    {
        if (platformVersion == null || platformVersion.isEmpty()) return "V83.Application"; //$NON-NLS-1$
        String[] parts = platformVersion.split("\\."); //$NON-NLS-1$
        return parts.length >= 2 ? "V8" + parts[1] + ".Application" : "V83.Application"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Убирает Pwd="..." из строки соединения (для лога). */
    public static String removePassword(String cs)
    {
        if (cs == null) return ""; //$NON-NLS-1$
        int idx = cs.toLowerCase().indexOf("pwd="); //$NON-NLS-1$
        return idx > 0 ? cs.substring(0, idx) : cs;
    }

    private void showError(String msg)
    {
        EclipseToastNotification.show("Ошибка подключения ИР", msg, 6_000); //$NON-NLS-1$
    }

    private void notifyListeners()
    {
        changeListeners.forEach(r -> { try { r.run(); } catch (Exception ignored) {} });
    }

    static void log(String msg)
    {
        try
        {
            Bundle b = FrameworkUtil.getBundle(ComConnectionRegistry.class);
            Platform.getLog(b).log(new Status(IStatus.INFO, b.getSymbolicName(),
                "[TormozitIR] " + msg)); //$NON-NLS-1$
        }
        catch (Exception e) { System.err.println("[TormozitIR] " + msg); } //$NON-NLS-1$
    }
}

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
 * <h3>Жизненный цикл тоста</h3>
 * Тост создаётся через {@link EclipseToastNotification#show} с {@code syncExec} внутри
 * {@link #doConnect} — гарантированно возвращает {@link Shell}.
 * Блок {@code finally} закрывает его при любом исходе.
 *
 * <h3>WMI</h3>
 * Подключение к WMI создаётся один раз в {@link #doConnectInternal} и используется
 * для поиска PID нового процесса ({@link WmiProcessHelper#findProcess}) и для
 * завершения процесса при отключении ({@link WmiProcessHelper#terminate}).
 */
public final class ComConnectionRegistry
{
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
        final Object               dispatch;    // Jacob Dispatch (1C COM automation)
        final Object               processObj;  // Win32_Process COM-объект (для Terminate)
        final String               appTitle;

        ComSession(State state, LocalDateTime startTime, long pid,
                   Object dispatch, Object processObj, String appTitle)
        {
            this.state      = state;
            this.startTime  = startTime;
            this.pid        = pid;
            this.dispatch   = dispatch;
            this.processObj = processObj;
            this.appTitle   = appTitle;
        }

        static ComSession connecting()
        {
            return new ComSession(State.CONNECTING, LocalDateTime.now(), 0, null, null, ""); //$NON-NLS-1$
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
        CompletableFuture.runAsync(
            () -> doConnect(key, connectionString, platformVersion, appLabel));
    }

    /**
     * Фоновый метод подключения.
     * Тост создаётся здесь (syncExec → всегда Shell), закрывается в finally.
     */
    private void doConnect(Object key, String connectionString,
                           String platformVersion, String appLabel)
    {
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
        String className              = buildComClassName(platformVersion);
        String connectionStringNoPass = removePassword(connectionString);
        log("COM-класс: " + className + ", БД: " + connectionStringNoPass); //$NON-NLS-1$

        // WMI создаём заранее — нужен для поиска PID нового процесса
        Object wmi = WmiProcessHelper.connectWmi();

        Object  comDispatch        = null;
        Object  processObj         = null;
        long    pid                = 0;
        boolean success            = false;
        String  descriptionOnError = ""; //$NON-NLS-1$
        long    momentStart        = System.currentTimeMillis();

        // До 2 попыток — аналог «Для НомерПопытки = 1 По 2» в ПодключениеИР()
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                long startMs = System.currentTimeMillis();
                comDispatch = ComJacobBridge.createComObject(className);

                // МоментСтартаПроцесса → ПолучитьПроцессОСЛкс(Неопределено, startMs, 2, "-Embedding")
                processObj = WmiProcessHelper.findProcess(wmi, startMs, 2);
                pid        = WmiProcessHelper.getPid(processObj);

                boolean connected = ComJacobBridge.connect(comDispatch, connectionString);
                if (connected) { success = true; break; }

                descriptionOnError = "Connect() вернул false"; //$NON-NLS-1$
                comDispatch = null; processObj = null; pid = 0;
            }
            catch (Exception e)
            {
                descriptionOnError = e.getMessage() != null ? e.getMessage() : e.toString();
                comDispatch = null; processObj = null; pid = 0;

                String errLow = descriptionOnError.toLowerCase();
                if (errLow.contains("пароль") || errLow.contains("password") //$NON-NLS-1$ //$NON-NLS-2$
                        || errLow.contains("не идентифицирован")) //$NON-NLS-1$
                {
                    showError("Неверное имя или пароль.\n" + descriptionOnError); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (errLow.contains("0x800706be")) //$NON-NLS-1$
                {
                    showError("Ошибка инициации приложения. Подробности в Error Log."); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (attempt == 2)
                {
                    String msg = "Ошибка подключения ИР " + connectionStringNoPass //$NON-NLS-1$
                        + " через " + className + ":\n" + descriptionOnError; //$NON-NLS-1$ //$NON-NLS-2$
                    showError(msg); log(msg);
                    if (!descriptionOnError.contains("Ошибка разделенного доступа")) //$NON-NLS-1$
                        EclipseToastNotification.show("Ошибка COM", //$NON-NLS-1$
                            "Зарегистрируйте " + className + " (/RegServer -CurrentUser).", 8_000); //$NON-NLS-1$ //$NON-NLS-2$
                    sessions.remove(key); notifyListeners(); return;
                }
                log("Попытка " + attempt + " неудача. Повтор..."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        if (!success || comDispatch == null) { sessions.remove(key); notifyListeners(); return; }

        long   duration = (System.currentTimeMillis() - momentStart) / 1000;
        String title    = "ИР - " + connectionStringNoPass.split(";")[0]; //$NON-NLS-1$ //$NON-NLS-2$

        // УстановитьЗаголовок (8.3.10+) / УстановитьЗаголовокСистемы (8.3.9-)
        try
        {
            try
            {
                ComJacobBridge.invoke(
                    ComJacobBridge.getProperty(comDispatch, "КлиентскоеПриложение"), //$NON-NLS-1$
                    "УстановитьЗаголовок", title); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                ComJacobBridge.invoke(comDispatch, "УстановитьЗаголовокСистемы", title); //$NON-NLS-1$
            }
        }
        catch (Exception ignored) {}

        try { ComJacobBridge.setProperty(comDispatch, "Visible", false); } //$NON-NLS-1$
        catch (Exception ignored) {}

        final long   finalPid        = pid;
        final Object finalDispatch   = comDispatch;
        final Object finalProcessObj = processObj;
        sessions.put(key, new ComSession(
            State.CONNECTED, LocalDateTime.now(), finalPid, finalDispatch, finalProcessObj, title));
        notifyListeners();

        EclipseToastNotification.show("ИР подключено", //$NON-NLS-1$
            title + " подключено за " + duration + " сек", 3_000); //$NON-NLS-1$ //$NON-NLS-2$
        log("Подключено: " + title + " за " + duration + " с, PID=" + finalPid); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    // -----------------------------------------------------------------------
    // Отключение (аналог ЗакрытьПриложениеИР)
    // -----------------------------------------------------------------------

    public void disconnect(Object key)
    {
        ComSession session = sessions.get(key);
        if (session == null || session.state == State.IDLE) return;
        CompletableFuture.runAsync(() -> doDisconnect(key, session));
    }

    private void doDisconnect(Object key, ComSession session)
    {
        boolean killed = false;

        if (session.dispatch != null)
        {
            try
            {
                // ЗавершитьРаботуСистемы(false) — штатное завершение без вопроса о сохранении
                ComJacobBridge.invoke(session.dispatch, "ЗавершитьРаботуСистемы", false); //$NON-NLS-1$
                log("ЗавершитьРаботуСистемы(false) — OK"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                log("ЗавершитьРаботуСистемы ошибка → УбитьПроцесс: " + e.getMessage()); //$NON-NLS-1$
                // УбитьПроцесс(ПроцессОС) — через Win32_Process.Terminate()
                Object wmi  = WmiProcessHelper.connectWmi();
                Object proc = session.processObj != null
                    ? session.processObj
                    : WmiProcessHelper.findProcessByPid(wmi, session.pid);
                WmiProcessHelper.terminate(proc, session.pid);
                killed = true;
            }
        }
        else if (session.pid > 0)
        {
            Object wmi  = WmiProcessHelper.connectWmi();
            Object proc = WmiProcessHelper.findProcessByPid(wmi, session.pid);
            WmiProcessHelper.terminate(proc, session.pid);
            killed = true;
        }

        EclipseToastNotification.show("ИР отключено", //$NON-NLS-1$
            killed
                ? "Процесс приложения ИР завершён принудительно." //$NON-NLS-1$
                : "Приложение ИР завершено. Отключение займёт несколько секунд.", //$NON-NLS-1$
            3_000);

        sessions.remove(key);
        notifyListeners();
        log("Сессия ИР удалена, key=" + key); //$NON-NLS-1$
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

    /** Удаляет Pwd="..." из строки соединения (для лога). */
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

    static void log(String message)
    {
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[IR-Bridge " + timestamp + "] " + message);
    }
}

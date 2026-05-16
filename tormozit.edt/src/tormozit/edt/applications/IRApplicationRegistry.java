package tormozit.edt.applications;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import tormozit.edt.handlers.OpenObjectHandler;
import tormozit.edt.menu.CompareEditorMenuHook;

/**
 * Реестр подключений к приложению ИР (Инструменты Разработчика 1С).
 *
 * <p>Объединяет бывший {@code ComConnectionRegistry} и прежний {@code IRApplicationRegistry}:
 * управляет жизненным циклом COM-сессий и адаптирует их к элементам панели
 * «Приложения» EDT ({@code InfobaseApplication}).
 *
 * <h3>Порт функций из RDT.os</h3>
 * <ul>
 *   <li>{@link #connect} ← {@code ПодключениеИР()}</li>
 *   <li>{@link #disconnect} ← {@code ЗакрытьПриложениеИР()}</li>
 * </ul>
 *
 * <h3>Тост-уведомление</h3>
 * {@link EclipseToastNotification#show} вызывается через {@code syncExec} →
 * всегда возвращает {@link Shell}. {@code finally} закрывает его при любом исходе.
 *
 * <h3>WMI</h3>
 * {@link WmiProcessHelper#connectWmi()} вызывается один раз до создания COM-объекта.
 * PID хранится в сессии и используется для принудительного завершения.
 */
public final class IRApplicationRegistry
{
    // -----------------------------------------------------------------------
    // Синглтон
    // -----------------------------------------------------------------------

    private static final IRApplicationRegistry INSTANCE = new IRApplicationRegistry();

    public static IRApplicationRegistry getInstance() { return INSTANCE; }

    private IRApplicationRegistry() {}

    // -----------------------------------------------------------------------
    // Состояние сессии
    // -----------------------------------------------------------------------

    public enum State { IDLE, CONNECTING, CONNECTED }

    public static final class IrSession
    {
        public final State         state;
        public final LocalDateTime startTime;      // момент успешного Connect()
        public final long          pid;            // PID процесса 1cv8.exe (0 если неизвестен)
        public final String        platformVersion; // версия платформы, например "8.5.1.1343"
        final Object               dispatch;       // Jacob ActiveXComponent (1С COM)
        final Object               processObj;     // Win32_Process COM-объект (для Terminate)
        final String               appTitle;       // заголовок «ИР - Srvr=...»

        IrSession(State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object dispatch, Object processObj, String appTitle)
        {
            this.state           = state;
            this.startTime       = startTime;
            this.pid             = pid;
            this.platformVersion = platformVersion;
            this.dispatch        = dispatch;
            this.processObj      = processObj;
            this.appTitle        = appTitle;
        }

        static IrSession connecting()
        {
            return new IrSession(State.CONNECTING, LocalDateTime.now(), 0, "", null, null, ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // UUID инфобазы → сессия
    private final Map<String, IrSession> sessions        = new ConcurrentHashMap<>();
    private final List<Runnable>         changeListeners = new CopyOnWriteArrayList<>();

    // авто-подключение при открытии базы в EDT
    private final Map<String, Boolean> autoConnectMap = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Публичный API — опрос состояния
    // -----------------------------------------------------------------------

    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }

    public boolean isConnected(Object element)
    {
        IrSession s = sessions.get(sessionKey(element));
        return s != null && s.state == State.CONNECTED;
    }

    public boolean isConnecting(Object element)
    {
        IrSession s = sessions.get(sessionKey(element));
        return s != null && s.state == State.CONNECTING;
    }

    /**
     * Возвращает момент успешного подключения, или {@code null} если нет сессии.
     */
    public LocalDateTime getSessionStart(Object element)
    {
        IrSession s = sessions.get(sessionKey(element));
        return (s != null && s.state == State.CONNECTED) ? s.startTime : null;
    }

    /**
     * Возвращает версию платформы, под которой запущено приложение ИР,
     * или {@code null} если нет активной сессии.
     *
     * <p>Пример: {@code "8.5.1.1343"}.
     */
    public String getSessionPlatformVersion(Object element)
    {
        IrSession s = sessions.get(sessionKey(element));
        return (s != null && s.state == State.CONNECTED) ? s.platformVersion : null;
    }

    public boolean isAutoConnect(Object element)
    {
        return autoConnectMap.getOrDefault(sessionKey(element), false);
    }

    public void setAutoConnect(Object element, boolean auto)
    {
        autoConnectMap.put(sessionKey(element), auto);
    }

    // -----------------------------------------------------------------------
    // Подключение (аналог ПодключениеИР)
    // -----------------------------------------------------------------------

    /**
     * Запускает асинхронное подключение к приложению ИР.
     * Немедленно устанавливает состояние CONNECTING — колонка показывает «подключение».
     */
    public void connect(Object element)
    {
        String key = sessionKey(element);

        IrSession existing = sessions.get(key);
        if (existing != null && (existing.state == State.CONNECTING
                || existing.state == State.CONNECTED)) return;

        sessions.put(key, IrSession.connecting());
        notifyListeners();

        String connectionString = buildConnectionString(element);
        String platformVersion  = extractEDTPlatformVersion(element);
        String appLabel         = DesignerSessionPoolAccessor.nameOf(element);

        log("connect() key=" + key + " cs=" + removePassword(connectionString) //$NON-NLS-1$ //$NON-NLS-2$
            + " platform=" + platformVersion); //$NON-NLS-1$

        CompletableFuture.runAsync(() -> doConnect(key, connectionString, platformVersion, appLabel));
    }

    /**
     * Фоновый метод — тост создаётся здесь (syncExec → Shell гарантирован),
     * закрывается в {@code finally} при любом исходе.
     */
    private void doConnect(String key, String connectionString,
                           String platformVersion, String appLabel)
    {
        Shell connectingToast = EclipseToastNotification.show(
            "Подключение",
            "Подключается приложение ИР «" + appLabel
                + "». Закрыть командой «Отключить приложение ИР».",
            60_000);
        try
        {
            doConnectInternal(key, connectionString, platformVersion, appLabel);
        }
        catch (Exception e)
        {
            EclipseToastNotification.show("Ошибка подключения ИР", e.getMessage(), 10_000);
            sessions.remove(key);
            notifyListeners();
        }
        finally
        {
            EclipseToastNotification.close(connectingToast);
        }
    }

    /**
     * Основная логика подключения — порт вложенного цикла из {@code ПодключениеИР()}.
     * @throws UnsupportedEncodingException 
     */
    private void doConnectInternal(String key, String connectionString,
                                   String platformVersion, String appLabel)
    {
        String className              = buildComClassName(platformVersion);
        String connectionStringNoPass = removePassword(connectionString);
        log("COM-класс: " + className + ", БД: " + connectionStringNoPass); //$NON-NLS-1$

        // WMI создаём до запуска 1cv8.exe — нужен для поиска PID нового процесса
        Object wmi = WmiProcessHelper.connectWmi();

        Object  comDispatch        = null;
        Object  processObj         = null;
        long    pid                = 0;
        boolean success            = false;
        String  descriptionOnError = ""; //$NON-NLS-1$
        long    momentStart        = System.currentTimeMillis();

        // До 2 попыток — аналог «Для НомерПопытки = 1 По 2»
        for (int attempt = 1; attempt <= 2; attempt++)
        {
            try
            {
                long startMs = System.currentTimeMillis();
                comDispatch = ComJacobBridge.createComObject(className);

                // ПолучитьПроцессОСЛкс(Неопределено, startMs, 2, "-Embedding")
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

                String low = descriptionOnError.toLowerCase();
                if (low.contains("пароль") || low.contains("password") //$NON-NLS-1$ //$NON-NLS-2$
                        || low.contains("не идентифицирован")) //$NON-NLS-1$
                {
                    showError("Неверное имя или пароль.\n" + descriptionOnError); //$NON-NLS-1$
                    sessions.remove(key); notifyListeners(); return;
                }
                if (low.contains("0x800706be")) //$NON-NLS-1$
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
            Object comApp = ComJacobBridge.getProperty(comDispatch, "КлиентскоеПриложение");
            ComJacobBridge.invoke(comApp, "УстановитьЗаголовок", title);
        }
        catch (Exception e)
        {
            ComJacobBridge.invoke(comDispatch, "УстановитьЗаголовокСистемы", title); //$NON-NLS-1$
        }

        final long   finalPid      = pid;
        final Object finalDispatch = comDispatch;
        final Object finalProcObj  = processObj;
        final String finalVersion  = platformVersion;

        sessions.put(key, new IrSession(
            State.CONNECTED, LocalDateTime.now(), finalPid, finalVersion,
            finalDispatch, finalProcObj, title));
        notifyListeners();

        EclipseToastNotification.show("ИР подключено", //$NON-NLS-1$
            title + " подключено за " + duration + " сек", 3_000); //$NON-NLS-1$ //$NON-NLS-2$
        log("Подключено: " + title + " за " + duration + " с, PID=" + finalPid //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " platform=" + platformVersion); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Отключение (аналог ЗакрытьПриложениеИР)
    // -----------------------------------------------------------------------

    public void disconnect(Object element)
    {
        String key = sessionKey(element);
        IrSession session = sessions.get(key);
        if (session == null || session.state == State.IDLE) return;
        CompletableFuture.runAsync(() -> doDisconnect(key, session));
    }

    private void doDisconnect(String key, IrSession session)
    {
        boolean killed = false;

        if (session.dispatch != null)
        {
            try
            {
                // ЗавершитьРаботуСистемы(false) — штатное завершение
                ComJacobBridge.invoke(session.dispatch, "ЗавершитьРаботуСистемы", false); //$NON-NLS-1$
                log("ЗавершитьРаботуСистемы(false) — OK"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                log("ЗавершитьРаботуСистемы ошибка → УбитьПроцесс: " + e.getMessage()); //$NON-NLS-1$
                Object wmi  = WmiProcessHelper.connectWmi();
                Object proc = session.processObj != null ? session.processObj
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
            killed ? "Процесс приложения ИР завершён принудительно." //$NON-NLS-1$
                   : "Приложение ИР завершено. Отключение займёт несколько секунд.", //$NON-NLS-1$
            3_000);

        sessions.remove(key);
        notifyListeners();
        log("Сессия ИР удалена, key=" + key); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Строка соединения (аналог СтрокаСоединенияБазыКонфигуратора)
    // -----------------------------------------------------------------------

    static String buildConnectionString(Object element)
    {
        Object infobase = getInfobase(element);
        if (infobase == null) return ""; //$NON-NLS-1$

        Object connectionString = CompareEditorMenuHook.getField(infobase, "connectionString"); //$NON-NLS-1$
        String result = (String) tryCall(connectionString, "asConnectionString"); //$NON-NLS-1$
        if (result != null && !result.isEmpty()
                && (result.contains("File=") || result.contains("Srvr="))) //$NON-NLS-1$ //$NON-NLS-2$
            return result;

        return ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Версия платформы из EDT-ключа пула ("uuid:version")
    // -----------------------------------------------------------------------

    static String extractEDTPlatformVersion(Object element)
    {
        Set<Object> keys = DesignerSessionPoolAccessor.getInstance().getActiveKeys();
        String uuid = extractInfobaseUuid(element);

        if (!uuid.isEmpty())
        {
            for (Object k : keys)
            {
                String version = (String) OpenObjectHandler.getField(k, "installationVersionWithBuild");
                return version;
            }
        }

        log("Версия платформы не определена, используется 8.3"); //$NON-NLS-1$
        return "8.3.0.0"; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Ключ сессии = UUID инфобазы
    // -----------------------------------------------------------------------

    private static String sessionKey(Object element)
    {
        String uuid = extractInfobaseUuid(element);
        return uuid.isEmpty() ? String.valueOf(System.identityHashCode(element)) : uuid;
    }

    static String extractInfobaseUuid(Object element)
    {
        if (element == null) return ""; //$NON-NLS-1$
        Object infobase = getInfobase(element);
        if (infobase == null) return ""; //$NON-NLS-1$

        Object uuid = tryCall(infobase, "getUuid"); //$NON-NLS-1$
        if (uuid instanceof String && !((String) uuid).isEmpty()) return (String) uuid;

        java.util.regex.Matcher m = DesignerSessionPoolAccessor.UUID_PATTERN
            .matcher(infobase.toString());
        return m.find() ? m.group() : ""; //$NON-NLS-1$
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

    /** Удаляет Pwd="..." из строки соединения. */
    public static String removePassword(String cs)
    {
        if (cs == null) return ""; //$NON-NLS-1$
        int idx = cs.toLowerCase().indexOf("pwd="); //$NON-NLS-1$
        return idx > 0 ? cs.substring(0, idx) : cs;
    }

    private static Object getInfobase(Object element)
    {
        if (element == null) return null;
        Object ib = tryCall(element, "getInfobase"); //$NON-NLS-1$
        return ib != null ? ib : readField(element, "infobase"); //$NON-NLS-1$
    }

    private static Object tryCall(Object obj, String method)
    {
        if (obj == null) return null;
        try { return obj.getClass().getMethod(method).invoke(obj); }
        catch (Exception e) { return null; }
    }

    private static Object readField(Object obj, String name)
    {
        Class<?> cls = obj.getClass();
        while (cls != null)
        {
            try { Field f = cls.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored)            { return null; }
        }
        return null;
    }

    private void showError(String msg)
    {
        EclipseToastNotification.show("Ошибка подключения ИР", msg, 6_000); //$NON-NLS-1$
    }

    private void notifyListeners()
    {
        changeListeners.forEach(r -> { try { r.run(); } catch (Exception ignored) {} });
    }

    private static final DateTimeFormatter LOG_TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$

    static void log(String message)
    {
        String ts = LocalTime.now().format(LOG_TIME_FMT);
        try
        {
            Bundle b = FrameworkUtil.getBundle(IRApplicationRegistry.class);
            if (b != null)
            {
                Platform.getLog(b).log(new Status(IStatus.INFO, b.getSymbolicName(),
                    "[TormozitIR " + ts + "] " + message)); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
        }
        catch (Exception ignored) {}
        System.out.println("[TormozitIR " + ts + "] " + message); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import tormozit.edt.menu.CompareEditorMenuHook;

/**
 * Реестр подключений к приложению ИР (Инструменты Разработчика 1С).
 *
 * <p>Фасад над {@link ComConnectionRegistry}, адаптированный к элементам дерева
 * панели «Приложения» EDT ({@code InfobaseApplication}).
 *
 * <h3>Строка соединения</h3>
 * Извлекается из поля {@code infobase} элемента ({@code InfobaseReferenceImpl})
 * рефлексивно — аналог {@code СтрокаСоединенияБазыКонфигуратора()} из RDT.os.
 *
 * <h3>Версия платформы</h3>
 * Берётся из ключа пула {@link DesignerSessionPoolAccessor}: формат {@code "uuid:version"}
 * (например {@code "199a85b4-...:8.5.1.1343"}).
 */
public final class IRApplicationRegistry
{
    // ---- Синглтон ----

    private static final IRApplicationRegistry INSTANCE = new IRApplicationRegistry();
    public static IRApplicationRegistry getInstance() { return INSTANCE; }
    private IRApplicationRegistry() {
        ComConnectionRegistry.getInstance().addChangeListener(this::notifyListeners);
    }

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    // -----------------------------------------------------------------------
    // Публичный API (используется из ApplicationsViewHook)
    // -----------------------------------------------------------------------

    /** Инициализация: пробрасываем события из ComConnectionRegistry. */
    public void init()
    {
        ComConnectionRegistry.getInstance().addChangeListener(this::notifyListeners);
    }

    public void addChangeListener(Runnable l)    { if (l != null) changeListeners.add(l); }
    public void removeChangeListener(Runnable l) { changeListeners.remove(l); }

    /** {@code true} если приложение ИР подключено для данного элемента. */
    public boolean isConnected(Object element)
    {
        return ComConnectionRegistry.getInstance().isConnected(sessionKey(element));
    }

    /** {@code true} пока идёт подключение. */
    public boolean isConnecting(Object element)
    {
        return ComConnectionRegistry.getInstance().isConnecting(sessionKey(element));
    }

    /**
     * Текст для колонки:
     * <ul>
     *   <li>«подключение...»  — пока идёт Connect()</li>
     *   <li>дата              — успешное подключение</li>
     *   <li>null              — нет сессии</li>
     * </ul>
     */
    public LocalDateTime getSessionStart(Object element)
    {
        return ComConnectionRegistry.getInstance().getSessionStart(sessionKey(element));
    }

    /** Возвращает true если элемент находится в состоянии "подключение". */
    public boolean isInConnectingState(Object element)
    {
        return ComConnectionRegistry.getInstance().isConnecting(sessionKey(element));
    }

    // -----------------------------------------------------------------------
    // Подключение
    // -----------------------------------------------------------------------

    /**
     * Запускает асинхронное подключение к приложению ИР для данного элемента.
     */
    public void connect(Object element)
    {
        String connectionString = buildConnectionString(element);
        String platformVersion  = extractPlatformVersion(element);
        String appLabel         = DesignerSessionPoolAccessor.nameOf(element);

        ComConnectionRegistry.log(
            "connect() element=" + appLabel //$NON-NLS-1$
            + " cs=" + ComConnectionRegistry.removePassword(connectionString) //$NON-NLS-1$
            + " platform=" + platformVersion); //$NON-NLS-1$

        ComConnectionRegistry.getInstance().connect(
            sessionKey(element), connectionString, platformVersion, appLabel);
    }

    // -----------------------------------------------------------------------
    // Отключение
    // -----------------------------------------------------------------------

    /**
     * Завершает работу приложения ИР.
     */
    public void disconnect(Object element)
    {
        ComConnectionRegistry.getInstance().disconnect(sessionKey(element));
    }

    // -----------------------------------------------------------------------
    // Ключ сессии — строка UUID инфобазы
    // -----------------------------------------------------------------------

    /**
     * Уникальный ключ для хранения сессии.
     * Используем UUID инфобазы (не сам объект, чтобы пережить пересоздание EDT-элементов).
     */
    private static String sessionKey(Object element)
    {
        String uuid = extractInfobaseUuid(element);
        return uuid.isEmpty() ? System.identityHashCode(element) + "" : uuid; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Строка соединения 1С
    // Аналог СтрокаСоединенияБазыКонфигуратора() из RDT.os
    // -----------------------------------------------------------------------

    /**
     * Строит строку соединения 1C из EDT-элемента.
     *
     * <p>Порядок попыток:
     * <ol>
     *   <li>Рефлексия на {@code InfobaseReferenceImpl}: поля type/path/server/database/user.</li>
     *   <li>Метод {@code getConnectionString()} или {@code buildConnectionString()}.</li>
     *   <li>Парсинг toString() на наличие File="..." или Srvr="...".</li>
     *   <li>Пустая строка (Connect() попробует подключиться без явной строки).</li>
     * </ol>
     */
    static String buildConnectionString(Object element)
    {
        Object infobase = getInfobase(element);
        Object connectionString = CompareEditorMenuHook.getField(infobase, "connectionString");
        Object result = tryCall(connectionString, "asConnectionString");
        if (result instanceof String && !((String) result).isEmpty()
                && (((String) result).contains("File=") //$NON-NLS-1$
                    || ((String) result).contains("Srvr="))) //$NON-NLS-1$
            return (String) result;
        return ""; //$NON-NLS-1$
    }

    /**
     * Собирает строку соединения из отдельных полей объекта инфобазы.
     * Поля читаются рефлексивно — имена соответствуют EDT-модели.
     */
    private static String assembleFromFields(Object infobase)
    {
        // Определяем тип: файловая или серверная
        // Пробуем поле "type", "dbType", "connectionType", "mode"
        String type = ""; //$NON-NLS-1$
        for (String fn : new String[]{ "type", "dbType", "connectionType", "mode", "infobaseType" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            Object v = readField(infobase, fn);
            if (v != null) { type = v.toString().toLowerCase(); break; }
        }

        // Файловая база
        if (type.contains("file") || type.contains("local"))
        {
            String path = readStringField(infobase, "path", "directory", "filePath", "location"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (!path.isEmpty())
            {
                String cs = "File=\"" + path + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
                String user = readStringField(infobase, "user", "userName", "login"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!user.isEmpty()) cs += "Usr=\"" + user + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
                return cs;
            }
        }

        // Серверная база
        String server = readStringField(infobase, "server", "host", "serverHost", "srvr"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String dbName = readStringField(infobase, "database", "dbName", "ref", "infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "databaseName", "dbRef"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!server.isEmpty() && !dbName.isEmpty())
        {
            String cs = "Srvr=\"" + server + "\";Ref=\"" + dbName + "\";"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String user = readStringField(infobase, "user", "userName", "login"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!user.isEmpty()) cs += "Usr=\"" + user + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
            return cs;
        }

        // Если тип не определён, пробуем угадать по доступным полям
        if (!server.isEmpty())
        {
            // Серверная, но dbName не найдено
            return "Srvr=\"" + server + "\";"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        return ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Версия платформы — из ключа DesignerSessionPool ("uuid:8.5.1.1343")
    // -----------------------------------------------------------------------

    static String extractPlatformVersion(Object element)
    {
        // Ключ пула: "199a85b4-...:8.5.1.1343"
        Set<Object> keys = DesignerSessionPoolAccessor.getInstance().getActiveKeys();
        String uuid = extractInfobaseUuid(element);
        if (!uuid.isEmpty())
        {
            for (Object k : keys)
            {
                if (k instanceof String && ((String) k).startsWith(uuid + ":")) //$NON-NLS-1$
                {
                    String version = ((String) k).substring(uuid.length() + 1);
                    ComConnectionRegistry.log("Версия платформы из ключа пула: " + version); //$NON-NLS-1$
                    return version;
                }
            }
        }

        // Fallback: пробуем читать напрямую из EDT-объекта
        Object infobase = getInfobase(element);
        if (infobase != null)
        {
            for (String fn : new String[]{ "platformVersion", "version", "appVersion" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object v = readField(infobase, fn);
                if (v instanceof String && !((String)v).isEmpty()) return (String) v;
            }
        }

        ComConnectionRegistry.log("Версия платформы не определена, используется 8.3"); //$NON-NLS-1$
        return "8.3.0.0"; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Вспомогательные методы
    // -----------------------------------------------------------------------

    private static Object getInfobase(Object element)
    {
        if (element == null) return null;
        Object ib = tryCall(element, "getInfobase"); //$NON-NLS-1$
        if (ib != null) return ib;
        ib = readField(element, "infobase"); //$NON-NLS-1$
        return ib;
    }

    static String extractInfobaseUuid(Object element)
    {
        if (element == null) return ""; //$NON-NLS-1$
        Object infobase = getInfobase(element);
        if (infobase == null) return ""; //$NON-NLS-1$

        Object uuid = tryCall(infobase, "getUuid"); //$NON-NLS-1$
        if (uuid instanceof String && !((String)uuid).isEmpty()) return (String)uuid;

        // Из toString(): "(name: ..., uuid: 199a85b4-..., ...)"
        java.util.regex.Matcher m = DesignerSessionPoolAccessor.UUID_PATTERN
            .matcher(infobase.toString());
        return m.find() ? m.group() : ""; //$NON-NLS-1$
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
            try
            {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
            catch (Exception ignored)            { return null; }
        }
        return null;
    }

    /**
     * Ищет первое непустое строковое поле среди перечисленных имён.
     */
    private static String readStringField(Object obj, String... fieldNames)
    {
        for (String name : fieldNames)
        {
            Object v = readField(obj, name);
            if (v instanceof String && !((String)v).isEmpty()) return (String)v;
        }
        return ""; //$NON-NLS-1$
    }

    private void notifyListeners()
    {
        changeListeners.forEach(r -> { try { r.run(); } catch (Exception ignored) {} });
    }

    private final java.util.Map<String, Boolean> autoConnectMap = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isAutoConnect(Object element) {
        return autoConnectMap.getOrDefault(sessionKey(element), false);
    }

    public void setAutoConnect(Object element, boolean auto) {
        autoConnectMap.put(sessionKey(element), auto);
    }
    
}

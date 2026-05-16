package tormozit.edt.applications;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import tormozit.edt.handlers.OpenObjectHandler;
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
        String platformVersion  = extractEDTPlatformVersion(element);
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
    static String buildConnectionString(Object element)
    {
        Object infobase = getInfobase(element);
        Object connectionString = CompareEditorMenuHook.getField(infobase, "connectionString");
        String result = (String) tryCall(connectionString, "asConnectionString");
        if (true
            && !result.isEmpty()
            && (false
                || result.contains("File=") //$NON-NLS-1$
                || result.contains("Srvr="))) //$NON-NLS-1$
            return (String) result;
        return ""; //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // Версия платформы, которую использует EDT для подключения к базе (из активной сессии или из свойства инфобазы)
    // -----------------------------------------------------------------------
    static String extractEDTPlatformVersion(Object element)
    {
        // Ключ пула: "199a85b4-...:8.5.1.1343"
        Set<Object> keys = DesignerSessionPoolAccessor.getInstance().getActiveKeys();
        String uuid = extractInfobaseUuid(element);
        if (!uuid.isEmpty())
        {
            for (Object k : keys)
            {
                if (true
//                    && k instanceof DebuggerSessionKey 
                    )
                {
                    String version = (String) OpenObjectHandler.getField(k, "installationVersionWithBuild");
//                    ComConnectionRegistry.log("Версия платформы из ключа пула: " + version); //$NON-NLS-1$
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
    /**
     * @param el
     * @return
     */
    public String getSessionPlatformVersion(Object element)
    {
        return ComConnectionRegistry.getInstance().getPlatformVersion(sessionKey(element));
    }
    
}

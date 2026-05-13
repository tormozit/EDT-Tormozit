package tormozit.edt.applications;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Статический реестр COM-подключений типа «Конфигуратор» к базам 1С.
 *
 * <p>Ключ — строковый идентификатор базы, который извлекается из строки
 * панели «Приложения» (обычно это имя приложения). Хранятся:
 * <ul>
 *   <li>момент начала сеанса ({@link LocalDateTime});</li>
 *   <li>сам COM-объект (тип {@code Object}, чтобы не зависеть от Jacob
 *       при компиляции; реальный тип — {@code com.jacob.com.Dispatch}).</li>
 * </ul>
 *
 * <p>Реестр потокобезопасен: внутренние хранилища — {@link ConcurrentHashMap},
 * список слушателей защищён {@code synchronized(ComConnectionRegistry.class)}.
 * Уведомления слушателей выполняются вне блокировки, чтобы избежать deadlock.
 */
public final class ComConnectionRegistry
{
    // ---- Хранилища ----

    /** Ключ → момент начала сеанса конфигуратора. */
    private static final ConcurrentHashMap<String, LocalDateTime> sessions =
        new ConcurrentHashMap<>();

    /**
     * Ключ → COM-объект (com.jacob.com.Dispatch или совместимый).
     * Хранится как Object, чтобы плагин компилировался без Jacob в classpath.
     */
    private static final ConcurrentHashMap<String, Object> comObjects =
        new ConcurrentHashMap<>();

    // ---- Слушатели изменений ----

    private static final List<Runnable> changeListeners = new ArrayList<>();

    // ---- Конструктор скрыт — утилитный класс ----

    private ComConnectionRegistry() {}

    // -----------------------------------------------------------------------
    // Публичный API — Конфигуратор
    // -----------------------------------------------------------------------

    /**
     * Регистрирует сеанс конфигуратора.
     *
     * @param key       идентификатор базы
     * @param startTime время начала сеанса
     */
    public static void registerSession(String key, LocalDateTime startTime)
    {
        registerSession(key, startTime, null);
    }

    /**
     * Регистрирует сеанс конфигуратора вместе с COM-объектом.
     *
     * @param key       идентификатор базы
     * @param startTime время начала сеанса
     * @param comObject COM dispatch-объект (может быть {@code null})
     */
    public static void registerSession(String key, LocalDateTime startTime, Object comObject)
    {
        if (key == null || key.isEmpty())
            throw new IllegalArgumentException("key must not be blank"); //$NON-NLS-1$
        sessions.put(key, startTime != null ? startTime : LocalDateTime.now());
        if (comObject != null)
            comObjects.put(key, comObject);
        notifyListeners();
    }

    /**
     * Возвращает время начала сеанса конфигуратора, или {@code null} если
     * для данного ключа сеанс не зарегистрирован.
     */
    public static LocalDateTime getSessionStart(String key)
    {
        return key != null ? sessions.get(key) : null;
    }

    /**
     * Возвращает COM-объект для ключа, или {@code null}.
     * Тип объекта — {@code com.jacob.com.Dispatch} (загружен через рефлексию).
     */
    public static Object getComObject(String key)
    {
        return key != null ? comObjects.get(key) : null;
    }

    /** Возвращает {@code true}, если для ключа зарегистрирован активный сеанс. */
    public static boolean isConnected(String key)
    {
        return key != null && sessions.containsKey(key);
    }

    /**
     * Отключает конфигуратор для одного ключа.
     *
     * <p>Пытается освободить COM-ресурс (вызывает {@code release()} через
     * рефлексию, если метод доступен), затем удаляет записи из хранилищ.
     */
    public static void disconnect(String key)
    {
        if (key == null) return;
        Object comObj = comObjects.remove(key);
        releaseComObject(comObj);
        sessions.remove(key);
        notifyListeners();
    }

    /**
     * Отключает конфигуратор для всех зарегистрированных ключей.
     * Вызывается при остановке плагина.
     */
    public static void disconnectAll()
    {
        comObjects.values().forEach(ComConnectionRegistry::releaseComObject);
        comObjects.clear();
        sessions.clear();
        notifyListeners();
    }

    /**
     * Неизменяемый снимок текущего набора ключей активных сеансов.
     */
    public static Set<String> connectedKeys()
    {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    // -----------------------------------------------------------------------
    // Слушатели изменений (для обновления UI)
    // -----------------------------------------------------------------------

    /**
     * Добавляет слушатель, который будет вызван при любом изменении реестра.
     * Слушатель вызывается в том потоке, который изменил реестр; для обновления
     * SWT-виджетов используйте {@code Display.getDefault().asyncExec(...)}.
     */
    public static synchronized void addChangeListener(Runnable listener)
    {
        if (listener != null && !changeListeners.contains(listener))
            changeListeners.add(listener);
    }

    /** Удаляет ранее добавленный слушатель. */
    public static synchronized void removeChangeListener(Runnable listener)
    {
        changeListeners.remove(listener);
    }

    // -----------------------------------------------------------------------
    // Внутренние утилиты
    // -----------------------------------------------------------------------

    private static void notifyListeners()
    {
        List<Runnable> copy;
        synchronized (ComConnectionRegistry.class)
        {
            copy = new ArrayList<>(changeListeners);
        }
        for (Runnable r : copy)
        {
            try { r.run(); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Пытается освободить COM-объект через рефлексию.
     * Ошибки игнорируются — объект мог уже быть освобождён.
     */
    private static void releaseComObject(Object comObj)
    {
        if (comObj == null) return;
        try
        {
            comObj.getClass().getMethod("release").invoke(comObj); //$NON-NLS-1$
        }
        catch (Exception ignored) {}
    }
}

package tormozit.edt.applications;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.eclipse.swt.ole.win32.Variant;

/**
 * Работа с WMI через COM (Jacob).
 *
 * <p>Точный порт функций из RDT.os:
 * <ul>
 *   <li>{@link #connectWmi()} ← {@code ПолучитьCOMОбъектWMIЛкс()}</li>
 *   <li>{@link #findProcess} ← {@code ПолучитьПроцессОСЛкс()}</li>
 *   <li>{@link #terminate}   ← {@code УбитьПроцесс()}</li>
 * </ul>
 *
 * <h3>Оригинал ПолучитьCOMОбъектWMIЛкс()</h3>
 * <pre>
 *   Locator = Новый COMОбъект("WbemScripting.SWbemLocator");
 *   Значение = Locator.ConnectServer(".", "root\cimv2");
 * </pre>
 *
 * <h3>Оригинал ПолучитьПроцессОСЛкс()</h3>
 * <pre>
 *   ТекстЗапросаWQL = "Select * from Win32_Process Where ..."
 *   ВыборкаПроцессовОС = WMIЛокатор.ExecQuery(ТекстЗапросаWQL);
 *   Для Каждого ПроцессОС Из ВыборкаПроцессовОС Цикл
 *       Значение = ПроцессОС; Прервать;
 *   КонецЦикла;
 * </pre>
 *
 * <h3>Формат дат в WQL</h3>
 * Оригинал: {@code УниверсальноеВремя()} + {@code "yyyyMMdd HH:mm:ss"}.
 * Здесь: {@code Instant → UTC → DateTimeFormatter("yyyyMMdd HH:mm:ss")}.
 */
public final class WmiProcessHelper
{
    // Формат даты для WQL — точно как в оригинале ПолучитьЛитералДатыДляWQLЛкс()
    private static final DateTimeFormatter WQL_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss").withZone(ZoneOffset.UTC); //$NON-NLS-1$

    private WmiProcessHelper() {}

    // -----------------------------------------------------------------------
    // ПолучитьCOMОбъектWMIЛкс()
    // -----------------------------------------------------------------------

    /**
     * Создаёт подключение к WMI.
     *
     * <pre>
     *   Locator = Новый COMОбъект("WbemScripting.SWbemLocator");
     *   Возврат Locator.ConnectServer(".", "root\cimv2");
     * </pre>
     *
     * @return SWbemServices (COM-объект), или null при ошибке
     */
    public static Object connectWmi()
    {
        try
        {
            Object locator = ComJacobBridge.createComObject("WbemScripting.SWbemLocator"); //$NON-NLS-1$
            Object wmi     = ComJacobBridge.invoke(locator, "ConnectServer", ".", "root\\cimv2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ComConnectionRegistry.log("WMI подключён: root\\cimv2"); //$NON-NLS-1$
            return wmi;
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("WMI connectWmi() ошибка: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // ПолучитьПроцессОСЛкс()
    // -----------------------------------------------------------------------

    /**
     * Ищет новый процесс {@code 1cv8.exe -Embedding}, запущенный около момента
     * {@code startedAfterMs}.
     *
     * <p>Порт вызова из {@code ПодключениеИР()}:
     * <pre>
     *   ПолучитьПроцессОСЛкс(Неопределено, МоментСтартаПроцесса, 2, "-Embedding")
     * </pre>
     *
     * @param wmi           SWbemServices из {@link #connectWmi()}
     * @param startedAfterMs System.currentTimeMillis() в момент ДО создания COM-объекта
     * @param toleranceSec  допустимое отклонение в секундах (2 как в оригинале)
     * @return Win32_Process COM-объект или null
     */
    public static Object findProcess(Object wmi, long startedAfterMs, int toleranceSec)
    {
        if (wmi == null) return null;

        // Аналог ПолучитьЛитералДатыДляWQLЛкс() — UTC, формат "yyyyMMdd HH:mm:ss"
        String dateFrom = wqlDate(startedAfterMs - toleranceSec * 1000L);
        String dateTo   = wqlDate(startedAfterMs + (toleranceSec + 1) * 1000L);

        // Строим WQL — точно как ПолучитьПроцессОСЛкс() с параметрами
        // ИмяИсполняемогоФайла="1cv8.exe", МаркерВКоманднойСтроке="-Embedding",
        // НачалоПроцесса=МоментСтартаПроцесса, ДопустимоеОтклонениеВремени=2
        String wql = "Select * from Win32_Process" //$NON-NLS-1$
            + " Where CommandLine LIKE '%-Embedding%'" //$NON-NLS-1$
            + " AND Name = '1cv8.exe'" //$NON-NLS-1$
            + " AND CreationDate >= '" + dateFrom + "'" //$NON-NLS-1$ //$NON-NLS-2$
            + " AND CreationDate <= '" + dateTo   + "'"; //$NON-NLS-1$ //$NON-NLS-2$

        ComConnectionRegistry.log("WQL: " + wql); //$NON-NLS-1$

        try
        {
            Object resultSet = ComJacobBridge.invoke(wmi, "ExecQuery", wql); //$NON-NLS-1$

            // Для Каждого ПроцессОС Из ВыборкаПроцессовОС Цикл
            //     Значение = ПроцессОС; Прервать;
            // КонецЦикла;
            for (Object process : ComJacobBridge.iterateComCollection(resultSet))
            {
                long pid = ComJacobBridge.toLong(ComJacobBridge.getProperty(process, "ProcessId")); //$NON-NLS-1$
                ComConnectionRegistry.log("Процесс ОС найден, PID=" + pid); //$NON-NLS-1$
                return process; // берём первый — как в оригинале
            }

            ComConnectionRegistry.log("Процесс ОС не найден (WQL вернул 0 результатов)"); //$NON-NLS-1$
            return null;
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("WMI ExecQuery ошибка: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Ищет процесс по PID через WMI.
     * Используется при отключении, если нужно получить свежий объект процесса.
     */
    public static Object findProcessByPid(Object wmi, long pid)
    {
        if (wmi == null || pid <= 0) return null;
        String wql = "Select * from Win32_Process Where ProcessID = " + pid; //$NON-NLS-1$
        try
        {
            Object resultSet = ComJacobBridge.invoke(wmi, "ExecQuery", wql); //$NON-NLS-1$
            for (Object process : ComJacobBridge.iterateComCollection(resultSet))
                return process;
            return null;
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("findProcessByPid(" + pid + ") ошибка: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // УбитьПроцесс()
    // -----------------------------------------------------------------------

    /**
     * Завершает процесс.
     *
     * <pre>
     *   // Оригинал:
     *   ПроцессОС.Terminate();
     * </pre>
     *
     * <p>Если {@code processObj} не null — вызывает {@code Terminate()} через COM.
     * Иначе — убиваем через {@code taskkill /F /PID} как запасной вариант.
     */
    public static void terminate(Object processObj, long pid)
    {
        if (processObj != null)
        {
            try
            {
                ComJacobBridge.invoke(processObj, "Terminate"); //$NON-NLS-1$
                ComConnectionRegistry.log("Win32_Process.Terminate() — OK, PID=" + pid); //$NON-NLS-1$
                return;
            }
            catch (Exception e)
            {
                ComConnectionRegistry.log("Win32_Process.Terminate() ошибка → taskkill: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        // Fallback
        killByPid(pid);
    }

    /** Извлекает PID из Win32_Process COM-объекта. */
    public static long getPid(Object processObj)
    {
        if (processObj == null) return 0L;
        try
        {
            return ComJacobBridge.toLong(ComJacobBridge.getProperty(processObj, "ProcessId")); //$NON-NLS-1$
        }
        catch (Exception e) { return 0L; }
    }

    // -----------------------------------------------------------------------
    // Вспомогательное
    // -----------------------------------------------------------------------

    /**
     * Формат даты для WQL — аналог {@code ПолучитьЛитералДатыДляWQLЛкс()}.
     * UTC, формат {@code "yyyyMMdd HH:mm:ss"}.
     */
    private static String wqlDate(long epochMs)
    {
        return WQL_DATE_FMT.format(Instant.ofEpochMilli(epochMs));
    }

    /** Принудительное завершение через taskkill (крайний fallback). */
    static void killByPid(long pid)
    {
        if (pid <= 0) return;
        try
        {
            new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .redirectErrorStream(true).start().waitFor();
            ComConnectionRegistry.log("taskkill /F /PID " + pid + " — выполнен"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("taskkill ошибка: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}

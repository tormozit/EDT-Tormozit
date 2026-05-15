package tormozit.edt.applications;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вспомогательный класс для работы с WMI-процессами через {@code wmic.exe}.
 *
 * <p>Аналог функции {@code ПолучитьПроцессОСЛкс()} из RDT.os:
 * <pre>
 *   SELECT * FROM Win32_Process
 *   WHERE Name = '1cv8.exe'
 *   AND CommandLine LIKE '%-Embedding%'
 *   AND CreationDate >= &lt;startMs - 2 sec&gt;
 * </pre>
 *
 * <p>Использует только стандартные Java-инструменты (ProcessBuilder + wmic.exe).
 * Никаких дополнительных библиотек не требуется.
 */
public final class WmiProcessHelper
{
    private static final Pattern PID_PATTERN =
        Pattern.compile("ProcessId\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    private WmiProcessHelper() {}

    // -----------------------------------------------------------------------
    // Поиск PID
    // -----------------------------------------------------------------------

    /**
     * Находит PID нового процесса {@code 1cv8.exe -Embedding}, запущенного
     * не ранее чем {@code startedAfterMs} миллисекунд назад (точность ±3 сек).
     *
     * <p>Аналог вызова:
     * <pre>ПолучитьПроцессОСЛкс(, МоментСтартаПроцесса,, "-Embedding")</pre>
     *
     * @param startedAfterMs System.currentTimeMillis() в момент до вызова createComObject()
     * @return PID или 0 если не найден
     */
    public static long findNewEmbeddingPid(long startedAfterMs)
    {
        // Даём процессу 1 сек, чтобы появиться в списке WMI
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        // wmic process where (name='1cv8.exe' and CommandLine like '%-Embedding%')
        //   get ProcessId,CreationDate /format:list
        try
        {
            Process proc = new ProcessBuilder(
                "wmic", "process", //$NON-NLS-1$ //$NON-NLS-2$
                "where", "(Name='1cv8.exe' AND CommandLine LIKE '%-Embedding%')", //$NON-NLS-1$ //$NON-NLS-2$
                "get", "ProcessId,CreationDate", //$NON-NLS-1$ //$NON-NLS-2$
                "/format:list") //$NON-NLS-1$
                .redirectErrorStream(true)
                .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), "CP866"))) //$NON-NLS-1$
            {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                    sb.append(line).append('\n');
                output = sb.toString();
            }
            proc.waitFor();
            ComConnectionRegistry.log("WMI output:\n" + output); //$NON-NLS-1$

            // Разбиваем по блокам (каждый процесс — отдельный блок)
            String[] blocks = output.split("\n\n"); //$NON-NLS-1$
            long bestPid = 0;
            long bestCreation = 0;

            for (String block : blocks)
            {
                block = block.trim();
                if (block.isEmpty()) continue;

                long pid = extractLongField(block, "ProcessId"); //$NON-NLS-1$
                if (pid <= 0) continue;

                // CreationDate в WMI: "20260514203045.123456+180"
                long creationMs = parseWmiDate(extractStringField(block, "CreationDate")); //$NON-NLS-1$

                // Берём процесс, стартовавший после нашего момента (±3 сек допуск)
                if (creationMs >= startedAfterMs - 3000 && creationMs > bestCreation)
                {
                    bestCreation = creationMs;
                    bestPid = pid;
                }
            }

            if (bestPid > 0)
                ComConnectionRegistry.log("Найден PID процесса 1cv8.exe -Embedding: " + bestPid); //$NON-NLS-1$
            else
                ComConnectionRegistry.log("Не удалось найти PID процесса 1cv8.exe -Embedding"); //$NON-NLS-1$

            return bestPid;
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("WMI findNewEmbeddingPid ошибка: " + e.getMessage()); //$NON-NLS-1$
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // Завершение процесса
    // -----------------------------------------------------------------------

    /**
     * Убивает процесс по PID через {@code taskkill /F /PID}.
     * Аналог {@code УбитьПроцесс(ПроцессОС)}.
     */
    public static void killByPid(long pid)
    {
        if (pid <= 0) return;
        try
        {
            Process proc = new ProcessBuilder(
                "taskkill", "/F", "/PID", String.valueOf(pid)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .redirectErrorStream(true)
                .start();
            proc.waitFor();
            ComConnectionRegistry.log("taskkill /F /PID " + pid + " выполнен"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("Ошибка taskkill PID=" + pid + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // Разбор WMI-вывода
    // -----------------------------------------------------------------------

    private static long extractLongField(String block, String fieldName)
    {
        String val = extractStringField(block, fieldName);
        if (val.isEmpty()) return 0;
        try { return Long.parseLong(val.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String extractStringField(String block, String fieldName)
    {
        Pattern p = Pattern.compile(fieldName + "\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : ""; //$NON-NLS-1$
    }

    /**
     * Парсит дату WMI формата "20260514203045.123456+180" в Unix-миллисекунды.
     */
    private static long parseWmiDate(String wmiDate)
    {
        if (wmiDate == null || wmiDate.length() < 14) return 0;
        try
        {
            // "yyyyMMddHHmmss"
            int year   = Integer.parseInt(wmiDate.substring(0, 4));
            int month  = Integer.parseInt(wmiDate.substring(4, 6));
            int day    = Integer.parseInt(wmiDate.substring(6, 8));
            int hour   = Integer.parseInt(wmiDate.substring(8, 10));
            int minute = Integer.parseInt(wmiDate.substring(10, 12));
            int second = Integer.parseInt(wmiDate.substring(12, 14));

            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); //$NON-NLS-1$
            cal.set(year, month - 1, day, hour, minute, second);
            cal.set(java.util.Calendar.MILLISECOND, 0);

            // Учитываем смещение часового пояса "+180" (минуты от UTC)
            int tzOffset = 0;
            int dotIdx = wmiDate.indexOf('.');
            if (dotIdx > 0)
            {
                String tzPart = wmiDate.substring(dotIdx + 1);
                int plusIdx = tzPart.indexOf('+');
                int minusIdx = tzPart.indexOf('-');
                try
                {
                    if (plusIdx >= 0)
                        tzOffset = Integer.parseInt(tzPart.substring(plusIdx + 1)) * 60_000;
                    else if (minusIdx >= 0)
                        tzOffset = -Integer.parseInt(tzPart.substring(minusIdx + 1)) * 60_000;
                }
                catch (NumberFormatException ignored) {}
            }
            return cal.getTimeInMillis() - tzOffset;
        }
        catch (Exception e) { return 0; }
    }
}

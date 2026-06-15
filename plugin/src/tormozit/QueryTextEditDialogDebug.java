package tormozit;

/**
 * Диагностика подменю «Комфорт» в «Редакторе запроса».
 *
 * <p>Включение: Параметры → Комфорт → «Общее логирование».
 */
public final class QueryTextEditDialogDebug
{
    private static final String TAG = "QueryEditor"; //$NON-NLS-1$

    private QueryTextEditDialogDebug()
    {
    }

    public static boolean isEnabled()
    {
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
    }

    /** Сбой ветки — в журнал при включённом {@link #isEnabled()}. */
    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}

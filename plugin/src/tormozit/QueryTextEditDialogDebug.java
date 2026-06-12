package tormozit;

/**
 * Диагностика подменю «Комфорт» в «Редакторе запроса».
 *
 * <p>Включение: Параметры → Комфорт → «Общее логирование»
 * или {@code -Dtormozit.queryEditor.debug=true}.
 */
public final class QueryTextEditDialogDebug
{
    private static final String TAG = "QueryEditor"; //$NON-NLS-1$

    private QueryTextEditDialogDebug()
    {
    }

    public static boolean isEnabled()
    {
        String prop = System.getProperty("tormozit.queryEditor.debug"); //$NON-NLS-1$
        if (prop != null)
            return !"false".equalsIgnoreCase(prop); //$NON-NLS-1$
        return Global.isLogEnabled();
    }

    public static void log(String msg)
    {
        if (isEnabled())
            Global.log(TAG, msg);
    }

    public static void verbose(String msg)
    {
        String prop = System.getProperty("tormozit.queryEditor.verbose"); //$NON-NLS-1$
        if (prop != null && !"false".equalsIgnoreCase(prop) && isEnabled()) //$NON-NLS-1$
            Global.log(TAG, msg);
    }

    /** Сбой ветки — всегда в журнал при включённом {@link #isEnabled()}. */
    public static void problem(String msg)
    {
        if (isEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}

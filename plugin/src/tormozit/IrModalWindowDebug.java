package tormozit;

/** Логи модального режима EDT↔ИР (журнал «Комфорт»). */
final class IrModalWindowDebug
{
    private static final String TAG = "IrModal"; //$NON-NLS-1$

    private IrModalWindowDebug() {}

    static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, msg);
    }

    static void step(String phase, String detail)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, phase + ": " + detail); //$NON-NLS-1$
    }

    static void problem(String msg)
    {
        if (Global.isLogEnabled())
            Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
    }
}

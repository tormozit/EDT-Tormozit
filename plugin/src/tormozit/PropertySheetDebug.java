package tormozit;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;

/**
 * Логи хука панели «Свойства»: {@code Global.log} → {@code [PropertySheet] …}.
 *
 * <ul>
 *   <li>Отключить всё: {@code -Dtormozit.propertySheet.debug=false}</li>
 *   <li>По умолчанию — только проблемы ({@code INCOMPLETE}, {@code GIVE UP}, {@code FAIL})</li>
 *   <li>Подробный scan/ui: {@code -Dtormozit.propertySheet.debug.verbose=true}</li>
 *   <li>Resolve/trace (очень шумно): {@code -Dtormozit.propertySheet.debug.trace=true}</li>
 * </ul>
 */
public final class PropertySheetDebug
{
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("tormozit.propertySheet.debug", "true")); //$NON-NLS-1$ //$NON-NLS-2$
    private static final boolean VERBOSE =
            "true".equalsIgnoreCase(System.getProperty("tormozit.propertySheet.debug.verbose", "false")); //$NON-NLS-1$ //$NON-NLS-2$
    private static final boolean TRACE =
            "true".equalsIgnoreCase(System.getProperty("tormozit.propertySheet.debug.trace", "false")); //$NON-NLS-1$ //$NON-NLS-2$

    private PropertySheetDebug() {}

    static boolean isEnabled()
    {
        return ENABLED;
    }

    static boolean isVerbose()
    {
        return ENABLED && VERBOSE;
    }

    static boolean isTrace()
    {
        return ENABLED && TRACE;
    }

    static String flags()
    {
        return "enabled=" + ENABLED + " verbose=" + VERBOSE + " trace=" + TRACE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public static void log(String msg)
    {
        if (ENABLED)
            Global.log("[PropertySheet] " + msg); //$NON-NLS-1$
    }

    /** Проблема / нештатная ситуация — всегда при включённом debug. */
    static void problem(String msg)
    {
        if (ENABLED)
            Global.log("[PropertySheet] [!] " + msg); //$NON-NLS-1$
    }

    static void scanProblem(String msg)
    {
        problem("[scan] " + msg); //$NON-NLS-1$
    }

    static void uiProblem(String msg)
    {
        problem("[ui] " + msg); //$NON-NLS-1$
    }

    static void valueControl(String msg)
    {
        if (ENABLED)
            Global.log("[PropertySheet] [value] " + msg); //$NON-NLS-1$
    }

    static void valueControlVerbose(String msg)
    {
        if (isVerbose())
            valueControl(msg);
    }

    static void scan(String msg)
    {
        if (isVerbose())
            log("[scan] " + msg); //$NON-NLS-1$
    }

    static void scanVerbose(String msg)
    {
        if (isTrace())
            log("[scan] " + msg); //$NON-NLS-1$
    }

    static void resolve(String msg)
    {
        if (isTrace())
            log("[resolve] " + msg); //$NON-NLS-1$
    }

    static void resolveVerbose(String msg)
    {
        if (isTrace())
            resolve(msg);
    }

    static void ui(String msg)
    {
        if (isVerbose())
            log("[ui] " + msg); //$NON-NLS-1$
    }

    static void uiVerbose(String msg)
    {
        if (isTrace())
            log("[ui] " + msg); //$NON-NLS-1$
    }

    static void feature(String msg)
    {
        if (isTrace())
            log("[feature] " + msg); //$NON-NLS-1$
    }

    public static String safe(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        return o.getClass().getSimpleName();
    }

    /** Полное имя класса — для логов [value]. */
    static String typeName(Object o)
    {
        if (o == null)
            return "<null>"; //$NON-NLS-1$
        String cn = o.getClass().getName();
        int dot = cn.lastIndexOf('.');
        return dot >= 0 ? cn.substring(dot + 1) : cn;
    }

    static String quote(String s)
    {
        if (s == null)
            return "<null>"; //$NON-NLS-1$
        if (s.length() > 60)
            return "\"" + s.substring(0, 57) + "...\""; //$NON-NLS-1$ //$NON-NLS-2$
        return "\"" + s + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    static String controlBrief(Control control)
    {
        if (control == null)
            return "<null>"; //$NON-NLS-1$
        if (control.isDisposed())
            return safe(control) + "(disposed)"; //$NON-NLS-1$
        Point size = control.getSize();
        Point loc = control.toDisplay(0, 0);
        String text = PropertySheetControlInterop.controlText(control);
        if (text.length() > 40)
            text = text.substring(0, 37) + "..."; //$NON-NLS-1$
        return safe(control) + " " + size.x + "x" + size.y //$NON-NLS-1$ //$NON-NLS-2$
                + " @(" + loc.x + "," + loc.y + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + (text.isEmpty() ? "" : " text=" + quote(text)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Дерево SWT под root (depth) — для диагностики slots=0. */
    static String compositeTreeBrief(org.eclipse.swt.widgets.Composite root, int maxDepth)
    {
        if (root == null || root.isDisposed())
            return "<null>"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        appendCompositeTree(root, 0, maxDepth, sb);
        return sb.toString();
    }

    private static void appendCompositeTree(org.eclipse.swt.widgets.Composite composite, int depth,
            int maxDepth, StringBuilder sb)
    {
        if (composite == null || composite.isDisposed() || depth > maxDepth)
            return;
        if (sb.length() > 0)
            sb.append(" | "); //$NON-NLS-1$
        sb.append(repeat("  ", depth)).append(safe(composite)); //$NON-NLS-1$
        sb.append(" ch=").append(composite.getChildren().length); //$NON-NLS-1$
        for (org.eclipse.swt.widgets.Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            sb.append(" | ").append(repeat("  ", depth + 1)).append(controlBrief(child)); //$NON-NLS-1$ //$NON-NLS-2$
            if (child instanceof org.eclipse.swt.widgets.Composite && depth + 1 < maxDepth)
                appendCompositeTree((org.eclipse.swt.widgets.Composite) child, depth + 1, maxDepth, sb);
        }
    }

    private static String repeat(String s, int n)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(s);
        return sb.toString();
    }

    /** Причина отклонения контрола как «имя свойства». */
    static String nameControlRejectReason(Control control, String expectedName)
    {
        if (control == null)
            return "control=null"; //$NON-NLS-1$
        if (control.isDisposed())
            return "disposed"; //$NON-NLS-1$
        if (PropertySheetUiContext.isFilterAreaControl(control))
            return "filterArea"; //$NON-NLS-1$
        if (PropertySheetControlInterop.isTwistieOrDecor(control))
            return "twistieOrDecor"; //$NON-NLS-1$
        Point size = control.getSize();
        if (size.x > 0 && size.x < 20)
            return "tooNarrow w=" + size.x; //$NON-NLS-1$
        if (size.y > 48)
            return "tooTall h=" + size.y; //$NON-NLS-1$
        String visible = PropertySheetControlInterop.controlText(control);
        if (!visible.isEmpty() && !expectedName.equals(visible))
            return "textMismatch visible=" + quote(visible); //$NON-NLS-1$
        return "ok"; //$NON-NLS-1$
    }
}

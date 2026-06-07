package tormozit;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** Одна строка свойства: имя слева + редактор значения справа. */
final class PropertySheetPaletteRow
{
    final Control nameControl;
    final Composite rowComposite;
    final Control[] rowControls;
    final String propertyName;
    /** AEF view (LabelViewModel view) для LWT origin/hit-test; может быть null. */
    final Object lwtView;

    PropertySheetPaletteRow(Control nameControl, Composite rowComposite, Control[] rowControls, String propertyName)
    {
        this(nameControl, rowComposite, rowControls, propertyName, null);
    }

    PropertySheetPaletteRow(Control nameControl, Composite rowComposite, Control[] rowControls,
            String propertyName, Object lwtView)
    {
        this.nameControl = nameControl;
        this.rowComposite = rowComposite;
        this.rowControls = rowControls;
        this.propertyName = propertyName != null ? propertyName : ""; //$NON-NLS-1$
        this.lwtView = lwtView;
    }

    boolean isAlive()
    {
        return nameControl != null && !nameControl.isDisposed();
    }
}

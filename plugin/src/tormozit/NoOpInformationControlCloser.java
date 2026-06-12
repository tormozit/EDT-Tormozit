package tormozit;

import org.eclipse.jface.text.AbstractInformationControlManager.IInformationControlCloser;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

/** Заглушка closer hover-менеджера: не закрывает окно при клике/движении мыши снаружи. */
final class NoOpInformationControlCloser implements IInformationControlCloser
{
    @Override
    public void setSubjectControl(Control control)
    {
        // no-op
    }

    @Override
    public void setInformationControl(IInformationControl control)
    {
        // no-op
    }

    @Override
    public void start(Rectangle informationArea)
    {
        // no-op
    }

    @Override
    public void stop()
    {
        // no-op
    }
}

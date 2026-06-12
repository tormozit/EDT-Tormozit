package tormozit;

import org.eclipse.jface.text.IInformationControl;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Скрытая заглушка {@link IInformationControl}: пока hover-инспектор закреплён,
 * редактор не создаёт новый видимый popup.
 */
final class PinnedHoverBlockerInformationControl implements IInformationControl
{
    static final String SHELL_MARKER_KEY = "tormozit.hoverBlockerShell"; //$NON-NLS-1$

    private final Shell shell;

    PinnedHoverBlockerInformationControl(Display display)
    {
        shell = new Shell(display, SWT.ON_TOP);
        shell.setData(SHELL_MARKER_KEY, Boolean.TRUE);
        shell.setSize(1, 1);
        shell.setVisible(false);
    }

    @Override
    public void setInformation(String information)
    {
        // no-op
    }

    @Override
    public void setSizeConstraints(int maxWidth, int maxHeight)
    {
        // no-op
    }

    @Override
    public Point computeSizeHint()
    {
        return new Point(1, 1);
    }

    @Override
    public void setVisible(boolean visible)
    {
        if (!shell.isDisposed())
            shell.setVisible(false);
    }

    @Override
    public void setSize(int width, int height)
    {
        // no-op
    }

    @Override
    public void setLocation(Point location)
    {
        // no-op
    }

    @Override
    public void dispose()
    {
        if (!shell.isDisposed())
            shell.dispose();
    }

    @Override
    public void addDisposeListener(DisposeListener listener)
    {
        if (!shell.isDisposed() && listener != null)
            shell.addDisposeListener(listener);
    }

    @Override
    public void removeDisposeListener(DisposeListener listener)
    {
        if (!shell.isDisposed() && listener != null)
            shell.removeDisposeListener(listener);
    }

    @Override
    public void setForegroundColor(Color foreground)
    {
        // no-op
    }

    @Override
    public void setBackgroundColor(Color background)
    {
        // no-op
    }

    @Override
    public boolean isFocusControl()
    {
        if (shell.isDisposed())
            return false;
        return shell.getDisplay().getActiveShell() == shell;
    }

    @Override
    public void setFocus()
    {
        // no-op
    }

    @Override
    public void addFocusListener(FocusListener listener)
    {
        // no-op
    }

    @Override
    public void removeFocusListener(FocusListener listener)
    {
        // no-op
    }
}

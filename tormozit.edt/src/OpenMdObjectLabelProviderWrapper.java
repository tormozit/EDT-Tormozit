import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Display;

public class OpenMdObjectLabelProviderWrapper implements IStyledLabelProvider {
    private final IStyledLabelProvider original;
    private SmartMatcher matcher;
    private Font boldFont;
    private final Styler matchStyler;

    public OpenMdObjectLabelProviderWrapper(IStyledLabelProvider original, SmartMatcher matcher) {
        this.original = original;
        this.matcher = matcher;
        this.matchStyler = new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle) {
                if (boldFont == null || boldFont.isDisposed()) {
                    Font def = JFaceResources.getDefaultFont();
                    FontData[] fd = def.getFontData();
                    for (FontData f : fd) f.setStyle(f.getStyle() | SWT.BOLD);
                    boldFont = new Font(Display.getDefault(), fd);
                }
                textStyle.font = boldFont;
                textStyle.foreground = Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED);
            }
        };
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    @Override
    public StyledString getStyledText(Object element) {
        StyledString base = original.getStyledText(element);
        if (base == null) return new StyledString();
        String plain = base.getString();
        for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(plain)) {
            base.setStyle(range.offset, range.length, matchStyler);
        }
        return base;
    }

    @Override
    public Image getImage(Object element) {
        return original.getImage(element);
    }

    @Override
    public void dispose() {
        if (boldFont != null && !boldFont.isDisposed()) boldFont.dispose();
        original.dispose();
    }

    @Override
    public void addListener(ILabelProviderListener listener)
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public boolean isLabelProperty(Object element, String property)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void removeListener(ILabelProviderListener listener)
    {
        // TODO Auto-generated method stub
        
    }
}
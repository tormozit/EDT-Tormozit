package tormozit;

import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.ViewerCell;

/**
 * Сохраняет штатный {@link StyledCellLabelProvider} EDT (чекбоксы, иконки, цвета)
 * и добавляет подсветку совпадений фильтра поверх {@link ViewerCell#getStyleRanges()}.
 */
public final class SmartStyledCellLabelWrapper extends StyledCellLabelProvider implements SmartLabelHighlight
{
    private final StyledCellLabelProvider base;
    private SmartMatcher highlightMatcher = new SmartMatcher(""); //$NON-NLS-1$

    public SmartStyledCellLabelWrapper(StyledCellLabelProvider base)
    {
        this.base = base;
    }

    @Override
    public void setHighlightPattern(String pattern)
    {
        highlightMatcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
    }

    @Override
    public void update(ViewerCell cell)
    {
        base.update(cell);
        if (cell == null || highlightMatcher.isEmpty)
            return;
        String text = cell.getText();
        if (text == null || text.isEmpty())
            return;
        SmartMatchHighlight.appendMatchRanges(cell, highlightMatcher.getHighlightRanges(text));
    }

    @Override
    public String getToolTipText(Object element)
    {
        Object tip = Global.invoke(base, "getToolTipText", element); //$NON-NLS-1$
        return tip instanceof String ? (String) tip : null;
    }

    @Override
    public void dispose()
    {
        base.dispose();
    }
}

package tormozit;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

public class SmartCompletionProposal implements ICompletionProposal, ICompletionProposalExtension6
{
    private final ICompletionProposal delegate;
    private final SmartCodeMatcher matcher;

    public SmartCompletionProposal(ICompletionProposal delegate, SmartCodeMatcher matcher)
    {
        this.delegate = delegate;
        this.matcher = matcher;
    }

    public ICompletionProposal getDelegate()
    {
        return delegate;
    }

    @Override
    public StyledString getStyledDisplayString()
    {
        String display = delegate.getDisplayString();
        if (display == null || matcher == null || matcher.isEmpty)
            return new StyledString(display != null ? display : "");

        StyledString result = new StyledString(display);
        for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(display))
        {
            result.setStyle(range.offset, range.length, StyledString.QUALIFIER_STYLER);
        }
        return result;
    }

    @Override public String getDisplayString() { return delegate.getDisplayString(); }
    @Override public Image getImage() { return delegate.getImage(); }
    @Override public Point getSelection(IDocument document) { return delegate.getSelection(document); }
    @Override public String getAdditionalProposalInfo() { return delegate.getAdditionalProposalInfo(); }
    @Override public void apply(IDocument document) { delegate.apply(document); }
    
    // === ДОБАВЛЕНО ===
    @Override
    public IContextInformation getContextInformation()
    {
        return delegate.getContextInformation();
    }
}
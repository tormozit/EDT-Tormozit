package tormozit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

public class SmartCompletionProposal implements 
    ICompletionProposal, 
    ICompletionProposalExtension3,
    ICompletionProposalExtension5,
    ICompletionProposalExtension6
{
    private final ICompletionProposal delegate;
    private final SmartCodeMatcher matcher;

    public SmartCompletionProposal(ICompletionProposal delegate, SmartCodeMatcher matcher)
    {
        this.delegate = delegate;
        this.matcher = matcher;
    }

    public ICompletionProposal getDelegate() { return delegate; }

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
    @Override public IContextInformation getContextInformation() { return delegate.getContextInformation(); }
    @Override public void apply(IDocument document) { delegate.apply(document); }

    @Override
    public String getAdditionalProposalInfo()
    {
        if (delegate instanceof ICompletionProposalExtension5)
        {
            Object info = ((ICompletionProposalExtension5) delegate)
                .getAdditionalProposalInfo(new NullProgressMonitor());
            if (info instanceof String) return (String) info;
            if (info instanceof StyledString) return ((StyledString) info).getString();
            return info != null ? info.toString() : null;
        }
        return delegate.getAdditionalProposalInfo();
    }

    @Override
    public IInformationControlCreator getInformationControlCreator()
    {
        if (delegate instanceof ICompletionProposalExtension3)
            return ((ICompletionProposalExtension3) delegate).getInformationControlCreator();
        return null;
    }

    @Override
    public Object getAdditionalProposalInfo(IProgressMonitor monitor)
    {
        return ((ICompletionProposalExtension5) delegate).getAdditionalProposalInfo(monitor);
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset)
    {
        return ((ICompletionProposalExtension3) delegate).getPrefixCompletionText(document, completionOffset);
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset)
    {
        return ((ICompletionProposalExtension3) delegate).getPrefixCompletionStart(document, completionOffset);
    }

}
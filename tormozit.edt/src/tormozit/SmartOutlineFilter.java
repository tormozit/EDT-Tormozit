package tormozit;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

public class SmartOutlineFilter extends ViewerFilter {
    
    private SmartMatcher matcher;
    private final ILabelProvider labelProvider;
    private final boolean pruneEmptyBranches;
    private final boolean codeMatcher;
    
    private final Map<Object, Integer> namePremiumCache = new HashMap<>();
    private final Map<Object, Integer> paramPremiumCache = new HashMap<>();

    public SmartOutlineFilter(ILabelProvider labelProvider) {
        this(labelProvider, false, false);
    }

    public SmartOutlineFilter(ILabelProvider labelProvider, boolean pruneEmptyBranches, boolean codeMatcher) {
        this.labelProvider = labelProvider;
        this.pruneEmptyBranches = pruneEmptyBranches;
        this.codeMatcher = codeMatcher;
        this.matcher = newMatcher("");
    }

    private SmartMatcher newMatcher(String pattern) {
        return codeMatcher ? new SmartCodeMatcher(pattern) : new SmartMatcher(pattern);
    }

    public void setPattern(String newPattern) {
        this.matcher = newMatcher(newPattern);
    }

    public void refreshPattern(String newPattern) {
        namePremiumCache.clear(); 
        paramPremiumCache.clear();
        setPattern(newPattern);
    }

    public Map<Object, Integer> getNamePremiumCache() {
        return namePremiumCache;
    }

    public Map<Object, Integer> getParamPremiumCache() {
        return paramPremiumCache;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        boolean hasChildren = false;
        TreeViewer treeViewer = viewer instanceof TreeViewer ? (TreeViewer) viewer : null;
        if (treeViewer != null) {
            Object cp = treeViewer.getContentProvider();
            if (cp instanceof ITreeContentProvider)
                hasChildren = ((ITreeContentProvider) cp).hasChildren(element);
        }

        String text = labelProvider.getText(element);

        if (!hasChildren) {
            if (!matcher.matches(text))
                return false;
        }
        else if (pruneEmptyBranches && treeViewer != null)
        {
            if (!matcher.matches(text) && !hasMatchingDescendant(treeViewer, element))
                return false;
        }

        int namePremium = matcher.computeNamePremium(text);
        int paramPremium = matcher.computeParamPremium(text);
        namePremiumCache.put(element, namePremium);
        paramPremiumCache.put(element, paramPremium);

        return true;
    }

    private boolean hasMatchingDescendant(TreeViewer viewer, Object parent)
    {
        Object cp = viewer.getContentProvider();
        if (!(cp instanceof ITreeContentProvider))
            return false;
        ITreeContentProvider tcp = (ITreeContentProvider) cp;
        for (Object child : tcp.getChildren(parent))
        {
            String text = labelProvider.getText(child);
            if (!tcp.hasChildren(child))
            {
                if (matcher.matches(text))
                    return true;
            }
            else if (matcher.matches(text) || hasMatchingDescendant(viewer, child))
            {
                return true;
            }
        }
        return false;
    }
}

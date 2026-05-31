import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import java.util.HashMap;
import java.util.Map;

public class OpenMdObjectFilter extends ViewerFilter {
    private final Object mdObjectsEngine;
    private final Object dialog;
    private SmartMatcher matcher;
    private final Map<Object, Integer> namePremiumCache = new HashMap<>();
    private final Map<Object, Integer> paramPremiumCache = new HashMap<>();

    public OpenMdObjectFilter(Object mdObjectsEngine, Object dialog) {
        this.mdObjectsEngine = mdObjectsEngine;
        this.dialog = dialog;
        this.matcher = new SmartMatcher("");
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
        namePremiumCache.clear();
        paramPremiumCache.clear();
    }

    public SmartMatcher getMatcher() { return matcher; }
    public Map<Object, Integer> getNamePremiumCache() { return namePremiumCache; }
    public Map<Object, Integer> getParamPremiumCache() { return paramPremiumCache; }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        String pattern = matcher.fullPattern; // поле public? нужно сделать getter, либо хранить отдельно
        boolean hasSpaces = pattern != null && pattern.contains(" ");

        String name = getName(element);
        String fullName = getFullName(element);
        String textToMatch = hasSpaces ? fullName : name;
        if (textToMatch == null) return false;

        if (!matcher.matches(textToMatch)) return false;

        int namePremium = matcher.computeNamePremium(name != null ? name : "");
        int paramPremium = matcher.computeParamPremium(fullName != null ? fullName : "");
        namePremiumCache.put(element, namePremium);
        paramPremiumCache.put(element, paramPremium);
        return true;
    }

    private String getName(Object element) {
        try {
            Object descr = Global.getField(element, "description");
            if (descr == null) return "";
            return (String) Global.invoke(mdObjectsEngine, "getName", descr);
        } catch (Exception e) { return ""; }
    }

    private String getFullName(Object element) {
        try {
            Object descr = Global.getField(element, "description");
            if (descr == null) return "";
            return (String) Global.invoke(mdObjectsEngine, "getFullName", descr);
        } catch (Exception e) { return ""; }
    }
}
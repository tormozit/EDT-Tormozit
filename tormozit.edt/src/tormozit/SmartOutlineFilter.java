package tormozit;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

public class SmartOutlineFilter extends ViewerFilter {
    
    private SmartMatcher matcher;
    private final ILabelProvider labelProvider;
    
    // ДВА КЭША: Раздельное хранение рейтингов для Имен и Параметров
    private final Map<Object, Integer> namePremiumCache = new HashMap<>();
    private final Map<Object, Integer> paramPremiumCache = new HashMap<>();

    public SmartOutlineFilter(ILabelProvider labelProvider) {
        this.labelProvider = labelProvider;
        this.matcher = new SmartMatcher("");
    }

    public void setPattern(String newPattern) {
        this.matcher = new SmartMatcher(newPattern);
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
        // Проверяем, является ли элемент родительским (имеет ли вложенные узлы)
        boolean hasChildren = false;
        if (viewer instanceof TreeViewer) {
            Object cp = ((TreeViewer) viewer).getContentProvider();
            if (cp instanceof org.eclipse.jface.viewers.ITreeContentProvider) {
                hasChildren = ((org.eclipse.jface.viewers.ITreeContentProvider) cp).hasChildren(element);
            }
        }

        String text = labelProvider.getText(element);
        
        // Если это терминальный (конечный) элемент и он не подходит под паттерн — скрываем его
        if (!hasChildren && !matcher.matches(text)) {
            return false;
        }

        // Вычисляем и сохраняем метрики для правильной сортировки компаратором (как для листьев, так и для родителей)
        int namePremium = matcher.computeNamePremium(text);
        int paramPremium = matcher.computeParamPremium(text);
        
        namePremiumCache.put(element, namePremium);
        paramPremiumCache.put(element, paramPremium);

        return true;
    }
}
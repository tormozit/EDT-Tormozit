package org.eclipse.ui.dialogs;

import java.util.List;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import tormozit.Global;
import tormozit.SmartMatcher;

public class OpenMdObjectItemsFilter extends FilteredItemsSelectionDialog.ItemsFilter {

    private SmartMatcher matcher;
    private final ILabelProvider labelProvider;
    private final FilteredItemsSelectionDialog dialog;

    public OpenMdObjectItemsFilter(FilteredItemsSelectionDialog dialog,
                                    ILabelProvider labelProvider,
                                    String pattern) {
        dialog.super();
        this.dialog = dialog;
        this.labelProvider = labelProvider;
        this.matcher = new SmartMatcher(pattern);
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    @Override
    public boolean isConsistentItem(Object item) {
        return true;
    }

    @Override
    public boolean matchItem(Object item) {
        if (matcher.isEmpty) {
            return isHistoryElement(item);
        }

        String objectName = getObjectName(labelProvider.getText(item));
        return matcher.matches(objectName);
    }

    /**
     * Извлекает чистое имя объекта из полного текста (до " - ").
     * Единая точка правды для фильтрации и подсветки.
     */
    public static String getObjectName(String fullText) {
        if (fullText == null) return "";
        int dashIdx = fullText.indexOf(" - ");
        return dashIdx >= 0 ? fullText.substring(0, dashIdx) : fullText;
    }

    private boolean isHistoryElement(Object item) {
        Object history = Global.invoke(dialog, "getSelectionHistory");
        if (history == null) return false;
        List<?> historyItems = (List<?>) Global.getField(history, "items");
        if (historyItems == null) return false;

        for (Object historyItem : historyItems) {
            if (isSameItem(item, historyItem)) return true;
        }
        return false;
    }

    private boolean isSameItem(Object a, Object b) {
        if (a == b) return true;
        Object descA = Global.getField(a, "description");
        Object descB = Global.getField(b, "description");
        if (descA == null || descB == null) return false;
        Object uriA = Global.invoke(descA, "getEObjectURI");
        Object uriB = Global.invoke(descB, "getEObjectURI");
        return uriA != null && uriA.equals(uriB);
    }

    @Override
    public String getPattern() {
        return matcher.isEmpty ? " " : matcher.fullPattern;
    }

    @Override
    public boolean isSubFilter(FilteredItemsSelectionDialog.ItemsFilter filter) {
        return false;
    }

    @Override
    public boolean isCamelCasePattern() {
        return false;
    }
}
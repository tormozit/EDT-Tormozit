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

        // === ИСКАТЬ ТОЛЬКО ПО ЧИСТОМУ ИМЕНИ (до " - ") ===
        String text = labelProvider.getText(item);
        String objectName = text;
        int dashIdx = text != null ? text.indexOf(" - ") : -1;
        if (dashIdx >= 0) {
            objectName = text.substring(0, dashIdx);
        }

        if (objectName != null && matcher.matches(objectName)) {
            return true;
        }
        return false;
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
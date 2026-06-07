package tormozit;

/**
 * Чтение значений AEF ValueViewModel EDT (TextViewModel, CheckboxViewModel, ComboSelectViewModel…).
 * Значения живут на viewModel, а не только в {@code getModel().getValue()}.
 */
final class PropertySheetAefValues
{
    private PropertySheetAefValues() {}

    static String readValue(Object valueVm)
    {
        if (valueVm == null)
            return ""; //$NON-NLS-1$
        Object checked = Global.invoke(valueVm, "isChecked"); //$NON-NLS-1$
        if (checked instanceof Boolean)
            return ((Boolean) checked).booleanValue() ? "true" : "false"; //$NON-NLS-1$ //$NON-NLS-2$
        Object selectedItem = Global.invoke(valueVm, "getSelectedItem"); //$NON-NLS-1$
        if (selectedItem != null)
        {
            String label = labelOf(selectedItem);
            if (!label.isEmpty())
                return label;
        }
        for (String method : new String[] {
                "getText", "getValue", "getEditingValue", "getPresentation", "getDisplayValue" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        })
        {
            String text = asString(Global.invoke(valueVm, method));
            if (!text.isEmpty())
                return text;
        }
        Object model = Global.invoke(valueVm, "getModel"); //$NON-NLS-1$
        if (model != null)
        {
            for (String method : new String[] {
                    "getSelectedIndex", "getSelectionIndex", "getValue", "getSingleValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "getText", "getPresentation", "getLabel", "getDisplayValue" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            })
            {
                String text = asString(Global.invoke(model, method));
                if (!text.isEmpty())
                    return text;
            }
        }
        return ""; //$NON-NLS-1$
    }

    static Boolean readBoolean(Object valueVm, Object valueView)
    {
        if (valueVm != null)
        {
            Object checked = Global.invoke(valueVm, "isChecked"); //$NON-NLS-1$
            if (checked instanceof Boolean)
                return (Boolean) checked;
        }
        return PropertySheetControlInterop.booleanSelectionFromView(valueView, valueVm);
    }

    static String readComboSelection(Object valueVm, java.util.List<String> items)
    {
        if (valueVm == null)
            return ""; //$NON-NLS-1$
        Object selectedItem = Global.invoke(valueVm, "getSelectedItem"); //$NON-NLS-1$
        if (selectedItem != null)
        {
            String label = labelOf(selectedItem);
            if (!label.isEmpty())
            {
                if (items != null && !items.isEmpty())
                {
                    int idx = indexOfIgnoreCase(items, label);
                    if (idx >= 0)
                        return items.get(idx);
                }
                return label;
            }
        }
        return readValue(valueVm);
    }

    private static String labelOf(Object item)
    {
        if (item == null)
            return ""; //$NON-NLS-1$
        if (item instanceof String)
            return (String) item;
        for (String method : new String[] {
                "getText", "getLabel", "getName", "getPresentation", "getDisplayName", "getLiteral" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        })
        {
            String text = asString(Global.invoke(item, method));
            if (!text.isEmpty())
                return text;
        }
        String asString = item.toString();
        if (asString != null && !asString.isEmpty() && !asString.contains("@")) //$NON-NLS-1$
            return asString;
        return ""; //$NON-NLS-1$
    }

    private static String asString(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof String)
            return (String) value;
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        return labelOf(value);
    }

    private static int indexOfIgnoreCase(java.util.List<String> items, String value)
    {
        if (items == null || value == null)
            return -1;
        for (int i = 0; i < items.size(); i++)
        {
            if (value.equalsIgnoreCase(items.get(i)))
                return i;
        }
        return -1;
    }
}

package tormozit;

import org.eclipse.jface.viewers.IStructuredSelection;

/** Отпечаток текущего источника палитры свойств (выделение в MdPropertyPaletteModel). */
final class PropertySheetSourceKey
{
    private PropertySheetSourceKey() {}

    static String fingerprint(Object page)
    {
        if (page == null)
            return ""; //$NON-NLS-1$
        Object selection = selectionOf(page);
        if (selection == null)
            return ""; //$NON-NLS-1$
        if (selection instanceof IStructuredSelection)
        {
            IStructuredSelection structured = (IStructuredSelection) selection;
            Object[] elements = structured.toArray();
            if (elements.length == 0)
                return "empty"; //$NON-NLS-1$
            StringBuilder sb = new StringBuilder(elements.length * 32);
            for (Object element : elements)
            {
                if (element == null)
                    continue;
                sb.append(element.getClass().getName()).append('#')
                        .append(System.identityHashCode(element)).append(';');
            }
            return sb.toString();
        }
        return selection.getClass().getName() + '#' + System.identityHashCode(selection);
    }

    private static Object selectionOf(Object page)
    {
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        if (paletteModel != null)
        {
            Object fromModel = Global.invoke(paletteModel, "getSelection"); //$NON-NLS-1$
            if (fromModel != null)
                return fromModel;
        }
        return Global.invoke(page, "getCurrentSelection"); //$NON-NLS-1$
    }
}

package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.widgets.Control;

/**
 * Обход дерева view-model палитры свойств EDT через {@code ITreeTransformation}.
 */
final class PropertySheetViewModelTree
{
    static final class Entry
    {
        final Kind kind;
        final String name;
        final String value;
        final Object viewModel;
        final Object valueViewModel;
        final Object valueView;

        Entry(Kind kind, String name, String value)
        {
            this(kind, name, value, null, null, null);
        }

        Entry(Kind kind, String name, String value, Object viewModel)
        {
            this(kind, name, value, viewModel, null, null);
        }

        Entry(Kind kind, String name, String value, Object viewModel,
                Object valueViewModel, Object valueView)
        {
            this.kind = kind;
            this.name = name != null ? name : ""; //$NON-NLS-1$
            this.value = value != null ? value : ""; //$NON-NLS-1$
            this.viewModel = viewModel;
            this.valueViewModel = valueViewModel;
            this.valueView = valueView;
        }
    }

    enum Kind
    {
        SECTION,
        PROPERTY
    }

    static final class ValuePair
    {
        final Object valueVm;
        final Object valueView;
        final String path;

        ValuePair(Object valueVm, Object valueView, String path)
        {
            this.valueVm = valueVm;
            this.valueView = valueView;
            this.path = path != null ? path : ""; //$NON-NLS-1$
        }
    }

    private PropertySheetViewModelTree() {}

    static List<Entry> collect(Object transformation)
    {
        if (transformation == null)
            return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        Object roots = Global.invoke(transformation, "getRoots"); //$NON-NLS-1$
        appendChildren(out, transformation, roots);
        return out;
    }

    static List<Entry> collect(Object page, Object transformation)
    {
        List<Entry> out = collect(transformation);
        if (!out.isEmpty())
        {
            enrichEntriesWithViews(page, out);
            List<Entry> rendererEntries = collectFromRenderer(page);
            mergePropertyValues(out, rendererEntries);
            enrichEntriesWithViews(page, out);
            if (entriesMissingValueViewModel(out))
            {
                List<Entry> scannerEntries = collectFromScanner(page);
                mergePropertyValues(out, scannerEntries);
                enrichEntriesWithViews(page, out);
            }
            PropertySheetDebug.uiVerbose("viewModelTree transformation enriched entries=" + out.size() //$NON-NLS-1$
                    + " nonEmptyValues=" + countNonEmptyPropertyValues(out) //$NON-NLS-1$
                    + " missingVm=" + countMissingValueViewModels(out)); //$NON-NLS-1$
            PropertySheetDebug.uiVerbose("viewModelTree transformation entries=" + out.size()); //$NON-NLS-1$
            return out;
        }

        out = collectFromRenderer(page);
        if (!out.isEmpty())
        {
            enrichEntriesWithViews(page, out);
            PropertySheetDebug.uiVerbose("viewModelTree renderer entries=" + out.size()); //$NON-NLS-1$
            return out;
        }

        out = collectFromScanner(page);
        enrichEntriesWithViews(page, out);
        PropertySheetDebug.uiVerbose("viewModelTree scanner entries=" + out.size()); //$NON-NLS-1$
        return out;
    }

    private static void appendChildren(List<Entry> out, Object transformation, Object nodes)
    {
        Iterator<?> it = toIterator(nodes);
        if (it == null)
            return;
        while (it.hasNext())
        {
            Object vm = it.next();
            if (vm == null)
                continue;
            if (isFieldRowViewModel(vm))
            {
                Entry entry = toPropertyEntry(vm);
                if (entry != null)
                    out.add(entry);
                continue;
            }
            String sectionName = SmartTreeElementLabels.resolve(vm, null);
            if (!sectionName.isEmpty())
                out.add(new Entry(Kind.SECTION, sectionName, "", vm)); //$NON-NLS-1$
            Object children = Global.invoke(transformation, "getChildren", vm); //$NON-NLS-1$
            appendChildren(out, transformation, children);
        }
    }

    static Entry toPropertyEntry(Object fieldRow)
    {
        if (fieldRow == null)
            return null;
        String name = ""; //$NON-NLS-1$
        String value = ""; //$NON-NLS-1$
        Object valueVm = null;
        Object valueView = null;
        Object children = Global.invoke(fieldRow, "getChildren"); //$NON-NLS-1$
        Iterator<?> it = toIterator(children);
        if (it != null)
        {
            while (it.hasNext())
            {
                Object child = it.next();
                if (child == null)
                    continue;
                String cn = child.getClass().getName();
                if (cn.contains("LabelViewModel") || cn.contains("LabelItem")) //$NON-NLS-1$ //$NON-NLS-2$
                    name = SmartTreeElementLabels.resolve(child, null);
                else if (cn.contains("ValueViewModel") || cn.contains("ValueItem") || isValueCandidate(child)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (valueVm == null)
                        valueVm = child;
                    String candidate = resolveValueText(child);
                    if (!candidate.isEmpty())
                        value = candidate;
                }
                else if (value.isEmpty())
                {
                    String candidate = resolveValueText(child);
                    if (!candidate.isEmpty())
                        value = candidate;
                }
            }
        }
        if (valueVm == null)
            valueVm = findValueViewModel(fieldRow, children);
        if (name.isEmpty())
            name = SmartTreeElementLabels.resolve(fieldRow, null);
        if (value.isEmpty() && children != null)
            value = resolveValueText(children);
        if (valueVm == null)
            valueVm = Global.invoke(fieldRow, "getValue"); //$NON-NLS-1$
        if (value.isEmpty() && valueVm != null)
            value = resolveValueText(valueVm);
        if (value.isEmpty())
        {
            // AEF IValue path: fieldRow.getModel().getValue()
            Object model = Global.invoke(fieldRow, "getModel"); //$NON-NLS-1$
            if (model != null)
            {
                Object modelValue = Global.invoke(model, "getValue"); //$NON-NLS-1$
                value = objectText(modelValue);
            }
        }
        if (name.isEmpty())
            return null;
        return new Entry(Kind.PROPERTY, name, value, fieldRow, valueVm, valueView);
    }

    private static Object findValueViewModel(Object fieldRow, Object children)
    {
        if (fieldRow == null)
            return null;
        for (String method : new String[] {
                "getValueViewModel", "getEditorViewModel", "getControlViewModel", "getTextViewModel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "getValue", "getEditor" //$NON-NLS-1$ //$NON-NLS-2$
        })
        {
            Object direct = Global.invoke(fieldRow, method);
            if (direct != null && !isLabelViewModel(direct))
                return direct;
        }
        for (String field : new String[] {
                "valueViewModel", "editorViewModel", "controlViewModel", "textViewModel", "value", "editor" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        })
        {
            Object direct = Global.getField(fieldRow, field);
            if (direct != null && !isLabelViewModel(direct))
                return direct;
        }
        Iterator<?> it = toIterator(children);
        if (it == null)
            return null;
        Object fallback = null;
        while (it.hasNext())
        {
            Object child = it.next();
            if (child == null || isLabelViewModel(child))
                continue;
            if (isValueCandidate(child))
                return child;
            if (fallback == null)
                fallback = child;
        }
        return fallback;
    }

    static Object resolveValueView(Object page, Object valueViewModel)
    {
        if (page == null || valueViewModel == null)
            return null;
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null;
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (mapObj instanceof Map)
            return ((Map<?, ?>) mapObj).get(valueViewModel);
        return null;
    }

    static Object resolveEntryValueViewModel(Entry entry)
    {
        return resolveEntryValueViewModel(entry, null);
    }

    static Object resolveEntryValueViewModel(Entry entry, Object page)
    {
        return resolveValuePair(entry, page, null).valueVm;
    }

    static Object resolveEntryValueView(Entry entry, Object page)
    {
        return resolveValuePair(entry, page, null).valueView;
    }

    static ValuePair resolveValuePair(Entry entry, Object page, Object labelViewHint)
    {
        if (entry == null)
            return new ValuePair(null, null, "entry=null"); //$NON-NLS-1$
        if (entry.valueViewModel != null)
        {
            Object view = entry.valueView != null ? entry.valueView : resolveValueView(page, entry.valueViewModel);
            return new ValuePair(entry.valueViewModel, view, "entry.vm"); //$NON-NLS-1$
        }
        ValuePair fromFieldRow = valuePairFromFieldRow(entry.viewModel, page);
        if (fromFieldRow.valueVm != null)
            return fromFieldRow;
        if (labelViewHint != null && page != null)
        {
            ValuePair fromLabel = findValuePairFromLabelView(page, labelViewHint);
            if (fromLabel.valueVm != null)
                return fromLabel;
        }
        if (page != null && entry.name != null && !entry.name.isEmpty())
        {
            ValuePair byName = findValuePairByPropertyName(page, entry.name, fieldRowOf(entry.viewModel));
            if (byName.valueVm != null)
                return byName;
        }
        if (page == null)
            return new ValuePair(null, null, "page=null"); //$NON-NLS-1$
        return new ValuePair(null, null, "miss:fieldRow,labelView,byName"); //$NON-NLS-1$
    }

    private static ValuePair valuePairFromFieldRow(Object fieldRowVm, Object page)
    {
        fieldRowVm = fieldRowOf(fieldRowVm);
        if (fieldRowVm == null)
            return new ValuePair(null, null, "fieldRow=null"); //$NON-NLS-1$
        Object children = Global.invoke(fieldRowVm, "getChildren"); //$NON-NLS-1$
        Object fromChildren = findValueViewModel(fieldRowVm, children);
        if (fromChildren != null)
            return pairFromVm(page, fromChildren, "fieldRow.children"); //$NON-NLS-1$
        if (page != null)
        {
            Object transformation = activeTransformation(page);
            Object fromTree = transformation != null
                    ? Global.invoke(transformation, "getChildren", fieldRowVm) : null; //$NON-NLS-1$
            fromChildren = findValueViewModel(fieldRowVm, fromTree);
            if (fromChildren != null)
                return pairFromVm(page, fromChildren, "tree.children"); //$NON-NLS-1$
            Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
            Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
            Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
            if (mapObj instanceof Map)
            {
                ValuePair fromMap = valuePairFromFieldRowMap(fieldRowVm, page, (Map<?, ?>) mapObj);
                if (fromMap.valueVm != null)
                    return fromMap;
            }
        }
        return new ValuePair(null, null, "fieldRow.miss"); //$NON-NLS-1$
    }

    private static ValuePair valuePairFromFieldRowMap(Object fieldRowVm, Object page, Map<?, ?> map)
    {
        if (fieldRowVm == null || map == null || map.isEmpty())
            return new ValuePair(null, null, "fieldRow.map=empty"); //$NON-NLS-1$
        for (Object key : map.keySet())
        {
            if (key == null || isLabelViewModel(key))
                continue;
            Object parent = parentOf(key);
            if (parent == fieldRowVm || (parent != null && parent.equals(fieldRowVm)))
                return new ValuePair(key, map.get(key), "fieldRow.map"); //$NON-NLS-1$
        }
        return new ValuePair(null, null, "fieldRow.map=miss"); //$NON-NLS-1$
    }

    private static ValuePair pairFromVm(Object page, Object valueVm, String path)
    {
        if (valueVm == null)
            return new ValuePair(null, null, path + ":vm=null"); //$NON-NLS-1$
        Object view = page != null ? resolveValueView(page, valueVm) : null;
        return new ValuePair(valueVm, view, path);
    }

    private static ValuePair findValuePairFromLabelView(Object page, Object labelView)
    {
        Object labelVm = resolveViewModel(labelView);
        if (labelVm == null)
            return new ValuePair(null, null, "labelView.vm=null"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return new ValuePair(null, null, "labelView.map=null"); //$NON-NLS-1$
        Map<?, ?> map = (Map<?, ?>) mapObj;
        Object valueVm = findSiblingValueViewModel(labelVm, collectValueModels(map));
        if (valueVm != null)
            return new ValuePair(valueVm, map.get(valueVm), "labelView.sibling"); //$NON-NLS-1$
        return new ValuePair(null, null, "labelView.sibling=miss parent=" //$NON-NLS-1$
                + PropertySheetDebug.safe(parentOf(labelVm)));
    }

    private static ValuePair findValuePairByPropertyName(Object page, String propertyName, Object fieldRowVm)
    {
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map))
            return new ValuePair(null, null, "byName.map=null"); //$NON-NLS-1$
        Map<?, ?> map = (Map<?, ?>) mapObj;
        List<Object> valueModels = collectValueModels(map);
        for (Object key : map.keySet())
        {
            if (key == null)
                continue;
            String cn = key.getClass().getName();
            if (!cn.contains("LabelViewModel") && !cn.contains("LabelItem")) //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            if (!propertyName.equals(SmartTreeElementLabels.resolve(key, null)))
                continue;
            Object sibling = findSiblingValueViewModel(key, valueModels);
            if (sibling != null)
                return new ValuePair(sibling, map.get(sibling), "byName.sibling"); //$NON-NLS-1$
            Object fieldRow = fieldRowVm != null ? fieldRowVm : parentOf(key);
            if (fieldRow != null)
            {
                ValuePair fromRow = valuePairFromFieldRow(fieldRow, page);
                if (fromRow.valueVm != null)
                {
                    Object view = map.get(fromRow.valueVm);
                    return new ValuePair(fromRow.valueVm, view != null ? view : fromRow.valueView,
                            "byName.fieldRowChildren"); //$NON-NLS-1$
                }
            }
            PropertySheetDebug.valueControl("RESOLVE labelOK sibling=MISS " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                    + " labelParent=" + PropertySheetDebug.safe(parentOf(key)) //$NON-NLS-1$
                    + " values=" + valueModels.size()); //$NON-NLS-1$
            return new ValuePair(null, null, "byName.sibling=miss"); //$NON-NLS-1$
        }
        return new ValuePair(null, null, "byName.label=miss"); //$NON-NLS-1$
    }

    private static List<Object> collectValueModels(Map<?, ?> map)
    {
        List<Object> valueModels = new ArrayList<>();
        for (Object key : map.keySet())
        {
            if (key != null && isValueCandidate(key))
                valueModels.add(key);
        }
        return valueModels;
    }

    static String resolveEntryDisplay(Object page, Entry entry, String nativeFieldText)
    {
        if (entry == null)
            return nativeFieldText != null ? nativeFieldText : ""; //$NON-NLS-1$
        String nativeText = nativeFieldText != null ? nativeFieldText : ""; //$NON-NLS-1$
        // Нативная палитра («Старая» вкладка) — источник истины; entry.value часто съезжает при index-fallback.
        if (!nativeText.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(nativeText))
            return nativeText;
        String value = entry.value != null ? entry.value : ""; //$NON-NLS-1$
        if (!value.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(value))
            return value;
        Object valueVm = resolveEntryValueViewModel(entry, page);
        Object valueView = entry.valueView;
        if (valueView == null && page != null && valueVm != null)
            valueView = resolveValueView(page, valueVm);
        if (valueView != null)
        {
            value = PropertySheetControlInterop.displayTextFromView(valueView, valueVm);
            if (!value.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(value))
                return value;
        }
        if (valueVm != null)
        {
            value = PropertySheetAefValues.readValue(valueVm);
            if (!value.isEmpty())
                return value;
            value = resolveValueText(valueVm, valueView);
            if (!value.isEmpty())
                return value;
        }
        return ""; //$NON-NLS-1$
    }

    static boolean isFieldRowViewModel(Object viewModel)
    {
        if (viewModel == null)
            return false;
        String cn = viewModel.getClass().getName();
        if (cn.contains("FieldViewModel") || cn.contains("PropertyViewModel") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("FieldItem") || cn.contains("PropertyItem")) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        String text = SmartTreeElementLabels.resolve(viewModel, null);
        if (!text.isEmpty())
            return false;
        Object children = Global.invoke(viewModel, "getChildren"); //$NON-NLS-1$
        Iterator<?> it = toIterator(children);
        if (it == null)
            return false;
        while (it.hasNext())
        {
            Object child = it.next();
            if (child == null)
                continue;
            String ccn = child.getClass().getName();
            if (ccn.contains("LabelViewModel") || ccn.contains("ValueViewModel") //$NON-NLS-1$ //$NON-NLS-2$
                    || ccn.contains("LabelItem") || ccn.contains("ValueItem")) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        }
        return false;
    }

    static Object activeTransformation(Object page)
    {
        if (page == null)
            return null;
        Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null;
        Object fromRenderer = renderer != null ? Global.invoke(renderer, "getTreeTransformation") : null; //$NON-NLS-1$
        if (fromRenderer != null)
            return fromRenderer;
        return palette != null ? Global.invoke(palette, "getTransformation") : null; //$NON-NLS-1$
    }

    private static List<Entry> collectFromRenderer(Object page)
    {
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        Object mapObj = renderer != null ? Global.getField(renderer, "viewModelToView") : null; //$NON-NLS-1$
        if (!(mapObj instanceof Map))
        {
            PropertySheetDebug.uiVerbose("viewModelTree renderer map=" + PropertySheetDebug.safe(mapObj)); //$NON-NLS-1$
            return Collections.emptyList();
        }

        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Object> labelModels = new ArrayList<>();
        List<Object> valueModels = new ArrayList<>();
        List<Object> valueViews = new ArrayList<>();
        Map<Object, Object> labelsByParent = new java.util.LinkedHashMap<>();
        Map<Object, Object> valuesByParent = new java.util.LinkedHashMap<>();
        Map<Object, Object> valueViewsByParent = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> mapEntry : ((Map<?, ?>) mapObj).entrySet())
        {
            Object key = mapEntry.getKey();
            if (key == null)
                continue;
            String cn = key.getClass().getName();
            if (cn.contains("LabelViewModel") || cn.contains("LabelItem")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                labelModels.add(key);
                Object parent = parentOf(key);
                if (parent != null)
                    labelsByParent.putIfAbsent(parent, key);
            }
            else if (isValueCandidate(key))
            {
                valueModels.add(key);
                valueViews.add(mapEntry.getValue());
                Object parent = parentOf(key);
                if (parent != null)
                {
                    valuesByParent.putIfAbsent(parent, key);
                    valueViewsByParent.putIfAbsent(parent, mapEntry.getValue());
                }
            }
        }
        for (Map.Entry<Object, Object> labelEntry : labelsByParent.entrySet())
        {
            Object labelVm = labelEntry.getValue();
            String name = SmartTreeElementLabels.resolve(labelVm, null);
            if (name.isEmpty() || !seen.add(name))
                continue;
            Object valueVm = valuesByParent.get(labelEntry.getKey());
            Object valueView = valueViewsByParent.get(labelEntry.getKey());
            out.add(new Entry(Kind.PROPERTY, name, resolveValueText(valueVm, valueView), labelVm, valueVm, valueView));
        }

        pairMissingValuesByParent(out, labelModels, valueModels, valueViews);

        if (entriesMissingValueViewModel(out))
        {
            PropertySheetDebug.uiVerbose("viewModelTree still missing vm after index pass labels=" //$NON-NLS-1$
                    + labelModels.size() + " values=" + valueModels.size() //$NON-NLS-1$
                    + " missing=" + countMissingValueViewModels(out)); //$NON-NLS-1$
        }

        PropertySheetDebug.uiVerbose("viewModelTree renderer mapSize=" + ((Map<?, ?>) mapObj).size() //$NON-NLS-1$
                + " labels=" + out.size()); //$NON-NLS-1$
        int nonEmpty = 0;
        for (Entry entry : out)
            if (entry.value != null && !entry.value.isEmpty())
                nonEmpty++;
        if (nonEmpty == 0 && !out.isEmpty())
            PropertySheetDebug.problem("viewModelTree values EMPTY labels=" + labelModels.size() //$NON-NLS-1$
                    + " valueCandidates=" + valueModels.size() //$NON-NLS-1$
                    + " mapSize=" + ((Map<?, ?>) mapObj).size()); //$NON-NLS-1$
        return out;
    }

    private static boolean isValueCandidate(Object viewModel)
    {
        if (viewModel == null)
            return false;
        String cn = viewModel.getClass().getName();
        if (cn.contains("LabelViewModel") || cn.contains("LabelItem") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("ScrolledComposite") || cn.contains("CompositeContent") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("CompositeViewModel") || cn.contains("Section") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("Separator") || cn.contains("Image")) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        return cn.contains("Value") || cn.contains("Text") || cn.contains("Combo") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("String") || cn.contains("Input") || cn.contains("Editable") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Select") || cn.contains("Preview") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("Check") || cn.contains("Boolean") || cn.contains("Spinner") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Link") || cn.contains("Button") || cn.contains("Editor") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Field"); //$NON-NLS-1$
    }

    private static boolean allValuesEmpty(List<Entry> entries)
    {
        if (entries == null || entries.isEmpty())
            return true;
        for (Entry entry : entries)
        {
            if (entry != null && entry.value != null && !entry.value.isEmpty())
                return false;
        }
        return true;
    }

    private static Object parentOf(Object viewModel)
    {
        if (viewModel == null)
            return null;
        for (String method : new String[] {
                "getParent", "getParentViewModel", "getContainer", "getOwner", "eContainer" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        })
        {
            Object parent = Global.invoke(viewModel, method);
            if (parent != null && parent != viewModel)
                return parent;
        }
        for (String field : new String[] {
                "parent", "parentViewModel", "container", "owner" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        })
        {
            Object parent = Global.getField(viewModel, field);
            if (parent != null && parent != viewModel)
                return parent;
        }
        return null;
    }

    private static List<Entry> collectFromScanner(Object page)
    {
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        if (scene == null)
            return Collections.emptyList();
        List<PropertySheetPaletteRow> rows = PropertySheetPaletteScanner.scan(scene, page);
        List<Entry> out = new ArrayList<>(rows.size());
        Set<String> seen = new LinkedHashSet<>();
        for (PropertySheetPaletteRow row : rows)
        {
            if (row == null || row.propertyName == null || row.propertyName.isEmpty())
                continue;
            if (seen.add(row.propertyName))
            {
                Entry probe = new Entry(Kind.PROPERTY, row.propertyName, scannerValueText(row));
                ValuePair pair = resolveValuePair(probe, page, row.lwtView);
                if (pair.valueVm == null)
                {
                    PropertySheetDebug.valueControl("RESOLVE scanner MISS " + PropertySheetDebug.quote(row.propertyName) //$NON-NLS-1$
                            + " path=" + PropertySheetDebug.quote(pair.path) //$NON-NLS-1$
                            + " labelView=" + PropertySheetDebug.typeName(row.lwtView)); //$NON-NLS-1$
                }
                out.add(new Entry(Kind.PROPERTY, row.propertyName, scannerValueText(row),
                        null, pair.valueVm, pair.valueView));
            }
        }
        return out;
    }

    private static Object resolveViewModel(Object valueView)
    {
        if (valueView == null)
            return null;
        Object valueVm = Global.getField(valueView, "viewModel"); //$NON-NLS-1$
        if (valueVm == null)
            valueVm = Global.invoke(valueView, "getViewModel"); //$NON-NLS-1$
        return valueVm;
    }

    private static String scannerValueText(PropertySheetPaletteRow row)
    {
        if (row == null)
            return ""; //$NON-NLS-1$
        if (row.rowControls != null)
        {
            for (Control control : row.rowControls)
            {
                if (control == null || control.isDisposed() || control == row.nameControl)
                    continue;
                String text = PropertySheetControlInterop.controlText(control);
                if (!text.isEmpty())
                    return text;
            }
        }
        if (row.rowComposite != null && !row.rowComposite.isDisposed())
        {
            for (Control child : row.rowComposite.getChildren())
            {
                if (child == null || child.isDisposed() || child == row.nameControl)
                    continue;
                String text = PropertySheetControlInterop.controlText(child);
                if (!text.isEmpty())
                    return text;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static String resolveValueText(Object valueVm)
    {
        return resolveValueText(valueVm, null);
    }

    private static String resolveValueText(Object valueVm, Object view)
    {
        String fromAef = PropertySheetAefValues.readValue(valueVm);
        if (!fromAef.isEmpty())
            return fromAef;
        // 1. Сначала пробуем через AEF IValue: valueVm.getModel().getValue()
        //    Это работает для LWT/SWT ValueViewModel одинако
        if (valueVm != null)
        {
            Object model = Global.invoke(valueVm, "getModel"); //$NON-NLS-1$
            if (model != null)
            {
                for (String method : new String[] {
                        "getSelectedIndex", "getSelectionIndex", "getSelectedItemIndex" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                })
                {
                    Object idxObj = Global.invoke(model, method);
                    if (idxObj instanceof Number)
                    {
                        int idx = ((Number) idxObj).intValue();
                        List<String> literals = enumLiteralsFrom(model);
                        if (idx >= 0 && idx < literals.size())
                            return literals.get(idx);
                    }
                }
                Object value = Global.invoke(model, "getValue"); //$NON-NLS-1$
                String s = objectText(value);
                if (!s.isEmpty())
                    return s;
                for (String m : new String[] { "getSingleValue", "getItemLabel", //$NON-NLS-1$ //$NON-NLS-2$
                        "getDisplayValue", "getPresentation", "getLabel", "getSelectedItem" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                {
                    Object r = Global.invoke(model, m);
                    s = objectText(r);
                    if (!s.isEmpty())
                        return s;
                }
            }
        }

        // 2. Через view (для SWT view — getText/getNativeControl)
        String fromView = resolveViewText(view);
        if (!fromView.isEmpty())
            return fromView;

        // 3. Методы viewModel
        for (String method : new String[] {
                "getValueText", "getPresentation", "getFormattedValue", "getDisplayValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "getEditingValue", "getText", "getTitle", "getDataPath", "getPath", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "getPropertyPath", "getFeaturePath" //$NON-NLS-1$ //$NON-NLS-2$
        })
        {
            Object byMethod = Global.invoke(valueVm, method);
            String s = objectText(byMethod);
            if (!s.isEmpty())
                return s;
        }

        for (String field : new String[] {
                "value", "text", "presentation", "formattedValue", "displayValue", "editingValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "dataPath", "path", "propertyPath", "featurePath" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        })
        {
            String s = objectText(Global.getField(valueVm, field));
            if (!s.isEmpty())
                return s;
        }

        String text = SmartTreeElementLabels.resolve(valueVm, null);
        Object value = Global.invoke(valueVm, "getValue"); //$NON-NLS-1$
        if (value != null)
        {
            String asString = value.toString();
            if (!asString.isEmpty())
                return asString;
        }
        return text != null ? text : ""; //$NON-NLS-1$
    }

    private static List<String> enumLiteralsFrom(Object model)
    {
        List<String> items = new ArrayList<>();
        if (model == null)
            return items;
        for (String method : new String[] {
                "getEnumLiterals", "getItems", "getAllowedValues", "getPossibleValues", "getChoices" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        })
        {
            Object raw = Global.invoke(model, method);
            Iterator<?> it = toIterator(raw);
            if (it == null)
                continue;
            while (it.hasNext())
            {
                Object item = it.next();
                if (item == null)
                    continue;
                String label = objectText(item);
                if (!label.isEmpty() && !items.contains(label))
                    items.add(label);
            }
            if (!items.isEmpty())
                return items;
        }
        return items;
    }

    private static String resolveViewText(Object view)
    {
        if (view == null)
            return ""; //$NON-NLS-1$
        for (String method : new String[] { "getText", "getValue", "getNativeControl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            String text = objectText(Global.invoke(view, method));
            if (!text.isEmpty())
                return text;
        }
        for (String field : new String[] { "nativeControl", "lightControl", "lightLabel", "swtControl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String text = objectText(Global.getField(view, field));
            if (!text.isEmpty())
                return text;
        }
        Object vm = Global.getField(view, "viewModel"); //$NON-NLS-1$
        if (vm == null)
            vm = Global.invoke(view, "getViewModel"); //$NON-NLS-1$
        String fromVm = objectText(vm);
        if (!fromVm.isEmpty())
            return fromVm;
        return ""; //$NON-NLS-1$
    }

    private static String objectText(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof org.eclipse.swt.widgets.Control)
            return PropertySheetControlInterop.controlText((org.eclipse.swt.widgets.Control) value);
        if (value instanceof String)
            return (String) value;
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        Object text = Global.invoke(value, "getText"); //$NON-NLS-1$
        if (text instanceof String && !((String) text).isEmpty())
            return (String) text;
        for (String method : new String[] {
                "getValue", "getObject", "getData", "getPresentation", "getName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "getDisplayName", "getLabel", "getLiteral", "getDataPath", "getPath", "getPropertyPath" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        })
        {
            Object nested = Global.invoke(value, method);
            if (nested == null || nested == value)
                continue;
            if (nested instanceof String && !((String) nested).isEmpty())
                return (String) nested;
            Object nestedText = Global.invoke(nested, "getText"); //$NON-NLS-1$
            if (nestedText instanceof String && !((String) nestedText).isEmpty())
                return (String) nestedText;
            Object nestedName = Global.invoke(nested, "getName"); //$NON-NLS-1$
            if (nestedName instanceof String && !((String) nestedName).isEmpty())
                return (String) nestedName;
            String asString = nested.toString();
            if (isUsefulObjectString(asString, nested))
                return asString;
        }
        Object swt = Global.invoke(value, "getSwtComposite"); //$NON-NLS-1$
        if (swt instanceof org.eclipse.swt.widgets.Control)
            return PropertySheetControlInterop.controlText((org.eclipse.swt.widgets.Control) swt);
        String asString = value.toString();
        if (isUsefulObjectString(asString, value))
            return asString;
        return ""; //$NON-NLS-1$
    }

    private static boolean isUsefulObjectString(String text, Object value)
    {
        if (text == null || text.isEmpty() || value == null)
            return false;
        String cn = value.getClass().getName();
        return !text.equals(cn) && !(text.startsWith(cn) && text.contains("@")); //$NON-NLS-1$
    }

    private static Iterator<?> toIterator(Object result)
    {
        if (result instanceof Iterable)
            return ((Iterable<?>) result).iterator();
        if (result instanceof Object[])
        {
            Object[] arr = (Object[]) result;
            List<Object> list = new ArrayList<>(arr.length);
            for (Object o : arr)
                list.add(o);
            return list.iterator();
        }
        return null;
    }

    /** Дозаполняет valueVm только по общему parent (field row), без index-fallback. */
    private static void pairMissingValuesByParent(List<Entry> out, List<Object> labelModels,
            List<Object> valueModels, List<Object> valueViews)
    {
        if (out == null || out.isEmpty() || labelModels == null || labelModels.isEmpty()
                || valueModels == null || valueModels.isEmpty())
            return;
        Map<String, Object> labelByName = new java.util.HashMap<>();
        for (Object labelVm : labelModels)
        {
            if (labelVm == null)
                continue;
            String name = SmartTreeElementLabels.resolve(labelVm, null);
            if (!name.isEmpty())
                labelByName.putIfAbsent(name, labelVm);
        }
        for (int i = 0; i < out.size(); i++)
        {
            Entry entry = out.get(i);
            if (entry == null || entry.kind != Kind.PROPERTY || entry.valueViewModel != null
                    || entry.name == null || entry.name.isEmpty())
                continue;
            Object valueVm = null;
            if (entry.viewModel != null)
            {
                Object children = Global.invoke(entry.viewModel, "getChildren"); //$NON-NLS-1$
                valueVm = findValueViewModel(entry.viewModel, children);
            }
            if (valueVm == null)
                valueVm = findSiblingValueViewModel(labelByName.get(entry.name), valueModels);
            if (valueVm == null)
                continue;
            int valueIdx = valueModels.indexOf(valueVm);
            Object valueView = valueIdx >= 0 && valueIdx < valueViews.size() ? valueViews.get(valueIdx) : null;
            String value = entry.value != null && !entry.value.isEmpty()
                    ? entry.value : resolveValueText(valueVm, valueView);
            out.set(i, new Entry(entry.kind, entry.name, value, entry.viewModel, valueVm, valueView));
        }
    }

    private static Object findSiblingValueViewModel(Object labelVm, List<Object> valueModels)
    {
        if (labelVm == null)
            return null;
        Object fieldRow = parentOf(labelVm);
        if (fieldRow != null)
        {
            Object children = Global.invoke(fieldRow, "getChildren"); //$NON-NLS-1$
            Object fromChildren = findValueViewModel(fieldRow, children);
            if (fromChildren != null)
                return fromChildren;
        }
        if (valueModels == null || valueModels.isEmpty())
            return null;
        if (fieldRow != null)
        {
            for (Object valueVm : valueModels)
            {
                if (valueVm == null)
                    continue;
                Object valueParent = parentOf(valueVm);
                if (fieldRow == valueParent || fieldRow.equals(valueParent))
                    return valueVm;
            }
        }
        return null;
    }

    private static Object fieldRowOf(Object viewModel)
    {
        if (viewModel == null)
            return null;
        if (isLabelViewModel(viewModel))
            return parentOf(viewModel);
        return viewModel;
    }

    private static boolean isLabelViewModel(Object viewModel)
    {
        if (viewModel == null)
            return false;
        String cn = viewModel.getClass().getName();
        return cn.contains("LabelViewModel") || cn.contains("LabelItem"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean entriesMissingValueViewModel(List<Entry> entries)
    {
        if (entries == null || entries.isEmpty())
            return false;
        for (Entry entry : entries)
        {
            if (entry != null && entry.kind == Kind.PROPERTY && entry.valueViewModel == null)
                return true;
        }
        return false;
    }

    private static int countMissingValueViewModels(List<Entry> entries)
    {
        if (entries == null || entries.isEmpty())
            return 0;
        int count = 0;
        for (Entry entry : entries)
        {
            if (entry != null && entry.kind == Kind.PROPERTY && entry.valueViewModel == null)
                count++;
        }
        return count;
    }

    private static int countNonEmptyPropertyValues(List<Entry> entries)
    {
        if (entries == null || entries.isEmpty())
            return 0;
        int count = 0;
        for (Entry entry : entries)
        {
            if (entry != null && entry.kind == Kind.PROPERTY
                    && entry.value != null && !entry.value.isEmpty())
                count++;
        }
        return count;
    }

    private static void enrichEntriesWithViews(Object page, List<Entry> entries)
    {
        if (page == null || entries == null || entries.isEmpty())
            return;
        for (int i = 0; i < entries.size(); i++)
        {
            Entry entry = entries.get(i);
            if (entry == null || entry.kind != Kind.PROPERTY)
                continue;
            Object valueVm = resolveEntryValueViewModel(entry, page);
            Object valueView = resolveEntryValueView(entry, page);
            String value = entry.value;
            if ((value == null || value.isEmpty()) && valueVm != null)
                value = PropertySheetAefValues.readValue(valueVm);
            if ((value == null || value.isEmpty()) && valueView != null)
                value = PropertySheetControlInterop.displayTextFromView(valueView, valueVm);
            if ((value == null || value.isEmpty()) && valueVm != null)
                value = resolveValueText(valueVm, valueView);
            if (valueVm != entry.valueViewModel || valueView != entry.valueView
                    || (value != null ? !value.equals(entry.value) : entry.value != null))
            {
                entries.set(i, new Entry(entry.kind, entry.name, value, entry.viewModel, valueVm, valueView));
            }
        }
    }

    private static void mergePropertyValues(List<Entry> target, List<Entry> source)
    {
        if (target == null || target.isEmpty() || source == null || source.isEmpty())
            return;
        Map<String, Entry> byName = new java.util.HashMap<>();
        for (Entry entry : source)
        {
            if (entry == null || entry.kind != Kind.PROPERTY || entry.name.isEmpty())
                continue;
            byName.putIfAbsent(entry.name, entry);
        }
        if (byName.isEmpty())
            return;
        for (int i = 0; i < target.size(); i++)
        {
            Entry entry = target.get(i);
            if (entry == null || entry.kind != Kind.PROPERTY || entry.name.isEmpty())
                continue;
            Entry extra = byName.get(entry.name);
            if (extra == null)
                continue;
            Object valueVm = entry.valueViewModel != null ? entry.valueViewModel : extra.valueViewModel;
            Object valueView = entry.valueView != null ? entry.valueView : extra.valueView;
            String value = entry.value != null && !entry.value.isEmpty() ? entry.value : ""; //$NON-NLS-1$
            if (value.isEmpty() && valueVm != null)
                value = PropertySheetAefValues.readValue(valueVm);
            if (value.isEmpty() && extra.value != null && !extra.value.isEmpty())
                value = extra.value;
            if (value.equals(entry.value) && valueVm == entry.valueViewModel && valueView == entry.valueView)
                continue;
            target.set(i, new Entry(entry.kind, entry.name, value, entry.viewModel, valueVm, valueView));
        }
    }
}

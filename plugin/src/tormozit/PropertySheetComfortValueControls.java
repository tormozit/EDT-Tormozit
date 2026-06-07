package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/** Создание value-контролов Comfort UI по типу AEF ValueViewModel. */
final class PropertySheetComfortValueControls
{
    enum Kind
    {
        TEXT,
        BOOLEAN,
        COMBO,
        HYPERLINK,
        SPINNER
    }

    static final class Created
    {
        final Kind kind;
        final Control control;
        final String displayValue;

        Created(Kind kind, Control control, String displayValue)
        {
            this.kind = kind;
            this.control = control;
            this.displayValue = displayValue != null ? displayValue : ""; //$NON-NLS-1$
        }
    }

    private PropertySheetComfortValueControls() {}

    static final class KindDecision
    {
        final Kind kind;
        final String reason;

        KindDecision(Kind kind, String reason)
        {
            this.kind = kind;
            this.reason = reason != null ? reason : ""; //$NON-NLS-1$
        }
    }

    static Kind detectKind(Object valueVm, Object valueView, Control nativeValue, String displayValue)
    {
        return detectKindDetailed(valueVm, valueView, nativeValue, displayValue).kind;
    }

    static KindDecision detectKindDetailed(Object valueVm, Object valueView, Control nativeValue,
            String displayValue)
    {
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (nativeValue != null && !nativeValue.isDisposed())
        {
            if (nativeValue instanceof Link)
                return new KindDecision(Kind.HYPERLINK, "native:Link"); //$NON-NLS-1$
            if (nativeValue instanceof Text)
                return new KindDecision(Kind.TEXT, "native:Text"); //$NON-NLS-1$
            Kind fromNative = detectKindFromControl(nativeValue);
            if (fromNative != Kind.TEXT)
                return new KindDecision(fromNative, "native:" + fromNative); //$NON-NLS-1$
        }
        Kind fromView = kindFromView(valueView);
        if (fromView != null)
            return new KindDecision(fromView, "view:" + PropertySheetDebug.typeName(valueView)); //$NON-NLS-1$
        if (isTextType(valueVm, valueView, model))
            return new KindDecision(Kind.TEXT, "vmText:" + PropertySheetDebug.typeName(valueVm)); //$NON-NLS-1$
        if (isLinkType(valueVm, valueView, model))
            return new KindDecision(Kind.HYPERLINK, "vmLink:" + PropertySheetDebug.typeName(valueVm)); //$NON-NLS-1$
        if (model != null && Global.invoke(model, "getValue") instanceof Boolean) //$NON-NLS-1$
            return new KindDecision(Kind.BOOLEAN, "model:Boolean"); //$NON-NLS-1$
        Kind fromNativeRow = detectKindFromNativeRow(nativeValue);
        if (fromNativeRow != null)
            return new KindDecision(fromNativeRow, "nativeRow:" + fromNativeRow); //$NON-NLS-1$
        if (hasComboItems(model, valueVm) || isComboType(valueVm, valueView, model))
            return new KindDecision(Kind.COMBO, "comboType"); //$NON-NLS-1$
        if (isBooleanType(valueVm, valueView, model))
            return new KindDecision(Kind.BOOLEAN, "boolType"); //$NON-NLS-1$
        if (isNumericType(valueVm, valueView, model, displayValue))
            return new KindDecision(Kind.SPINNER, "numeric"); //$NON-NLS-1$
        return new KindDecision(Kind.TEXT, "default"); //$NON-NLS-1$
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText)
    {
        return create(parent, entry, nativeValue, displayText, ""); //$NON-NLS-1$
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath)
    {
        return create(parent, entry, nativeValue, displayText, resolvePath, 0);
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath, int valueWidthHint)
    {
        Object valueVm = entry != null ? entry.valueViewModel : null;
        Object valueView = entry != null ? entry.valueView : null;
        String propName = entry != null ? entry.name : ""; //$NON-NLS-1$
        String display = firstNonEmpty(displayText,
                entry != null && !isOpenPlaceholder(entry.value) ? entry.value : "", //$NON-NLS-1$
                PropertySheetAefValues.readValue(valueVm),
                valueView != null ? PropertySheetControlInterop.displayTextFromView(valueView, valueVm) : "", //$NON-NLS-1$
                resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        KindDecision decision = detectKindDetailed(valueVm, valueView, nativeValue, display);
        Kind kind = decision.kind;
        String kindReason = decision.reason;
        if (kind == Kind.HYPERLINK && (isOpenPlaceholder(display) || display.isEmpty()))
            display = "Открыть"; //$NON-NLS-1$
        else if (isOpenPlaceholder(display))
            display = firstNonEmpty(displayText, nativeControlText(nativeValue),
                    PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView));
        if (kind == Kind.TEXT && isOpenPlaceholder(display))
            display = firstNonEmpty(displayText, PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView));
        Boolean boolFromView = PropertySheetAefValues.readBoolean(valueVm, valueView);
        if (boolFromView != null && kind != Kind.HYPERLINK && kind != Kind.COMBO)
        {
            kind = Kind.BOOLEAN;
            kindReason = "boolFromView"; //$NON-NLS-1$
        }
        else if (isBooleanType(valueVm, valueView, model) && kind == Kind.TEXT)
        {
            kind = Kind.BOOLEAN;
            kindReason = "boolTypeOverride"; //$NON-NLS-1$
        }
        List<String> comboItems = null;
        if (kind == Kind.COMBO)
        {
            comboItems = resolveComboItems(valueVm, valueView, nativeValue);
            if (display.isEmpty())
                display = resolveComboDisplayValue(valueVm, valueView, comboItems);
        }
        logValueRow(propName, kind, kindReason, display, valueVm, valueView, nativeValue, resolvePath);
        logValueControl(propName, kind, display, valueVm, valueView, model, boolFromView, comboItems);
        switch (kind)
        {
            case BOOLEAN:
                return createBoolean(parent, valueVm, valueView, display, nativeValue, boolFromView);
            case COMBO:
                return createCombo(parent, valueVm, valueView, display, nativeValue, comboItems, valueWidthHint);
            case HYPERLINK:
                return createHyperlink(parent, display, valueWidthHint);
            case SPINNER:
                return createSpinner(parent, valueVm, valueView, display, nativeValue, valueWidthHint);
            default:
                return createText(parent, display, nativeValue != null || valueVm != null, valueWidthHint);
        }
    }

    static void applyWidthHint(Created created, int valueWidthHint)
    {
        if (created == null || created.control == null || created.control.isDisposed()
                || valueWidthHint <= 0 || created.kind == Kind.BOOLEAN)
            return;
        Object layoutData = created.control.getLayoutData();
        if (layoutData instanceof org.eclipse.swt.layout.GridData)
        {
            org.eclipse.swt.layout.GridData gd = (org.eclipse.swt.layout.GridData) layoutData;
            gd.widthHint = valueWidthHint;
            gd.grabExcessHorizontalSpace = true;
        }
    }

    private static void logValueControl(String propName, Kind kind, String display, Object valueVm,
            Object valueView, Object model, Boolean boolFromView, List<String> comboItems)
    {
        if (propName == null || propName.isEmpty())
            return;
        boolean boolType = isBooleanType(valueVm, valueView, model) || boolFromView != null;
        if (kind == Kind.BOOLEAN)
        {
            PropertySheetDebug.valueControlVerbose("checkbox OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " selected=" + boolFromView //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm)); //$NON-NLS-1$
            return;
        }
        if (boolType)
        {
            PropertySheetDebug.valueControl("checkbox MISS " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " kind=" + kind //$NON-NLS-1$
                    + " boolFromView=" + boolFromView //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView) //$NON-NLS-1$
                    + " model=" + PropertySheetDebug.safe(model) //$NON-NLS-1$
                    + " types=" + PropertySheetDebug.quote(typeNames(valueVm, valueView, model))); //$NON-NLS-1$
        }
        if (kind == Kind.COMBO)
        {
            int items = comboItems != null ? comboItems.size() : 0;
            if (display == null || display.isEmpty())
            {
                PropertySheetDebug.valueControl("combo EMPTY " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                        + " items=" + items //$NON-NLS-1$
                        + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                        + " viewText=" + PropertySheetDebug.quote( //$NON-NLS-1$
                                valueView != null ? PropertySheetControlInterop.displayTextFromView(valueView, valueVm) : "") //$NON-NLS-1$
                        + " modelText=" + PropertySheetDebug.quote(resolveModelText(valueVm, valueView))); //$NON-NLS-1$
            }
            else {
                PropertySheetDebug.valueControlVerbose("combo OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                        + " value=" + PropertySheetDebug.quote(display)); //$NON-NLS-1$
            }
        }
        if (kind == Kind.TEXT && (display == null || display.isEmpty()))
        {
            PropertySheetDebug.valueControl("text EMPTY " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView) //$NON-NLS-1$
                    + " aef=" + PropertySheetDebug.quote(PropertySheetAefValues.readValue(valueVm))); //$NON-NLS-1$
        }
        if (kind == Kind.HYPERLINK)
            PropertySheetDebug.valueControlVerbose("hyperlink OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView)); //$NON-NLS-1$
    }

    static void applyDisplay(Created created, String display, Object valueVm, Object valueView, Control nativeValue)
    {
        if (created == null || created.control == null || created.control.isDisposed())
            return;
        String value = display != null ? display : ""; //$NON-NLS-1$
        if (value.isEmpty())
            value = firstNonEmpty(PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        switch (created.kind)
        {
            case BOOLEAN:
            {
                Boolean selected = PropertySheetAefValues.readBoolean(valueVm, valueView);
                boolean on = selected != null ? selected.booleanValue() : resolveBoolean(valueVm, valueView, value, nativeValue);
                if (created.control instanceof Button)
                    ((Button) created.control).setSelection(on);
                break;
            }
            case COMBO:
            {
                java.util.List<String> items = resolveComboItems(valueVm, valueView, nativeValue);
                if (value.isEmpty())
                    value = PropertySheetAefValues.readComboSelection(valueVm, items);
                if (created.control instanceof CCombo)
                {
                    CCombo combo = (CCombo) created.control;
                    int idx = indexOf(items, value);
                    if (idx >= 0)
                        combo.select(idx);
                    else if (!value.isEmpty())
                        combo.setText(value);
                }
                break;
            }
            case SPINNER:
            {
                int number = resolveSpinner(valueVm, valueView, value, nativeValue);
                if (created.control instanceof Spinner)
                    ((Spinner) created.control).setSelection(number);
                break;
            }
            case TEXT:
                if (created.control instanceof Text)
                    ((Text) created.control).setText(value);
                break;
            default:
                break;
        }
    }

    static String readDisplayValue(Control control, Kind kind)
    {
        if (control == null || control.isDisposed())
            return ""; //$NON-NLS-1$
        switch (kind)
        {
            case BOOLEAN:
                if (control instanceof Button)
                    return ((Button) control).getSelection() ? "true" : "false"; //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case COMBO:
                if (control instanceof Combo)
                    return ((Combo) control).getText();
                if (control instanceof CCombo)
                    return ((CCombo) control).getText();
                break;
            case SPINNER:
                if (control instanceof Spinner)
                    return String.valueOf(((Spinner) control).getSelection());
                break;
            case HYPERLINK:
                if (control instanceof Link)
                    return stripLinkMarkup(((Link) control).getText());
                break;
            default:
                break;
        }
        return PropertySheetControlInterop.controlText(control);
    }

    static void wireChange(Created created, Runnable onChange)
    {
        if (created == null || created.control == null || created.control.isDisposed() || onChange == null)
            return;
        Control control = created.control;
        switch (created.kind)
        {
            case BOOLEAN:
            case COMBO:
            case HYPERLINK:
            case SPINNER:
                control.addListener(SWT.Selection, e -> onChange.run());
                break;
            default:
                if (control instanceof Text)
                    ((Text) control).addModifyListener(e -> onChange.run());
                break;
        }
    }

    static void applyToNative(Object sessionObj, Created created, Control nativeValue, Object valueVm,
            String propertyName)
    {
        if (created == null || created.control == null || created.control.isDisposed())
            return;
        switch (created.kind)
        {
            case BOOLEAN:
                applyBoolean(created, nativeValue, valueVm);
                return;
            case COMBO:
                applyCombo(created, nativeValue, valueVm);
                return;
            case HYPERLINK:
                fireHyperlink(sessionObj, nativeValue, valueVm, propertyName);
                return;
            case SPINNER:
                applySpinner(created, nativeValue, valueVm);
                return;
            default:
                applyText(created, nativeValue, valueVm);
                return;
        }
    }

    private static Created createText(Composite parent, String value, boolean editable, int valueWidthHint)
    {
        Text text = new Text(parent, SWT.BORDER | SWT.SINGLE);
        text.setText(value != null ? value : ""); //$NON-NLS-1$
        text.setEditable(editable);
        applyValueGridData(text, valueWidthHint);
        return new Created(Kind.TEXT, text, value);
    }

    private static Created createBoolean(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, Boolean boolFromView)
    {
        boolean selected = boolFromView != null ? boolFromView.booleanValue()
                : resolveBoolean(valueVm, valueView, display, nativeValue);
        Button check = new Button(parent, SWT.CHECK);
        check.setSelection(selected);
        GridDataFactory.swtDefaults().align(SWT.LEFT, SWT.CENTER).applyTo(check);
        return new Created(Kind.BOOLEAN, check, selected ? "true" : "false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Created createCombo(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, List<String> items, int valueWidthHint)
    {
        if (items == null)
            items = resolveComboItems(valueVm, valueView, nativeValue);
        String value = display != null ? display : ""; //$NON-NLS-1$
        if (value.isEmpty())
            value = resolveComboDisplayValue(valueVm, valueView, items);
        CCombo combo = new CCombo(parent, SWT.BORDER | SWT.READ_ONLY);
        List<String> merged = new ArrayList<>(items);
        if (!value.isEmpty() && indexOf(merged, value) < 0)
            merged.add(0, value);
        if (!merged.isEmpty())
            combo.setItems(merged.toArray(new String[0]));
        if (!value.isEmpty())
        {
            int idx = indexOf(merged, value);
            if (idx >= 0)
                combo.select(idx);
            else
                combo.setText(value);
        }
        applyValueGridData(combo, valueWidthHint);
        return new Created(Kind.COMBO, combo, combo.getText());
    }

    private static Created createHyperlink(Composite parent, String display, int valueWidthHint)
    {
        String value = display != null && !display.isEmpty() ? display : "Открыть"; //$NON-NLS-1$
        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>" + escapeLinkText(value) + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        applyValueGridData(link, valueWidthHint);
        return new Created(Kind.HYPERLINK, link, value);
    }

    private static Created createSpinner(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, int valueWidthHint)
    {
        int number = resolveSpinner(valueVm, valueView, display, nativeValue);
        Spinner spinner = new Spinner(parent, SWT.BORDER);
        spinner.setMinimum(Integer.MIN_VALUE / 2);
        spinner.setMaximum(Integer.MAX_VALUE / 2);
        spinner.setSelection(number);
        applyValueGridData(spinner, valueWidthHint);
        return new Created(Kind.SPINNER, spinner, String.valueOf(number));
    }

    private static void applyValueGridData(Control control, int valueWidthHint)
    {
        if (valueWidthHint > 0)
            GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER)
                    .hint(valueWidthHint, SWT.DEFAULT).applyTo(control);
        else
            GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(control);
        if (valueWidthHint > 0)
        {
            Object layoutData = control.getLayoutData();
            if (layoutData instanceof org.eclipse.swt.layout.GridData)
            {
                org.eclipse.swt.layout.GridData gd = (org.eclipse.swt.layout.GridData) layoutData;
                gd.widthHint = valueWidthHint;
                gd.grabExcessHorizontalSpace = true;
            }
        }
    }

    private static void applyText(Created created, Control nativeValue, Object valueVm)
    {
        String value = readDisplayValue(created.control, Kind.TEXT);
        pushNativeText(nativeValue, value);
        pushModelValue(valueVm, value);
    }

    private static void applyBoolean(Created created, Control nativeValue, Object valueVm)
    {
        boolean selected = created.control instanceof Button && ((Button) created.control).getSelection();
        if (nativeValue instanceof Button && (((Button) nativeValue).getStyle() & SWT.CHECK) != 0)
        {
            ((Button) nativeValue).setSelection(selected);
            nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            return;
        }
        pushModelValue(valueVm, Boolean.valueOf(selected));
        pushNativeText(nativeValue, selected ? "true" : "false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void applyCombo(Created created, Control nativeValue, Object valueVm)
    {
        String value = readDisplayValue(created.control, Kind.COMBO);
        if (nativeValue instanceof Combo)
        {
            Combo combo = (Combo) nativeValue;
            int idx = combo.indexOf(value);
            if (idx >= 0)
                combo.select(idx);
            else
                combo.setText(value);
            combo.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            return;
        }
        if (nativeValue instanceof CCombo)
        {
            CCombo combo = (CCombo) nativeValue;
            combo.setText(value);
            combo.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            return;
        }
        pushModelValue(valueVm, value);
        pushNativeText(nativeValue, value);
    }

    private static void applySpinner(Created created, Control nativeValue, Object valueVm)
    {
        String value = readDisplayValue(created.control, Kind.SPINNER);
        if (nativeValue instanceof Spinner)
        {
            try
            {
                ((Spinner) nativeValue).setSelection(Integer.parseInt(value));
                nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                return;
            }
            catch (NumberFormatException ignored) {}
        }
        pushModelValue(valueVm, value);
        pushNativeText(nativeValue, value);
    }

    private static void fireHyperlink(Object sessionObj, Control nativeValue, Object valueVm, String propertyName)
    {
        Object valueView = null;
        if (sessionObj instanceof PropertySheetComfortUi.SessionAccessor && valueVm != null)
        {
            Object page = ((PropertySheetComfortUi.SessionAccessor) sessionObj).page();
            Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
            if (scene != null)
                valueView = PropertySheetViewModelTree.resolveValueView(page, valueVm);
        }
        for (String method : new String[] {
                "linkActivated", "open", "execute", "performOpen", "handleOpen", "doOpen", "openEditor", "openLink" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        })
        {
            Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
            try
            {
                if (valueView != null && Global.invokeVoid(valueView, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK view." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
                if (model != null && Global.invokeVoid(model, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK model." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
                if (valueVm != null && Global.invokeVoid(valueVm, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK vm." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
            }
            catch (Throwable ignored) {}
        }
        Control target = nativeValue;
        if ((target == null || target.isDisposed()) && sessionObj instanceof PropertySheetComfortUi.SessionAccessor)
            target = ((PropertySheetComfortUi.SessionAccessor) sessionObj)
                    .resolveNativeValueControl(propertyName, valueVm);
        if (target != null && !target.isDisposed())
            PropertySheetDebug.valueControl("hyperlink skip native notify " //$NON-NLS-1$
                    + PropertySheetDebug.quote(propertyName) + " target=" + PropertySheetDebug.safe(target)); //$NON-NLS-1$
        else
            PropertySheetDebug.valueControl("hyperlink FAIL " + PropertySheetDebug.quote(propertyName)); //$NON-NLS-1$
    }

    private static void pushNativeText(Control nativeValue, String value)
    {
        if (nativeValue == null || nativeValue.isDisposed())
            return;
        if (value == null)
            value = ""; //$NON-NLS-1$
        try
        {
            if (nativeValue instanceof Text)
                ((Text) nativeValue).setText(value);
            else if (nativeValue instanceof Combo)
                ((Combo) nativeValue).setText(value);
            else if (nativeValue instanceof CCombo)
                ((CCombo) nativeValue).setText(value);
            else
                Global.invoke(nativeValue, "setText", value); //$NON-NLS-1$
            nativeValue.notifyListeners(SWT.Modify, new org.eclipse.swt.widgets.Event());
            nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        }
        catch (Throwable ignored) {}
    }

    private static void pushModelValue(Object valueVm, Object value)
    {
        if (valueVm == null)
            return;
        Object model = Global.invoke(valueVm, "getModel"); //$NON-NLS-1$
        if (model != null)
        {
            Global.invoke(model, "setValue", value); //$NON-NLS-1$
            Global.invoke(model, "valueChanged"); //$NON-NLS-1$
        }
        Global.invoke(valueVm, "setValue", value); //$NON-NLS-1$
    }

    private static String resolveComboDisplayValue(Object valueVm, Object valueView, List<String> items)
    {
        String fromAef = PropertySheetAefValues.readComboSelection(valueVm, items);
        if (!fromAef.isEmpty())
            return fromAef;
        if (valueView != null)
        {
            String fromView = PropertySheetControlInterop.displayTextFromView(valueView, valueVm);
            if (!fromView.isEmpty())
                return fromView;
        }
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
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
                    if (idx >= 0 && items != null && idx < items.size())
                        return items.get(idx);
                }
            }
            Object value = Global.invoke(model, "getValue"); //$NON-NLS-1$
            String label = labelForEnumValue(value, items);
            if (!label.isEmpty())
                return label;
            Object single = Global.invoke(model, "getSingleValue"); //$NON-NLS-1$
            label = labelForEnumValue(single, items);
            if (!label.isEmpty())
                return label;
        }
        return resolveModelText(valueVm, valueView);
    }

    private static String labelForEnumValue(Object value, List<String> items)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof Number && items != null && !items.isEmpty())
        {
            int idx = ((Number) value).intValue();
            if (idx >= 0 && idx < items.size())
                return items.get(idx);
        }
        String direct = asString(value);
        if (!direct.isEmpty())
        {
            int idx = indexOfIgnoreCase(items, direct);
            if (idx >= 0 && items != null)
                return items.get(idx);
            if (!looksLikeClassName(direct, value))
                return direct;
        }
        for (String method : new String[] {
                "getLabel", "getName", "getLiteral", "getPresentation", "getDisplayName", "getText" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        })
        {
            String nested = asString(Global.invoke(value, method));
            if (!nested.isEmpty())
            {
                int idx = indexOfIgnoreCase(items, nested);
                if (idx >= 0 && items != null)
                    return items.get(idx);
                return nested;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static boolean looksLikeClassName(String text, Object value)
    {
        return value != null && text.equals(value.getClass().getName());
    }

    private static int indexOfIgnoreCase(List<String> items, String value)
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

    private static String resolveModelText(Object valueVm, Object valueView)
    {
        String fromAef = PropertySheetAefValues.readValue(valueVm);
        if (!fromAef.isEmpty())
            return fromAef;
        if (valueVm == null && valueView == null)
            return ""; //$NON-NLS-1$
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
                for (String method : new String[] {
                    "getValue", "getSingleValue", "getItemLabel", "getDisplayValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "getPresentation", "getLabel", "getText", "getSelectedItem", "getCurrentLiteral" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            })
            {
                String text = asString(Global.invoke(model, method));
                if (!text.isEmpty())
                    return text;
            }
        }
        for (String method : new String[] { "getText", "getValue", "getPresentation", "getDisplayValue" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String text = asString(valueVm != null ? Global.invoke(valueVm, method) : Global.invoke(valueView, method));
            if (!text.isEmpty())
                return text;
        }
        return ""; //$NON-NLS-1$
    }

    private static boolean resolveBoolean(Object valueVm, Object valueView, String display, Control nativeValue)
    {
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
            Object value = Global.invoke(model, "getValue"); //$NON-NLS-1$
            if (value instanceof Boolean)
                return (Boolean) value;
        }
        if (nativeValue instanceof Button && (((Button) nativeValue).getStyle() & SWT.CHECK) != 0)
            return ((Button) nativeValue).getSelection();
        if ("true".equalsIgnoreCase(display) || "да".equalsIgnoreCase(display)) //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        if ("false".equalsIgnoreCase(display) || "нет".equalsIgnoreCase(display)) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Object selected = valueView != null ? Global.invoke(valueView, "getSelection") : null; //$NON-NLS-1$
        return selected instanceof Boolean && (Boolean) selected;
    }

    private static int resolveSpinner(Object valueVm, Object valueView, String display, Control nativeValue)
    {
        if (nativeValue instanceof Spinner)
            return ((Spinner) nativeValue).getSelection();
        String text = firstNonEmpty(display, resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        try
        {
            if (!text.isEmpty())
                return (int) Double.parseDouble(text.replace(',', '.'));
        }
        catch (NumberFormatException ignored) {}
        return 0;
    }

    private static List<String> resolveComboItems(Object valueVm, Object valueView, Control nativeValue)
    {
        List<String> items = new ArrayList<>();
        collectItems(items, valueVm);
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        collectItems(items, model);
        if (items.isEmpty() && valueVm != null)
            collectItems(items, valueVm);
        if (items.isEmpty() && nativeValue instanceof Combo)
        {
            for (String item : ((Combo) nativeValue).getItems())
                addItem(items, item);
        }
        if (items.isEmpty() && nativeValue instanceof CCombo)
        {
            for (String item : ((CCombo) nativeValue).getItems())
                addItem(items, item);
        }
        return items;
    }

    private static void collectItems(List<String> items, Object source)
    {
        if (source == null)
            return;
        for (String method : new String[] {
                "getItems", "getEnumLiterals", "getAllowedValues", "getPossibleValues", "getChoices" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        })
        {
            Object raw = Global.invoke(source, method);
            if (appendItems(items, raw))
                return;
        }
    }

    private static boolean appendItems(List<String> items, Object raw)
    {
        Iterator<?> it = toIterator(raw);
        if (it == null)
            return false;
        while (it.hasNext())
        {
            Object item = it.next();
            if (item == null)
                continue;
            String label = asString(item);
            if (label.isEmpty())
            {
                for (String method : new String[] { "getLabel", "getName", "getPresentation", "getDisplayName" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                {
                    label = asString(Global.invoke(item, method));
                    if (!label.isEmpty())
                        break;
                }
            }
            addItem(items, label.isEmpty() ? item.toString() : label);
        }
        return !items.isEmpty();
    }

    private static Kind detectKindFromControl(Control control)
    {
        if (control == null || control.isDisposed())
            return Kind.TEXT;
        if (control instanceof Button && (((Button) control).getStyle() & SWT.CHECK) != 0)
            return Kind.BOOLEAN;
        if (control instanceof Combo || control instanceof CCombo)
            return Kind.COMBO;
        if (control instanceof Link)
            return Kind.HYPERLINK;
        if (control instanceof Spinner)
            return Kind.SPINNER;
        return Kind.TEXT;
    }

    private static String nativeControlText(Control control)
    {
        return control != null ? PropertySheetControlInterop.controlText(control) : ""; //$NON-NLS-1$
    }

    static Kind kindFromViewPublic(Object valueView)
    {
        return kindFromView(valueView);
    }

    private static Kind kindFromView(Object valueView)
    {
        if (valueView == null)
            return null;
        String cn = valueView.getClass().getName();
        if (cn.contains("LwtLinkView") || cn.contains("SwtLinkView") || cn.contains("LinkView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Kind.HYPERLINK;
        if (cn.contains("LwtTextView") || cn.contains("SwtTextView") || cn.contains("TextView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Kind.TEXT;
        if (cn.contains("LwtCheckboxView") || cn.contains("SwtCheckBoxView") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LwtCheckableLabelView") || cn.contains("SwtCheckableLabelView")) //$NON-NLS-1$ //$NON-NLS-2$
            return Kind.BOOLEAN;
        if (cn.contains("LwtComboView") || cn.contains("SwtComboView") || cn.contains("ImageComboView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Kind.COMBO;
        if (cn.contains("SpinnerView")) //$NON-NLS-1$
            return Kind.SPINNER;
        return null;
    }

    private static boolean isLinkType(Object valueVm, Object valueView, Object model)
    {
        if (kindFromView(valueView) == Kind.HYPERLINK)
            return true;
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("LinkViewModel") || cn.contains("Hyperlink") || cn.contains("LinkValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("LinkEditor") || cn.contains("OpenEditor") || cn.contains("ModuleLink") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("ReferenceEditor") || cn.contains("LwtLinkView") || cn.contains("SwtLinkView"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isTextType(Object valueVm, Object valueView, Object model)
    {
        if (kindFromView(valueView) == Kind.TEXT)
            return true;
        String cn = typeNames(valueVm, valueView, model);
        if (cn.contains("LinkViewModel") || cn.contains("LwtLinkView") || cn.contains("SwtLinkView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        return cn.contains("StringValueControlViewModel") || cn.contains("ValueControlViewModel") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("TextViewModel") || cn.contains("StringValue") || cn.contains("TextValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("MdText") || cn.contains("LightText") || cn.contains("InputValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("EditableString") || cn.contains("StringEditor") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LwtTextView") || cn.contains("SwtTextView") || cn.contains("FormattedText"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void logValueRow(String propName, Kind kind, String kindReason, String display,
            Object valueVm, Object valueView, Control nativeValue, String resolvePath)
    {
        if (propName == null || propName.isEmpty())
            return;
        PropertySheetDebug.valueControl(PropertySheetDebug.quote(propName)
                + " kind=" + kind //$NON-NLS-1$
                + " reason=" + kindReason //$NON-NLS-1$
                + " display=" + PropertySheetDebug.quote(display) //$NON-NLS-1$
                + " vm=" + PropertySheetDebug.typeName(valueVm) //$NON-NLS-1$
                + " view=" + PropertySheetDebug.typeName(valueView) //$NON-NLS-1$
                + " native=" + PropertySheetDebug.quote(nativeControlText(nativeValue)) //$NON-NLS-1$
                + " resolve=" + PropertySheetDebug.quote(resolvePath != null ? resolvePath : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Kind detectKindFromNativeRow(Control nativeValue)
    {
        Composite row = nativeRowOf(nativeValue);
        if (row == null)
            return null;
        NativeRowKinds kinds = new NativeRowKinds();
        collectNativeRowKinds(row, nativeValue, kinds);
        if (kinds.hasText)
            return Kind.TEXT;
        if (kinds.hasCheck)
            return Kind.BOOLEAN;
        if (kinds.hasCombo)
            return Kind.COMBO;
        if (kinds.hasSpinner)
            return Kind.SPINNER;
        if (kinds.hasLink)
            return Kind.HYPERLINK;
        return null;
    }

    private static final class NativeRowKinds
    {
        boolean hasText;
        boolean hasLink;
        boolean hasCheck;
        boolean hasCombo;
        boolean hasSpinner;
    }

    private static Composite nativeRowOf(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Composite row = control instanceof Composite ? (Composite) control : control.getParent();
        while (row != null && !row.isDisposed())
        {
            if (row.getParent() instanceof org.eclipse.swt.custom.ScrolledComposite)
                return row;
            Control[] children = row.getChildren();
            if (children.length >= 2)
                return row;
            row = row.getParent();
        }
        return control.getParent();
    }

    private static void collectNativeRowKinds(Composite row, Control skip, NativeRowKinds kinds)
    {
        if (row == null || row.isDisposed() || kinds == null)
            return;
        for (Control child : row.getChildren())
        {
            if (child == null || child.isDisposed() || child == skip)
                continue;
            if (child instanceof Text)
                kinds.hasText = true;
            else if (child instanceof Link)
                kinds.hasLink = true;
            else if (child instanceof Button && (((Button) child).getStyle() & SWT.CHECK) != 0)
                kinds.hasCheck = true;
            else if (child instanceof Combo || child instanceof CCombo)
                kinds.hasCombo = true;
            else if (child instanceof Spinner)
                kinds.hasSpinner = true;
            else if (child instanceof Composite)
                collectNativeRowKinds((Composite) child, skip, kinds);
        }
    }

    static boolean isOpenPlaceholder(String text)
    {
        return "Открыть".equals(text); //$NON-NLS-1$
    }

    private static boolean isComboType(Object valueVm, Object valueView, Object model)
    {
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("Combo") || cn.contains("Enum") || cn.contains("Choice") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("ListValue") || cn.contains("Select") || cn.contains("Literal") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("LightCombo"); //$NON-NLS-1$
    }

    private static boolean isBooleanType(Object valueVm, Object valueView, Object model)
    {
        if (model != null && Global.invoke(model, "getValue") instanceof Boolean) //$NON-NLS-1$
            return true;
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("Boolean") || cn.contains("CheckBox") || cn.contains("Checkbox") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Toggle") || cn.contains("LightCheckBox") || cn.contains("MdCheck"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isNumericType(Object valueVm, Object valueView, Object model, String displayValue)
    {
        String cn = typeNames(valueVm, valueView, model);
        if (cn.contains("Spinner") || cn.contains("IntegerValue") || cn.contains("Decimal") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("NumberValue") || cn.contains("Numeric") || cn.contains("LightSpinner")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        if (displayValue != null && !displayValue.isEmpty() && displayValue.matches("-?\\d+(?:[.,]\\d+)?")) //$NON-NLS-1$
        {
            String cnAll = typeNames(valueVm, valueView, model);
            return !cnAll.contains("Enum") && !cnAll.contains("Combo") && !cnAll.contains("Code"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return false;
    }

    private static boolean hasComboItems(Object model, Object valueVm)
    {
        List<String> items = new ArrayList<>();
        collectItems(items, model);
        if (items.size() < 2 && valueVm != null)
            collectItems(items, valueVm);
        return items.size() >= 2;
    }

    private static String typeNames(Object valueVm, Object valueView, Object model)
    {
        StringBuilder sb = new StringBuilder();
        if (valueVm != null)
            sb.append(valueVm.getClass().getName());
        if (valueView != null)
            sb.append(valueView.getClass().getName());
        if (model != null)
            sb.append(model.getClass().getName());
        return sb.toString();
    }

    private static String firstNonEmpty(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isEmpty())
                return value;
        }
        return ""; //$NON-NLS-1$
    }

    private static int indexOf(List<String> items, String value)
    {
        if (value == null || items == null)
            return -1;
        for (int i = 0; i < items.size(); i++)
        {
            if (value.equals(items.get(i)))
                return i;
        }
        return -1;
    }

    private static void addItem(List<String> items, String item)
    {
        if (item == null || item.isEmpty())
            return;
        if (!items.contains(item))
            items.add(item);
    }

    private static String asString(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof String)
            return (String) value;
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        Object text = Global.invoke(value, "getText"); //$NON-NLS-1$
        if (text instanceof String && !((String) text).isEmpty())
            return (String) text;
        Object label = Global.invoke(value, "getLabel"); //$NON-NLS-1$
        if (label instanceof String && !((String) label).isEmpty())
            return (String) label;
        Object name = Global.invoke(value, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String) name).isEmpty())
            return (String) name;
        String asString = value.toString();
        return asString != null ? asString : ""; //$NON-NLS-1$
    }

    private static Iterator<?> toIterator(Object raw)
    {
        if (raw instanceof Iterable)
            return ((Iterable<?>) raw).iterator();
        if (raw instanceof Object[])
        {
            Object[] arr = (Object[]) raw;
            List<Object> list = new ArrayList<>(arr.length);
            for (Object o : arr)
                list.add(o);
            return list.iterator();
        }
        return null;
    }

    private static String escapeLinkText(String text)
    {
        return text.replace("&", "&&"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String stripLinkMarkup(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace("<a>", "").replace("</a>", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}

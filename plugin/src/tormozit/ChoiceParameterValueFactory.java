package tormozit;

import java.math.BigDecimal;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.EcoreUtil2;

import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.FixedArrayValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.BasicDbObjectTypes;
import com._1c.g5.v8.dt.metadata.mdtype.EmptyRef;
import com._1c.g5.v8.dt.metadata.mdtype.EnumTypes;

/**
 * Создание пустых {@link Value} по {@link TypeItem} — логика как в EDT
 * {@code TypeSelectionEditor.getValue}, через публичные API.
 */
final class ChoiceParameterValueFactory
{
    private static final Set<String> REFERENCE_CATEGORIES = Set.of(
            "CatalogRef", //$NON-NLS-1$
            "EnumRef", //$NON-NLS-1$
            "ChartOfCharacteristicTypesRef", //$NON-NLS-1$
            "ChartOfCalculationTypesRef", //$NON-NLS-1$
            "BusinessProcessRoutePointRef", //$NON-NLS-1$
            "ChartOfAccountsRef"); //$NON-NLS-1$

    TypeItem pickType(Field field)
    {
        if (field == null)
            return null;
        TypeDescription td = field.getType();
        if (td == null || td.getTypes().isEmpty())
            return null;
        return td.getTypes().get(0);
    }

    Value createDefault(TypeItem typeItem)
    {
        if (typeItem == null)
            return null;

        String typeName = McoreUtil.getTypeName(typeItem);
        if (typeName != null)
        {
            switch (typeName)
            {
                case "Boolean": //$NON-NLS-1$
                    BooleanValue bool = McoreFactory.eINSTANCE.createBooleanValue();
                    bool.setValue(false);
                    return bool;
                case "String": //$NON-NLS-1$
                    StringValue str = McoreFactory.eINSTANCE.createStringValue();
                    str.setValue(""); //$NON-NLS-1$
                    return str;
                case "Number": //$NON-NLS-1$
                    NumberValue num = McoreFactory.eINSTANCE.createNumberValue();
                    num.setValue(BigDecimal.ZERO);
                    return num;
                case "Date": //$NON-NLS-1$
                    return McoreFactory.eINSTANCE.createDateValue();
                case "FixedArray": //$NON-NLS-1$
                    return McoreFactory.eINSTANCE.createFixedArrayValue();
                default:
                    break;
            }
        }

        String category = McoreUtil.getTypeCategory(typeItem);
        if (category != null && REFERENCE_CATEGORIES.contains(category))
        {
            EmptyRef emptyRef = getEmptyRef(typeItem);
            if (emptyRef == null)
            {
                ChoiceParametersDebug.log("createDefault: no EmptyRef for " + category); //$NON-NLS-1$
                return null;
            }
            ReferenceValue ref = McoreFactory.eINSTANCE.createReferenceValue();
            ref.setValue(emptyRef);
            return ref;
        }

        ChoiceParametersDebug.log("createDefault: unsupported type " //$NON-NLS-1$
                + ChoiceParametersDebug.quote(typeName) + " / " + ChoiceParametersDebug.quote(category)); //$NON-NLS-1$
        return null;
    }

    boolean sameValueType(Value current, TypeItem expectedType)
    {
        if (current == null || expectedType == null)
            return false;

        String expectedKey = typeKey(expectedType);
        if (expectedKey == null)
            return false;

        if (current instanceof ReferenceValue)
        {
            if (!isReferenceKey(expectedKey))
                return false;
            MdObject curMd = resolveMdObject(((ReferenceValue) current).getValue());
            MdObject expMd = EcoreUtil2.getContainerOfType(expectedType, MdObject.class);
            return curMd != null && expMd != null && (curMd == expMd || curMd.equals(expMd));
        }

        String currentKey = valueTypeKey(current);
        return currentKey != null && currentKey.equals(expectedKey);
    }

    private static String typeKey(TypeItem typeItem)
    {
        String typeName = McoreUtil.getTypeName(typeItem);
        if (typeName != null && !typeName.isEmpty())
            return typeName;
        return McoreUtil.getTypeCategory(typeItem);
    }

    private static boolean isReferenceKey(String key)
    {
        return key != null && REFERENCE_CATEGORIES.contains(key);
    }

    private static String valueTypeKey(Value value)
    {
        if (value instanceof BooleanValue)
            return "Boolean"; //$NON-NLS-1$
        if (value instanceof StringValue)
            return "String"; //$NON-NLS-1$
        if (value instanceof NumberValue)
            return "Number"; //$NON-NLS-1$
        if (value instanceof DateValue)
            return "Date"; //$NON-NLS-1$
        if (value instanceof FixedArrayValue)
            return "FixedArray"; //$NON-NLS-1$
        return null;
    }

    private static EmptyRef getEmptyRef(TypeItem typeItem)
    {
        MdObject md = EcoreUtil2.getContainerOfType(typeItem, MdObject.class);
        if (md == null)
            return null;

        EStructuralFeature feature = md.eClass().getEStructuralFeature("producedTypes"); //$NON-NLS-1$
        if (feature == null)
            return null;

        Object produced = md.eGet(feature);
        if (produced instanceof BasicDbObjectTypes)
            return ((BasicDbObjectTypes) produced).getRefType().getEmptyRef();
        if (produced instanceof EnumTypes)
            return ((EnumTypes) produced).getRefType().getEmptyRef();
        return null;
    }

    private static MdObject resolveMdObject(EObject ref)
    {
        if (ref == null)
            return null;
        return EcoreUtil2.getContainerOfType(ref, MdObject.class);
    }
}

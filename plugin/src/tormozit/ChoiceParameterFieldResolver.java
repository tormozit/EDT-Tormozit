package tormozit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.xtext.EcoreUtil2;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.ui.aef.models.IChoiceParametersModel;
import com._1c.g5.v8.dt.md.ui.aef.providers.FieldLabelProvider;
import com._1c.g5.v8.dt.md.ui.aef.providers.ScriptVariantProvider;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.ChoiceParametersViewModel;
import com._1c.g5.v8.dt.md.ui.controls.value.ValueRecord;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;

/**
 * Карта имён параметров выбора ({@code Отбор.*} / {@code Filter.*}) → {@link Field}.
 */
final class ChoiceParameterFieldResolver
{
    private static final String PREFIX_RU = "Отбор."; //$NON-NLS-1$
    private static final String PREFIX_EN = "Filter."; //$NON-NLS-1$

    private ChoiceParameterFieldResolver() {}

    static Map<String, Field> buildMap(ChoiceParametersViewModel viewModel, IV8Project v8Project)
    {
        if (viewModel == null || v8Project == null)
            return Collections.emptyMap();

        ValueRecord record = viewModel.getRecord();
        if (record == null || record.typeDescription == null)
            return Collections.emptyMap();

        FieldSource selfSource = findSelfFieldSource(record.typeDescription);
        if (selfSource == null)
        {
            ChoiceParametersDebug.log("buildMap: FieldSource not found"); //$NON-NLS-1$
            return Collections.emptyMap();
        }

        Object[] elements = IChoiceParametersModel.COMPLETION_PROVIDER.getElements(selfSource);
        if (elements == null || elements.length == 0)
            return Collections.emptyMap();

        String prefix = v8Project.getScriptVariant() == ScriptVariant.ENGLISH ? PREFIX_EN : PREFIX_RU;
        ILabelProvider labels = new FieldLabelProvider(new ScriptVariantProvider(v8Project));
        Map<String, Field> map = new LinkedHashMap<>();
        try
        {
            for (Object element : elements)
            {
                if (!(element instanceof Field))
                    continue;
                Field field = (Field) element;
                String label = labels.getText(field);
                if (label == null || label.isEmpty())
                    continue;
                map.put(prefix + label, field);
            }
        }
        finally
        {
            labels.dispose();
        }

        ChoiceParametersDebug.log("buildMap: " + map.size() + " fields"); //$NON-NLS-1$ //$NON-NLS-2$
        return map;
    }

    private static FieldSource findSelfFieldSource(TypeDescription typeDescription)
    {
        EList<TypeItem> types = typeDescription.getTypes();
        if (types == null)
            return null;
        for (TypeItem item : types)
        {
            if (!(item instanceof Type))
                continue;
            if (((Type) item).eContainer() instanceof MdRefType)
            {
                FieldSource fs = EcoreUtil2.getContainerOfType(item, FieldSource.class);
                if (fs != null)
                    return fs;
            }
        }
        return null;
    }
}

package tormozit;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.internal.CompareUIPlugin;
import org.eclipse.compare.internal.ViewerDescriptor;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Composite;

/**
 * Модальное сравнение двух строковых фрагментов с редактируемой правой панелью.
 */
final class StringFragmentCompareInput extends CompareEditorInput
{
    private static final String OK_LABEL = "Вставить"; //$NON-NLS-1$
    /** id из {@code plugin.xml} бандла {@code com._1c.g5.v8.dt.bsl.ui}. */
    private static final String BSL_XTEXT_VIEWER_ID =
        "com._1c.g5.v8.dt.bsl.Bsl.compare.contentMergeViewers"; //$NON-NLS-1$
    /** id из {@code fragment.xml} бандла {@code com._1c.g5.v8.dt.bsl.gumtree.ui}. */
    private static final String BSL_SEMANTIC_VIEWER_ID = "BslMergeViewerCreator"; //$NON-NLS-1$

    private final String compareViewerType;
    private final EditableStringCompareElement leftElement;
    private final EditableStringCompareElement rightElement;
    /** Устанавливается только при нажатии OK в диалоге (не опрашивать {@link #okPressed()} вручную). */
    private boolean insertConfirmed;

    StringFragmentCompareInput(String leftText, String rightText, String compareViewerType)
    {
        super(createConfiguration());
        this.compareViewerType = compareViewerType != null && !compareViewerType.isBlank()
            ? compareViewerType
            : "txt"; //$NON-NLS-1$
        leftElement = new EditableStringCompareElement(
            "selection." + this.compareViewerType, leftText, this.compareViewerType, false); //$NON-NLS-1$
        rightElement = new EditableStringCompareElement(
            "clipboard." + this.compareViewerType, rightText, this.compareViewerType, true); //$NON-NLS-1$
    }

    /**
     * Выбор просмотрщика — в момент создания viewer по реальному {@link ICompareInput}:
     * {@code CompareContentViewerSwitchingPane} принимает только дескриптор из списка,
     * найденного для этого input (иначе остаётся «Сравнение по умолчанию»).
     */
    @Override
    public Viewer findContentViewer(Viewer oldViewer, ICompareInput input, Composite parent)
    {
        applyPreferredBslViewerDescriptor(oldViewer, input);
        return super.findContentViewer(oldViewer, input, parent);
    }

    /**
     * Для BSL: GumTree («С учётом семантики»), иначе Xtext («Сравнение встроенного языка»).
     */
    private void applyPreferredBslViewerDescriptor(Viewer oldViewer, Object input)
    {
        if (!"bsl".equals(compareViewerType)) //$NON-NLS-1$
            return;
        try
        {
            CompareConfiguration config = getCompareConfiguration();
            CompareUIPlugin plugin = CompareUIPlugin.getDefault();
            if (config == null || plugin == null)
                return;
            ViewerDescriptor[] descriptors = plugin.findContentViewerDescriptor(
                oldViewer, input, config);
            if (descriptors == null || descriptors.length == 0)
                return;
            ViewerDescriptor preferred = findViewerDescriptorById(
                descriptors, BSL_SEMANTIC_VIEWER_ID);
            if (preferred == null)
                preferred = findViewerDescriptorById(descriptors, BSL_XTEXT_VIEWER_ID);
            if (preferred != null)
                setContentViewerDescriptor(preferred);
        }
        catch (Exception e)
        {
            Global.log("StringFragmentCompareInput.applyPreferredBslViewerDescriptor: " + e); //$NON-NLS-1$
        }
    }

    private static ViewerDescriptor findViewerDescriptorById(
        ViewerDescriptor[] descriptors, String viewerId)
    {
        for (ViewerDescriptor descriptor : descriptors)
        {
            Object config = Global.getField(descriptor, "fConfiguration"); //$NON-NLS-1$
            if (config == null)
                continue;
            Object id = Global.invoke(config, "getAttribute", "id"); //$NON-NLS-1$ //$NON-NLS-2$
            if (viewerId.equals(id))
                return descriptor;
        }
        return null;
    }

    private static CompareConfiguration createConfiguration()
    {
        CompareConfiguration config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(true);
        config.setLeftLabel("Выделенный текст"); //$NON-NLS-1$
        config.setRightLabel("Новый текст"); //$NON-NLS-1$
        return config;
    }

    /**
     * Открывает модальный диалог сравнения.
     *
     * @return текст правой панели после подтверждения, или {@code null} при отмене
     */
    String openDialog()
    {
        insertConfirmed = false;
        CompareUI.openCompareDialog(this);
        if (!insertConfirmed)
            return null;
        IDocument doc = CompareUI.getDocument(rightElement);
        if (doc != null)
            return doc.get();
        return rightElement.getContent();
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor)
    {
        return new DiffNode(null, Differencer.CHANGE, null, leftElement, rightElement);
    }

    @Override
    public String getOKButtonLabel()
    {
        return OK_LABEL;
    }

    @Override
    public boolean okPressed()
    {
        insertConfirmed = true;
        return super.okPressed();
    }

    @Override
    public void cancelPressed()
    {
        insertConfirmed = false;
        super.cancelPressed();
    }

    /**
     * Кнопка «Вставить» доступна сразу: подтверждение не привязано к «грязности» правой панели.
     */
    @Override
    public boolean isSaveNeeded()
    {
        return true;
    }
}

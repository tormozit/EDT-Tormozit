package tormozit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.IModificationDate;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.graphics.Image;

/**
 * Строковый элемент для модального сравнения через Eclipse Compare API.
 * {@link #getType()} возвращает расширение файла (например {@code bsl}) для выбора merge-viewer.
 */
final class EditableStringCompareElement
    implements ITypedElement, IStreamContentAccessor, IEncodedStreamContentAccessor,
        IEditableContent, IModificationDate
{
    private static final long MODIFICATION_DATE = System.currentTimeMillis();

    private String content;
    private final String name;
    private final String compareViewerType;
    private final boolean editable;

    EditableStringCompareElement(String name, String content, String compareViewerType, boolean editable)
    {
        this.name = name;
        this.content = content != null ? content : ""; //$NON-NLS-1$
        this.compareViewerType = compareViewerType;
        this.editable = editable;
    }

    String getContent()
    {
        return content;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public Image getImage()
    {
        return null;
    }

    @Override
    public String getType()
    {
        return compareViewerType;
    }

    @Override
    public InputStream getContents()
    {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getCharset()
    {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public long getModificationDate()
    {
        return MODIFICATION_DATE;
    }

    @Override
    public boolean isEditable()
    {
        return editable;
    }

    @Override
    public void setContent(byte[] newContent)
    {
        if (!editable)
            return;
        content = newContent != null
            ? new String(newContent, StandardCharsets.UTF_8)
            : ""; //$NON-NLS-1$
    }

    @Override
    public ITypedElement replace(ITypedElement dest, ITypedElement src)
    {
        if (!(dest instanceof EditableStringCompareElement destElem))
            return dest;
        if (src == null)
            return dest;
        if (src instanceof EditableStringCompareElement srcElem)
            destElem.content = srcElem.content;
        else if (src instanceof IStreamContentAccessor accessor)
        {
            try (InputStream in = accessor.getContents())
            {
                if (in != null)
                    destElem.content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            catch (IOException | CoreException e)
            {
                Global.log("EditableStringCompareElement.replace: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return dest;
    }
}

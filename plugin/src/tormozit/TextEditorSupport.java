package tormozit;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Поиск активного текстового редактора (в т.ч. вложенного в {@link DtGranularEditor})
 * и замена выделенного фрагмента.
 */
public final class TextEditorSupport
{
    /** Расширение файла для выбора merge-viewer Eclipse Compare (см. {@code extensions} в plugin.xml EDT). */
    private static final String FALLBACK_COMPARE_EXTENSION = "txt"; //$NON-NLS-1$
    private static final String BSL_COMPARE_EXTENSION = "bsl"; //$NON-NLS-1$
    /** Расширение языка запросов EDT ({@code com._1c.g5.v8.dt.ql}). */
    static final String QL_COMPARE_EXTENSION = "ql"; //$NON-NLS-1$

    private TextEditorSupport()
    {
    }

    /** Контекст активного текстового редактора. */
    public static final class Context
    {
        public final ITextEditor editor;
        public final ISourceViewer viewer;
        public final int offset;
        public final int length;
        public final String selectedText;
        /** Расширение для {@link org.eclipse.compare.ITypedElement#getType()} (например {@code bsl}). */
        public final String compareViewerType;
        public final boolean editable;

        Context(ITextEditor editor, ISourceViewer viewer, int offset, int length,
            String selectedText, String compareViewerType, boolean editable)
        {
            this.editor = editor;
            this.viewer = viewer;
            this.offset = offset;
            this.length = length;
            this.selectedText = selectedText;
            this.compareViewerType = compareViewerType;
            this.editable = editable;
        }
    }

    /**
     * Разрешает {@link ITextEditor} из активной части и/или редактора Workbench.
     */
    public static Context resolveContext(IWorkbenchPart part, IEditorPart editorPart)
    {
        ITextEditor textEditor = resolveTextEditor(part);
        if (textEditor == null && editorPart != null)
            textEditor = resolveTextEditor(editorPart);
        if (textEditor == null)
            return null;
        return buildContext(textEditor);
    }

    /**
     * Контекст для встроенного {@link ISourceViewer} без {@link ITextEditor}
     * (например «Редактор запроса»).
     */
    public static Context buildContext(
        ISourceViewer viewer, String compareViewerType, boolean editable)
    {
        if (viewer == null)
            return null;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return null;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return null;

        int offset = textSel.getOffset();
        int length = textSel.getLength();
        String selected = textSel.getText();
        if (selected == null)
            selected = ""; //$NON-NLS-1$

        String ext = compareViewerType != null && !compareViewerType.isBlank()
            ? compareViewerType
            : FALLBACK_COMPARE_EXTENSION;

        return new Context(
            null,
            viewer,
            offset,
            length,
            selected,
            ext,
            editable);
    }

    public static ITextEditor resolveTextEditor(IWorkbenchPart part)
    {
        if (part instanceof ITextEditor textEditor)
            return textEditor;
        if (part instanceof DtGranularEditor<?> granular)
            return embeddedTextEditor(granular);
        return null;
    }

    private static ITextEditor embeddedTextEditor(DtGranularEditor<?> granular)
    {
        IFormPage activePage = granular.getActivePageInstance();
        if (!(activePage instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return null;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof ITextEditor textEditor)
            return textEditor;
        return null;
    }

    private static Context buildContext(ITextEditor editor)
    {
        ISourceViewer viewer = getSourceViewer(editor);
        if (viewer == null)
            return null;

        IDocument doc = viewer.getDocument();
        if (doc == null)
            return null;

        Object sel = viewer.getSelectionProvider().getSelection();
        if (!(sel instanceof ITextSelection textSel))
            return null;

        int offset = textSel.getOffset();
        int length = textSel.getLength();
        String selected = textSel.getText();
        if (selected == null)
            selected = ""; //$NON-NLS-1$

        return new Context(
            editor,
            viewer,
            offset,
            length,
            selected,
            resolveCompareViewerType(editor),
            editor.isEditable());
    }

    public static ISourceViewer getSourceViewer(ITextEditor editor)
    {
        if (editor instanceof BslXtextEditor bslEditor)
            return bslEditor.getInternalSourceViewer();

        ISourceViewer adapted = editor.getAdapter(ISourceViewer.class);
        if (adapted != null)
            return adapted;

        Object viewer = Global.invoke(editor, "getSourceViewer"); //$NON-NLS-1$
        if (viewer instanceof ISourceViewer sourceViewer)
            return sourceViewer;

        return null;
    }

    /**
     * Тип элемента сравнения для Eclipse Compare — <b>расширение файла</b>, не content type ID.
     * Для BSL EDT регистрирует merge-viewer с {@code extensions="bsl"}.
     */
    static String resolveCompareViewerType(ITextEditor editor)
    {
        try
        {
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                {
                    String ext = file.getFileExtension();
                    if (ext != null && !ext.isBlank())
                        return ext;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("TextEditorSupport.resolveCompareViewerType: " + e); //$NON-NLS-1$
        }

        if (editor instanceof BslXtextEditor)
            return BSL_COMPARE_EXTENSION;

        return FALLBACK_COMPARE_EXTENSION;
    }

    /**
     * Заменяет выделенный фрагмент и выделяет вставленный диапазон.
     */
    public static void replaceSelectionAndSelect(Context ctx, String newText)
    {
        if (ctx == null || newText == null)
            return;

        IDocument doc = ctx.viewer.getDocument();
        if (doc == null)
            return;

        if (doc instanceof IXtextDocument xtextDoc)
        {
            xtextDoc.modify(resource ->
            {
                try
                {
                    xtextDoc.replace(ctx.offset, ctx.length, newText);
                }
                catch (BadLocationException e)
                {
                    throw new RuntimeException("Ошибка замены текста", e); //$NON-NLS-1$
                }
                return null;
            });
        }
        else
        {
            try
            {
                doc.replace(ctx.offset, ctx.length, newText);
            }
            catch (BadLocationException e)
            {
                Global.log("TextEditorSupport.replaceSelectionAndSelect: " + e); //$NON-NLS-1$
                return;
            }
        }

        int newLength = newText.length();
        ctx.viewer.getSelectionProvider().setSelection(
            new org.eclipse.jface.text.TextSelection(ctx.offset, newLength));
        ctx.viewer.setSelectedRange(ctx.offset, newLength);
    }

    public static String readClipboardText(Shell shell)
    {
        Display display = shell != null ? shell.getDisplay() : Display.getDefault();
        if (display == null || display.isDisposed())
            return null;
        Clipboard cb = new Clipboard(display);
        try
        {
            return (String) cb.getContents(TextTransfer.getInstance());
        }
        finally
        {
            cb.dispose();
        }
    }

    public static boolean clipboardHasText(Shell shell)
    {
        String text = readClipboardText(shell);
        return text != null && !text.isEmpty();
    }
}

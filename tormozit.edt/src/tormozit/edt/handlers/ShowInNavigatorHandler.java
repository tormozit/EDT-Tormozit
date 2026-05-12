package tormozit.edt.handlers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import tormozit.edt.selection.CompareEditorSelectionProvider;

public class ShowInNavigatorHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        showInNavigator(HandlerUtil.getActiveEditor(event), HandlerUtil.getActiveShell(event));
        return null;
    }
    public static void showInNavigator(IEditorPart editor, Shell shell) {
        ((CompareEditorSelectionProvider) editor.getSite().getSelectionProvider()).showObjectInNavigator(OpenObjectHandler.getSelection(editor), true);
    }
}

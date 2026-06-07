package tormozit;

import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Поиск SWT-контейнера палитры свойств, когда {@code getNewPaletteContent()} ещё null
 * (типично для LWT до первого render).
 */
final class PropertySheetPaletteHost
{
    static final class Target
    {
        final Composite host;
        final Control nativeControl;
        final String via;

        Target(Composite host, Control nativeControl, String via)
        {
            this.host = host;
            this.nativeControl = nativeControl;
            this.via = via != null ? via : ""; //$NON-NLS-1$
        }

        boolean isValid()
        {
            return host != null && !host.isDisposed();
        }
    }

    private PropertySheetPaletteHost() {}

    static Target resolve(Object page)
    {
        if (page == null)
            return new Target(null, null, "page=null"); //$NON-NLS-1$

        Target target;

        target = fromScrolled(Global.invoke(page, "getNewPaletteScrolledComposite"), "getNewPaletteScrolledComposite"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target.isValid())
            return target;

        target = fromScrolled(Global.getField(page, "newPaletteScrolledComposite"), "field.newPaletteScrolledComposite"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target.isValid())
            return target;

        target = fromNative(asControl(Global.invoke(page, "getNewPaletteContent")), "getNewPaletteContent"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target.isValid())
            return target;

        target = fromNative(asControl(Global.getField(page, "newPaletteContent")), "field.newPaletteContent"); //$NON-NLS-1$ //$NON-NLS-2$
        if (target.isValid())
            return target;

        target = fromRenderer(page);
        if (target.isValid())
            return target;

        Composite paletteRoot = PropertySheetUiContext.findPaletteRoot(page);
        target = fromNative(paletteRoot, "findPaletteRoot"); //$NON-NLS-1$
        if (target.isValid())
            return target;
        if (paletteRoot != null && !paletteRoot.isDisposed())
            return new Target(paletteRoot, paletteRoot, "findPaletteRoot.self"); //$NON-NLS-1$

        target = fromBoxComposite(page);
        if (target.isValid())
            return target;

        Composite root = asComposite(Global.getField(page, "rootComposite")); //$NON-NLS-1$
        if (root == null)
            root = asComposite(Global.invoke(page, "getRootComposite")); //$NON-NLS-1$
        if (root != null && !root.isDisposed())
            return new Target(root, null, "rootComposite"); //$NON-NLS-1$

        PropertySheetDebug.uiVerbose("paletteHost MISS page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
        return new Target(null, null, "none"); //$NON-NLS-1$
    }

    private static Target fromRenderer(Object page)
    {
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null;
        Control composite = asControl(renderer != null ? Global.invoke(renderer, "getComposite") : null); //$NON-NLS-1$
        if (composite == null || composite.isDisposed())
            return new Target(null, null, "renderer.getComposite"); //$NON-NLS-1$

        Control nativeControl = composite;
        Control current = composite;
        ScrolledComposite scrolledAncestor = null;
        for (Composite parent = composite.getParent(); parent != null; parent = parent.getParent())
        {
            if (parent instanceof ScrolledComposite)
                scrolledAncestor = (ScrolledComposite) parent;
            current = parent;
        }
        if (scrolledAncestor != null && !scrolledAncestor.isDisposed())
            return new Target(scrolledAncestor.getParent(), scrolledAncestor, "renderer.scrolledAncestor"); //$NON-NLS-1$
        if (composite.getParent() != null && !composite.getParent().isDisposed())
            return new Target(composite.getParent(), nativeControl, "renderer.getComposite.parent"); //$NON-NLS-1$
        if (composite instanceof Composite)
            return new Target((Composite) composite, nativeControl, "renderer.getComposite.self"); //$NON-NLS-1$
        return new Target(null, null, "renderer.getComposite"); //$NON-NLS-1$
    }

    private static Target fromBoxComposite(Object page)
    {
        Composite box = asComposite(Global.getField(page, "boxComposite")); //$NON-NLS-1$
        if (box == null || box.isDisposed())
            return new Target(null, null, "boxComposite"); //$NON-NLS-1$
        if (box.getLayout() instanceof StackLayout)
        {
            Control top = ((StackLayout) box.getLayout()).topControl;
            if (top != null && !top.isDisposed())
                return new Target(box, top, "boxComposite.topControl"); //$NON-NLS-1$
        }
        for (Control child : box.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            String cn = child.getClass().getName();
            if (cn.contains("ScrolledComposite") || cn.contains("PropertiesScrolledForm") //$NON-NLS-1$ //$NON-NLS-2$
                    || cn.contains("LayoutComposite") || cn.contains("LightComposite")) //$NON-NLS-1$ //$NON-NLS-2$
                return new Target(box, child, "boxComposite.child:" + child.getClass().getSimpleName()); //$NON-NLS-1$
        }
        return new Target(box, null, "boxComposite"); //$NON-NLS-1$
    }

    private static Target fromScrolled(Object value, String via)
    {
        if (!(value instanceof ScrolledComposite))
            return new Target(null, null, via);
        ScrolledComposite scrolled = (ScrolledComposite) value;
        if (scrolled.isDisposed())
            return new Target(null, null, via);
        Composite host = scrolled.getParent();
        if (host != null && !host.isDisposed())
            return new Target(host, scrolled, via);
        return new Target(null, null, via);
    }

    private static Target fromNative(Control nativeContent, String via)
    {
        if (nativeContent == null || nativeContent.isDisposed())
            return new Target(null, null, via);
        Composite parent = nativeContent.getParent();
        if (parent instanceof ScrolledComposite && parent.getParent() != null && !parent.getParent().isDisposed())
            return new Target(parent.getParent(), parent, via + ".scrolledParent"); //$NON-NLS-1$
        if (parent != null && !parent.isDisposed())
            return new Target(parent, nativeContent, via);
        if (nativeContent instanceof Composite)
            return new Target((Composite) nativeContent, nativeContent, via + ".self"); //$NON-NLS-1$
        return new Target(null, null, via);
    }

    static void hideNative(Control nativeControl)
    {
        if (nativeControl == null || nativeControl.isDisposed())
            return;
        nativeControl.setVisible(false);
        Object layoutData = nativeControl.getLayoutData();
        if (layoutData instanceof org.eclipse.swt.layout.GridData)
            ((org.eclipse.swt.layout.GridData) layoutData).exclude = true;
        Composite parent = nativeControl.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
    }

    static void restoreNative(Control nativeControl)
    {
        if (nativeControl == null || nativeControl.isDisposed())
            return;
        nativeControl.setVisible(true);
        Object layoutData = nativeControl.getLayoutData();
        if (layoutData instanceof org.eclipse.swt.layout.GridData)
            ((org.eclipse.swt.layout.GridData) layoutData).exclude = false;
        Composite parent = nativeControl.getParent();
        if (parent != null && !parent.isDisposed())
            parent.layout(true, true);
    }

    private static Control asControl(Object value)
    {
        return value instanceof Control ? (Control) value : null;
    }

    private static Composite asComposite(Object value)
    {
        return value instanceof Composite ? (Composite) value : null;
    }
}

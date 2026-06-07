package tormozit;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Попытка переключить палитру свойств EDT с LWT renderer на штатный SWT renderer
 * путём пересоздания scene/palette на обычном {@link Composite}.
 */
final class PropertySheetRendererSwitch
{
    private static final String SWITCHED_KEY = "tormozit.propertySheet.swtSwitched"; //$NON-NLS-1$

    private PropertySheetRendererSwitch() {}

    static boolean tryActivateSwt(Object page)
    {
        if (page == null)
            return false;
        if (Boolean.TRUE.equals(Global.getField(page, SWITCHED_KEY)))
            return isSwtRenderer(page);

        try
        {
            return tryActivateSwtInternal(page);
        }
        catch (Throwable e)
        {
            PropertySheetDebug.problem("rendererSwitch ERROR: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean tryActivateSwtInternal(Object page)
    {
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null;
        if (isSwt(renderer))
        {
            Global.setField(page, SWITCHED_KEY, Boolean.TRUE);
            PropertySheetDebug.uiVerbose("rendererSwitch SKIP already SWT"); //$NON-NLS-1$
            return true;
        }

        Object engine = Global.getField(page, "engine"); //$NON-NLS-1$
        Object toolkit = Global.getField(page, "toolkit"); //$NON-NLS-1$
        Object paletteModel = Global.invoke(page, "getPaletteModel"); //$NON-NLS-1$
        Object oldPalette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        if (engine == null || toolkit == null || paletteModel == null || oldPalette == null)
        {
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL missing engine/toolkit/model/palette"); //$NON-NLS-1$
            return false;
        }

        Object transformation = Global.invoke(oldPalette, "getTransformation"); //$NON-NLS-1$
        if (transformation == null)
        {
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL transformation=null"); //$NON-NLS-1$
            return false;
        }

        PropertySheetPaletteHost.Target target = PropertySheetPaletteHost.resolve(page);
        if (!target.isValid())
        {
            PropertySheetDebug.uiVerbose("rendererSwitch WAIT host=null via=" + target.via); //$NON-NLS-1$
            return false;
        }

        Composite host = target.host;
        Control oldContent = target.nativeControl;

        Composite swtRoot = new Composite(host, host.getStyle() & ~org.eclipse.swt.SWT.BORDER);
        if (oldContent != null && !oldContent.isDisposed() && oldContent.getLayoutData() != null)
            swtRoot.setLayoutData(oldContent.getLayoutData());
        else
            GridDataFactory.fillDefaults().grab(true, true).applyTo(swtRoot);
        swtRoot.setVisible(false);

        Object newPalette = createPaletteComponent(page, swtRoot, toolkit, transformation);
        if (newPalette == null)
        {
            swtRoot.dispose();
            PropertySheetPaletteHost.restoreNative(oldContent);
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL palette ctor"); //$NON-NLS-1$
            return false;
        }

        Object swtParams = createSwtRenderingParameters(swtRoot, toolkit, transformation);
        if (swtParams == null)
        {
            disposeQuiet(newPalette);
            swtRoot.dispose();
            PropertySheetPaletteHost.restoreNative(oldContent);
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL swt params"); //$NON-NLS-1$
            return false;
        }

        disposeQuiet(scene);
        Object newScene = Global.invoke(engine, "createScene", newPalette, swtParams); //$NON-NLS-1$
        if (newScene == null)
        {
            disposeQuiet(newPalette);
            swtRoot.dispose();
            PropertySheetPaletteHost.restoreNative(oldContent);
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL scene=null"); //$NON-NLS-1$
            return false;
        }

        renderer = Global.invoke(newScene, "getRenderer"); //$NON-NLS-1$
        if (!isSwt(renderer))
        {
            disposeQuiet(newScene);
            disposeQuiet(newPalette);
            swtRoot.dispose();
            PropertySheetDebug.uiVerbose("rendererSwitch FAIL renderer still " //$NON-NLS-1$
                    + PropertySheetDebug.safe(renderer));
            return false;
        }

        PropertySheetPaletteHost.hideNative(oldContent);
        swtRoot.setVisible(true);
        StackLayout stack = host.getLayout() instanceof StackLayout ? (StackLayout) host.getLayout() : null;
        if (stack != null)
        {
            stack.topControl = swtRoot;
            host.layout(true, true);
        }

        Global.setField(page, "paletteComponent", newPalette); //$NON-NLS-1$
        Global.setField(page, "scene", newScene); //$NON-NLS-1$
        Global.setField(page, "newPaletteContent", swtRoot); //$NON-NLS-1$
        Global.setField(page, SWITCHED_KEY, Boolean.TRUE);

        Global.invoke(newPalette, "setModel", paletteModel); //$NON-NLS-1$
        Global.invokeVoid(newPalette, "refreshChildren"); //$NON-NLS-1$

        PropertySheetDebug.uiVerbose("rendererSwitch OK via=" + target.via //$NON-NLS-1$
                + " renderer=" + PropertySheetDebug.safe(renderer)); //$NON-NLS-1$
        return true;
    }

    static boolean isSwtRenderer(Object page)
    {
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null;
        return isSwt(renderer);
    }

    private static boolean isSwt(Object renderer)
    {
        return renderer != null && renderer.getClass().getName().contains("Swt"); //$NON-NLS-1$
    }

    private static Object createPaletteComponent(Object page, Composite swtRoot, Object toolkit, Object transformation)
    {
        ClassLoader cl = page.getClass().getClassLoader();
        Class<?> paletteClass = resolvePaletteClass(page, cl);
        if (paletteClass == null)
            return null;
        try
        {
            Class<?> toolkitClass = Class.forName(
                    "org.eclipse.ui.forms.widgets.FormToolkit", false, cl); //$NON-NLS-1$
            Class<?> transformClass = Class.forName(
                    "com._1c.g5.aef2.renderers.ITreeTransformation", false, cl); //$NON-NLS-1$
            java.lang.reflect.Constructor<?> ctor = paletteClass.getConstructor(
                    Composite.class, toolkitClass, transformClass);
            return ctor.newInstance(swtRoot, toolkit, transformation);
        }
        catch (Throwable e)
        {
            PropertySheetDebug.uiVerbose("rendererSwitch palette ctor " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static Class<?> resolvePaletteClass(Object page, ClassLoader cl)
    {
        String[] candidates = {
                "com._1c.g5.v8.dt.md.ui.properties.components.MdPropertyPaletteComponent", //$NON-NLS-1$
                "com._1c.g5.properties.ui.PropertyPaletteComponent" //$NON-NLS-1$
        };
        for (String name : candidates)
        {
            try
            {
                Class<?> type = Class.forName(name, false, cl);
                if (type.isInstance(Global.invoke(page, "getPaletteComponent"))) //$NON-NLS-1$
                    return type;
            }
            catch (Throwable ignored) {}
        }
        for (String name : candidates)
        {
            try
            {
                return Class.forName(name, false, cl);
            }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object createSwtRenderingParameters(Composite swtRoot, Object toolkit, Object transformation)
    {
        ClassLoader cl = toolkit.getClass().getClassLoader();
        try
        {
            Class<?> paramsClass = Class.forName(
                    "com._1c.g5.aef2.swt.renderers.SwtRenderingParameters", false, cl); //$NON-NLS-1$
            Class<?> toolkitClass = Class.forName(
                    "org.eclipse.ui.forms.widgets.FormToolkit", false, cl); //$NON-NLS-1$
            Class<?> transformClass = Class.forName(
                    "com._1c.g5.aef2.renderers.ITreeTransformation", false, cl); //$NON-NLS-1$
            java.lang.reflect.Constructor<?> ctor = paramsClass.getConstructor(
                    Composite.class, toolkitClass, transformClass);
            return ctor.newInstance(swtRoot, toolkit, transformation);
        }
        catch (Throwable e)
        {
            PropertySheetDebug.uiVerbose("rendererSwitch swt params " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static Control asControl(Object value)
    {
        return value instanceof Control ? (Control) value : null;
    }

    private static void disposeQuiet(Object target)
    {
        if (target == null)
            return;
        try
        {
            Global.invokeVoid(target, "dispose"); //$NON-NLS-1$
        }
        catch (Throwable ignored) {}
    }
}

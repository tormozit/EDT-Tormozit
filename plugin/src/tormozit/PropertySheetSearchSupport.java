package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Умный фильтр и UI-доработки AEF-палитры свойств EDT
 * ({@code com._1c.g5.properties.ui.PropertySheetPage} / {@code MdPropertySheetPage}).
 *
 * <p>Фильтр — {@link SmartMatcher} поверх {@code ITreeTransformation}.
 * UI — {@link PropertySheetComfortCoordinator} (SWT renderer / Comfort UI + подсветка/selection/copy).
 */
final class PropertySheetSearchSupport
{
    private static final String FILTER_PREF_KEY = "com._1c.g5.properties.ui.filter"; //$NON-NLS-1$
    private static final Map<Object, Object> NATIVE_TRANSFORMS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetSearchSupport() {}

    /** Синхронизация «Новой» вкладки после смены источника — без refreshChildren нативной палитры. */
    static void prepareComfortSync(Object page)
    {
        if (page == null)
            return;
        String pattern = readCurrentPattern(page);
        apply(page, pattern, false);
        PropertySheetDebug.uiVerbose("prepareComfortSync page=" + PropertySheetDebug.safe(page) //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(pattern)); //$NON-NLS-1$
    }

    static void apply(Object page, String pattern)
    {
        apply(page, pattern, true);
    }

    static void apply(Object page, String pattern, boolean refreshNative)
    {
        if (page == null)
            return;

        try
        {
            applyInternal(page, pattern, refreshNative);
        }
        catch (Throwable e)
        {
            PropertySheetDebug.problem("apply ERROR: " + e); //$NON-NLS-1$
        }
    }

    private static String readCurrentPattern(Object page)
    {
        Object searchBox = Global.getField(page, "searchBox"); //$NON-NLS-1$
        if (searchBox == null)
            searchBox = Global.invoke(page, "getSearchBox"); //$NON-NLS-1$
        String pattern = ""; //$NON-NLS-1$
        if (searchBox != null)
        {
            Object text = Global.invoke(searchBox, "getText"); //$NON-NLS-1$
            if (text != null)
                pattern = text.toString();
        }
        if (pattern.isEmpty())
        {
            Object filterText = Global.invoke(page, "getFilterText"); //$NON-NLS-1$
            if (filterText != null)
                pattern = filterText.toString();
        }
        return pattern != null ? pattern : ""; //$NON-NLS-1$
    }

    private static void applyInternal(Object page, String pattern, boolean refreshNative)
    {
        SmartMatcher matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
        PropertySheetDebug.uiVerbose("apply pattern=\"" + matcher.fullPattern + "\" empty=" + matcher.isEmpty //$NON-NLS-1$ //$NON-NLS-2$
                + " refreshNative=" + refreshNative); //$NON-NLS-1$

        Object paletteComponent = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (paletteComponent == null || scene == null)
        {
            PropertySheetDebug.uiVerbose("apply WAIT: palette=" + PropertySheetDebug.safe(paletteComponent) //$NON-NLS-1$
                    + " scene=" + PropertySheetDebug.safe(scene)); //$NON-NLS-1$
            return;
        }

        if (matcher.isEmpty)
        {
            if (refreshNative)
                clearNativeFilterPreference(page);
            NATIVE_TRANSFORMS.remove(page);
            restoreNativeTransform(page, paletteComponent, scene);
            if (refreshNative)
            {
                Global.invoke(page, "setFilterText", ""); //$NON-NLS-1$ //$NON-NLS-2$
                refreshPalette(paletteComponent);
                forceRestoreAfterClear(page, paletteComponent, scene, matcher);
            }
            scheduleUiSync(page, matcher);
            PropertySheetDebug.uiVerbose("apply empty sync refreshNative=" + refreshNative); //$NON-NLS-1$
            return;
        }

        Object renderer = Global.invoke(scene, "getRenderer"); //$NON-NLS-1$
        if (renderer == null)
            return;

        if (refreshNative)
            clearNativeFilterPreference(page);
        else
            NATIVE_TRANSFORMS.remove(page);

        Object baseTransform = resolveBaseTransform(page, paletteComponent);
        if (baseTransform == null)
        {
            PropertySheetDebug.problem("apply FAIL: baseTransform=null"); //$NON-NLS-1$
            return;
        }

        Object wrapped = wrapTransformation(page, baseTransform, matcher, scene);
        NATIVE_TRANSFORMS.putIfAbsent(page, baseTransform);
        Global.invoke(renderer, "setTreeTransformation", wrapped); //$NON-NLS-1$
        if (refreshNative)
            refreshPalette(paletteComponent);
        scheduleUiSync(page, matcher);

        PropertySheetDebug.uiVerbose("apply done transform=" + PropertySheetDebug.safe(wrapped) //$NON-NLS-1$
                + " refreshNative=" + refreshNative); //$NON-NLS-1$
    }

    private static void scheduleUiSync(Object page, SmartMatcher matcher)
    {
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            if (page == null)
                return;
            Object palette = Global.invoke(page, "getPaletteComponent"); //$NON-NLS-1$
            Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
            if (palette == null || scene == null)
                return;
            PropertySheetComfortCoordinator.scheduleRefresh(page, matcher);
        });
    }

    private static Object resolveBaseTransform(Object page, Object paletteComponent)
    {
        Object stored = NATIVE_TRANSFORMS.get(page);
        if (stored != null)
            return stored;
        return Global.invoke(paletteComponent, "getTransformation"); //$NON-NLS-1$
    }

    private static void restoreNativeTransform(Object page, Object paletteComponent, Object scene)
    {
        Object base = NATIVE_TRANSFORMS.get(page);
        if (base == null)
            base = Global.invoke(paletteComponent, "getTransformation"); //$NON-NLS-1$

        Object renderer = scene != null ? Global.invoke(scene, "getRenderer") : null; //$NON-NLS-1$
        if (renderer != null && base != null)
            Global.invoke(renderer, "setTreeTransformation", base); //$NON-NLS-1$

        NATIVE_TRANSFORMS.remove(page);
        PropertySheetDebug.uiVerbose("restoreNativeTransform base=" + PropertySheetDebug.safe(base)); //$NON-NLS-1$
    }

    private static void refreshPalette(Object paletteComponent)
    {
        Global.invokeVoid(paletteComponent, "refreshChildren"); //$NON-NLS-1$
    }

    private static void forceRestoreAfterClear(Object page, Object paletteComponent, Object scene, SmartMatcher matcher)
    {
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        Runnable pass = () -> {
            if (page == null)
                return;
            clearNativeFilterPreference(page);
            restoreNativeTransform(page, paletteComponent, scene);
            refreshPalette(paletteComponent);
            scheduleUiSync(page, matcher);
            PropertySheetDebug.uiVerbose("apply empty forceRestore pass"); //$NON-NLS-1$
        };
        display.timerExec(80, pass);
        display.timerExec(180, pass);
    }

    private static ClassLoader pageClassLoader(Object page)
    {
        if (page != null)
            return page.getClass().getClassLoader();
        return PropertySheetSearchSupport.class.getClassLoader();
    }

    private static ClassLoader aefClassLoader(Object scene, Object fallback)
    {
        if (scene != null)
            return scene.getClass().getClassLoader();
        if (fallback != null)
            return fallback.getClass().getClassLoader();
        return PropertySheetSearchSupport.class.getClassLoader();
    }

    private static Class<?> loadAefClass(Object scene, Object fallback, String name) throws ClassNotFoundException
    {
        return Class.forName(name, false, aefClassLoader(scene, fallback));
    }

    private static void clearNativeFilterPreference(Object page)
    {
        setPreferencesFilterValue(page, ""); //$NON-NLS-1$
    }

    private static void setPreferencesFilterValue(Object page, String value)
    {
        try
        {
            Class<?> pluginClass = Class.forName(
                    "com._1c.g5.properties.ui.PropertiesUiPlugin", false, pageClassLoader(page)); //$NON-NLS-1$
            Object plugin = Global.invoke(pluginClass, "getDefault"); //$NON-NLS-1$
            Object store = Global.invoke(plugin, "getPreferenceStore"); //$NON-NLS-1$
            if (store != null)
                Global.invoke(store, "setValue", FILTER_PREF_KEY, value != null ? value : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Throwable ignored) {}
    }

    private static Object wrapTransformation(Object page, Object baseTransform, SmartMatcher matcher, Object scene)
    {
        ClassLoader cl = aefClassLoader(scene, baseTransform);
        Class<?> iface;
        try
        {
            iface = loadAefClass(scene, baseTransform, "com._1c.g5.aef2.renderers.ITreeTransformation"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            return baseTransform;
        }

        InvocationHandler handler = new InvocationHandler()
        {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
            {
                String name = method.getName();
                if ("getChildren".equals(name)) //$NON-NLS-1$
                {
                    clearNativeFilterPreference(page);
                    try
                    {
                        Object result = method.invoke(baseTransform, args);
                        // args[0] — родительский viewModel.
                        // Если его дети — это LabelViewModel/ValueViewModel (leaf-пара),
                        // не фильтруем: иначе ValueViewModel будет удалён и вместо
                        // значения отобразится вторая колонка имён.
                        Object parent = (args != null && args.length > 0) ? args[0] : null;
                        if (PropertySheetViewModelTree.isFieldRowViewModel(parent))
                            return result; // детей поля не трогаем
                        return filterViewModels(result, matcher);
                    }
                    finally
                    {
                        clearNativeFilterPreference(page);
                    }
                }
                if ("getRoots".equals(name)) //$NON-NLS-1$
                {
                    clearNativeFilterPreference(page);
                    try
                    {
                        Object result = method.invoke(baseTransform, args);
                        return filterViewModels(result, matcher);
                    }
                    finally
                    {
                        clearNativeFilterPreference(page);
                    }
                }
                return method.invoke(baseTransform, args);
            }
        };

        return Proxy.newProxyInstance(cl, new Class<?>[] { iface }, handler);
    }

    private static Object filterViewModels(Object result, SmartMatcher matcher)
    {
        if (result == null || matcher.isEmpty)
            return result;

        List<Object> kept = new ArrayList<>();
        Iterator<?> it = toIterator(result);
        if (it == null)
            return result;

        while (it.hasNext())
        {
            Object vm = it.next();
            if (vm == null)
                continue;
            if (viewModelMatches(vm, matcher) || subtreeHasMatch(vm, matcher))
                kept.add(vm);
        }
        return kept;
    }

    private static boolean subtreeHasMatch(Object viewModel, SmartMatcher matcher)
    {
        if (viewModel == null)
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
            if (viewModelMatches(child, matcher) || subtreeHasMatch(child, matcher))
                return true;
        }
        return false;
    }

    private static boolean viewModelMatches(Object viewModel, SmartMatcher matcher)
    {
        String text = SmartTreeElementLabels.resolve(viewModel, null);
        if (matcher.matches(text))
            return true;
        Object value = Global.invoke(viewModel, "getValue"); //$NON-NLS-1$
        if (value != null)
        {
            String valueText = value.toString();
            if (matcher.matches(valueText))
                return true;
        }
        return false;
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
}

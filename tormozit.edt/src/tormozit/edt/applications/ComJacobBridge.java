package tormozit.edt.applications;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Рефлексивная обёртка над Jacob (Java COM Bridge).
 *
 * <h3>Classloader в OSGi</h3>
 * {@code Thread.currentThread().getContextClassLoader()} — системный classloader,
 * не видит {@code Bundle-ClassPath}. Правильно: {@code ComJacobBridge.class.getClassLoader()}
 * — это Equinox BundleClassLoader, который знает про {@code lib/jacob.jar}.
 *
 * <h3>DLL-загрузка</h3>
 * Получаем корень бандла через {@code bundle.getEntry("/")} + {@link FileLocator#toFileURL},
 * затем ищем DLL в {@code lib/}. {@code System.load(absolutePath)} вызывается
 * до первой загрузки класса Jacob, чтобы JVM не искала DLL повторно.
 */
public final class ComJacobBridge
{
    private ComJacobBridge() {}

    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String  unavailableReason;

    private static Class<?> classActiveXComponent;
    private static Class<?> classDispatch;
    private static Class<?> classVariant;
    private static Class<?> classComThread;
    private static Method   methodDispatchCall;
    private static Method   methodDispatchPut;
    private static Method   methodDispatchGet;
    private static Method   methodVariantToBoolean;
    private static Method   methodComThreadInitSTA;
    private static Method   methodComThreadRelease;

    // -----------------------------------------------------------------------
    // Инициализация
    // -----------------------------------------------------------------------

    private static void ensureJacob()
    {
        if (initialized) return;
        synchronized (ComJacobBridge.class)
        {
            if (initialized) return;
            initialized = true;
            try
            {
                // Шаг 1: явно загружаем DLL до статического инициализатора Jacob
//                preloadNativeDll();

                // Шаг 2: загружаем классы через bundle classloader (видит Bundle-ClassPath)
                ClassLoader cl = ComJacobBridge.class.getClassLoader();

                classActiveXComponent = cl.loadClass("com.jacob.activeX.ActiveXComponent"); //$NON-NLS-1$
                classDispatch         = cl.loadClass("com.jacob.com.Dispatch");              //$NON-NLS-1$
                classVariant          = cl.loadClass("com.jacob.com.Variant");               //$NON-NLS-1$
                classComThread        = cl.loadClass("com.jacob.com.ComThread");             //$NON-NLS-1$

                methodDispatchCall     = classDispatch.getMethod("call",     classDispatch, String.class, Object[].class); //$NON-NLS-1$
                methodDispatchPut      = classDispatch.getMethod("put",      classDispatch, String.class, Object.class);   //$NON-NLS-1$
                methodDispatchGet      = classDispatch.getMethod("get",      classDispatch, String.class);                 //$NON-NLS-1$
                methodVariantToBoolean = classVariant.getMethod("toBoolean");  //$NON-NLS-1$
                methodComThreadInitSTA = classComThread.getMethod("InitSTA"); //$NON-NLS-1$
                methodComThreadRelease = classComThread.getMethod("Release"); //$NON-NLS-1$

                available = true;
                ComConnectionRegistry.log("Jacob инициализирован успешно"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                available = false;
                unavailableReason = e.toString();
                ComConnectionRegistry.log("Jacob НЕУДАЧА: " + e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Находит и явно загружает jacob-*.dll из директории lib/ плагина.
     *
     * <p>Алгоритм:
     * <ol>
     *   <li>Получаем корень бандла: {@code bundle.getEntry("/")} + {@link FileLocator#toFileURL}.</li>
     *   <li>Перебираем имена DLL в {@code lib/}.</li>
     *   <li>Логируем содержимое {@code lib/} для диагностики.</li>
     *   <li>Fallback: {@code System.loadLibrary()} из PATH.</li>
     * </ol>
     */
    private static void preloadNativeDll() throws Exception
    {
        Bundle bundle = FrameworkUtil.getBundle(ComJacobBridge.class);
        if (bundle == null)
            throw new IllegalStateException("Bundle не найден"); //$NON-NLS-1$

        // Получаем корневую директорию бандла
        File bundleRoot = resolveBundleRoot(bundle);
        ComConnectionRegistry.log("Bundle root: " + (bundleRoot != null ? bundleRoot.getAbsolutePath() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

        String[] dllNames = {
            "jacob-1.21-x64.dll", "jacob-1.21-x86.dll", //$NON-NLS-1$ //$NON-NLS-2$
            "jacob-1.20-x64.dll", "jacob-1.20-x86.dll", //$NON-NLS-1$ //$NON-NLS-2$
            "jacob.dll" //$NON-NLS-1$
        };

        if (bundleRoot != null)
        {
            File libDir = new File(bundleRoot, "lib"); //$NON-NLS-1$
            ComConnectionRegistry.log("lib/ dir: " + libDir.getAbsolutePath() //$NON-NLS-1$
                + " exists=" + libDir.exists()); //$NON-NLS-1$

            // Логируем содержимое lib/ для диагностики
            if (libDir.isDirectory())
            {
                File[] files = libDir.listFiles();
                if (files != null)
                    for (File f : files)
                        ComConnectionRegistry.log("  lib/" + f.getName()); //$NON-NLS-1$
            }

            for (String name : dllNames)
            {
                File dll = new File(libDir, name);
                if (dll.exists())
                {
                    System.load(dll.getAbsolutePath());
                    ComConnectionRegistry.log("DLL загружена: " + dll.getAbsolutePath()); //$NON-NLS-1$
                    return;
                }
            }
            ComConnectionRegistry.log("DLL не найдена в lib/"); //$NON-NLS-1$
        }
    }

    private static File resolveBundleRoot(Bundle bundle)
    {
        try
        {
            URL rootEntry = bundle.getEntry("/"); //$NON-NLS-1$
            if (rootEntry == null) return null;
            URL fileUrl = FileLocator.toFileURL(rootEntry);
            // toFileURL возвращает URL вида "file:/C:/path/to/bundle/"
            String path = fileUrl.getPath().replaceAll("%20", " "); //$NON-NLS-1$ //$NON-NLS-2$
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1); //$NON-NLS-1$
            return new File(path);
        }
        catch (Exception e)
        {
            ComConnectionRegistry.log("resolveBundleRoot ошибка: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static void requireJacob()
    {
        ensureJacob();
        if (!available)
            throw new UnsupportedOperationException("Jacob недоступен: " + unavailableReason); //$NON-NLS-1$
    }

    // -----------------------------------------------------------------------
    // COM-поток
    // -----------------------------------------------------------------------

    public static void initComThread()
    {
        requireJacob();
        try { methodComThreadInitSTA.invoke(null); }
        catch (Exception e) { throw new RuntimeException("ComThread.InitSTA() ошибка: " + e.getMessage(), e); } //$NON-NLS-1$
    }

    public static void releaseComThread()
    {
        if (!available) return;
        try { methodComThreadRelease.invoke(null); }
        catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Создание COM-объекта
    // -----------------------------------------------------------------------

    public static Object createComObject(String className)
    {
        requireJacob();
        try
        {
            initComThread(); // STA обязателен
            return classActiveXComponent.getConstructor(String.class).newInstance(className);
        }
        catch (Exception e)
        {
            Throwable c = unwrap(e);
            throw new RuntimeException("Ошибка создания COM '" + className + "': " + c.getMessage(), c); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -----------------------------------------------------------------------
    // COM-методы и свойства
    // -----------------------------------------------------------------------

    public static boolean connect(Object dispatch, String connectionString)
    {
        return toBoolean(invoke(dispatch, "Connect", connectionString)); //$NON-NLS-1$
    }

    public static Object invoke(Object dispatch, String method, Object... args)
    {
        requireJacob();
        try { return methodDispatchCall.invoke(null, dispatch, method, args); }
        catch (Exception e) { Throwable c = unwrap(e); throw new RuntimeException("COM." + method + "(): " + c.getMessage(), c); } //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static Object getProperty(Object dispatch, String property)
    {
        requireJacob();
        try { return methodDispatchGet.invoke(null, dispatch, property); }
        catch (Exception e) { Throwable c = unwrap(e); throw new RuntimeException("COM.get(" + property + "): " + c.getMessage(), c); } //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static void setProperty(Object dispatch, String property, Object value)
    {
        requireJacob();
        try { methodDispatchPut.invoke(null, dispatch, property, value); }
        catch (Exception e) { Throwable c = unwrap(e); throw new RuntimeException("COM.put(" + property + "): " + c.getMessage(), c); } //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -----------------------------------------------------------------------
    // Утилиты
    // -----------------------------------------------------------------------

    private static boolean toBoolean(Object variant)
    {
        if (variant == null) return false;
        if (variant instanceof Boolean) return (Boolean) variant;
        try { return (Boolean) methodVariantToBoolean.invoke(variant); }
        catch (Exception e) { return false; }
    }

    private static Throwable unwrap(Exception e)
    {
        return e.getCause() != null ? e.getCause() : e;
    }
}

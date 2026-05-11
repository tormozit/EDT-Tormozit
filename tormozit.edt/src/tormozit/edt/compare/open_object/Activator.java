package tormozit.edt.compare.open_object;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import tormozit.edt.compare.open_object.assist.ContentAssistAutoOpenManager;
import tormozit.edt.compare.open_object.assist.ContentAssistAutoOpenSettings;

/**
 * Activator (точка входа) плагина EDT Compare - Open Object.
 */
public class Activator extends AbstractUIPlugin
{
    public static final String PLUGIN_ID = "tormozit.edt"; //$NON-NLS-1$

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        instance = this;

        // Инициализируем и запускаем менеджер автооткрытия подсказки
        ContentAssistAutoOpenSettings settings =
            ContentAssistAutoOpenSettings.init(PLUGIN_ID);
        ContentAssistAutoOpenManager manager =
            ContentAssistAutoOpenManager.init(settings);
        manager.start();
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        ContentAssistAutoOpenManager mgr = ContentAssistAutoOpenManager.getInstance();
        if (mgr != null)
            mgr.stop();

        instance = null;
        super.stop(context);
    }

    public static Activator getDefault()
    {
        return instance;
    }
}

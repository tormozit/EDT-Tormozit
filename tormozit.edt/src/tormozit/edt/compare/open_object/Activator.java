package tormozit.edt.compare.open_object;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * Activator (точка входа) плагина EDT Compare - Open Object.
 */
public class Activator extends AbstractUIPlugin {

    /** Идентификатор плагина — должен совпадать с Bundle-SymbolicName в MANIFEST.MF */
    public static final String PLUGIN_ID = "tormozit.edt"; //$NON-NLS-1$

    private static Activator instance;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }
}

package tormozit.edt.assist;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;

/**
 * Инициализирует значения по умолчанию для настроек автооткрытия подсказки.
 * Регистрируется через точку расширения {@code org.eclipse.core.runtime.preferences}
 * в {@code plugin.xml}.
 */
public class ContentAssistAutoOpenInitializer extends AbstractPreferenceInitializer
{
    @Override
    public void initializeDefaultPreferences()
    {
        ContentAssistAutoOpenSettings settings =
            ContentAssistAutoOpenSettings.getInstance();
        if (settings == null)
            return; // Activator ещё не запущен — нормальная ситуация при первом запуске

        settings.getPreferenceStore().setDefault(
            ContentAssistAutoOpenSettings.PREF_ENABLED,
            ContentAssistAutoOpenSettings.DEFAULT_ENABLED);

        settings.getPreferenceStore().setDefault(
            ContentAssistAutoOpenSettings.PREF_TIMEOUT,
            ContentAssistAutoOpenSettings.DEFAULT_TIMEOUT);
    }
}

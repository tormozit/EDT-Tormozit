package tormozit.edt.compare.open_object.assist;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Управляет подпиской на открытие/закрытие BSL-редакторов и применяет
 * к ним патч автооткрытия подсказки.
 *
 * <p>Отслеживает:
 * <ul>
 *   <li>открытие новых рабочих окон ({@link IWindowListener});</li>
 *   <li>открытие/закрытие редакторов ({@link IPartListener2});</li>
 *   <li>переключение страниц в {@link DtGranularEditor} ({@link IPageChangedListener}).</li>
 * </ul>
 */
public final class ContentAssistAutoOpenManager
{
    private static ContentAssistAutoOpenManager instance;

    private final ContentAssistAutoOpenSettings  settings;
    private final WindowListener                 windowListener = new WindowListener();
    private final SettingsChangeListener         settingsListener = new SettingsChangeListener();

    /** partService → зарегистрированный listener (один на окно). */
    private final Map<IWorkbenchWindow, IPartListener2>        partListeners =
        new HashMap<>();
    /** DtGranularEditor → зарегистрированный page-listener. */
    private final Map<DtGranularEditor<?>, IPageChangedListener> pageListeners =
        new HashMap<>();

    private ContentAssistAutoOpenManager(ContentAssistAutoOpenSettings settings)
    {
        this.settings = settings;
    }

    // ---- Синглтон ----

    public static synchronized ContentAssistAutoOpenManager init(
            ContentAssistAutoOpenSettings settings)
    {
        if (instance == null)
            instance = new ContentAssistAutoOpenManager(settings);
        return instance;
    }

    public static ContentAssistAutoOpenManager getInstance() { return instance; }

    // ---- Lifecycle ----

    /**
     * Запускает менеджер: загружает настройки, применяет патч к уже открытым
     * редакторам, регистрирует слушатели окон и изменений настроек.
     */
    public void start()
    {
        settings.loadSettings();
        settings.addPropertyChangeListener(settingsListener);

        Display display = Display.getDefault();
        if (display == null)
            return;

        display.syncExec(() ->
        {
            applyPatchToOpenedEditors();
            PlatformUI.getWorkbench().addWindowListener(windowListener);
            for (IWorkbenchWindow window :
                    PlatformUI.getWorkbench().getWorkbenchWindows())
                registerPartListener(window);
        });
    }

    /** Останавливает менеджер: снимает все слушатели. */
    public void stop()
    {
        settings.removePropertyChangeListener(settingsListener);
        PlatformUI.getWorkbench().removeWindowListener(windowListener);
        // Слушатели частей/страниц живут столько же, сколько плагин —
        // при uninstall плагина Eclipse сам очищает партсервисы.
    }

    // ---- Патч ----

    /** Применяет патч ко всем уже открытым BSL-редакторам во всех окнах. */
    public void applyPatchToOpenedEditors()
    {
        if (!settings.isEnabled())
            return;

        Display display = Display.getDefault();
        if (display == null)
            return;

        display.syncExec(() ->
        {
            for (IWorkbenchWindow window :
                    PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    IWorkbenchPartReference ref = page.getActivePartReference();
                    if (ref == null)
                        continue;
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) part);
                }
            }
        });
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        if (!settings.isEnabled())
            return;

        // getActiveEditor() — protected в MultiPageEditorPart, недоступен снаружи.
        // Используем getActivePageInstance() → DtGranularEditorXtextEditorPage.
        org.eclipse.ui.forms.editor.IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            IEditorPart embedded =
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor)
                applyPatchToBslEditor((BslXtextEditor) embedded);
        }

        // Подписываемся на смену страниц (переход в BSL-страницу объекта)
        if (!pageListeners.containsKey(editor))
        {
            IPageChangedListener pl = new PageChangeListener();
            editor.addPageChangedListener(pl);
            pageListeners.put(editor, pl);
        }
    }

    private void applyPatchToBslEditor(BslXtextEditor editor)
    {
        if (!settings.isEnabled())
            return;

        ISourceViewer viewer = editor.getInternalSourceViewer();
        if (viewer instanceof SourceViewer)
            ContentAssistAutoOpenPatcher.applyPatch(
                (SourceViewer) viewer,
                settings.getTimeout(),
                settings.getCharset());
    }

    // ---- Вспомогательные методы регистрации ----

    private void registerPartListener(IWorkbenchWindow window)
    {
        if (!partListeners.containsKey(window))
        {
            IPartListener2 pl = new PartListener();
            window.getPartService().addPartListener(pl);
            partListeners.put(window, pl);
        }
    }

    // ---- Внутренние слушатели ----

    /** Реагирует на изменение настроек в Preferences. */
    private class SettingsChangeListener implements IPropertyChangeListener
    {
        @Override
        public void propertyChange(PropertyChangeEvent event)
        {
            String prop = event.getProperty();
            if (ContentAssistAutoOpenSettings.PREF_ENABLED.equals(prop)
                    || ContentAssistAutoOpenSettings.PREF_TIMEOUT.equals(prop))
            {
                settings.loadSettings();
                applyPatchToOpenedEditors();
            }
        }
    }

    private class WindowListener implements IWindowListener
    {
        @Override
        public void windowOpened(IWorkbenchWindow window)
        {
            registerPartListener(window);
        }

        @Override
        public void windowClosed(IWorkbenchWindow window)
        {
            IPartListener2 pl = partListeners.remove(window);
            if (pl != null)
                window.getPartService().removePartListener(pl);
        }

        @Override public void windowActivated(IWorkbenchWindow window) {}
        @Override public void windowDeactivated(IWorkbenchWindow window) {}
    }

    private class PartListener implements IPartListener2
    {
        @Override
        public void partOpened(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor<?>)
                applyPatchToGranularEditor((DtGranularEditor<?>) part);
            else if (part instanceof BslXtextEditor)
                applyPatchToBslEditor((BslXtextEditor) part);
        }

        @Override
        public void partClosed(IWorkbenchPartReference ref)
        {
            IWorkbenchPart part = ref.getPart(false);
            if (part instanceof DtGranularEditor<?>)
            {
                DtGranularEditor<?> editor = (DtGranularEditor<?>) part;
                IPageChangedListener pl = pageListeners.remove(editor);
                if (pl != null)
                    editor.removePageChangedListener(pl);
            }
        }
    }

    private class PageChangeListener implements IPageChangedListener
    {
        @Override
        public void pageChanged(PageChangedEvent event)
        {
            Object page = event.getSelectedPage();
            if (page instanceof DtGranularEditorXtextEditorPage<?>)
            {
                IEditorPart embedded =
                    ((DtGranularEditorXtextEditorPage<?>) page).getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor)
                    applyPatchToBslEditor((BslXtextEditor) embedded);
            }
        }
    }
}

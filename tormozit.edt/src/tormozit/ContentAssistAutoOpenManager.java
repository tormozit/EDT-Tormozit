package tormozit;

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
import org.eclipse.ui.IEditorReference;
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
 * <p><b>Порядок инициализации:</b>
 * <ol>
 *   <li>{@code ContentAssistAutoOpenManager.init(settings)} — вызывается
 *       из {@code Activator.start()} (безопасно, без UI);</li>
 *   <li>{@code start()} — вызывается из {@code earlyStartup()} уже на
 *       UI-потоке, когда Workbench гарантированно инициализирован.</li>
 * </ol>
 *
 * <p>{@code start()} не использует {@code syncExec}/{@code asyncExec} —
 * он уже выполняется на UI-потоке (через {@code earlyStartup()}).
 * {@link #applyPatchToOpenedEditors()} безопасен из любого потока.
 */
public final class ContentAssistAutoOpenManager
{
    private static ContentAssistAutoOpenManager instance;

    private final ContentAssistAutoOpenSettings  settings;
    private final WindowListener                 windowListener   = new WindowListener();
    private final SettingsChangeListener         settingsListener = new SettingsChangeListener();

    private final Map<IWorkbenchWindow, IPartListener2>          partListeners =
        new HashMap<>();
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
     * Запускает менеджер.
     *
     * <p><b>Вызывается с UI-потока</b> из {@code earlyStartup() → asyncExec},
     * когда Workbench готов. Не использует {@code syncExec}/{@code asyncExec},
     * чтобы не замедлять старт.
     */
    public void start()
    {
        settings.loadSettings();
        settings.addPropertyChangeListener(settingsListener);

        if (!PlatformUI.isWorkbenchRunning())
            return;

        // Применяем патч к уже открытым редакторам (восстановленная сессия)
        applyPatchToOpenedEditorsOnUIThread();

        // Регистрируем слушатели окон и частей
        PlatformUI.getWorkbench().addWindowListener(windowListener);
        for (IWorkbenchWindow window :
                PlatformUI.getWorkbench().getWorkbenchWindows())
            registerPartListener(window);
    }

    /** Останавливает менеджер. */
    public void stop()
    {
        settings.removePropertyChangeListener(settingsListener);
        if (PlatformUI.isWorkbenchRunning())
            PlatformUI.getWorkbench().removeWindowListener(windowListener);
    }

    // ---- Патч ----

    /**
     * Применяет патч ко всем открытым BSL-редакторам.
     * Безопасен из любого потока: если вызван не с UI-потока,
     * планирует {@code asyncExec}.
     */
    public void applyPatchToOpenedEditors()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        if (Display.getCurrent() != null)           // уже на UI-потоке
            applyPatchToOpenedEditorsOnUIThread();
        else
            display.asyncExec(this::applyPatchToOpenedEditorsOnUIThread);
    }

    /**
     * Применяет патч ко всем открытым редакторам.
     * <b>Вызывать только с UI-потока.</b>
     */
    private void applyPatchToOpenedEditorsOnUIThread()
    {
        if (!settings.isEnabled())
            return;
        if (!PlatformUI.isWorkbenchRunning())
            return;

        for (IWorkbenchWindow window :
                PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                // Перебираем ВСЕ открытые редакторы, а не только активный
                for (IEditorReference ref : page.getEditorReferences())
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof DtGranularEditor<?>)
                        applyPatchToGranularEditor((DtGranularEditor<?>) part);
                    else if (part instanceof BslXtextEditor)
                        applyPatchToBslEditor((BslXtextEditor) part);
                }
            }
        }
    }

    private void applyPatchToGranularEditor(DtGranularEditor<?> editor)
    {
        if (!settings.isEnabled())
            return;

        // getActiveEditor() — protected в MultiPageEditorPart, недоступен снаружи.
        // Используем getActivePageInstance() → DtGranularEditorXtextEditorPage.
        org.eclipse.ui.forms.editor.IFormPage activePage =
            editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            IEditorPart embedded =
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
            if (embedded instanceof BslXtextEditor)
                applyPatchToBslEditor((BslXtextEditor) embedded);
        }

        // Подписываемся на смену страниц
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
                applyPatchToOpenedEditors(); // безопасен из любого потока
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

        @Override public void windowActivated(IWorkbenchWindow window)   {}
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

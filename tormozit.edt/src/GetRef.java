
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchSite;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.internal.PartSite;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.emf.workspace.util.WorkspaceSynchronizer;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Глобальная команда «Ссылка» — помещает в буфер обмена полное русское имя
 * редактируемого объекта метаданных и показывает всплывающее уведомление.
 *
 * <h3>Поддерживаемые источники</h3>
 * <ol>
 *   <li><b>Активный BSL-редактор</b> (автономный или страница DtGranularEditor) —
 *       из пути {@code .bsl}-файла строится полное имя вплоть до суффикса модуля:
 *       {@code Справочник.Валюты.МодульОбъекта}</li>
 *   <li><b>Активный DtGranularEditor</b> (редактор объекта/формы EDT) —
 *       из пути {@code .mdo}-файла: {@code Справочник.Валюты}</li>
 *   <li><b>Выделение в навигаторе EDT</b> — из {@link IFile}, {@link IResource},
 *       или {@link EObject} выбранного узла:
 *       {@code Справочник.Валюты.Макет.Классификатор}</li>
 * </ol>
 *
 * <h3>Формат пути EDT → полное имя</h3>
 * <pre>
 *   src/Catalogs/Валюты/Валюты.mdo            → Справочник.Валюты
 *   src/Catalogs/Валюты/Ext/ObjectModule.bsl  → Справочник.Валюты.МодульОбъекта
 *   src/Catalogs/Валюты/Ext/ManagerModule.bsl → Справочник.Валюты.МодульМенеджера
 *   src/Catalogs/Валюты/Forms/ФормаЭл/...     → Справочник.Валюты.Форма.ФормаЭл
 *   src/Catalogs/Валюты/Templates/Кл/Кл.mdo   → Справочник.Валюты.Макет.Кл
 *   src/Catalogs/Валюты/Commands/Кнопка/...   → Справочник.Валюты.Команда.Кнопка
 *   src/CommonModules/МойМодуль/Ext/Module.bsl → ОбщийМодуль.МойМодуль
 *   src/CommonForms/МояФорма/Ext/Form.bsl      → ОбщаяФорма.МояФорма
 *   src/ext/Расш1/Catalogs/Валюты/Валюты.mdo     → Расш1 Справочник.Валюты
 * </pre>
 */
public class GetRef extends AbstractHandler
{
    // =========================================================================
    // Вспомогательные маппинги (локальные для этого класса)
    // =========================================================================

    /**
     * Имя вложенной папки EDT → русское название раздела в полном имени МД.
     * Пример: «Templates» → «Макет».
     */
    private static final Map<String, String> SUBFOLDER_TO_RU = new LinkedHashMap<>();
    static
    {
        SUBFOLDER_TO_RU.put("Forms",          "Форма");
        SUBFOLDER_TO_RU.put("Templates",      "Макет");
        SUBFOLDER_TO_RU.put("Commands",       "Команда");
        SUBFOLDER_TO_RU.put("Recalculations", "Перерасчет");
    }

    /**
     * Имя BSL-файла → русский суффикс модуля в полном имени МД.
     * Пример: «ObjectModule.bsl» → «МодульОбъекта».
     */
    private static final Map<String, String> BSL_TO_MODULE_RU = new LinkedHashMap<>();
    static
    {
        BSL_TO_MODULE_RU.put("ObjectModule.bsl",    "МодульОбъекта");
        BSL_TO_MODULE_RU.put("ManagerModule.bsl",   "МодульМенеджера");
        BSL_TO_MODULE_RU.put("RecordSetModule.bsl", "МодульНабораЗаписей");
        BSL_TO_MODULE_RU.put("Module.bsl",          "Модуль");
        // Form.bsl под Forms/<Name>/Ext/ — не добавляет суффикс: имя уже в пути Forms/<Name>
    }

    /**
     * Папки верхнего уровня, где файл в {@code Ext/} является самим объектом,
     * а не его подмодулем: {@code CommonModules}, {@code CommonForms} и т.д.
     * Для них суффикс модуля в полное имя не добавляется.
     */
    private static final Set<String> TOP_LEVEL_CONTAINERS = new HashSet<>(Arrays.asList(
        "CommonModules", "CommonForms", "CommonTemplates",
        "CommonPictures", "CommonCommands"
    ));

    // =========================================================================
    // IHandler
    // =========================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        IWorkbenchPage page = part.getSite().getPage();
        String ref="";
        if (part == page.findView("com._1c.g5.v8.dt.ui2.navigator"))
            ref = refFromNavigator(page);
        else 
            ref = getRefFromEditor(page);
        if (ref == null || ref.isBlank())
        {
            ToastNotification.show("Ссылка",
                "Не удалось определить имя объекта метаданных.\n"
                + "Откройте редактор объекта или активируйте его узел в навигаторе.", 5000);
            return null;
        }
        setClipboardText(ref, HandlerUtil.getActiveShell(event));
        ToastNotification.show("Скопирована ссылка", ref, 6000);
        return null;
    }

    // =========================================================================
    // Публичный API (используется другими классами плагина)
    // =========================================================================

    /**
     * Возвращает полное русское имя МД-объекта, связанного с текущим состоянием UI.
     * Пробует последовательно: активный редактор → навигатор.
     *
     * @return строка вида «Справочник.Валюты.Макет.Классификатор», или {@code null}
     */
    public static String getRefFromEditor(IWorkbenchPage page)
    {
        IEditorPart editor = (page != null) ? page.getActiveEditor() : null;

        // 1. Многостраничный редактор EDT (форма, модуль, макет …)
        if (editor instanceof DtGranularEditor<?>)
        {
            String ref = refFromGranularEditor((DtGranularEditor<?>) editor);
            if (ref != null) return ref;
        }

        // 2. Любой редактор с IFile-вводом (BSL, .mdo, .xml …)
        if (editor != null)
        {
            String ref = refFromEditorInput(editor.getEditorInput());
            if (ref != null) return ref;
        }
        return null;
    }

    // =========================================================================
    // Источник 1: DtGranularEditor
    // =========================================================================

    /**
     * Для DtGranularEditor сначала проверяем активную страницу:
     * <ul>
     *   <li>Если это страница BSL-модуля → берём файл встроенного редактора
     *       → получаем полный путь с суффиксом модуля.</li>
     *   <li>Иначе → берём основной ввод редактора (.mdo объекта)
     *       → получаем имя без суффикса.</li>
     * </ul>
     */
    private static String refFromGranularEditor(DtGranularEditor<?> editor)
    {
        // Активная BSL-страница (МодульОбъекта, МодульМенеджера …)
        IFormPage activePage = editor.getActivePageInstance();
        if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
        {
            IEditorPart embedded =
                ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
            if (embedded != null)
            {
                String ref = refFromEditorInput(embedded.getEditorInput());
                if (ref != null) return ref;
            }
        }

        // Основной ввод (обычно .mdo файл объекта)
        return refFromEditorInput(editor.getEditorInput());
    }

    // =========================================================================
    // Источник 2: IEditorInput
    // =========================================================================

    private static String refFromEditorInput(IEditorInput input)
    {
        if (input == null) return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null) return null;
        return pathToFullName(file.getProjectRelativePath().toString());
    }

    // =========================================================================
    // Источник 3: навигатор EDT
    // =========================================================================

    private static String refFromNavigator(IWorkbenchPage page)
    {
        if (page == null) return null;
        try
        {
            CommonNavigator nav = (CommonNavigator)
                page.findView("com._1c.g5.v8.dt.ui2.navigator"); //$NON-NLS-1$
            if (nav == null) return null;

            IStructuredSelection sel = (IStructuredSelection)
                nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty()) return null;

            Object element = sel.getFirstElement();
//
//            // Стратегия 1: элемент адаптируется к IFile
//            IFile file = Adapters.adapt(element, IFile.class);
//            if (file != null)
//            {
//                String ref = pathToFullName(file.getProjectRelativePath().toString());
//                if (ref != null) return ref;
//            }
//
//            // Стратегия 2: элемент адаптируется к IResource (IFolder)
//            IResource resource = Adapters.adapt(element, IResource.class);
//            if (resource != null && resource.getType() != IResource.PROJECT)
//            {
//                String ref = pathToFullName(resource.getProjectRelativePath().toString());
//                if (ref != null) return ref;
//            }

            // Стратегия 3: EMF EObject → URI ресурса → IFile
            if (element instanceof EObject)
            {
                String ref = eObjectToFullName((EObject) element);
                if (ref != null) return ref;
            }

            // Стратегия 4: рефлексия (некоторые EDT-обёртки имеют getFile() / getResource())
            for (String getter : new String[]{ "getFile", "getResource", "getMdObject" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                Object result = Global.call(element, getter);
                if (result instanceof IFile)
                {
                    String ref = pathToFullName(((IFile) result).getProjectRelativePath().toString());
                    if (ref != null) return ref;
                }
                if (result instanceof EObject)
                {
                    String ref = eObjectToFullName((EObject) result);
                    if (ref != null) return ref;
                }
            }
        }
        catch (Exception e)
        {
            Global.log("GetRef.refFromNavigator: " + e); //$NON-NLS-1$
        }
        return null;
    }

    /** EObject → путь его EMF-ресурса → полное имя МД. */
    private static String eObjectToFullName(EObject obj)
    {
        Resource emfResource = obj.eResource();
        if (emfResource == null) return null;
        URI uri = emfResource.getURI();
        return uri.path().substring(1);
//        return pathToFullName(uri);
    }

    // =========================================================================
    // Ядро: путь в EDT-проекте → полное русское имя МД
    // =========================================================================

    /**
     * Преобразует путь файла (или папки) внутри EDT-проекта в полное русское имя МД.
     *
     * <p>Поддерживаемые входные форматы (project-relative, '/' или '\'):
     * <pre>
     *   src/&lt;Folder&gt;/&lt;Object&gt;/&lt;Object&gt;.mdo
     *   src/&lt;Folder&gt;/&lt;Object&gt;/Ext/ObjectModule.bsl
     *   src/&lt;Folder&gt;/&lt;Object&gt;/Forms/&lt;Form&gt;/Ext/Form.bsl
     *   src/&lt;Folder&gt;/&lt;Object&gt;/Templates/&lt;Tpl&gt;/&lt;Tpl&gt;.mdo
     *   src/&lt;Folder&gt;/&lt;Object&gt;/Commands/&lt;Cmd&gt;/&lt;Cmd&gt;.mdo
     *   src/ext/&lt;ExtName&gt;/&lt;Folder&gt;/&lt;Object&gt;/...   (расширение)
     *   &lt;IFolder&gt; пути (без файла) — обрабатываются так же
     * </pre>
     *
     * @param projectRelativePath путь относительно корня EDT-проекта
     * @return полное имя, например {@code "Справочник.Валюты.Макет.Классификатор"},
     *         или {@code null} если путь не распознан
     */
    static String pathToFullName(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');

        // ── Разбор префикса: src/ или src/ext/<ExtName>/ ─────────────────
        String extensionName = null; // имя расширения, если путь в src/ext/
        String relative;            // путь после src/ или src/ext/<ext>/

        if (path.startsWith("src/")) //$NON-NLS-1$
        {
            relative = path.substring("src/".length()); //$NON-NLS-1$
        }
        else if (path.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = path.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            extensionName = rest.substring(0, slash);
            relative = rest.substring(slash + 1);
        }
        else
        {
            return null; // не EDT-путь
        }

        // ── Разбор сегментов: Folder / Object / [SubKind / SubName / ...] ───
        // Пример: "Catalogs/Валюты/Templates/Классификатор/Классификатор.mdo"
        //          [0]       [1]    [2]        [3]           [4]
        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 1 || p[0].isEmpty()) return null;

        // p[0] — папка типа (EN мн.ч.), например "Catalogs"
        String folder = p[0];
        String typeRu = MdTypeMapping.folderToRu(folder);
        if (typeRu == null) return null;

        // Только папка типа — без конкретного объекта
        if (p.length < 2 || p[1].isEmpty())
            return withExt(extensionName, typeRu);

        // p[1] — имя объекта (или ObjectName.mdo на уровне папки типа — EDT так не делает,
        //         но обработаем на всякий случай)
        String objectName = stripFileExt(p[1]);
        String base = withExt(extensionName, typeRu + "." + objectName); //$NON-NLS-1$

        if (p.length == 2) return base; // папка объекта выбрана без вложений

        // p[2] — либо "ObjectName.mdo" (самого объекта), либо "Ext", "Forms", "Templates"…
        String seg2 = p[2];

        // ── Файл .mdo самого объекта: Catalogs/Валюты/Валюты.mdo ────────────
        if (seg2.endsWith(".mdo") && stripFileExt(seg2).equals(objectName)) //$NON-NLS-1$
            return base;

        // ── Ext/ — модули ────────────────────────────────────────────────────
        if ("Ext".equals(seg2)) //$NON-NLS-1$
        {
            if (p.length < 4) return base;
            String bslFile = p[3];

            // Для CommonModules/CommonForms и т.п. Ext-файл — это сам объект
            if (TOP_LEVEL_CONTAINERS.contains(folder)) return base;

            // Добавляем суффикс модуля
            String moduleSuffix = BSL_TO_MODULE_RU.get(bslFile);
            return base + (moduleSuffix != null ? "." + moduleSuffix : ""); //$NON-NLS-1$
        }

        // ── Forms / Templates / Commands / Recalculations ────────────────────
        String sectionRu = SUBFOLDER_TO_RU.get(seg2);
        if (sectionRu != null)
        {
            if (p.length < 4 || p[3].isEmpty())
                return base + "." + sectionRu; // только папка раздела выбрана //$NON-NLS-1$

            String subName = stripFileExt(p[3]);
            // p[4..] — Ext/Form.bsl и т.п.; имя мы уже получили из p[3]
            return base + "." + sectionRu + "." + subName; //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Прочие вложенные папки — возвращаем имя базового объекта
        return base;
    }

    /** Добавляет имя расширения в качестве префикса, если оно задано. */
    private static String withExt(String extensionName, String name)
    {
        return extensionName != null ? extensionName + " " + name : name; //$NON-NLS-1$
    }

    /** Убирает расширение .mdo или .bsl (и только их). */
    private static String stripFileExt(String name)
    {
        if (name.endsWith(".mdo")) return name.substring(0, name.length() - 4); //$NON-NLS-1$
        if (name.endsWith(".bsl")) return name.substring(0, name.length() - 4); //$NON-NLS-1$
        return name;
    }

    // =========================================================================
    // Буфер обмена
    // =========================================================================

    private static void setClipboardText(String text, Shell shell)
    {
        Clipboard cb = new Clipboard(shell.getDisplay());
        try
        {
            cb.setContents(
                new Object[]   { text },
                new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            cb.dispose();
        }
    }
}


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexManager;
import com._1c.g5.v8.dt.bm.index.emf.IBmEmfIndexProvider;
import com._1c.g5.v8.dt.bsl.ui.menu.BslHandlerUtil;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorEmbeddedEditorPage;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Глобальная команда «Перейти к определению».
 */
public class GoToDefinition extends AbstractHandler
{  
    // =======================================================================
    // МАППИНГИ
    // =======================================================================

    /** RU ед.ч. → папка EDT (обратная совместимость; источник — {@link MdTypeMapping}). */
    static final Map<String, String> TYPE_TO_FOLDER = MdTypeMapping.getRuToFolderMap();

    /** Суффикс модуля платформы → BSL-файл в EDT. */
    static final Map<String, String> MODULE_SUFFIX_TO_BSL = new LinkedHashMap<>();
    static
    {
        MODULE_SUFFIX_TO_BSL.put("МодульОбъекта",               "ObjectModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульМенеджера",             "ManagerModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульНабораЗаписей",         "RecordSetModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульФормы",                 "Form.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульМодуля",                "Module.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульОбщийМодуль",           "Module.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульОбъектаБизнесПроцесса", "ObjectModule.bsl");
        MODULE_SUFFIX_TO_BSL.put("МодульЗадачи",                "ObjectModule.bsl");
    }

    /** Тип данных с суффиксом → базовый тип МД. Например «СправочникСсылка» → «Справочник». */
    static final Map<String, String> TYPE_SUFFIX_MAP = new LinkedHashMap<>();
    static
    {
        addSuffix("Справочник",             "Ссылка", "Объект", "Менеджер", "Выборка",
                                            "МенеджерТаблицы", "Список");
        addSuffix("Документ",               "Ссылка", "Объект", "Менеджер", "Выборка",
                                            "МенеджерТаблицы", "Список");
        addSuffix("Перечисление",           "Ссылка", "Менеджер");
        addSuffix("ПланОбмена",             "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовХарактеристик", "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланСчетов",             "Ссылка", "Объект", "Менеджер");
        addSuffix("ПланВидовРасчета",       "Ссылка", "Объект", "Менеджер");
        addSuffix("БизнесПроцесс",          "Ссылка", "Объект", "Менеджер");
        addSuffix("Задача",                 "Ссылка", "Объект", "Менеджер");
        addSuffix("РегистрСведений",        "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрНакопления",      "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ",
                                            "ВыборкаДетальныеЗаписи", "ВыборкаОстатки",
                                            "ВыборкаОстаткиИОбороты", "ВыборкаОбороты");
        addSuffix("РегистрБухгалтерии",     "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("РегистрРасчета",         "НаборЗаписей", "Запись", "Менеджер",
                                            "МенеджерТаблицы", "Ключ");
        addSuffix("ВнешняяОбработка",       "Объект");
        addSuffix("ВнешнийОтчет",           "Объект");
    }

    private static void addSuffix(String base, String... suffixes)
    {
        for (String s : suffixes)
            TYPE_SUFFIX_MAP.put(base + s, base);
    }

    // =======================================================================
    // РЕГУЛЯРНЫЕ ВЫРАЖЕНИЯ
    // =======================================================================

    private static final Pattern MODULE_LINE_REF = Pattern.compile(
        "\\{" +
        "(?:([А-ЯЁа-яёA-Za-z0-9]+)\\s)?" +
        "([А-ЯЁа-яёA-Za-z0-9._/\\\\]+)" +
        "\\((\\d+)" +
        "(?::([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)," +
        "(\\d+))?" +
        "\\)\\}");

    private static final Pattern GIT_FILE_REF = Pattern.compile(
        "(?:src/(?:cf/|ext/[^/]+/)?)?([A-Za-z].+\\.bsl):(\\d+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TYPE_DESCRIPTION_SPLITTER = Pattern.compile("\\s*,\\s*");

    /** Объявление Процедуры/Функции — для поиска метода при переходе по расширенной ссылке. */
    private static final Pattern METHOD_DECL = Pattern.compile(
        "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+(%s)\\s*[\\(\\s]",
        Pattern.UNICODE_CASE);

    // =======================================================================
    // ТОЧКА ВХОДА
    // =======================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        File newFile = null, oldFile = null;
        String command = "";
        String transportFolder = IRApplicationRegistry.transportFolder;
        Shell shell  = HandlerUtil.getActiveShell(event);
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
        File commandFile = new File(transportFolder + "\\Команда.txt"); //$NON-NLS-1$

        if (commandFile.exists()
                && System.currentTimeMillis() - commandFile.lastModified() < 5000)
        {
            try
            {
                command = Global.readTextFromFile(commandFile);
                Files.delete(commandFile.toPath());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode commandObject = mapper.readTree(command);
                command = commandObject.get("Команда").asText(); //$NON-NLS-1$
                long PID = commandObject.get("ИДПроцесса").asLong(); //$NON-NLS-1$
                IRApplicationRegistry.getSession(PID);
            }
            catch (IOException e) { e.printStackTrace(); }
            newFile = new File(transportFolder + "\\НовыйТекст.txt"); //$NON-NLS-1$
            oldFile = new File(transportFolder + "\\СтарыйТекст.txt"); //$NON-NLS-1$
        }
        else
        {
            command = getClipboardText(shell);
            if (command == null || command.isBlank())
            {
                ToastNotification.show("Перейти к определению", "Буфер обмена пуст.", 4000);
                return null;
            }
            command = command.strip();
        }

        Global.log("GoToDefinition: ref = " + command); //$NON-NLS-1$

        if (!jump(command, shell, page))
            ToastNotification.show("Перейти к определению",
                "Не удалось перейти по ссылке:\n" + truncate(command, 120), 5000);

        if (newFile != null && (command.contains("Макет.") || command.contains("Template."))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            DtGranularEditor templateEditor = (DtGranularEditor) page.getActiveEditor();
            DtGranularEditorEmbeddedEditorPage dcsEditor =
                (DtGranularEditorEmbeddedEditorPage) templateEditor
                    .findPage("editors.commontemplate.pages.dcs"); //$NON-NLS-1$
            try
            {
                String currentFilename = DataCompositionSchemaEditorHook.exportToFile(dcsEditor);
                boolean allowImport = Global.readTextFromFile(new File(currentFilename))
                    .compareTo(Global.readTextFromFile(oldFile)) == 0;
                if (allowImport)
                    DataCompositionSchemaEditorHook.importFromFile(dcsEditor, newFile);
                else
                    ToastNotification.show(command,
                        "Объект был изменён в EDT после начала редактирования в ИР. "
                        + "Загрузка не выполнена. Временный файл: " + newFile, 10000);
            }
            catch (Exception e) { e.printStackTrace(); }
        }
        return null;
    }

    // =======================================================================
    // ГЛАВНЫЙ ДИСПЕТЧЕР
    // =======================================================================

    public boolean jump(String raw, Shell shell, IWorkbenchPage page)
    {
        if (raw == null) return false;
        String ref = raw.strip();
        if (ref.isEmpty()) return false;

        if (ref.startsWith("http://") || ref.startsWith("https://")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            openUrl(ref);
            return true;
        }

        if (ref.contains("{") && ref.contains("}")) //$NON-NLS-1$ //$NON-NLS-2$
            return handleModuleLineRefs(ref, shell, page);

        Matcher gitM = GIT_FILE_REF.matcher(ref);
        if (gitM.matches())
            return openGitFileRef(gitM.group(1), parseInt(gitM.group(2)), page, shell);

        if (ref.startsWith("БД.")) //$NON-NLS-1$
            ref = ref.substring(3);

        if (ref.startsWith("ПакетXDTO.")) //$NON-NLS-1$
            return openXdtoRef(ref, shell, page);

        if (ref.contains(",")) //$NON-NLS-1$
        {
            List<String> names = extractMdNamesFromTypeDescription(ref);
            if (!names.isEmpty())
            {
                if (names.size() == 1) return openByFullName(names.get(0), shell, page);
                return pickAndOpen(names, shell, page);
            }
        }

        String stripped = stripTypeSuffix(ref);
        if (stripped != null)
            return openByFullName(stripped, shell, page);

        return openByFullName(ref, shell, page);
    }

    // =======================================================================
    // ССЫЛКИ СТРОК МОДУЛЕЙ
    // =======================================================================

    private boolean handleModuleLineRefs(String text, Shell shell, IWorkbenchPage page)
    {
        List<ModuleLineRef> refs = parseAllModuleLineRefs(text);
        if (refs.isEmpty()) return false;

        ModuleLineRef chosen;
        if (refs.size() == 1)
        {
            chosen = refs.get(0);
        }
        else
        {
            String[] labels = refs.stream().map(ModuleLineRef::displayLabel).toArray(String[]::new);
            MessageDialog dlg = new MessageDialog(shell,
                "Выберите строку стека", null, //$NON-NLS-1$
                "Найдено несколько ссылок. Выберите строку для перехода:", //$NON-NLS-1$
                MessageDialog.QUESTION, labels, 0);
            int idx = dlg.open();
            if (idx < 0 || idx >= refs.size()) return false;
            chosen = refs.get(idx);
        }
        return openModuleLineRef(chosen, page, shell);
    }

    /**
     * Открывает модуль по ссылке строки.
     *
     * <p>Стратегии (в порядке приоритета):
     * <ol>
     *   <li>Git-путь ({@code r.file != null}) → {@link #openGitFileRef}.</li>
     *   <li><b>IBmEmfIndex + OpenHelper</b> — проверенный подход из EDT.CodeLinkOpener:
     *       находит {@link MdObject} по индексу, открывает редактор объекта через
     *       {@link OpenHelper#openEditor(MdObject, EStructuralFeature)}, активирует
     *       страницу модуля, переходит к строке через {@link BslHandlerUtil}.
     *       Для расширенных ссылок ({@code method != null}) ищет метод в документе
     *       и переходит по смещению внутри него.</li>
     *   <li>Fallback — открытие BSL-файла напрямую через {@link IDE#openEditor}.</li>
     * </ol>
     */
    private boolean openModuleLineRef(ModuleLineRef r, IWorkbenchPage page, Shell shell)
    {
        if (r.file != null)
            return openGitFileRef(r.file, r.line, page, shell);

        // Основной путь: IBmEmfIndex + OpenHelper
        if (openModuleRefViaBmIndex(r, page))
            return true;

        // Fallback: файловый путь
        String bslPath = moduleToBslPath(r.modulePath, r.extension);
        if (bslPath == null)
        {
            Global.log("GoToDefinition: не могу построить путь для " + r.modulePath); //$NON-NLS-1$
            return false;
        }
        return openBslFileAt(bslPath, r.line, page, shell);
    }

    // =======================================================================
    // ОТКРЫТИЕ МОДУЛЯ ЧЕРЕЗ IBmEmfIndex + OpenHelper
    // (алгоритм из EDT.CodeLinkOpener/OpenerHandlerDialog.java)
    // =======================================================================

    /**
     * Открывает модуль 1С по ссылке, используя {@link IBmEmfIndexManager} для поиска
     * {@link MdObject} и {@link OpenHelper} для открытия редактора.
     *
     * <h3>Алгоритм</h3>
     * <ol>
     *   <li>Разбирает русский путь модуля по парам «(ТипRU, Имя)»:
     *       {@code ["Справочник","Валюты","МодульОбъекта"]} → тип {@code CATALOG},
     *       EN FQN {@code "Catalog.Валюты"}, свойство модуля {@code "objectModule"}.</li>
     *   <li>Получает {@link MdObject} через
     *       {@link IBmEmfIndexProvider}.</li>
     *   <li>Открывает редактор объекта через
     *       {@link OpenHelper#openEditor(MdObject, EStructuralFeature)}.</li>
     *   <li>Для форм активирует страницу модуля:
     *       {@code FormEditor.setActivePage("editors.form.pages.module")}.</li>
     *   <li>Получает {@link XtextEditor} через {@link BslHandlerUtil#extractXtextEditor}.</li>
     *   <li>Переходит к строке:
     *       <ul>
     *         <li>Расширенная ссылка ({@code method != null}): ищет метод в документе
     *             → переходит к {@code methodLine + offset}.</li>
     *         <li>Стандартная ссылка: переходит к {@code line}.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @return {@code true} если переход выполнен; {@code false} при любой ошибке
     *         (вызывающий код применит file-based fallback)
     */
    private boolean openModuleRefViaBmIndex(ModuleLineRef r, IWorkbenchPage page)
    {
        IProject activeProject = Global.getActiveProject(page, true);
        if (activeProject == null) 
            return false;
        if (r.modulePath == null) return false;

        String[] frags = r.modulePath.split("\\.", -1); //$NON-NLS-1$
        if (frags.length < 2) return false;

        // ── Разбираем пары (ТипRU, Имя) ────────────────────────────────────
        // Последний фрагмент (нечётный индекс) — суффикс модуля.
        // Пример: ["Справочник","Валюты","МодульОбъекта"]   → pairCount=1, suffix="МодульОбъекта"
        //         ["Обработка","ирКод","Форма","Форма","Форма"] → pairCount=2, suffix="Форма"
        int    pairCount    = frags.length / 2;
        String mdClassRu    = null;
        boolean isForm      = false;
        boolean isCommand   = false;
        StringBuilder engFqn = new StringBuilder();

        for (int i = 0; i < pairCount; i++)
        {
            String ruType  = frags[i * 2];
            String objName = frags[i * 2 + 1];
            String enType  = MdTypeMapping.anyToEnSing(ruType);
            if (enType == null) return false;
            mdClassRu = ruType;
            if ("Форма".equals(ruType)) //$NON-NLS-1$ //$NON-NLS-2$
                isForm = true;
            else if ("Команда".equals(ruType)) //$NON-NLS-1$ //$NON-NLS-2$
                isCommand = true;
            if (engFqn.length() > 0) engFqn.append('.');
            engFqn.append(enType).append('.').append(objName);
        }

        if (mdClassRu == null) return false;

        // ── EClass для поиска в индексе ──────────────────────────────────────
        EClass mdLiteral = getMdLiteralForIndex(mdClassRu, isForm, isCommand);
        if (mdLiteral == null) 
            return false;
        IV8ProjectManager projectManager = (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);
        IV8Project v8Project = projectManager.getProject(activeProject);
        if (v8Project == null) 
            return false;
        QualifiedName qn = buildQualifiedName(engFqn.toString());
        MdObject mdObject = (MdObject) resolveEObjectByQualifiedName(qn.toString(), v8Project);

        // ── Свойство-модуль (objectModule, managerModule, …) ─────────────────
        // Последний фрагмент пути — суффикс модуля (МодульОбъекта, МодульМенеджера, Форма, …)
        String lastFrag      = frags[frags.length - 1];
        String enModuleSuffix = MdTypeMapping.anyToEnSing(lastFrag); // "ObjectModule", "Form", …
        EStructuralFeature moduleProperty = null;
        if (enModuleSuffix != null)
        {
            // Имя свойства = EN-имя с маленькой первой буквой: "ObjectModule" → "objectModule"
            String propName = Character.toLowerCase(enModuleSuffix.charAt(0))
                              + enModuleSuffix.substring(1);
            moduleProperty = mdObject.eClass().getEStructuralFeature(propName);
        }

        // ── Открываем редактор ───────────────────────────────────────────────
        OpenHelper openHelper = new OpenHelper();
        IEditorPart editorPart = openHelper.openEditor(mdObject, moduleProperty);
        if (editorPart == null) return false;

        // Для форм: активируем страницу модуля
        if (isForm)
        {
            FormEditor formEditor = (FormEditor) editorPart;
            formEditor.setActivePage("editors.form.pages.module"); //$NON-NLS-1$
        }
        
        // ── Извлекаем XtextEditor и переходим к строке ──────────────────────
        XtextEditor xtextEditor = BslHandlerUtil.extractXtextEditor(editorPart);
        if (xtextEditor == null) return false;

        IXtextDocument document = (IXtextDocument) xtextEditor.getDocument();

        // Определяем целевую строку (0-based)
        int targetLine0;
        if (r.method != null && !r.method.isEmpty())
        {
            // Расширенная ссылка: ищем метод, переходим на methodLine + offset
            int methodLine0 = findMethodLine(document, r.method);
            targetLine0 = (methodLine0 >= 0) ? methodLine0 + r.offset : r.line - 1;
        }
        else
        {
            targetLine0 = r.line - 1;
        }

        try
        {
            int offset = document.getLineOffset(Math.max(0, targetLine0));
            xtextEditor.selectAndReveal(offset, 0);
            return true;
        }
        catch (BadLocationException e)
        {
            Global.log("GoToDefinition.openModuleRefViaBmIndex: revealLine " + targetLine0 + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    /**
     * Возвращает {@link EClass} из {@link MdClassPackage.Literals} для поиска объекта
     * в BM-индексе.
     *
     * <p>Карта соответствий воспроизводит {@code getMdLiteral} из
     * EDT.CodeLinkOpener/OpenerHandlerDialog с учётом флагов {@code isForm}/{@code isCommand}.
     */
    private static EClass getMdLiteralForIndex(String mdClassRu, boolean isForm, boolean isCommand)
    {
        switch (mdClassRu)
        {
            case "Конфигурация":            return MdClassPackage.Literals.CONFIGURATION;
            case "Подсистема":              return MdClassPackage.Literals.SUBSYSTEM;
            case "HTTPСервис":              return MdClassPackage.Literals.HTTP_SERVICE;
            case "WebСервис":               return MdClassPackage.Literals.WEB_SERVICE;
            case "ВнешнийИсточникДанных":   return MdClassPackage.Literals.EXTERNAL_DATA_SOURCE;
            case "ОбщийМодуль":             return MdClassPackage.Literals.COMMON_MODULE;
            case "ОбщаяФорма":              return MdClassPackage.Literals.COMMON_FORM;
            case "ОбщаяКоманда":            return MdClassPackage.Literals.COMMON_COMMAND;
            case "Константа":               return MdClassPackage.Literals.CONSTANT;

            case "Справочник":
                return isForm ? MdClassPackage.Literals.CATALOG_FORM
                     : isCommand ? MdClassPackage.Literals.CATALOG_COMMAND
                     : MdClassPackage.Literals.CATALOG;
            case "Документ":
                return isForm ? MdClassPackage.Literals.DOCUMENT_FORM
                     : isCommand ? MdClassPackage.Literals.DOCUMENT_COMMAND
                     : MdClassPackage.Literals.DOCUMENT;
            case "Перечисление":
                return isForm ? MdClassPackage.Literals.ENUM_FORM
                     : isCommand ? MdClassPackage.Literals.ENUM_COMMAND
                     : MdClassPackage.Literals.ENUM;
            case "Обработка":
                return isForm ? MdClassPackage.Literals.DATA_PROCESSOR_FORM
                     : isCommand ? MdClassPackage.Literals.DATA_PROCESSOR_COMMAND
                     : MdClassPackage.Literals.DATA_PROCESSOR;
            case "Отчет":
                return isForm ? MdClassPackage.Literals.REPORT_FORM
                     : isCommand ? MdClassPackage.Literals.REPORT_COMMAND
                     : MdClassPackage.Literals.REPORT;
            case "ПланОбмена":
                return isForm ? MdClassPackage.Literals.EXCHANGE_PLAN_FORM
                     : isCommand ? MdClassPackage.Literals.EXCHANGE_PLAN_COMMAND
                     : MdClassPackage.Literals.EXCHANGE_PLAN;
            case "ПланВидовХарактеристик":
                return isForm ? MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES_FORM
                     : isCommand ? MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES_COMMAND
                     : MdClassPackage.Literals.CHART_OF_CHARACTERISTIC_TYPES;
            case "ПланВидовРасчета":
                return isForm ? MdClassPackage.Literals.CHART_OF_CALCULATION_TYPES_FORM
                     : isCommand ? MdClassPackage.Literals.CHART_OF_CALCULATION_TYPES_COMMAND
                     : MdClassPackage.Literals.CHART_OF_CALCULATION_TYPES;
            case "ПланСчетов":
                return isForm ? MdClassPackage.Literals.CHART_OF_ACCOUNTS_FORM
                     : isCommand ? MdClassPackage.Literals.CHART_OF_ACCOUNTS_COMMAND
                     : MdClassPackage.Literals.CHART_OF_ACCOUNTS;
            case "РегистрСведений":
                return isForm ? MdClassPackage.Literals.INFORMATION_REGISTER_FORM
                     : isCommand ? MdClassPackage.Literals.INFORMATION_REGISTER_COMMAND
                     : MdClassPackage.Literals.INFORMATION_REGISTER;
            case "РегистрНакопления":
                return isForm ? MdClassPackage.Literals.ACCUMULATION_REGISTER_FORM
                     : isCommand ? MdClassPackage.Literals.ACCUMULATION_REGISTER_COMMAND
                     : MdClassPackage.Literals.ACCUMULATION_REGISTER;
            case "РегистрБухгалтерии":
                return isForm ? MdClassPackage.Literals.ACCOUNTING_REGISTER_FORM
                     : isCommand ? MdClassPackage.Literals.ACCOUNTING_REGISTER_COMMAND
                     : MdClassPackage.Literals.ACCOUNTING_REGISTER;
            case "РегистрРасчета":
                return isForm ? MdClassPackage.Literals.CALCULATION_REGISTER_FORM
                     : isCommand ? MdClassPackage.Literals.CALCULATION_REGISTER_COMMAND
                     : MdClassPackage.Literals.CALCULATION_REGISTER;
            case "БизнесПроцесс":
                return isForm ? MdClassPackage.Literals.BUSINESS_PROCESS_FORM
                     : isCommand ? MdClassPackage.Literals.BUSINESS_PROCESS_COMMAND
                     : MdClassPackage.Literals.BUSINESS_PROCESS;
            case "Задача":
                return isForm ? MdClassPackage.Literals.TASK_FORM
                     : isCommand ? MdClassPackage.Literals.TASK_COMMAND
                     : MdClassPackage.Literals.TASK;

            default: return null;
        }
    }

    /**
     * Строит {@link QualifiedName} из строки вида {@code "Catalog.Валюты.Form.Форма"}.
     */
    private static QualifiedName buildQualifiedName(String dotSeparated)
    {
        String[] parts = dotSeparated.split("\\.", -1); //$NON-NLS-1$
        QualifiedName qn = null;
        for (String p : parts)
            qn = qn == null ? QualifiedName.create(p) : qn.append(p);
        return qn;
    }

    /**
     * Ищет 0-based номер строки объявления метода {@code methodName} в документе.
     * Используется для расширенных ссылок вида
     * {@code {Module(line:MethodName,offset)}}: даже если метод переместился
     * по файлу, переход всё равно попадёт в нужную строку.
     *
     * @return 0-based номер строки или {@code -1} если метод не найден
     */
    private static int findMethodLine(IXtextDocument document, String methodName)
    {
        Pattern p = Pattern.compile(
            String.format(
                "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+%s\\s*[\\(\\s]", //$NON-NLS-1$
                Pattern.quote(methodName)),
            Pattern.UNICODE_CASE);

        String text  = document.get();
        int    start = 0;
        int    line  = 0;
        while (start <= text.length())
        {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String lineText = text.substring(start, end);
            if (p.matcher(lineText).find()) return line;
            start = end + 1;
            line++;
        }
        return -1;
    }

    // =======================================================================
    // GIT-ССЫЛКА
    // =======================================================================

    private static boolean openGitFileRef(String filePath, int line, IWorkbenchPage page, Shell shell)
    {
        IFile file = findFileInWorkspace(filePath, page);
        if (file == null && !filePath.startsWith("src/")) //$NON-NLS-1$
            file = findFileInWorkspace("src/" + filePath, page); //$NON-NLS-1$
        if (file == null)
        {
            Global.log("GoToDefinition: файл не найден: " + filePath); //$NON-NLS-1$
            return false;
        }
        return openFileAtLine(file, line, page, shell);
    }

    // =======================================================================
    // XDTO
    // =======================================================================

    private static boolean openXdtoRef(String ref, Shell shell, IWorkbenchPage page)
    {
        String[] parts = ref.split("\\.", 3); //$NON-NLS-1$
        if (parts.length < 2) return false;
        return openMdObjectByFullName("ПакетXDTO." + parts[1], shell, page); //$NON-NLS-1$
    }

    // =======================================================================
    // ПОЛНОЕ ИМЯ МД
    // =======================================================================

    public static boolean openByFullName(String fullName, Shell shell, IWorkbenchPage page)
    {
        if (fullName == null || fullName.isBlank()) return false;
        fullName = fullName.strip();

        String extension = null;
        int spaceIdx = fullName.indexOf(' ');
        if (spaceIdx > 0 && !fullName.substring(0, spaceIdx).contains(".")) //$NON-NLS-1$
        {
            extension = fullName.substring(0, spaceIdx);
            fullName  = fullName.substring(spaceIdx + 1);
        }

        if (isModuleSuffixPath(fullName))
        {
            String bslPath = moduleToBslPath(fullName, extension);
            if (bslPath != null) return openBslFileAt(bslPath, 0, page, shell);
        }

        int formPos = indexOfFormPart(fullName);
        if (formPos >= 0)
        {
            String formBsl = formNameToBslPath(fullName, formPos, extension);
            if (formBsl != null && openBslFileAt(formBsl, 0, page, shell)) return true;
            return openMdObjectByFullName(fullName.substring(0, formPos), shell, page);
        }

        return openMdObjectByFullName(fullName, shell, page);
    }

    // =======================================================================
    // EDT: ОТКРЫТИЕ ОБЪЕКТА МД
    // =======================================================================

    private static boolean openMdObjectByFullName(String fullName, Shell shell, IWorkbenchPage page)
    {
        IV8ProjectManager projectManager =
            (IV8ProjectManager) Global.getServiceByClass(IV8ProjectManager.class);

        IProject activeProject = Global.getActiveProject(page, true);
        if (activeProject == null)
        {
            ToastNotification.show("Переход к определению", "Сначала нужно активировать проект.");
            return false;
        }

        IV8Project v8Project = projectManager.getProject(activeProject);
        if (v8Project == null)
        {
            ToastNotification.show("Переход к определению",
                "Проект не распознан EDT: " + activeProject.getName());
            return false;
        }

        EObject eObject = resolveEObjectByQualifiedName(fullName, v8Project);
        if (eObject != null)
        {
            new OpenHelper().openEditor((MdObject) eObject);
            return true;
        }

        String mdoPath = mdNameToMdoPath(fullName);
        if (mdoPath == null)
        {
            Global.log("GoToDefinition: не могу построить .mdo-путь для: " + fullName); //$NON-NLS-1$
            return false;
        }

        IFile mdoFile = findFileInWorkspace(mdoPath, page);
        if (mdoFile == null)
        {
            Global.log("GoToDefinition: .mdo не найден: " + mdoPath); //$NON-NLS-1$
            return false;
        }

        eObject = resolveEObjectViaResourceSet(mdoFile, v8Project);
        if (eObject != null)
        {
            new OpenHelper().openEditor((MdObject) eObject);
            return true;
        }

        Global.log("GoToDefinition: EObject не получен, открываем .mdo напрямую"); //$NON-NLS-1$
        try
        {
            return IDE.openEditor(page, mdoFile, true) != null;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: IDE.openEditor(.mdo): " + e); //$NON-NLS-1$
            return false;
        }
    }

    public static EObject resolveEObjectViaResourceSet(IFile file, IV8Project v8Project)
    {
        URI fileUri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(fileUri);
        IQualifiedNameFilePathConverter conv = rsp.get(IQualifiedNameFilePathConverter.class);
        QualifiedName fqn = conv.getFqn(file);
        if (fqn == null)
        {
            Global.log("GoToDefinition: getFqn вернул null для " + file.getFullPath()); //$NON-NLS-1$
            return null;
        }
        return resolveEObjectByQualifiedName(fqn.toString(), v8Project);
    }

    public static EObject resolveEObjectByQualifiedName(String fqn, IV8Project v8Project)
    {
        IBmModelManager modelManager =
            (IBmModelManager) Global.getServiceByClass(IBmModelManager.class);
        BmPlatform platform = modelManager.getBmPlatform();
        IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
        IBmNamespace ns = modelManager.getBmNamespace(v8Project.getProject());
        try
        {
            return (EObject) transaction.getTopObjectByFqn(ns, fqn);
        }
        catch (Exception e) { return null; }
        finally { transaction.commit(); }
    }

    // =======================================================================
    // ФАЙЛОВЫЕ ОПЕРАЦИИ
    // =======================================================================

    private static boolean openBslFileAt(String bslPath, int line, IWorkbenchPage page, Shell shell)
    {
        IFile file = findFileInWorkspace(bslPath, page);
        if (file == null)
        {
            Global.log("GoToDefinition: BSL не найден: " + bslPath); //$NON-NLS-1$
            return false;
        }
        return openFileAtLine(file, line, page, shell);
    }

    private static boolean openFileAtLine(IFile file, int line, IWorkbenchPage page, Shell shell)
    {
        try
        {
            IEditorPart editor = IDE.openEditor(page, file, true);
            if (editor == null) return false;
            if (line > 0) revealLine(editor, line);
            return true;
        }
        catch (PartInitException e)
        {
            Global.log("GoToDefinition: openFileAtLine: " + e); //$NON-NLS-1$
            return false;
        }
    }

    private static void revealLine(IEditorPart editor, int lineNumber)
    {
        if (lineNumber <= 0 || !(editor instanceof ITextEditor)) return;
        ITextEditor te  = (ITextEditor) editor;
        IDocument   doc = te.getDocumentProvider().getDocument(te.getEditorInput());
        if (doc == null) return;
        try
        {
            int offset = doc.getLineOffset(Math.max(0, lineNumber - 1));
            te.selectAndReveal(offset, 0);
        }
        catch (BadLocationException e)
        {
            Global.log("GoToDefinition: revealLine " + lineNumber + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static IFile findFileInWorkspace(String relPath, IWorkbenchPage page)
    {
        String path = relPath.replace('\\', '/');
        IProject active = Global.getActiveProject(page, true);
        if (active != null)
        {
            IFile f = findInProject(active, path);
            if (f != null) return f;
        }
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (!p.isOpen() || p.equals(active)) continue;
            IFile f = findInProject(p, path);
            if (f != null) return f;
        }
        return null;
    }

    private static IFile findInProject(IProject project, String path)
    {
        IFile f = project.getFile(path);
        return f.exists() ? f : null;
    }

    // =======================================================================
    // КОНВЕРТАЦИЯ ИМЁН МОДУЛЕЙ → ПУТИ BSL
    // =======================================================================

    static String moduleToBslPath(String modulePath, String extension)
    {
        String[] parts = modulePath.split("\\.", -1); //$NON-NLS-1$
        if (parts.length == 0) return null;
        String folder = MdTypeMapping.anyToFolder(parts[0]);
        if (folder == null) return null;
        String prefix = extension != null
            ? "src/ext/" + extension + "/" + folder //$NON-NLS-1$ //$NON-NLS-2$
            : "src/" + folder; //$NON-NLS-1$

        if (folder.startsWith("Common") && parts.length >= 2) //$NON-NLS-1$
            return prefix + "/" + parts[1] + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
        if (parts.length < 2) return null;
        String objectName = parts[1];
        for (int i = 2; i < parts.length - 1; i++)
        {
            if ("Форма".equals(parts[i]) && i + 1 < parts.length) //$NON-NLS-1$
                return prefix + "/" + objectName + "/Forms/" + parts[i + 1] + "/Ext/Form.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (parts.length >= 3)
        {
            String bslFile = MODULE_SUFFIX_TO_BSL.get(parts[parts.length - 1]);
            if (bslFile != null)
                return prefix + "/" + objectName + "/Ext/" + bslFile; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static String mdNameToMdoPath(String fullName)
    {
        String[] parts = fullName.split("\\.", 3); //$NON-NLS-1$
        if (parts.length < 2) return null;
        String folder = MdTypeMapping.anyToFolder(parts[0]);
        if (folder == null) return null;
        String objectName = parts[1];
        return "src/" + folder + "/" + objectName + "/" + objectName + ".mdo"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static String formNameToBslPath(String fullName, int formIdx, String extension)
    {
        String[] parts = fullName.split("\\."); //$NON-NLS-1$
        for (int i = 0; i < parts.length - 1; i++)
        {
            if ("Форма".equals(parts[i]) || "Forms".equals(parts[i])) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (i >= 1 && i + 1 < parts.length)
                {
                    String folder = MdTypeMapping.anyToFolder(parts[0]);
                    if (folder == null) return null;
                    String prefix = extension != null
                        ? "src/ext/" + extension + "/" + folder //$NON-NLS-1$ //$NON-NLS-2$
                        : "src/" + folder; //$NON-NLS-1$
                    return prefix + "/" + parts[1] + "/Forms/" + parts[i + 1] + "/Ext/Form.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        return null;
    }

    // =======================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =======================================================================

    private static String stripTypeSuffix(String ref)
    {
        int dot = ref.indexOf('.');
        if (dot < 0) return null;
        String base = TYPE_SUFFIX_MAP.get(ref.substring(0, dot));
        return base != null ? base + ref.substring(dot) : null;
    }

    private static List<String> extractMdNamesFromTypeDescription(String types)
    {
        List<String> result = new ArrayList<>();
        for (String part : TYPE_DESCRIPTION_SPLITTER.split(types))
        {
            String t = part.strip();
            if (t.isEmpty()) continue;
            String stripped = stripTypeSuffix(t);
            if (stripped != null)     result.add(stripped);
            else if (t.contains(".")) result.add(t); //$NON-NLS-1$
        }
        return result;
    }

    private static boolean pickAndOpen(List<String> names, Shell shell, IWorkbenchPage page)
    {
        MessageDialog dlg = new MessageDialog(shell,
            "Выберите объект", null, //$NON-NLS-1$
            "Найдено несколько объектов. Выберите для перехода:", //$NON-NLS-1$
            MessageDialog.QUESTION, names.toArray(new String[0]), 0);
        int idx = dlg.open();
        if (idx < 0 || idx >= names.size()) return false;
        return openByFullName(names.get(idx), shell, page);
    }

    private static boolean isModuleSuffixPath(String name)
    {
        for (String suffix : MODULE_SUFFIX_TO_BSL.keySet())
            if (name.endsWith("." + suffix)) return true; //$NON-NLS-1$
        return false;
    }

    private static int indexOfFormPart(String fullName)
    {
        for (String marker : new String[]{ ".Форма.", ".Forms.", ".ЭлементыФормы.", ".Элементы." }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            int idx = fullName.indexOf(marker);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    // =======================================================================
    // ПАРСИНГ ССЫЛОК СТРОК МОДУЛЕЙ
    // =======================================================================

    private static List<ModuleLineRef> parseAllModuleLineRefs(String text)
    {
        List<ModuleLineRef> result = new ArrayList<>();
        Matcher m = MODULE_LINE_REF.matcher(text);
        while (m.find())
        {
            ModuleLineRef r = new ModuleLineRef();
            r.extension  = m.group(1);
            r.modulePath = m.group(2);
            r.line       = parseInt(m.group(3));
            r.method     = m.group(4);
            r.offset     = parseInt(m.group(5));
            if (r.modulePath.contains("/") || r.modulePath.contains("\\")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                r.file       = r.modulePath;
                r.modulePath = null;
            }
            result.add(r);
        }
        return result;
    }

    private static final class ModuleLineRef
    {
        String extension;
        String modulePath;
        String file;
        int    line;
        String method;
        int    offset;

        String displayLabel()
        {
            String base = modulePath != null ? modulePath : file;
            String s = (base != null ? base : "") + " (" + line + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (method != null)    s += " : " + method;    //$NON-NLS-1$
            if (extension != null) s += " [" + extension + "]"; //$NON-NLS-1$ //$NON-NLS-2$
            return s;
        }
    }

    // =======================================================================
    // УТИЛИТЫ
    // =======================================================================

    private static void openUrl(String url)
    {
        try { org.eclipse.swt.program.Program.launch(url); }
        catch (Exception e) { Global.log("GoToDefinition: openUrl: " + e); } //$NON-NLS-1$
    }

    private static String getClipboardText(Shell shell)
    {
        Clipboard cb = new Clipboard(shell.getDisplay());
        try { return (String) cb.getContents(TextTransfer.getInstance()); }
        finally { cb.dispose(); }
    }

    private static int parseInt(String s)
    {
        if (s == null) return 0;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026"; //$NON-NLS-1$
    }
}

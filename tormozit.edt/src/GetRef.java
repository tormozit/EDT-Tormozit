
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.xtext.resource.IResourceServiceProvider;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
import com._1c.g5.v8.dt.compare.model.MatchedObjectsComparisonNode;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Глобальная команда «Ссылка».
 *
 * <h3>Режим 1: BSL-редактор активен → ссылки на строку модуля (3 уведомления)</h3>
 * <ol>
 *   <li><b>Расширенная ссылка</b> — помещается в буфер обмена и показывается
 *       первым уведомлением. Поддерживается только в ИР:
 *       <pre>{[РасшИмя ]Тип.Объект.Модуль(НомерСтроки:ИмяМетода,СмещениеВМетоде)}: *ТекстСтроки</pre></li>
 *   <li><b>Стандартная ссылка</b> — второе уведомление. Поддерживается в ИР и EDT:
 *       <pre>{[РасшИмя ]Тип.Объект.Модуль(НомерСтроки)}</pre></li>
 *   <li><b>Полное имя метода</b> — третье уведомление (только если курсор внутри метода).
 *       Для вставки в документирующие комментарии после «см. »:
 *       <pre>см. Тип.Объект.Форма.ИмяФормы.ИмяМетода</pre></li>
 * </ol>
 *
 * <h3>Режим 2: навигатор / compare editor / другой редактор → имя объекта МД</h3>
 * Пример: {@code Справочник.Валюты.Макет.Классификатор}
 */
public class GetRef extends AbstractHandler
{
    // =========================================================================
    // Маппинги
    // =========================================================================

    /** EDT-папка под-объекта → русское название раздела. */
    private static final Map<String, String> SUBFOLDER_TO_RU = new LinkedHashMap<>();
    static
    {
        SUBFOLDER_TO_RU.put("Forms",          "Форма");
        SUBFOLDER_TO_RU.put("Templates",      "Макет");
        SUBFOLDER_TO_RU.put("Commands",       "Команда");
        SUBFOLDER_TO_RU.put("Recalculations", "Перерасчет");
    }

    /** Имя BSL-файла в Ext/ → русский суффикс в полном имени объекта. */
    private static final Map<String, String> BSL_TO_MODULE_RU = new LinkedHashMap<>();
    static
    {
        BSL_TO_MODULE_RU.put("ObjectModule.bsl",    "МодульОбъекта");
        BSL_TO_MODULE_RU.put("ManagerModule.bsl",   "МодульМенеджера");
        BSL_TO_MODULE_RU.put("RecordSetModule.bsl", "МодульНабораЗаписей");
        BSL_TO_MODULE_RU.put("Module.bsl",          "Модуль");
        // Form.bsl под Forms/<Name>/Ext/ обрабатывается отдельно
    }

    /**
     * Папки, где Ext-файл является самим объектом, а не его под-модулем
     * (суффикс модуля не добавляется).
     */
    private static final Set<String> TOP_LEVEL_CONTAINERS = new HashSet<>(Arrays.asList(
        "CommonModules", "CommonForms", "CommonTemplates", "CommonPictures", "CommonCommands"
    ));

    /**
     * Суффиксы, идентифицирующие ТИП модуля в пути ссылки.
     * Убираются при построении ссылки на метод («см. Путь.ИмяМетода»),
     * но только для путей из 3+ сегментов (чтобы не трогать «ОбщийМодуль.МойМодуль»).
     */
    private static final Set<String> MODULE_TYPE_SUFFIXES = new HashSet<>(Arrays.asList(
        "МодульОбъекта", "МодульМенеджера", "МодульНабораЗаписей", "Модуль", "Форма"
    ));

    /** Строка объявления Процедуры/Функции (опциональный «Асинх»). */
    private static final Pattern METHOD_START = Pattern.compile(
        "^\\s*(?:Асинх\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+" +
        "([А-ЯЁа-яёA-Za-z_][А-ЯЁа-яёA-Za-z0-9_]*)",
        Pattern.UNICODE_CASE);

    /** Строка завершения Процедуры/Функции. */
    private static final Pattern METHOD_END = Pattern.compile(
        "^\\s*(?:КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b",
        Pattern.UNICODE_CASE);

    // =========================================================================
    // IHandler
    // =========================================================================

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        IWorkbenchPage page  = part.getSite().getPage();
        Shell          shell = HandlerUtil.getActiveShell(event);

        // Режим 1: BSL-редактор → ссылки на строку модуля
        BslXtextEditor bslEditor = getActiveBslEditor(part, page);
        if (bslEditor != null)
        {
            showModuleLineRefs(bslEditor, shell);
            return null;
        }

        // Режим 2: имя объекта МД
        String ref = resolveRef(part, page);
        if (ref == null || ref.isBlank())
        {
            ToastNotification.show("Ссылка",
                "Не удалось определить имя объекта метаданных.\n"
                + "Выберите узел в навигаторе, дереве сравнения или откройте редактор объекта.",
                5000);
            return null;
        }

        Global.log("GetRef: " + ref); //$NON-NLS-1$
        setClipboardText(ref, shell);
        ToastNotification.show("Скопирована ссылка", ref, 6000);
        return null;
    }

    // =========================================================================
    // Режим 1: ссылки на строку модуля BSL
    // =========================================================================

    /**
     * Формирует три варианта ссылки на строку модуля и показывает их в отдельных уведомлениях.
     * Расширенная ссылка (вариант 1) сразу помещается в буфер обмена.
     */
    private static void showModuleLineRefs(BslXtextEditor bslEditor, Shell shell)
    {
        // Путь к модулю
        IFile file = bslEditor.getEditorInput() != null
            ? bslEditor.getEditorInput().getAdapter(IFile.class) : null;
        if (file == null) return;

        ModuleRef moduleRef = pathToModuleRef(file.getProjectRelativePath().toString());
        if (moduleRef == null)
        {
            // Не удалось разобрать как BSL-модуль; fallback на имя объекта
            String ref = pathToFullName(file.getProjectRelativePath().toString());
            if (ref != null) { setClipboardText(ref, shell); ToastNotification.show("Скопирована ссылка", ref, 6000); }
            else ToastNotification.show("Ссылка", "Не удалось определить путь к модулю", 5000);
            return;
        }

        // Документ и позиция курсора
        ISourceViewer viewer = bslEditor.getInternalSourceViewer();
        if (viewer == null) return;
        IDocument doc = viewer.getDocument();
        if (doc == null) return;

        Object selObj = viewer.getSelectionProvider().getSelection();
        if (!(selObj instanceof ITextSelection)) return;
        ITextSelection textSel = (ITextSelection) selObj;

        int lineNumber; // 1-based
        String markedLine;
        MethodInfo method;
        try
        {
            int line0 = doc.getLineOfOffset(textSel.getOffset());
            lineNumber = line0 + 1;

            // Текст строки с «*» на позиции курсора (leading whitespace убирается)
            IRegion li  = doc.getLineInformation(line0);
            String  raw = doc.get(li.getOffset(), li.getLength());

            int leadingWs = 0;
            while (leadingWs < raw.length()
                    && (raw.charAt(leadingWs) == ' ' || raw.charAt(leadingWs) == '\t'))
                leadingWs++;

            int col         = textSel.getOffset() - li.getOffset();
            String stripped = raw.substring(leadingWs).stripTrailing();
            int strippedCol = Math.min(Math.max(0, col - leadingWs), stripped.length());
            markedLine = stripped.substring(0, strippedCol) + "*" + stripped.substring(strippedCol); //$NON-NLS-1$

            // Объемлющий метод
            method = findEnclosingMethod(doc, line0);
        }
        catch (BadLocationException e)
        {
            Global.log("GetRef.showModuleLineRefs: " + e); //$NON-NLS-1$
            return;
        }

        String prefix = moduleRef.toRefPrefix(); // «[РасшИмя ]Тип.Объект.Модуль»

        // ── Ссылка 1: расширенная ────────────────────────────────────────────
        String ref1;
        if (method != null)
        {
            int offset = lineNumber - method.declarationLine1;
            ref1 = "{" + prefix + "(" + lineNumber + ":" + method.name + "," + offset + ")}: " + markedLine; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        else
        {
            ref1 = "{" + prefix + "(" + lineNumber + ")}: " + markedLine; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        // ── Ссылка 2: стандартная ─────────────────────────────────────────────
        String ref2 = "{" + prefix + "(" + lineNumber + ")}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // ── Ссылка 3: полное имя метода ───────────────────────────────────────
        // Использует modulePath БЕЗ имени расширения; последний суффикс-тип модуля заменяется
        // именем метода: «Обработка.ирКонсольКода.Форма.Форма.Форма» → «...Форма.МетодИмя»
        String ref3 = null;
        if (method != null)
        {
            String methodBase = stripModuleSuffix(moduleRef.modulePath);
            ref3 = "см. " + methodBase + "." + method.name; //$NON-NLS-1$ //$NON-NLS-2$
        }

        Global.log("GetRef (line ref): " + ref1); //$NON-NLS-1$

        // Ref 1 → буфер обмена + первое уведомление
        setClipboardText(ref1, shell);
        ToastNotification.show("Расширенная ссылка строки", ref1, 10_000);

        // Ref 2 → второе уведомление
        ToastNotification.show("Стандартная ссылка строки", ref2, 10_000);

        // Ref 3 → третье уведомление (если курсор внутри метода)
        if (ref3 != null)
            ToastNotification.show("Полное имя метода", ref3, 10_000);
    }

    /**
     * Возвращает активный {@link BslXtextEditor} или {@code null} если BSL-редактор
     * не активен.
     *
     * <p>Проверяет (в порядке приоритета):
     * <ol>
     *   <li>Сам {@code part} — BSL-редактор (открыт как самостоятельная вкладка).</li>
     *   <li>Активный редактор — {@link DtGranularEditor} с активной BSL-страницей.</li>
     *   <li>Активный редактор — непосредственно BSL-редактор.</li>
     * </ol>
     */
    private static BslXtextEditor getActiveBslEditor(IWorkbenchPart part, IWorkbenchPage page)
    {
        if (part instanceof BslXtextEditor) return (BslXtextEditor) part;

        IEditorPart editor = page != null ? page.getActiveEditor() : null;

        if (editor instanceof DtGranularEditor<?>)
        {
            IFormPage activePage = ((DtGranularEditor<?>) editor).getActivePageInstance();
            if (activePage instanceof DtGranularEditorXtextEditorPage<?>)
            {
                IEditorPart embedded =
                    ((DtGranularEditorXtextEditorPage<?>) activePage).getEmbeddedEditor();
                if (embedded instanceof BslXtextEditor)
                    return (BslXtextEditor) embedded;
            }
        }

        if (editor instanceof BslXtextEditor) return (BslXtextEditor) editor;

        return null;
    }

    // =========================================================================
    // Конвертация пути файла → путь модуля для ссылок
    // =========================================================================

    /**
     * Разбирает project-relative путь BSL-файла и возвращает {@link ModuleRef}
     * с именем расширения (или {@code null}) и путём модуля в формате ссылок 1С.
     *
     * <pre>
     *   src/Catalogs/Валюты/Ext/ObjectModule.bsl                        → null, "Справочник.Валюты.МодульОбъекта"
     *   src/CommonModules/МойМодуль/Ext/Module.bsl                       → null, "ОбщийМодуль.МойМодуль"
     *   src/DataProcessors/ирКонсольКода/Forms/Форма/Ext/Form.bsl        → null, "Обработка.ирКонсольКода.Форма.Форма.Форма"
     *   src/ext/ИнструментыРазработчикаTormozit/DataProcessors/.../Form.bsl → "ИнструментыРазработчикаTormozit", "Обработка.ирКонсольКода.Форма.Форма.Форма"
     * </pre>
     *
     * <p>Ключевое отличие от {@link #pathToFullName}: для формы добавляется
     * суффикс {@code .Форма} (тип модуля формы) — итого 5 сегментов вместо 4.
     */
    static ModuleRef pathToModuleRef(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');

        String extensionName = null;
        String relative;

        if (path.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = path.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            extensionName = rest.substring(0, slash);
            relative = rest.substring(slash + 1);
        }
        else if (path.startsWith("src/")) //$NON-NLS-1$
        {
            relative = path.substring("src/".length()); //$NON-NLS-1$
        }
        else { return null; }

        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 3 || p[0].isEmpty() || p[1].isEmpty()) return null;

        String typeRu = MdTypeMapping.folderToRu(p[0]);
        if (typeRu == null) return null;

        String base = typeRu + "." + p[1]; // «Тип.Объект» //$NON-NLS-1$
        String seg2 = p[2];

        // ── Ext/<BslFile> — модуль непосредственно объекта ───────────────────
        if (seg2.endsWith(".bsl") && p.length >= 3) //$NON-NLS-1$
        {
            if (TOP_LEVEL_CONTAINERS.contains(p[0]))
                // CommonModules, CommonForms: модуль И есть объект, суффикс не нужен
                return new ModuleRef(extensionName, base);

            String moduleSuffix = BSL_TO_MODULE_RU.get(p[3]);
            if (moduleSuffix == null) return null;
            return new ModuleRef(extensionName, base + "." + moduleSuffix); //$NON-NLS-1$
        }

        // ── Forms/<ФормаИмя>/Ext/Form.bsl — модуль формы ────────────────────
        // Путь: [0]=Folder [1]=Object [2]=Forms [3]=FormName [4]=Ext [5]=Form.bsl
        if ("Forms".equals(seg2) && p.length >= 6 //$NON-NLS-1$
                && "Ext".equals(p[4]) && "Form.bsl".equals(p[5])) //$NON-NLS-1$ //$NON-NLS-2$
        {
            // «Обработка.Объект.Форма.ИмяФормы.Форма» — последняя «Форма» = тип модуля формы
            return new ModuleRef(extensionName, base + ".Форма." + p[3] + ".Форма"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return null; // не BSL-модуль
    }

    /**
     * Убирает суффикс типа модуля (последний сегмент) из пути модуля,
     * чтобы получить базовый путь для ссылки на метод.
     *
     * <p>Суффикс убирается только для путей с 3+ сегментами (не трогает
     * двухсегментные пути вида «ОбщийМодуль.МойМодуль»).
     *
     * <pre>
     *   "Справочник.Валюты.МодульОбъекта"            → "Справочник.Валюты"
     *   "Обработка.Объект.Форма.Форма.Форма"          → "Обработка.Объект.Форма.Форма"
     *   "ОбщийМодуль.МойМодуль"                       → "ОбщийМодуль.МойМодуль"  (не меняется)
     * </pre>
     */
    private static String stripModuleSuffix(String modulePath)
    {
        int lastDot = modulePath.lastIndexOf('.');
        if (lastDot < 0) return modulePath;
        // Не трогаем двухсегментные пути (один '.'):  ОбщийМодуль.МойМодуль
        if (modulePath.indexOf('.') == lastDot) return modulePath;
        String lastSegment = modulePath.substring(lastDot + 1);
        return MODULE_TYPE_SUFFIXES.contains(lastSegment)
            ? modulePath.substring(0, lastDot)
            : modulePath;
    }

    // =========================================================================
    // Поиск объемлющего метода
    // =========================================================================

    /**
     * Ищет объявление Процедуры/Функции, содержащей строку {@code cursorLine0}.
     *
     * <p>Алгоритм: идёт от строки курсора вверх.
     * <ul>
     *   <li>Встречает «КонецПроцедуры»/«КонецФункции» — курсор вне метода,
     *       возвращает {@code null}.</li>
     *   <li>Встречает «Процедура»/«Функция» — найден объемлющий метод.</li>
     * </ul>
     *
     * @param cursorLine0 0-based номер строки с курсором
     * @return информация о методе или {@code null}
     */
    private static MethodInfo findEnclosingMethod(IDocument doc, int cursorLine0)
    {
        for (int line = cursorLine0; line >= 0; line--)
        {
            String text;
            try
            {
                IRegion info = doc.getLineInformation(line);
                text = doc.get(info.getOffset(), info.getLength());
            }
            catch (BadLocationException e) { break; }

            // КонецПроцедуры выше курсора → мы вне какого-либо метода
            if (line < cursorLine0 && METHOD_END.matcher(text).find())
                return null;

            Matcher m = METHOD_START.matcher(text);
            if (m.find())
                return new MethodInfo(m.group(1), line + 1); // 1-based
        }
        return null;
    }

    // =========================================================================
    // Режим 2: имя объекта МД
    // =========================================================================

    private static String resolveRef(IWorkbenchPart part, IWorkbenchPage page)
    {
        // Дерево сравнения конфигураций
        if (part instanceof IEditorPart
                && Global.COMPARE_EDITOR_ID.equals(part.getSite().getId()))
            return refFromCompareEditor((IEditorPart) part);

        // Навигатор
        if (part == page.findView(Global.NAVIGATOR_VIEW_ID))
            return refFromNavigator(page);

        // Редактор (не BSL — он перехвачен выше)
        String ref = getRefFromEditor(page);
        if (ref != null) return ref;

        // Fallback: навигатор
        return refFromNavigator(page);
    }

    // ── Дерево сравнения ─────────────────────────────────────────────────────

    private static String refFromCompareEditor(IEditorPart editor)
    {
        ISelection sel = CompareConfigOpenObjectHandler.getSelection(editor);
        if (!(sel instanceof IStructuredSelection)) return null;

        Object element = ((IStructuredSelection) sel).getFirstElement();
        if (element == null) return null;

        MatchedObjectsComparisonNode node =
            CompareConfigSelectionListener.resolveMatchedNode(element);
        if (node == null) return null;

        IComparisonSession session = CompareConfigSelectionListener.getSession(editor);
        if (session == null) return null;

        Long bmId = node.getMainObjectId();
        if (bmId == null || bmId == -1L) bmId = node.getOtherObjectId();
        if (bmId == null || bmId == -1L) return null;

        return eObjectToFullName(
            CompareConfigOpenObjectHandler.getEObject(session, bmId, node));
    }

    // ── Редактор ─────────────────────────────────────────────────────────────

    public static String getRefFromEditor(IWorkbenchPage page)
    {
        IEditorPart editor = page != null ? page.getActiveEditor() : null;
        if (editor == null) return null;

        if (editor instanceof DtGranularEditor<?>)
        {
            String ref = refFromGranularEditor((DtGranularEditor<?>) editor);
            if (ref != null) return ref;
        }
        return refFromEditorInput(editor.getEditorInput());
    }

    private static String refFromGranularEditor(DtGranularEditor<?> editor)
    {
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
        return refFromEditorInput(editor.getEditorInput());
    }

    private static String refFromEditorInput(IEditorInput input)
    {
        if (input == null) return null;
        IFile file = input.getAdapter(IFile.class);
        if (file == null) return null;
        return pathToFullName(file.getProjectRelativePath().toString());
    }

    // ── Навигатор ────────────────────────────────────────────────────────────

    private static String refFromNavigator(IWorkbenchPage page)
    {
        if (page == null) return null;
        try
        {
            CommonNavigator nav =
                (CommonNavigator) page.findView(Global.NAVIGATOR_VIEW_ID);
            if (nav == null) return null;
            IStructuredSelection sel = (IStructuredSelection)
                nav.getSite().getSelectionProvider().getSelection();
            if (sel == null || sel.isEmpty()) return null;
            return refFromElement(sel.getFirstElement());
        }
        catch (Exception e)
        {
            Global.log("GetRef.refFromNavigator: " + e); //$NON-NLS-1$
            return null;
        }
    }

    private static String refFromElement(Object element)
    {
        if (element == null) return null;

        if (element instanceof EObject)
        {
            String ref = eObjectToFullName((EObject) element);
            if (ref != null) return ref;
        }

        IFile file = Adapters.adapt(element, IFile.class);
        if (file != null)
        {
            String ref = pathToFullName(file.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }

        IResource resource = Adapters.adapt(element, IResource.class);
        if (resource != null && resource.getType() != IResource.PROJECT)
        {
            String ref = pathToFullName(resource.getProjectRelativePath().toString());
            if (ref != null) return ref;
        }

        for (String getter : new String[]{ "getFile", "getResource", "getMdObject" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object result = Global.call(element, getter);
            if (result instanceof EObject)
            {
                String ref = eObjectToFullName((EObject) result);
                if (ref != null) return ref;
            }
            if (result instanceof IFile)
            {
                String ref = pathToFullName(((IFile) result).getProjectRelativePath().toString());
                if (ref != null) return ref;
            }
        }
        return null;
    }

    // =========================================================================
    // EObject → полное имя МД
    // =========================================================================

    static String eObjectToFullName(EObject obj)
    {
        if (obj == null) return null;
        Resource emfResource = obj.eResource();
        if (emfResource == null) return null;
        URI uri = emfResource.getURI();

        if ("bm".equals(uri.scheme())) //$NON-NLS-1$
        {
            String fqnQnp = getFqnViaQnp(obj, uri);
            if (fqnQnp != null)
            {
                String r = MdTypeMapping.bmFqnToRuFullName(fqnQnp);
                if (r != null) return r;
            }
            String uriPath = uri.path();
            if (uriPath != null)
            {
                if (uriPath.startsWith("/")) uriPath = uriPath.substring(1); //$NON-NLS-1$
                String r = MdTypeMapping.bmFqnToRuFullName(uriPath);
                if (r != null) return r;
            }
            return null;
        }

        if (uri.isPlatformResource())
        {
            String pp = uri.toPlatformString(true);
            if (pp == null) return null;
            int s = pp.indexOf('/', 1);
            if (s < 0) return null;
            return pathToFullName(pp.substring(s + 1));
        }
        return null;
    }

    private static String getFqnViaQnp(EObject obj, URI uri)
    {
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
            if (rsp == null) return null;
            org.eclipse.xtext.naming.IQualifiedNameProvider qnp =
                rsp.get(org.eclipse.xtext.naming.IQualifiedNameProvider.class);
            if (qnp == null) return null;
            org.eclipse.xtext.naming.QualifiedName fqn = qnp.getFullyQualifiedName(obj);
            return fqn != null ? fqn.toString() : null;
        }
        catch (Exception e)
        {
            Global.log("GetRef.getFqnViaQnp: " + e); //$NON-NLS-1$
            return null;
        }
    }

    // =========================================================================
    // Путь файла EDT → полное русское имя МД (для объектов/папок)
    // =========================================================================

    static String pathToFullName(String projectRelativePath)
    {
        if (projectRelativePath == null) return null;
        String path = projectRelativePath.replace('\\', '/');

        String extensionName = null;
        String relative;

        if (path.startsWith("src/ext/")) //$NON-NLS-1$
        {
            String rest = path.substring("src/ext/".length()); //$NON-NLS-1$
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            extensionName = rest.substring(0, slash);
            relative = rest.substring(slash + 1);
        }
        else if (path.startsWith("src/")) //$NON-NLS-1$
            relative = path.substring("src/".length()); //$NON-NLS-1$
        else
            return null;

        String[] p = relative.split("/", -1); //$NON-NLS-1$
        if (p.length < 1 || p[0].isEmpty()) return null;

        String typeRu = MdTypeMapping.folderToRu(p[0]);
        if (typeRu == null) return null;
        if (p.length < 2 || p[1].isEmpty()) return withExt(extensionName, typeRu);

        String objectName = stripFileExt(p[1]);
        String base = withExt(extensionName, typeRu + "." + objectName); //$NON-NLS-1$
        if (p.length == 2) return base;

        String seg2 = p[2];
        if (seg2.endsWith(".mdo") && stripFileExt(seg2).equals(objectName)) return base; //$NON-NLS-1$

        if ("Ext".equals(seg2)) //$NON-NLS-1$
        {
            if (p.length < 4) return base;
            if (TOP_LEVEL_CONTAINERS.contains(p[0])) return base;
            String moduleSuffix = BSL_TO_MODULE_RU.get(p[3]);
            return moduleSuffix != null ? base + "." + moduleSuffix : base; //$NON-NLS-1$
        }

        String sectionRu = SUBFOLDER_TO_RU.get(seg2);
        if (sectionRu != null)
        {
            if (p.length < 4 || p[3].isEmpty()) return base + "." + sectionRu; //$NON-NLS-1$
            return base + "." + sectionRu + "." + stripFileExt(p[3]); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return base;
    }

    private static String withExt(String extensionName, String name)
    {
        return extensionName != null ? extensionName + " " + name : name; //$NON-NLS-1$
    }

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
        try { cb.setContents(new Object[]{ text }, new Transfer[]{ TextTransfer.getInstance() }); }
        finally { cb.dispose(); }
    }

    // =========================================================================
    // Вспомогательные классы
    // =========================================================================

    /**
     * Путь к BSL-модулю в формате ссылок 1С.
     *
     * <pre>
     *   extensionName = "ИнструментыРазработчикаTormozit"  (или null для конфигурации)
     *   modulePath    = "Обработка.ирКонсольКода.Форма.Форма.Форма"
     *   toRefPrefix() = "ИнструментыРазработчикаTormozit Обработка.ирКонсольКода.Форма.Форма.Форма"
     * </pre>
     */
    static final class ModuleRef
    {
        final String extensionName; // имя расширения или null
        final String modulePath;    // путь в формате ссылок (без имени расширения)

        ModuleRef(String extensionName, String modulePath)
        {
            this.extensionName = extensionName;
            this.modulePath    = modulePath;
        }

        /** «[ИмяРасш ]ПутьМодуля» — вставляется в фигурные скобки ссылок 1 и 2. */
        String toRefPrefix()
        {
            return extensionName != null ? extensionName + " " + modulePath : modulePath; //$NON-NLS-1$
        }
    }

    /** Объемлющий метод (Процедура/Функция). */
    private static final class MethodInfo
    {
        final String name;          // имя метода
        final int declarationLine1; // 1-based номер строки объявления

        MethodInfo(String name, int declarationLine1)
        {
            this.name             = name;
            this.declarationLine1 = declarationLine1;
        }
    }
}

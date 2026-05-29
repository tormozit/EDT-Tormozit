
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TODO Нужно максимально опереться на нтатный преобразователь EDT com._1c.g5.v8.dt.md.naming.MdSymbolicLinkLocalizer.localizeSymbolicLink(String symbolicLink, EObject contextObject, EStructuralFeature feature, Locale locale)
 *
 * <h3>Три формы одного типа</h3>
 * <pre>
 *   RU  (ед.ч. рус.)  — «Справочник»
 *   EN1 (ед.ч. англ.) — «Catalog»
 *   EN+ (мн.ч. англ.) — «Catalogs»  (имя папки в EDT-проекте; null для вложенных типов)
 * </pre>
 *
 * <h3>Поддерживаемые преобразования одного имени типа</h3>
 * <pre>
 *   ruToEnSing("Справочник")          = "Catalog"
 *   enSingToRu("Catalog")             = "Справочник"
 *   ruToFolder("Справочник")          = "Catalogs"
 *   enSingToFolder("Catalog")         = "Catalogs"
 *   folderToRu("Catalogs")            = "Справочник"
 *   folderToEnSing("Catalogs")        = "Catalog"
 * </pre>
 *
 * <h3>Нормализация в любую форму из любой</h3>
 * <pre>
 *   anyToRu("Catalog")      = anyToRu("Catalogs")      = "Справочник"
 *   anyToEnSing("Catalogs") = anyToEnSing("Справочник") = "Catalog"
 *   anyToFolder("Справочник") = anyToFolder("Catalog")  = "Catalogs"
 * </pre>
 *
 * <h3>Операции с полным именем объекта («Тип.Имя» или «Папка/Имя»)</h3>
 * <pre>
 *   toRuFullName("Catalog.Валюты")        = "Справочник.Валюты"
 *   toEnSingFullName("Справочник.Валюты") = "Catalog.Валюты"
 *   toFolderPath("Справочник.Валюты")     = "Catalogs/Валюты"
 *   toFolderPath("Catalog.Валюты")        = "Catalogs/Валюты"
 *   pathToRuFullName("Catalogs/Валюты")   = "Справочник.Валюты"
 *   pathToEnSingFullName("Catalogs/Валюты") = "Catalog.Валюты"
 * </pre>
 *
 * <h3>BM URI → полное русское имя (используется в GetRef)</h3>
 * <pre>
 *   bmFqnToRuFullName("Catalog.Валюты")                         = "Справочник.Валюты"
 *   bmFqnToRuFullName("Catalog.Валюты.Template.Классификатор")  = "Справочник.Валюты.Макет.Классификатор"
 * </pre>
 */
public final class MdTypeMapping
{
    private MdTypeMapping() {}

    // =========================================================================
    // Шесть производных маппингов — заполняются через add() / addAlias()
    // =========================================================================

    /** RU ед.ч. → EN ед.ч.  «Справочник» → «Catalog» */
    static final Map<String, String> RU_TO_EN_SING        = new LinkedHashMap<>();

    /** EN ед.ч. → RU ед.ч.  «Catalog» → «Справочник» */
    static final Map<String, String> EN_SING_TO_RU        = new LinkedHashMap<>();

    /** RU ед.ч. → EN мн.ч./папка  «Справочник» → «Catalogs»  (замена TYPE_TO_FOLDER) */
    static final Map<String, String> RU_TO_FOLDER         = new LinkedHashMap<>();

    /** EN ед.ч. → EN мн.ч./папка  «Catalog» → «Catalogs» */
    static final Map<String, String> EN_SING_TO_FOLDER    = new LinkedHashMap<>();

    /** EN мн.ч./папка → RU ед.ч.  «Catalogs» → «Справочник» */
    static final Map<String, String> FOLDER_TO_RU         = new LinkedHashMap<>();

    /** EN мн.ч./папка → EN ед.ч.  «Catalogs» → «Catalog» */
    static final Map<String, String> FOLDER_TO_EN_SING    = new LinkedHashMap<>();

    // =========================================================================
    // Единая таблица: (RU ед.ч., EN ед.ч., EN мн.ч./папка или null)
    // =========================================================================

    static
    {
        // ── Объекты верхнего уровня с папкой в EDT ───────────────────────────
        add("Справочник",                   "Catalog",                      "Catalogs");
        add("Документ",                     "Document",                     "Documents");
        add("Перечисление",                 "Enum",                         "Enums");
        add("ПланОбмена",                   "ExchangePlan",                 "ExchangePlans");
        add("Обработка",                    "DataProcessor",                "DataProcessors");
        add("Отчет",                        "Report",                       "Reports");
        add("БизнесПроцесс",                "BusinessProcess",              "BusinessProcesses");
        add("Задача",                       "Task",                         "Tasks");
        add("Последовательность",           "Sequence",                     "Sequences");

        add("РегистрСведений",              "InformationRegister",          "InformationRegisters");
        add("РегистрНакопления",            "AccumulationRegister",         "AccumulationRegisters");
        add("РегистрБухгалтерии",           "AccountingRegister",           "AccountingRegisters");
        add("РегистрРасчета",               "CalculationRegister",          "CalculationRegisters");

        add("ПланВидовХарактеристик",       "ChartOfCharacteristicTypes",   "ChartsOfCharacteristicTypes");
        add("ПланСчетов",                   "ChartOfAccounts",              "ChartsOfAccounts");
        add("ПланВидовРасчета",             "ChartOfCalculationTypes",      "ChartsOfCalculationTypes");

        add("ОбщийМодуль",                  "CommonModule",                 "CommonModules");
        add("ОбщаяФорма",                   "CommonForm",                   "CommonForms");
        add("ОбщийМакет",                   "CommonTemplate",               "CommonTemplates");
        add("ОбщаяКартинка",                "CommonPicture",                "CommonPictures");
        add("ОбщаяКоманда",                 "CommonCommand",                "CommonCommands");
        add("ОбщийАтрибут",                 "CommonAttribute",              "CommonAttributes");

        add("Константа",                    "Constant",                     "Constants");
        add("ОпределяемыйТип",              "DefinedType",                  "DefinedTypes");
        add("ПодпискаНаСобытие",            "EventSubscription",            "EventSubscriptions");
        add("РегламентноеЗадание",          "ScheduledJob",                 "ScheduledJobs");
        add("ФункциональнаяОпция",          "FunctionalOption",             "FunctionalOptions");
        add("ПараметрФункциональнойОпции",  "FunctionalOptionsParameter",   "FunctionalOptionsParameters");
        add("Роль",                         "Role",                         "Roles");
        add("Подсистема",                   "Subsystem",                    "Subsystems");
        add("ЯзыкКонфигурации",             "Language",                     "Languages");

        add("ВнешнийИсточникДанных",        "ExternalDataSource",           "ExternalDataSources");
        add("ПакетXDTO",                    "XDTOPackage",                  "XDTOPackages");
        add("WebСервис",                    "WebService",                   "WebServices");
        add("HTTPСервис",                   "HTTPService",                  "HTTPServices");
        add("IntegrationService",           "IntegrationService",           "IntegrationServices");
        add("СтильОформления",              "StyleItem",                    "StyleItems");
        add("Интерфейс",                    "Interface",                    "Interfaces");

        // ── Псевдонимы ───────────────────────────────────────────────────────
        addAlias("ОбщийМодульПовторногоИспользования", "ОбщийМодуль");

        // ── Вложенные типы без самостоятельной папки в EDT ───────────────────
        // Модули конфигурации/приложения
        add("Конфигурация",                     "Configuration",                null);
        add("МодульУправляемогоПриложения",     "ManagedApplicationModule",     null);
        add("МодульОбычногоПриложения",         "OrdinaryApplicationModule",    null);
        add("МодульВнешнегоСоединения",         "ExternalConnectionModule",     null);
        add("МодульОбъекта",                    "ObjectModule",                 null);
        add("МодульНабораЗаписей",              "RecordSetModule",              null);
        add("МодульМенеджера",                  "ManagerModule",                null);
        add("МодульМенеджераЗначения",          "ValueManagerModule",           null);
        add("МодульКоманды",                    "CommandModule",                null);
        add("МодульСеанса",                     "SessionModule",                null);
        add("Модуль",                           "Module",                       null);
        add("Форма",                            "Form",                         null);
        add("Команда",                          "Command",                      null);

        // ── Под-объекты объектов метаданных (используются в BM FQN) ─────────
        // Эти типы встречаются как сегменты BM URI вида
        // "Catalog.Валюты.Template.Классификатор" и нужны для GetRef.eObjectToFullName.
        add("Макет",               "Template",        null);
        add("ТабличнаяЧасть",      "TabularSection",  null);
        add("Реквизит",            "Attribute",       null);
        add("Измерение",           "Dimension",       null);
        add("Ресурс",              "Resource",        null);
        add("Перерасчет",          "Recalculation",   null);
        add("ПризнакУчета",        "AccountingFlag",  null);
    }

    // =========================================================================
    // Методы поиска по одному имени типа
    // =========================================================================

    /** «Справочник» → «Catalog»; {@code null} если тип не известен. */
    public static String ruToEnSing(String ru)             { return RU_TO_EN_SING.get(ru); }

    /** «Catalog» → «Справочник»; {@code null} если тип не известен. */
    public static String enSingToRu(String enSing)         { return EN_SING_TO_RU.get(enSing); }

    /** «Справочник» → «Catalogs»; {@code null} для вложенных типов (модули, формы). */
    public static String ruToFolder(String ru)             { return RU_TO_FOLDER.get(ru); }

    /** «Catalog» → «Catalogs»; {@code null} для вложенных типов. */
    public static String enSingToFolder(String enSing)     { return EN_SING_TO_FOLDER.get(enSing); }

    /** «Catalogs» → «Справочник»; {@code null} если папка не известна. */
    public static String folderToRu(String folder)         { return FOLDER_TO_RU.get(folder); }

    /** «Catalogs» → «Catalog»; {@code null} если папка не известна. */
    public static String folderToEnSing(String folder)     { return FOLDER_TO_EN_SING.get(folder); }

    // =========================================================================
    // Нормализация из любой формы в нужную
    // =========================================================================

    /**
     * Принимает тип в любой форме (RU / EN ед.ч. / EN мн.ч.) и возвращает RU ед.ч.
     * Возвращает {@code null} если тип не распознан.
     */
    public static String anyToRu(String type)
    {
        if (type == null) return null;
        if (RU_TO_EN_SING.containsKey(type)) return type;          // уже RU
        String r = EN_SING_TO_RU.get(type);  if (r != null) return r; // EN ед.ч.
        return FOLDER_TO_RU.get(type);                             // EN мн.ч.
    }

    /**
     * Принимает тип в любой форме и возвращает EN ед.ч.
     * Возвращает {@code null} если тип не распознан.
     */
    public static String anyToEnSing(String type)
    {
        if (type == null) return null;
        if (EN_SING_TO_RU.containsKey(type)) return type;          // уже EN ед.ч.
        String e = RU_TO_EN_SING.get(type);  if (e != null) return e; // RU
        return FOLDER_TO_EN_SING.get(type);                        // EN мн.ч.
    }

    /**
     * Принимает тип в любой форме и возвращает EN мн.ч. (папку EDT).
     * Возвращает {@code null} если тип не распознан или не имеет самостоятельной папки.
     */
    public static String anyToFolder(String type)
    {
        if (type == null) return null;
        if (FOLDER_TO_RU.containsKey(type)) return type;           // уже папка
        String f = RU_TO_FOLDER.get(type);   if (f != null) return f; // RU
        return EN_SING_TO_FOLDER.get(type);                        // EN ед.ч.
    }

    // =========================================================================
    // Операции с полным именем объекта  («Тип.Имя»  или «Папка/Имя»)
    // =========================================================================

    /**
     * Нормализует тип к RU ед.ч. в полном имени вида «Тип.Объект».
     *
     * <pre>
     *   "Catalog.Валюты"    → "Справочник.Валюты"
     *   "Catalogs/Валюты"   → "Справочник.Валюты"
     *   "Справочник.Валюты" → "Справочник.Валюты"  (без изменений)
     * </pre>
     *
     * @return нормализованное имя через «.», {@code null} если тип не распознан
     */
    public static String toRuFullName(String fullName)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String ru = anyToRu(p.type);
        return ru == null ? null : ru + "." + p.name; //$NON-NLS-1$
    }

    /**
     * Нормализует тип к EN ед.ч. в полном имени вида «Тип.Объект».
     *
     * <pre>
     *   "Справочник.Валюты" → "Catalog.Валюты"
     *   "Catalogs/Валюты"   → "Catalog.Валюты"
     *   "Catalog.Валюты"    → "Catalog.Валюты"      (без изменений)
     * </pre>
     *
     * @return нормализованное имя через «.», {@code null} если тип не распознан
     */
    public static String toEnSingFullName(String fullName)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String en = anyToEnSing(p.type);
        return en == null ? null : en + "." + p.name; //$NON-NLS-1$
    }

    /**
     * Преобразует полное имя объекта в путь к папке EDT (через {@code '/'}).
     *
     * <pre>
     *   "Справочник.Валюты" → "Catalogs/Валюты"
     *   "Catalog.Валюты"    → "Catalogs/Валюты"
     *   "Catalogs/Валюты"   → "Catalogs/Валюты"     (уже готово)
     * </pre>
     *
     * @return строка вида «Folder/ObjectName», {@code null} если тип без папки
     */
    public static String toFolderPath(String fullName)
    {
        return toFolderPath(fullName, '/');
    }

    /**
     * То же что {@link #toFolderPath(String)}, но с явным разделителем.
     *
     * @param separator {@code '/'} или {@code '\\'}
     */
    public static String toFolderPath(String fullName, char separator)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String folder = anyToFolder(p.type);
        return folder == null ? null : folder + separator + p.name;
    }

    /**
     * Конвертирует путь вида «Папка/Объект» в полное имя с RU типом.
     *
     * <pre>
     *   "Catalogs/Валюты"   → "Справочник.Валюты"
     *   "Catalogs\Валюты"   → "Справочник.Валюты"
     * </pre>
     */
    public static String pathToRuFullName(String path)
    {
        Parsed p = parse(path);
        if (p == null) return null;
        String ru = anyToRu(p.type);
        return ru == null ? null : ru + "." + p.name; //$NON-NLS-1$
    }

    /**
     * Конвертирует путь вида «Папка/Объект» в полное имя с EN ед.ч. типом.
     *
     * <pre>
     *   "Catalogs/Валюты"   → "Catalog.Валюты"
     * </pre>
     */
    public static String pathToEnSingFullName(String path)
    {
        Parsed p = parse(path);
        if (p == null) return null;
        String en = anyToEnSing(p.type);
        return en == null ? null : en + "." + p.name; //$NON-NLS-1$
    }

    // =========================================================================
    // BM URI FQN → полное русское имя (для GetRef.eObjectToFullName)
    // =========================================================================

    /**
     * Конвертирует FQN из BM URI (или от {@code IQualifiedNameProvider}) в полное русское имя.
     *
     * <p>BM URI в EDT имеет вид {@code bm://Конфигурация/Catalog.Валюты}, где часть пути
     * ({@code uri.path().substring(1)}) — это FQN объекта в EN-форме.
     * Аналогично {@code IQualifiedNameProvider} возвращает FQN вида
     * {@code "Catalog.Валюты.Template.Классификатор"}.
     *
     * <p>Алгоритм: разбивает FQN по «.», каждую пару сегментов (ТипEN, Имя) конвертирует
     * через {@link #anyToRu(String)}.
     *
     * <pre>
     *   "Catalog.Валюты"                         → "Справочник.Валюты"
     *   "Catalog.Валюты.Template.Классификатор"  → "Справочник.Валюты.Макет.Классификатор"
     *   "CommonModule.МойМодуль"                 → "ОбщийМодуль.МойМодуль"
     * </pre>
     *
     * @param fqn FQN в EN-форме; может быть {@code null}
     * @return полное русское имя, или {@code null} если первый тип не распознан
     */
    public static String bmFqnToRuFullName(String fqn)
    {
        if (fqn == null || fqn.isBlank()) return null;

        String[] parts = fqn.split("\\.", -1); //$NON-NLS-1$
        if (parts.length < 2) return null;

        // Первая пара: тип и имя объекта верхнего уровня
        String typeRu = anyToRu(parts[0]);
        if (typeRu == null) return null;

        StringBuilder sb = new StringBuilder(typeRu).append('.').append(parts[1]);

        // Дополнительные пары: тип и имя под-объекта (макет, форма, команда …)
        for (int i = 2; i + 1 < parts.length; i += 2)
        {
            String subTypeRu = anyToRu(parts[i]);
            if (subTypeRu != null)
                sb.append('.').append(subTypeRu).append('.').append(parts[i + 1]);
        }

        return sb.toString();
    }

    // =========================================================================
    // Совместимость: замена TYPE_TO_FOLDER в GoToDefinition
    // =========================================================================

    /**
     * Возвращает неизменяемое представление карты «RU ед.ч. → папка EDT».
     * Используется в GoToDefinition для инициализации TYPE_TO_FOLDER:
     * <pre>
     *   static final Map&lt;String, String&gt; TYPE_TO_FOLDER = MdTypeMapping.getRuToFolderMap();
     * </pre>
     */
    public static Map<String, String> getRuToFolderMap()
    {
        return Collections.unmodifiableMap(RU_TO_FOLDER);
    }

    // =========================================================================
    // Регистрация
    // =========================================================================

    /**
     * Регистрирует запись типа метаданных во все шесть маппингов.
     *
     * @param ru     русское ед.ч.: «Справочник»
     * @param enSing английское ед.ч.: «Catalog»
     * @param enPlur папка EDT (мн.ч.): «Catalogs»; {@code null} для вложенных типов
     */
    private static void add(String ru, String enSing, String enPlur)
    {
        RU_TO_EN_SING.put(ru, enSing);
        EN_SING_TO_RU.put(enSing, ru);
        if (enPlur != null)
        {
            RU_TO_FOLDER.put(ru, enPlur);
            EN_SING_TO_FOLDER.put(enSing, enPlur);
            FOLDER_TO_RU.put(enPlur, ru);
            FOLDER_TO_EN_SING.put(enPlur, enSing);
        }
    }

    /**
     * Регистрирует альтернативное RU-имя для уже существующего типа.
     * Псевдоним участвует в RU → EN1 и RU → папка, но не перезаписывает
     * обратные маппинги (EN → RU, папка → RU).
     */
    private static void addAlias(String ruAlias, String ruCanonical)
    {
        String enSing = RU_TO_EN_SING.get(ruCanonical);
        String folder = RU_TO_FOLDER.get(ruCanonical);
        if (enSing != null) RU_TO_EN_SING.put(ruAlias, enSing);
        if (folder != null) RU_TO_FOLDER.put(ruAlias, folder);
    }

    // =========================================================================
    // Вспомогательный разбор строки «Тип.Имя» или «Папка/Имя»
    // =========================================================================

    private static final class Parsed
    {
        final String type;
        final String name;
        Parsed(String type, String name) { this.type = type; this.name = name; }
    }

    private static Parsed parse(String s)
    {
        if (s == null || s.isBlank()) return null;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '.' || c == '/' || c == '\\') { best = i; break; }
        }
        if (best == Integer.MAX_VALUE) return null;
        String type = s.substring(0, best);
        String name = s.substring(best + 1);
        if (type.isBlank() || name.isBlank()) return null;
        return new Parsed(type, name);
    }
}

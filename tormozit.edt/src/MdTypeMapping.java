
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единственный источник правды для маппинга имён типов метаданных 1С
 * во всех трёх вариантах написания.
 *
 * <h3>Три формы одного типа</h3>
 * <pre>
 *   RU  (ед.ч. рус.)  — «Справочник»
 *   EN1 (ед.ч. англ.) — «Catalog»
 *   EN+ (мн.ч. англ.) — «Catalogs»  (имя папки в EDT-проекте; null для вложенных типов)
 * </pre>
 *
 * <h3>Поддерживаемые направления</h3>
 * <pre>
 *   RU  → EN1   ruToEnSing("Справочник")          = "Catalog"
 *   EN1 → RU    enSingToRu("Catalog")             = "Справочник"
 *   RU  → EN+   ruToFolder("Справочник")          = "Catalogs"
 *   EN1 → EN+   enSingToFolder("Catalog")         = "Catalogs"
 *   EN+ → RU    folderToRu("Catalogs")            = "Справочник"
 *   EN+ → EN1   folderToEnSing("Catalogs")        = "Catalog"
 * </pre>
 *
 * <h3>Операции с полным именем объекта («Тип.Имя»)</h3>
 * <pre>
 *   toRuFullName ("Catalog.Валюты")    = "Справочник.Валюты"
 *   toEnSingFullName("Справочник.Валюты") = "Catalog.Валюты"
 *   toFolderPath("Справочник.Валюты")  = "Catalogs/Валюты"
 *   toFolderPath("Catalog.Валюты")     = "Catalogs/Валюты"
 *   pathToRuFullName("Catalogs/Валюты")    = "Справочник.Валюты"
 *   pathToEnSingFullName("Catalogs/Валюты") = "Catalog.Валюты"
 * </pre>
 *
 * <h3>Использование в GoToDefinition</h3>
 * <pre>
 *   // Старый код (тип RU → папка EDT):
 *   TYPE_TO_FOLDER.get(typeRu)
 *   // Новый код:
 *   MdTypeMapping.ruToFolder(typeRu)
 *   // или через совместимый мап:
 *   MdTypeMapping.RU_TO_FOLDER.get(typeRu)
 * </pre>
 */
public final class MdTypeMapping
{
    private MdTypeMapping() {}

    // =========================================================================
    // Шесть производных маппингов (все заполняются из единой таблицы add())
    // =========================================================================

    /** RU ед.ч. → EN ед.ч.  «Справочник» → «Catalog» */
    public static final Map<String, String> RU_TO_EN_SING        = new LinkedHashMap<>();

    /** EN ед.ч. → RU ед.ч.  «Catalog» → «Справочник» */
    public static final Map<String, String> EN_SING_TO_RU        = new LinkedHashMap<>();

    /** RU ед.ч. → EN мн.ч. (папка EDT)  «Справочник» → «Catalogs»  (совместимость с TYPE_TO_FOLDER) */
    public static final Map<String, String> RU_TO_FOLDER         = new LinkedHashMap<>();

    /** EN ед.ч. → EN мн.ч. (папка EDT)  «Catalog» → «Catalogs» */
    public static final Map<String, String> EN_SING_TO_FOLDER    = new LinkedHashMap<>();

    /** EN мн.ч. (папка) → RU ед.ч.  «Catalogs» → «Справочник» */
    public static final Map<String, String> FOLDER_TO_RU         = new LinkedHashMap<>();

    /** EN мн.ч. (папка) → EN ед.ч.  «Catalogs» → «Catalog» */
    public static final Map<String, String> FOLDER_TO_EN_SING    = new LinkedHashMap<>();

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

        // ── Вложенные типы без папки в EDT (модули, формы, команды) ─────────
        // null в позиции папки означает «не является самостоятельным объектом МД»
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

        // Делаем все публичные мапы неизменяемыми
        seal();
    }

    // =========================================================================
    // Методы поиска по одному имени типа
    // =========================================================================

    /** «Справочник» → «Catalog»; {@code null} если тип не известен. */
    public static String ruToEnSing(String ru)
    {
        return RU_TO_EN_SING.get(ru);
    }

    /** «Catalog» → «Справочник»; {@code null} если тип не известен. */
    public static String enSingToRu(String enSing)
    {
        return EN_SING_TO_RU.get(enSing);
    }

    /**
     * «Справочник» → «Catalogs» (имя папки EDT).
     * Возвращает {@code null} для вложенных типов (модули, формы).
     */
    public static String ruToFolder(String ru)
    {
        return RU_TO_FOLDER.get(ru);
    }

    /**
     * «Catalog» → «Catalogs» (имя папки EDT).
     * Возвращает {@code null} для вложенных типов.
     */
    public static String enSingToFolder(String enSing)
    {
        return EN_SING_TO_FOLDER.get(enSing);
    }

    /** «Catalogs» → «Справочник»; {@code null} если папка не известна. */
    public static String folderToRu(String folder)
    {
        return FOLDER_TO_RU.get(folder);
    }

    /** «Catalogs» → «Catalog»; {@code null} если папка не известна. */
    public static String folderToEnSing(String folder)
    {
        return FOLDER_TO_EN_SING.get(folder);
    }

    // =========================================================================
    // Операции с полным именем объекта («Тип.Имя»)
    // =========================================================================

    /**
     * Приводит полное имя к русскому варианту типа.
     *
     * <pre>
     *   "Catalog.Валюты"      → "Справочник.Валюты"
     *   "Справочник.Валюты"   → "Справочник.Валюты"  (без изменений)
     *   "Catalogs/Валюты"     → "Справочник.Валюты"  (папка → ед.ч.)
     * </pre>
     *
     * @return нормализованное имя, или {@code null} если тип не распознан
     */
    public static String toRuFullName(String fullName)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String ru = anyToRu(p.type);
        return ru == null ? null : ru + "." + p.objectName;
    }

    /**
     * Приводит полное имя к английскому ед.ч. варианту типа.
     *
     * <pre>
     *   "Справочник.Валюты"   → "Catalog.Валюты"
     *   "Catalog.Валюты"      → "Catalog.Валюты"     (без изменений)
     *   "Catalogs/Валюты"     → "Catalog.Валюты"     (папка → ед.ч.)
     * </pre>
     *
     * @return нормализованное имя, или {@code null} если тип не распознан
     */
    public static String toEnSingFullName(String fullName)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String en = anyToEnSing(p.type);
        return en == null ? null : en + "." + p.objectName;
    }

    /**
     * Преобразует полное имя объекта в путь к папке EDT (через {@code /}).
     *
     * <pre>
     *   "Справочник.Валюты"   → "Catalogs/Валюты"
     *   "Catalog.Валюты"      → "Catalogs/Валюты"
     *   "Catalogs/Валюты"     → "Catalogs/Валюты"   (уже готово)
     * </pre>
     *
     * @return строка вида «Folder/ObjectName», или {@code null} если тип без папки
     */
    public static String toFolderPath(String fullName)
    {
        return toFolderPath(fullName, '/');
    }

    /**
     * То же что {@link #toFolderPath(String)}, но с явным разделителем.
     *
     * @param separator символ-разделитель ({@code '/'} или {@code '\\'})
     */
    public static String toFolderPath(String fullName, char separator)
    {
        Parsed p = parse(fullName);
        if (p == null) return null;
        String folder = anyToFolder(p.type);
        return folder == null ? null : folder + separator + p.objectName;
    }

    /**
     * Конвертирует путь к папке EDT в полное имя с русским типом.
     *
     * <pre>
     *   "Catalogs/Валюты"     → "Справочник.Валюты"
     *   "Catalogs\Валюты"     → "Справочник.Валюты"
     * </pre>
     *
     * @return полное имя, или {@code null} если папка не распознана
     */
    public static String pathToRuFullName(String path)
    {
        Parsed p = parse(path);
        if (p == null) return null;
        String ru = anyToRu(p.type);
        return ru == null ? null : ru + "." + p.objectName;
    }

    /**
     * Конвертирует путь к папке EDT в полное имя с английским ед.ч. типом.
     *
     * <pre>
     *   "Catalogs/Валюты"     → "Catalog.Валюты"
     * </pre>
     *
     * @return полное имя, или {@code null} если папка не распознана
     */
    public static String pathToEnSingFullName(String path)
    {
        Parsed p = parse(path);
        if (p == null) return null;
        String en = anyToEnSing(p.type);
        return en == null ? null : en + "." + p.objectName;
    }

    // =========================================================================
    // Нормализация типа из любой формы
    // =========================================================================

    /**
     * Приводит тип в любой форме (RU / EN ед.ч. / EN мн.ч.) к RU ед.ч.
     * Возвращает {@code null} если тип не распознан.
     */
    public static String anyToRu(String type)
    {
        if (type == null) return null;
        // 1. Уже RU
        if (RU_TO_EN_SING.containsKey(type)) return type;
        // 2. EN ед.ч. → RU
        String ru = EN_SING_TO_RU.get(type);
        if (ru != null) return ru;
        // 3. EN мн.ч. (папка) → RU
        return FOLDER_TO_RU.get(type);
    }

    /**
     * Приводит тип в любой форме (RU / EN ед.ч. / EN мн.ч.) к EN ед.ч.
     * Возвращает {@code null} если тип не распознан.
     */
    public static String anyToEnSing(String type)
    {
        if (type == null) return null;
        // 1. Уже EN ед.ч.
        if (EN_SING_TO_RU.containsKey(type)) return type;
        // 2. RU → EN ед.ч.
        String en = RU_TO_EN_SING.get(type);
        if (en != null) return en;
        // 3. EN мн.ч. (папка) → EN ед.ч.
        return FOLDER_TO_EN_SING.get(type);
    }

    /**
     * Приводит тип в любой форме к EN мн.ч. (имя папки EDT).
     * Возвращает {@code null} если тип не распознан или не имеет папки.
     */
    public static String anyToFolder(String type)
    {
        if (type == null) return null;
        // 1. Уже папка
        if (FOLDER_TO_RU.containsKey(type)) return type;
        // 2. RU → папка
        String folder = RU_TO_FOLDER.get(type);
        if (folder != null) return folder;
        // 3. EN ед.ч. → папка
        return EN_SING_TO_FOLDER.get(type);
    }

    // =========================================================================
    // Вспомогательный разбор
    // =========================================================================

    /** Пара «тип / имя объекта», полученная при разборе строки вида «Тип.Имя» или «Папка/Имя». */
    private static final class Parsed
    {
        final String type;       // первая часть («Справочник», «Catalog», «Catalogs»)
        final String objectName; // вторая часть («Валюты»); может содержать вложенные «.»/«/»
        Parsed(String type, String objectName) { this.type = type; this.objectName = objectName; }
    }

    /**
     * Разбирает строки вида:
     * <ul>
     *   <li>{@code "Тип.ИмяОбъекта"} — через точку</li>
     *   <li>{@code "Folder/ИмяОбъекта"} — через слэш (прямой или обратный)</li>
     * </ul>
     * Возвращает {@code null} если разделитель не найден.
     */
    private static Parsed parse(String fullName)
    {
        if (fullName == null || fullName.isBlank()) return null;
        // Ищем первый разделитель: '.' '/' или '\\'
        int dot   = fullName.indexOf('.');
        int slash = fullName.indexOf('/');
        int bslash = fullName.indexOf('\\');

        int sep = -1;
        if (dot   >= 0) sep = dot;
        if (slash >= 0 && (sep < 0 || slash < sep)) sep = slash;
        if (bslash >= 0 && (sep < 0 || bslash < sep)) sep = bslash;

        if (sep < 0) return null;

        String type = fullName.substring(0, sep);
        String rest = fullName.substring(sep + 1);
        if (type.isBlank() || rest.isBlank()) return null;
        return new Parsed(type, rest);
    }

    // =========================================================================
    // Регистрация и заморозка
    // =========================================================================

    /**
     * Регистрирует одну запись типа метаданных.
     *
     * @param ru     русское ед.ч.: «Справочник»
     * @param enSing английское ед.ч.: «Catalog»
     * @param enPlur английское мн.ч. (папка EDT): «Catalogs»; {@code null} для вложенных типов
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void seal()
    {
        // Заменяем mutable карты unmodifiable-обёртками через рефлексию,
        // чтобы не дублировать содержимое и сохранить ссылки в публичных полях.
        try
        {
            for (java.lang.reflect.Field f : MdTypeMapping.class.getDeclaredFields())
            {
                if (f.getType() == Map.class)
                {
                    f.setAccessible(true);
                    f.set(null, Collections.unmodifiableMap((Map) f.get(null)));
                }
            }
        }
        catch (Exception e)
        {
            // В крайнем случае — поля останутся mutable, это не критично
            Global.log("MdTypeMapping: не удалось сделать мапы unmodifiable: " + e); //$NON-NLS-1$
        }
    }
}

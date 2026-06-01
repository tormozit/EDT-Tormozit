package tormozit;

import java.util.ArrayList;
import java.util.List;

/**
 * Адаптивная версия {@link SmartMatcher} для списков автодополнения кода
 * (BSL-редактор и аналогичные), где пробел является концом ввода, а слова
 * в идентификаторах разделены только CamelCase, подчёркиванием, точкой
 * или цифрами.
 *
 * <p>Не разбивает фильтр по пробелам при создании; вместо этого для каждого
 * элемента списка «на лету» подбирает оптимальное разбиение фильтра на слова
 * с учётом границ слов кандидата.
 *
 * <p>API полностью совместим с {@link SmartMatcher} — можно подменить
 * {@code new SmartMatcher(filter)} на {@code new SmartCodeMatcher(filter)}
 * в вызове {@link SmartCompletionSorter}.
 */
public class SmartCodeMatcher extends SmartMatcher {

    public SmartCodeMatcher(String filterPattern) {
        super(filterPattern);
    }

    @Override
    public int computeNamePremium(String text) {
        return computeAdaptivePremium(splitNameAndParams(text)[0]);
    }

    @Override
    public int computeParamPremium(String text) {
        return computeAdaptivePremium(splitNameAndParams(text)[1]);
    }

    // -----------------------------------------------------------------
    //  Адаптивное разбиение
    // -----------------------------------------------------------------

    /** Разделяет сигнатуру на [0] Имя и [1] Параметры (копия из SmartMatcher) */
    private String[] splitNameAndParams(String text) {
        if (text == null) return new String[]{"", ""};
        int parenIdx = text.indexOf('(');
        if (parenIdx >= 0) {
            return new String[] {
                text.substring(0, parenIdx).trim(),
                text.substring(parenIdx).trim()
            };
        } else {
            return new String[] { text.trim(), "" };
        }
    }

    /**
     * Адаптивный расчёт премии: разбивает кандидата на слова и жадно
     * подбирает, как «разрезать» фильтр по началам этих слов.
     */
    private int computeAdaptivePremium(String partText) {
        if (isEmpty || partText == null || partText.isEmpty()) {
            return 0;
        }

        String lower = partText.toLowerCase();

        /* 1. Быстрый путь — фильтр идёт подряд в тексте */
        int idx = lower.indexOf(fullPattern);
        if (idx >= 0) {
            if (idx == 0) return 40;                       // начало строки
            if (isWordBoundary(partText, idx)) return 30;    // начало слова
            boolean crosses = false;
            for (int i = idx + 1; i < idx + fullPattern.length(); i++) {
                if (isWordBoundary(partText, i)) { crosses = true; break; }
            }
            return crosses ? 5 : 15;                         // внутри / через границу
        }

        /* 2. Разбиваем кандидата на слова */
        List<String> words = splitWords(partText);
        if (words.isEmpty()) return 0;

        /* 3. Жадно подбираем разбивку фильтра по началам слов */
        String remaining = fullPattern;
        int matchedWords = 0;
        int first = -1, last = -1;
        int w = 0;

        while (!remaining.isEmpty() && w < words.size()) {
            String word = words.get(w).toLowerCase();

            // Сколько символов совпадает с начала слова?
            int common = 0;
            int max = Math.min(remaining.length(), word.length());
            while (common < max && remaining.charAt(common) == word.charAt(common)) {
                common++;
            }

            if (common > 0) {
                if (first == -1) first = w;
                last = w;
                matchedWords++;
                remaining = remaining.substring(common);
                w++;                       // переходим к следующему слову (жадно)
            } else {
                w++;                       // слово не подошло — пропускаем
            }
        }

        /* 4. Резерв: если осталась «хвостовая» часть — ищем её внутри оставшихся слов */
        if (!remaining.isEmpty()) {
            int start = (last == -1) ? 0 : last + 1;
            for (int i = start; i < words.size(); i++) {
                if (words.get(i).toLowerCase().contains(remaining)) {
                    if (first == -1) first = i;
                    last = i;
                    matchedWords++;
                    remaining = "";
                    break;
                }
            }
        }

        if (!remaining.isEmpty()) return 0; // фильтр не покрыт — элемент не релевантен

        /* 5. Расчёт премии */
        int gap = last - first + 1;          // сколько слов охвачено с first по last
        boolean consecutive = (gap == matchedWords); // без дыр?

        if (first == 0) {
            return consecutive ? 35 : 20;    // совпадение с начала имени
        } else {
            return consecutive ? 18 : 8;     // совпадение начинается не с первого слова
        }
    }

    // -----------------------------------------------------------------
    //  Разбиение на слова (CamelCase + underscore + дефис + точка + цифры)
    // -----------------------------------------------------------------

    private List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null || text.isEmpty()) return words;

        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || isDelimiter(text.charAt(i)) || isWordBoundary(text, i)) {
                if (i > start) words.add(text.substring(start, i));
                // пропускаем сам разделитель
                start = i + (i < text.length() && isDelimiter(text.charAt(i)) ? 1 : 0);
            }
        }
        return words;
    }

    private boolean isDelimiter(char c) {
        return c == '_' || c == ' ' || c == '-' || c == '.';
    }

    /**
     * Граница слова для CamelCase и цифр:
     *  - строчная → заглавная  (НовоеСлово)
     *  - цифра ↔ буква
     *  - последовательность заглавных перед строчной (XMLReader → XML | Reader)
     */
    private boolean isWordBoundary(String text, int i) {
        if (i <= 0 || i >= text.length()) return true;
        char prev = text.charAt(i - 1);
        char cur = text.charAt(i);

        if (Character.isLowerCase(prev) && Character.isUpperCase(cur)) return true;
        if (Character.isDigit(prev) != Character.isDigit(cur)) return true;

        if (Character.isUpperCase(prev) && Character.isUpperCase(cur)) {
            if (i + 1 < text.length() && Character.isLowerCase(text.charAt(i + 1))) {
                return true; // перед 'R' в XMLReader
            }
        }
        return false;
    }
}
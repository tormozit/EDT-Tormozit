package tormozit;

import java.util.ArrayList;
import java.util.List;

public class SmartCodeMatcher extends SmartMatcher {

    public SmartCodeMatcher(String filterPattern) {
        super(filterPattern);
    }

    @Override
    public int computeNamePremium(String text) {
        return computeAdaptivePremium(extractName(text));
    }

    @Override
    public int computeParamPremium(String text) {
        return computeAdaptivePremium(extractParams(text));
    }

    /** Извлекает имя метода/идентификатора, отсекая параметры и тип */
    private String extractName(String text) {
        if (text == null) return "";
        // Отрезаем параметры
        int parenIdx = text.indexOf('(');
        String withoutParams = (parenIdx >= 0) ? text.substring(0, parenIdx).trim() : text.trim();
        // Отрезаем тип после двоеточия
        int colonIdx = withoutParams.indexOf(':');
        return (colonIdx >= 0) ? withoutParams.substring(0, colonIdx).trim() : withoutParams;
    }

    private String extractParams(String text) {
        if (text == null) return "";
        int parenIdx = text.indexOf('(');
        return (parenIdx >= 0) ? text.substring(parenIdx).trim() : "";
    }

    private int computeAdaptivePremium(String partText) {
        if (isEmpty || partText == null || partText.isEmpty()) {
            return 0;
        }
        String lower = partText.toLowerCase();
        int idx = lower.indexOf(fullPattern);
        if (idx >= 0) {
            if (idx == 0) return 40;
            if (isWordBoundary(partText, idx)) return 30;
            boolean crosses = false;
            for (int i = idx + 1; i < idx + fullPattern.length(); i++) {
                if (isWordBoundary(partText, i)) { crosses = true; break; }
            }
            return crosses ? 5 : 15;
        }
        List<String> words = splitWords(partText);
        if (words.isEmpty()) return 0;
        String remaining = fullPattern;
        int matchedWords = 0;
        int first = -1, last = -1;
        int w = 0;
        while (!remaining.isEmpty() && w < words.size()) {
            String word = words.get(w).toLowerCase();
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
                w++;
            } else {
                w++;
            }
        }
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
        if (!remaining.isEmpty()) return 0;
        int gap = last - first + 1;
        boolean consecutive = (gap == matchedWords);
        if (first == 0) {
            return consecutive ? 35 : 20;
        } else {
            return consecutive ? 18 : 8;
        }
    }

    private List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        if (text == null || text.isEmpty()) return words;
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || isDelimiter(text.charAt(i)) || isWordBoundary(text, i)) {
                if (i > start) words.add(text.substring(start, i));
                start = i + (i < text.length() && isDelimiter(text.charAt(i)) ? 1 : 0);
            }
        }
        return words;
    }

    private boolean isDelimiter(char c) {
        return c == '_' || c == ' ' || c == '-' || c == '.';
    }

    @Override
    public List<HighlightRange> getHighlightRanges(String text) {
        List<HighlightRange> ranges = new ArrayList<>();
        if (isEmpty || text == null) return ranges;
        // Подсвечиваем только в имени, не в параметрах и не в типе
        String namePart = extractName(text);
        String lowerName = namePart.toLowerCase();
        String lowerFull = fullPattern.toLowerCase();

        // Сначала пробуем полное совпадение
        int fullIdx = lowerName.indexOf(lowerFull);
        if (fullIdx >= 0) {
            ranges.add(new HighlightRange(fullIdx, lowerFull.length()));
            return ranges;
        }

        // Адаптивное разбиение по словам
        List<String> words = splitWords(namePart);
        String remaining = lowerFull;
        int pos = 0;
        for (String word : words) {
            if (remaining.isEmpty()) break;
            String wordLower = word.toLowerCase();
            int common = 0;
            int max = Math.min(remaining.length(), word.length());
            while (common < max && remaining.charAt(common) == wordLower.charAt(common)) {
                common++;
            }
            if (common > 0) {
                ranges.add(new HighlightRange(pos, common));
                remaining = remaining.substring(common);
            }
            pos += word.length();
            if (pos < namePart.length() && isDelimiter(namePart.charAt(pos))) {
                pos++; // пропускаем разделитель
            }
        }
        // Хвост
        if (!remaining.isEmpty()) {
            int tailIdx = lowerName.indexOf(remaining);
            if (tailIdx >= 0) {
                ranges.add(new HighlightRange(tailIdx, remaining.length()));
            }
        }
        return ranges;
    }
}
package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.contentassist.IContextInformationValidator;

/**
 * Обёртка над оригинальным {@link IContentAssistProcessor}.
 *
 * <p><b>Кэширование:</b> при первом вызове {@code computeCompletionProposals}
 * запрашивает полный список у delegate и сохраняет его. При последующих
 * вызовах, если фильтр изменился на ±1 символ (ввод/удаление), берёт кэш,
 * фильтрует и сортирует — <b>delegate не вызывается</b>.
 */
public class SmartContentAssistProcessor implements IContentAssistProcessor
{
    private static final int NAME_WEIGHT = 10;
    private static final int PARAM_WEIGHT = 1;

    private final IContentAssistProcessor delegate;
    private final String activationChars;

    // Кэш полного списка
    private ICompletionProposal[] cachedProposals;
    private String cachedFilter = "";
    private boolean cacheValid = false;

    public SmartContentAssistProcessor(IContentAssistProcessor delegate, String activationChars)
    {
        this.delegate = delegate;
        this.activationChars = activationChars;
    }

    public IContentAssistProcessor getDelegate()
    {
        return delegate;
    }

    /** Сбрасывает кэш полного списка proposals. */
    public void invalidateCache()
    {
        cacheValid = false;
        cachedProposals = null;
        cachedFilter = "";
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset)
    {
        String filter = computeFilter(viewer, offset);

        // Быстрый путь: контекст тот же, только фильтр изменился
        if (cacheValid && cachedProposals != null && isIncrementalChange(filter))
        {
            cachedFilter = filter;
            return filterAndSort(cachedProposals, filter);
        }

        // Медленный путь: перезапрашиваем полный список у delegate
        ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, offset);
        if (raw == null || raw.length == 0)
        {
            cacheValid = false;
            return raw;
        }

        cachedProposals = raw;
        cachedFilter = filter;
        cacheValid = true;
        return filterAndSort(raw, filter);
    }

    // -------------------------------------------------------------------------
    // Проверка контекста: фильтр изменился на ±1 символ (ввод/удаление)
    // -------------------------------------------------------------------------

    private boolean isIncrementalChange(String newFilter)
    {
        if (newFilter.equals(cachedFilter))
            return true; // фильтр не изменился (стрелки и т.п.)

        int diff = newFilter.length() - cachedFilter.length();
        if (diff == 1 && newFilter.startsWith(cachedFilter))
            return true; // добавлен 1 символ

        if (diff == -1 && cachedFilter.startsWith(newFilter))
            return true; // удалён 1 символ (Backspace)

        return false;
    }

    // -------------------------------------------------------------------------
    // Фильтрация + сортировка
    // -------------------------------------------------------------------------

    private ICompletionProposal[] filterAndSort(ICompletionProposal[] raw, String filter)
    {
        if (filter.isEmpty())
        {
            // При пустом фильтре возвращаем полный список (delegate уже отсортировал)
            return raw;
        }

        SmartCodeMatcher matcher = new SmartCodeMatcher(filter);

        // Фильтрация: оставляем только score > 0
        List<ICompletionProposal> filtered = new ArrayList<>(raw.length);
        int[] scores = new int[raw.length];

        for (int i = 0; i < raw.length; i++)
        {
            int score = computeScore(matcher, raw[i]);
            if (score > 0)
            {
                scores[filtered.size()] = score;
                filtered.add(raw[i]);
            }
        }

        if (filtered.isEmpty())
            return new ICompletionProposal[0];

        // Сортировка по убыванию score, затем алфавит
        Integer[] idx = new Integer[filtered.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;

        final int[] s = Arrays.copyOf(scores, filtered.size());

        Arrays.sort(idx, (a, b) -> {
            if (s[a] != s[b])
                return Integer.compare(s[b], s[a]);
            return compareDisplayStrings(filtered.get(a), filtered.get(b));
        });

        // Оборачиваем в SmartCompletionProposal с подсветкой
        ICompletionProposal[] result = new ICompletionProposal[idx.length];
        for (int i = 0; i < idx.length; i++)
        {
            ICompletionProposal original = filtered.get(idx[i]);
            result[i] = new SmartCompletionProposal(original, matcher);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Утилиты (доступны извне)
    // -------------------------------------------------------------------------

    /** Совокупная премия предложения при заданном matcher-е. */
    static int computeScore(SmartCodeMatcher matcher, ICompletionProposal proposal)
    {
        String display = displayString(proposal);
        if (display == null || display.isEmpty()) return 0;
        return matcher.computeNamePremium(display) * NAME_WEIGHT
             + matcher.computeParamPremium(display) * PARAM_WEIGHT;
    }

    /**
     * Полное сравнение двух предложений: сначала по score (убывание),
     * потом алфавитно. Используется из {@link SmartCodeProposalSorter}.
     */
    public static int compareProposals(SmartCodeMatcher matcher,
                                       ICompletionProposal p1,
                                       ICompletionProposal p2)
    {
        int s1 = computeScore(matcher, p1);
        int s2 = computeScore(matcher, p2);
        if (s1 != s2)
            return Integer.compare(s2, s1);
        return compareDisplayStrings(p1, p2);
    }

    private static int compareDisplayStrings(ICompletionProposal p1, ICompletionProposal p2)
    {
        String d1 = displayString(p1);
        String d2 = displayString(p2);
        if (d1 == null) return d2 == null ? 0 : 1;
        if (d2 == null) return -1;
        return d1.compareToIgnoreCase(d2);
    }

    static String displayString(ICompletionProposal p)
    {
        return (p == null) ? null : p.getDisplayString();
    }

    // -------------------------------------------------------------------------
    // Вычисление фильтра
    // -------------------------------------------------------------------------

    /**
     * Вычисляет фильтр: идём назад от курсора до первого символа,
     * не являющегося частью BSL-идентификатора.
     */
    private String computeFilter(ITextViewer viewer, int offset)
    {
        try
        {
            IDocument doc = viewer.getDocument();
            if (doc == null || offset <= 0) return "";
            int start = offset;
            while (start > 0 && isFilterChar(doc.getChar(start - 1)))
                start--;
            return (start < offset) ? doc.get(start, offset - start) : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * Символы, допустимые внутри BSL-идентификатора.
     * Точка исключена намеренно: после точки начинается новый контекст
     * (Объект.Метод), фильтр берётся только от последнего сегмента.
     */
    private boolean isFilterChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---- Делегирование остальных методов ------------------------------------

    @Override
    public IContextInformation[] computeContextInformation(ITextViewer viewer, int offset)
    {
        return delegate.computeContextInformation(viewer, offset);
    }

    @Override
    public char[] getCompletionProposalAutoActivationCharacters()
    {
        if (activationChars != null) return activationChars.toCharArray();
        return delegate.getCompletionProposalAutoActivationCharacters();
    }

    @Override
    public char[] getContextInformationAutoActivationCharacters()
    {
        return delegate.getContextInformationAutoActivationCharacters();
    }

    @Override
    public String getErrorMessage()
    {
        return delegate.getErrorMessage();
    }

    @Override
    public IContextInformationValidator getContextInformationValidator()
    {
        return delegate.getContextInformationValidator();
    }
}
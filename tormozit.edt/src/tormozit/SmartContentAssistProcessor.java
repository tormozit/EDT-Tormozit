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
 * Обёртка над оригинальным {@link IContentAssistProcessor} BSL-редактора.
 *
 * <p>Выполняет две задачи:
 * <ol>
 *   <li><b>Фильтрация</b> — элементы с премией 0 исключаются из списка.</li>
 *   <li><b>Сортировка</b> — список упорядочивается по убыванию премии
 *       {@link SmartCodeMatcher}.</li>
 * </ol>
 *
 * <p>Встраивается через {@link ContentAssistPatcher#applyPatch} (идемпотентно).
 * Встроенный sorter {@code ContentAssistant} нейтрализован через
 * {@link ContentAssistPatcher.NeutralSorter} — наш порядок сохраняется.
 */
public class SmartContentAssistProcessor implements IContentAssistProcessor
{
    /** Вес совпадения в имени метода/идентификатора */
    private static final int NAME_WEIGHT  = 10;
    /** Вес совпадения в параметрах */
    private static final int PARAM_WEIGHT = 1;

    private final IContentAssistProcessor delegate;
    private final String activationChars;

    public SmartContentAssistProcessor(IContentAssistProcessor delegate, String activationChars)
    {
        this.delegate = delegate;
        this.activationChars = activationChars;
    }

    public IContentAssistProcessor getDelegate()
    {
        return delegate;
    }

    // -------------------------------------------------------------------------

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset)
    {
        ICompletionProposal[] raw = delegate.computeCompletionProposals(viewer, offset);
        if (raw == null || raw.length == 0)
            return raw;

        String filter = computeFilter(viewer, offset);
        SmartCodeMatcher matcher = new SmartCodeMatcher(filter);

        // Если фильтр пуст — возвращаем исходный порядок Xtext без изменений.
        if (matcher.isEmpty)
            return raw;

        // --- Фильтрация: оставляем только элементы с премией > 0 ---
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

        // --- Сортировка: по убыванию премии, затем алфавит ---
        // Индексный массив — score уже вычислен, не пересчитываем при сравнении
        Integer[] idx = new Integer[filtered.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;

        final int[] s = Arrays.copyOf(scores, filtered.size());
        final List<ICompletionProposal> fp = filtered;

        Arrays.sort(idx, (a, b) -> {
            if (s[a] != s[b])
                return Integer.compare(s[b], s[a]); // убывание
            return compareDisplayStrings(fp.get(a), fp.get(b));
        });

        ICompletionProposal[] result = new ICompletionProposal[idx.length];
        for (int i = 0; i < idx.length; i++)
            result[i] = filtered.get(idx[i]);

        return result;
    }

    // -------------------------------------------------------------------------
    // Утилиты — доступны из SmartCompletionSorter
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
     * потом алфавитно. Используется из {@link SmartCompletionSorter}.
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

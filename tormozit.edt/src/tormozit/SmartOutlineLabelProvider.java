package tormozit;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Display;

public class SmartOutlineLabelProvider extends LabelProvider implements IStyledLabelProvider {
    
    private final IStyledLabelProvider baseProvider;
    private SmartMatcher matcher;
    
    // Кэш для системного шрифта (защита от утечек дескрипторов GDI/ОС)
    private Font boldFont;
    private final Styler matchStyler;
    
    public SmartOutlineLabelProvider(IStyledLabelProvider baseProvider) {
        this.baseProvider = baseProvider;
        this.matcher = new SmartMatcher("");
        
        // Инициализируем стайлер внутри конструктора
        this.matchStyler = new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle) {
                // Создаем жирный шрифт один раз при первой отрисовке
                if (boldFont == null || boldFont.isDisposed()) {
                    Font defaultFont = JFaceResources.getDefaultFont();
                    FontData[] fontData = defaultFont.getFontData();
                    for (FontData fd : fontData) {
                        fd.setStyle(fd.getStyle() | SWT.BOLD); // Накатываем флаг жирности
                    }
                    boldFont = new Font(Display.getDefault(), fontData);
                }
                
                // Назначаем шрифт в TextStyle (теперь без ошибок компиляции)
                textStyle.font = boldFont;
                
                // Цвет букв совпадения (темно-красный/кирпичный отлично виден на синем фоне)
                textStyle.foreground = Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED);
            }
        };
    }

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    @Override
    public StyledString getStyledText(Object element) {
        // Получаем базовую строку
        StyledString styledString = baseProvider.getStyledText(element);
        if (styledString == null) {
            return new StyledString();
        }
        
        String plainText = styledString.getString();

        // Накладываем стили подсветки нашего поиска поверх
        for (SmartMatcher.HighlightRange range : matcher.getHighlightRanges(plainText)) {
            // ИСПРАВЛЕНО: Вместо DECORATIONS_STYLER теперь честно используем наш matchStyler
            styledString.setStyle(range.offset, range.length, this.matchStyler);
        }

        return styledString;
    }

    @Override
    public Image getImage(Object element) {
        if (baseProvider instanceof LabelProvider) {
            return ((LabelProvider) baseProvider).getImage(element);
        }
        return null;
    }

    /**
     * Обязательно уничтожаем созданный шрифт в операционной системе,
     * когда LabelProvider закрывается, чтобы 1C:EDT не текла по памяти.
     */
    @Override
    public void dispose() {
        if (boldFont != null && !boldFont.isDisposed()) {
            boldFont.dispose();
        }
        if (baseProvider != null) {
            baseProvider.dispose();
        }
        super.dispose();
    }
}
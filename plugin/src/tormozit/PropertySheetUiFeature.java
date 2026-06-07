package tormozit;

/**
 * Расширяемая доработка UI палитры свойств EDT (подсветка, клик, выделение строки…).
 */
interface PropertySheetUiFeature
{
    /** Вызывается после {@code refreshChildren} и сканирования строк палитры. */
    void refresh(PropertySheetUiContext ctx);

    /** Снять слушатели/состояние при закрытии страницы (опционально). */
    default void dispose(PropertySheetUiContext ctx) {}
}

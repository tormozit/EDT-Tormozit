package tormozit;

/**
 * Строка «локального пересечения» для списка конфликтов на странице «Клавиши».
 * Команда EDT в узком контексте редактора с тем же сочетанием, что у выбранной привязки.
 */
final class ComfortKeysLocalConflictRow
{
    final String commandId;
    final String commandName;
    final String contextId;
    final String contextName;
    final String sequenceFormatted;
    final int bindingType;

    ComfortKeysLocalConflictRow(
            String commandId,
            String commandName,
            String contextId,
            String contextName,
            String sequenceFormatted,
            int bindingType)
    {
        this.commandId = commandId;
        this.commandName = commandName;
        this.contextId = contextId;
        this.contextName = contextName;
        this.sequenceFormatted = sequenceFormatted;
        this.bindingType = bindingType;
    }

    String commandColumnText()
    {
        String name = commandName != null && !commandName.isBlank()
                ? commandName
                : commandId;
        String typeMark = bindingType == org.eclipse.jface.bindings.Binding.USER
                ? " U" //$NON-NLS-1$
                : "  "; //$NON-NLS-1$
        return typeMark + " " + name; //$NON-NLS-1$
    }

    String contextColumnText()
    {
        String ctx = contextName != null && !contextName.isBlank()
                ? contextName
                : contextId;
        if (sequenceFormatted != null && !sequenceFormatted.isBlank())
            return ctx + "  " + sequenceFormatted; //$NON-NLS-1$
        return ctx;
    }
}

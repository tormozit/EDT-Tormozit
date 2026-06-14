package tormozit;



import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;



import org.eclipse.core.commands.Command;

import org.eclipse.core.commands.ParameterizedCommand;

import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jface.bindings.Binding;

import org.eclipse.jface.bindings.TriggerSequence;

import org.eclipse.jface.bindings.keys.KeySequence;

import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.commands.ICommandService;

import org.eclipse.ui.internal.keys.BindingService;

import org.eclipse.ui.internal.keys.model.BindingElement;

import org.eclipse.ui.internal.keys.model.KeyController;

import org.eclipse.ui.internal.keys.model.ModelElement;

import org.eclipse.ui.keys.IBindingService;



/**

 * Ищет другие команды в локальных контекстах EDT с тем же сочетанием, что у выбранной привязки.

 * Источник — каталог {@link KeyController} / BindingManager страницы «Клавиши», а не

 * runtime {@link BindingService#getBindings()} (там FormatAction может быть снят хуками).

 */

final class ComfortKeysLocalConflictAnalyzer

{

    private static final String TAG = "ComfortKeysLocalConflict"; //$NON-NLS-1$



    private static final String DEFAULT_SCHEME_ID =

            "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$



    private static final String DESIGNER_SCHEME_ID =

            "com._1c.g5.v8.designer.scheme"; //$NON-NLS-1$



    private static final String WINDOW_CONTEXT_ID =

            "org.eclipse.ui.contexts.window"; //$NON-NLS-1$



    private static final String DIALOG_CONTEXT_ID =

            "org.eclipse.ui.contexts.dialog"; //$NON-NLS-1$



    private static final String DIALOG_AND_WINDOW_CONTEXT_ID =

            "org.eclipse.ui.contexts.dialogAndWindow"; //$NON-NLS-1$



    private ComfortKeysLocalConflictAnalyzer()

    {

    }



    static List<ComfortKeysLocalConflictRow> analyze(

            KeyController keyController,

            BindingElement selected,

            IProgressMonitor monitor)

    {

        List<ComfortKeysLocalConflictRow> result = new ArrayList<>();

        if (keyController == null || selected == null)

            return result;



        TriggerSequence trigger = selected.getTrigger();

        if (!(trigger instanceof KeySequence targetSequence))

            return result;



        String selectedCommandId = selected.getId();

        BindingService bindingService = resolveBindingService(keyController);

        String activeScheme = resolveActiveScheme(keyController, bindingService);

        String platform = bindingService != null ? bindingService.getPlatform() : null;

        String locale = bindingService != null ? bindingService.getLocale() : null;



        ICommandService commandService =

                PlatformUI.getWorkbench().getService(ICommandService.class);



        Map<String, ComfortKeysLocalConflictRow> byCommandContext = new LinkedHashMap<>();
        Binding[] bindings = resolveManagerBindings(keyController);
        int total = bindings.length;

        for (int i = 0; i < total; i++)
        {
            if (monitor != null && monitor.isCanceled())
                break;
            if (monitor != null && (i % 50 == 0))
                monitor.worked(1);

            Binding binding = bindings[i];
            if (binding == null || !matchesSequence(binding, targetSequence))
                continue;
            if (!schemeMatches(binding.getSchemeId(), activeScheme))
                continue;
            if (!matchesPlatformLocale(binding, platform, locale))
                continue;

            String contextId = binding.getContextId();
            if (contextId == null || isGlobalContext(contextId))
                continue;

            ParameterizedCommand pc = binding.getParameterizedCommand();
            String commandId = pc != null ? pc.getId() : null;
            if (commandId == null || commandId.isBlank())
                continue;

            if (commandId.equals(selectedCommandId))
                continue;

            int bindingType = binding.getType();
            String signature = commandId + '|' + contextId;
            String commandName = resolveCommandName(commandService, commandId);
            String contextName = resolveContextName(keyController, contextId);
            String sequenceFormatted = targetSequence.format();

            ComfortKeysLocalConflictRow candidate = new ComfortKeysLocalConflictRow(
                    commandId,
                    commandName,
                    contextId,
                    contextName,
                    sequenceFormatted,
                    bindingType);

            ComfortKeysLocalConflictRow existing = byCommandContext.get(signature);
            if (existing == null
                    || (existing.bindingType != Binding.USER
                            && candidate.bindingType == Binding.USER))
                byCommandContext.put(signature, candidate);
        }

        result = new ArrayList<>(byCommandContext.values());



        result.sort(Comparator

                .comparing((ComfortKeysLocalConflictRow r) -> r.contextName,

                        String.CASE_INSENSITIVE_ORDER)

                .thenComparing(r -> r.commandName, String.CASE_INSENSITIVE_ORDER));



        if (Global.isLogEnabled())

        {

            Global.log(TAG, "analyze seq=" + targetSequence.format() //$NON-NLS-1$

                    + " scheme=" + activeScheme //$NON-NLS-1$

                    + " catalog=" + total //$NON-NLS-1$

                    + " localHits=" + result.size()); //$NON-NLS-1$

        }

        return result;

    }



    private static int resolveBindingType(BindingElement selected)
    {
        try
        {
            Object type = Global.invoke(selected, "getType"); //$NON-NLS-1$
            if (type instanceof Integer integer)
                return integer.intValue();
            if (type instanceof Number number)
                return number.intValue();
        }
        catch (Exception ignored)
        {
            // package-private getType недоступен из tormozit
        }
        return selected.getUserDelta() != null
                ? selected.getUserDelta().intValue()
                : Binding.SYSTEM;
    }



    private static BindingService resolveBindingService(KeyController keyController)

    {

        try

        {

            Object service = Global.invoke(keyController, "getService"); //$NON-NLS-1$

            if (service instanceof BindingService bindingService)

                return bindingService;

        }

        catch (Exception ignored)

        {

            // поле bindingService

        }

        try

        {

            Field field = KeyController.class.getDeclaredField("bindingService"); //$NON-NLS-1$

            field.setAccessible(true);

            Object service = field.get(keyController);

            if (service instanceof BindingService bindingService)

                return bindingService;

        }

        catch (Exception ignored)

        {

            // fallback — workbench

        }

        IBindingService service =

                PlatformUI.getWorkbench().getService(IBindingService.class);

        if (service instanceof BindingService bindingService)

            return bindingService;

        return null;

    }



    private static Binding[] resolveManagerBindings(KeyController keyController)

    {

        Object manager = resolveBindingManager(keyController);

        if (manager == null)

            return new Binding[0];

        try

        {

            Object bindings = Global.invoke(manager, "getBindings"); //$NON-NLS-1$

            if (bindings instanceof Binding[] arr)

                return arr;

        }

        catch (Exception ignored)

        {

            // внутренний API изменился

        }

        return new Binding[0];

    }



    private static Object resolveBindingManager(KeyController keyController)

    {

        try

        {

            Object manager = Global.invoke(keyController, "getManager"); //$NON-NLS-1$

            if (manager != null)

                return manager;

        }

        catch (Exception ignored)

        {

            // fBindingManager

        }

        try

        {

            Field field = KeyController.class.getDeclaredField("fBindingManager"); //$NON-NLS-1$

            field.setAccessible(true);

            return field.get(keyController);

        }

        catch (Exception ignored)

        {

            return null;

        }

    }



    private static String resolveActiveScheme(

            KeyController keyController,

            BindingService bindingService)

    {

        try

        {

            Object schemeModel = keyController.getSchemeModel();

            if (schemeModel != null)

            {

                Object selected = Global.invoke(schemeModel, "getSelectedElement"); //$NON-NLS-1$

                if (selected instanceof ModelElement element)

                {

                    String id = element.getId();

                    if (id != null && !id.isBlank())

                        return id;

                }

            }

        }

        catch (Exception ignored)

        {

            // activeSchemeId

        }

        try

        {

            Field field = KeyController.class.getDeclaredField("activeSchemeId"); //$NON-NLS-1$

            field.setAccessible(true);

            Object id = field.get(keyController);

            if (id instanceof String schemeId && !schemeId.isBlank())

                return schemeId;

        }

        catch (Exception ignored)

        {

            // BindingService

        }

        if (bindingService != null && bindingService.getActiveScheme() != null)

            return bindingService.getActiveScheme().getId();

        return DEFAULT_SCHEME_ID;

    }



    private static String resolveBindingElementContextId(BindingElement selected)

    {

        ModelElement context = selected.getContext();

        if (context != null)

        {

            String id = context.getId();

            if (id != null && !id.isBlank())

                return id;

        }

        try

        {

            Object id = Global.invoke(selected, "getContextId"); //$NON-NLS-1$

            if (id instanceof String contextId && !contextId.isBlank())

                return contextId;

        }

        catch (Exception ignored)

        {

            // package-private getContextId недоступен из tormozit

        }

        return null;

    }



    private static boolean matchesSequence(Binding binding, KeySequence target)

    {

        TriggerSequence trigger = binding.getTriggerSequence();

        if (!(trigger instanceof KeySequence keySequence))

            return false;

        if (target.equals(keySequence))

            return true;

        return target.getTriggers().length > 0

                && keySequence.getTriggers().length > 0

                && target.getTriggers()[0].equals(keySequence.getTriggers()[0]);

    }



    private static boolean isGlobalContext(String contextId)

    {

        if (WINDOW_CONTEXT_ID.equals(contextId)

                || DIALOG_CONTEXT_ID.equals(contextId)

                || DIALOG_AND_WINDOW_CONTEXT_ID.equals(contextId))

            return true;

        return contextId.endsWith(".window") //$NON-NLS-1$

                || contextId.contains("dialog"); //$NON-NLS-1$

    }



    private static boolean schemeMatches(String schemeId, String activeScheme)

    {

        if (schemeId == null)

            return false;

        return activeScheme.equals(schemeId)

                || DEFAULT_SCHEME_ID.equals(schemeId)

                || DESIGNER_SCHEME_ID.equals(schemeId);

    }



    private static boolean matchesPlatformLocale(Binding binding, String platform, String locale)

    {

        String bindingPlatform = binding.getPlatform();

        if (bindingPlatform != null && !bindingPlatform.isBlank()

                && platform != null && !bindingPlatform.equals(platform))

            return false;

        String bindingLocale = binding.getLocale();

        return bindingLocale == null || bindingLocale.isBlank()

                || locale == null || bindingLocale.equals(locale);

    }



    private static String resolveCommandName(ICommandService commandService, String commandId)

    {

        if (commandService == null)

            return commandId;

        Command command = commandService.getCommand(commandId);

        if (command == null)

            return commandId;

        try

        {

            String name = command.getName();

            if (name != null && !name.isBlank())

                return name;

        }

        catch (Exception ignored)

        {

            // команда ещё не определена

        }

        return commandId;

    }



    private static String resolveContextName(KeyController keyController, String contextId)

    {

        try

        {

            Object contextModel = keyController.getContextModel();

            if (contextModel == null)

                return contextId;

            Object contexts = Global.invoke(contextModel, "getElements"); //$NON-NLS-1$

            if (!(contexts instanceof Iterable<?> iterable))

            {

                contexts = Global.invoke(contextModel, "getContexts"); //$NON-NLS-1$

            }

            if (!(contexts instanceof Iterable<?> iterable))

                return contextId;

            for (Object ctx : iterable)

            {

                Object id = Global.invoke(ctx, "getId"); //$NON-NLS-1$

                if (!contextId.equals(id))

                    continue;

                Object name = Global.invoke(ctx, "getName"); //$NON-NLS-1$

                if (name instanceof String s && !s.isBlank())

                    return s;

            }

        }

        catch (Exception ignored)

        {

            // fallback — id контекста

        }

        return contextId;

    }

}



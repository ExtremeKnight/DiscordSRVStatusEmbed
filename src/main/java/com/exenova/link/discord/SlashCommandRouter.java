package com.exenova.link.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Routes slash command interactions to {@link SlashHandler} methods. */
public final class SlashCommandRouter extends ListenerAdapter {
    private record Route(Object owner, Method method, SlashHandler spec) { }

    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Logger logger;

    public SlashCommandRouter(Logger logger) { this.logger = logger; }

    /** Registers every {@link SlashHandler} method on {@code owner}. */
    public void registerAll(Object owner) {
        for (Method method : owner.getClass().getDeclaredMethods()) {
            SlashHandler spec = method.getAnnotation(SlashHandler.class);
            if (spec == null) continue;
            if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != SlashCommandInteractionEvent.class) {
                throw new IllegalStateException("@SlashHandler method " + method.getName() + " must take one SlashCommandInteractionEvent");
            }
            method.setAccessible(true);
            routes.put(spec.value().toLowerCase(Locale.ROOT), new Route(owner, method, spec));
        }
    }

    public boolean handles(String name) { return routes.containsKey(name.toLowerCase(Locale.ROOT)); }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        Route route = routes.get(event.getName().toLowerCase(Locale.ROOT));
        if (route == null) return;
        if (route.spec().defer()) event.deferReply(route.spec().ephemeral()).queue();
        try {
            route.method().invoke(route.owner(), event);
        } catch (InvocationTargetException exception) {
            logger.log(Level.WARNING, "Slash command /" + event.getName() + " failed", exception.getCause());
            reply(event, "Something went wrong running that command.");
        } catch (ReflectiveOperationException exception) {
            logger.log(Level.WARNING, "Slash command /" + event.getName() + " could not be invoked", exception);
            reply(event, "Something went wrong running that command.");
        }
    }

    private static void reply(SlashCommandInteractionEvent event, String message) {
        if (event.isAcknowledged()) event.getHook().sendMessage(message).setEphemeral(true).queue();
        else event.reply(message).setEphemeral(true).queue();
    }
}

package com.exenova.link.integration;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.EmbedBuilder;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.awt.Color;
import java.time.Instant;
import java.util.function.Function;

/**
 * Listens to another plugin's Bukkit event by class name, so plugins like
 * AdvancedBan (punishments) and NuVotifier (votes) are supported without
 * compiling against their APIs. The describer turns the event into an embed,
 * or returns null to ignore it.
 */
final class ReflectiveEventHook implements Listener, AutoCloseable {
    record Relay(String channelPath, EmbedBuilder embed) { }

    ReflectiveEventHook(ExeNovaLink plugin, String eventClass, Function<Event, Relay> describer) {
        Class<? extends Event> type;
        try {
            type = Class.forName(eventClass, true, findLoader(eventClass)).asSubclass(Event.class);
        } catch (ClassNotFoundException | ClassCastException exception) {
            throw new IllegalStateException("Event " + eventClass + " not found", exception);
        }
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
            if (!type.isInstance(event)) return;
            Relay relay;
            try { relay = describer.apply(event); } catch (RuntimeException exception) { return; }
            if (relay != null) plugin.postToChannel(relay.channelPath(), relay.embed().setTimestamp(Instant.now()).build());
        }, plugin, true);
    }

    /** Finds the class loader of whichever plugin ships {@code className}. */
    private static ClassLoader findLoader(String className) throws ClassNotFoundException {
        for (org.bukkit.plugin.Plugin other : Bukkit.getPluginManager().getPlugins()) {
            try { Class.forName(className, false, other.getClass().getClassLoader()); return other.getClass().getClassLoader(); }
            catch (ClassNotFoundException ignored) { }
        }
        throw new ClassNotFoundException(className);
    }

    @Override public void close() { HandlerList.unregisterAll(this); }

    // ---- describers ------------------------------------------------------

    static Relay describeAdvancedBan(Event event) {
        Object punishment = Integrations.call(event, "getPunishment");
        if (punishment == null) return null;
        Object type = Integrations.call(punishment, "getType");
        return new Relay("punishments.channel-id", new EmbedBuilder()
                .setTitle("🔨 " + String.valueOf(Integrations.call(type, "getName")))
                .addField("Player", String.valueOf(Integrations.call(punishment, "getName")), true)
                .addField("Staff", String.valueOf(Integrations.call(punishment, "getOperator")), true)
                .addField("Duration", String.valueOf(Integrations.call(punishment, "getDuration", false)), true)
                .addField("Reason", String.valueOf(Integrations.call(punishment, "getReason")), false)
                .setColor(new Color(0xED4245)));
    }

    static Relay describeVote(Event event) {
        Object vote = Integrations.call(event, "getVote");
        if (vote == null) return null;
        return new Relay("votes.channel-id", new EmbedBuilder()
                .setDescription("🗳️ **" + Integrations.call(vote, "getUsername") + "** voted on **" + Integrations.call(vote, "getServiceName") + "**. Thank you!")
                .setColor(new Color(0x57F287)));
    }
}

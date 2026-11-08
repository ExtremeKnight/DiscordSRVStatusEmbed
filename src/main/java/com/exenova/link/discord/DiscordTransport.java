package com.exenova.link.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import com.exenova.link.ExeNovaLink;

/** Resolves configured Discord destinations and keeps JDA operations asynchronous. */
public final class DiscordTransport {
    private final ExeNovaLink plugin;

    public DiscordTransport(ExeNovaLink plugin) { this.plugin = plugin; }

    public MessageChannel resolve(JDA jda, String configuredPath, String value) {
        if (jda == null || value == null || value.isBlank()) return null;
        String id = value.matches("\\d{17,20}") ? value : plugin.getConfig().getString(configuredPath + "." + value, "");
        return id.matches("\\d{17,20}") ? jda.getTextChannelById(id) : null;
    }

    public void send(MessageChannel channel, String message) {
        if (channel != null && message != null && !message.isBlank()) channel.sendMessage(message).queue();
    }
}

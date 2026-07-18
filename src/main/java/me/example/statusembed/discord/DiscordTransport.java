package me.example.statusembed.discord;

import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageChannel;
import me.example.statusembed.StatusEmbed;

/** Resolves configured Discord destinations and keeps JDA operations asynchronous. */
public final class DiscordTransport {
    private final StatusEmbed plugin;

    public DiscordTransport(StatusEmbed plugin) { this.plugin = plugin; }

    public MessageChannel resolve(JDA jda, String configuredPath, String value) {
        if (jda == null || value == null || value.isBlank()) return null;
        String id = value.matches("\\d{17,20}") ? value : plugin.getConfig().getString(configuredPath + "." + value, "");
        return id.matches("\\d{17,20}") ? jda.getTextChannelById(id) : null;
    }

    public void send(MessageChannel channel, String message) {
        if (channel != null && message != null && !message.isBlank()) channel.sendMessage(message).queue();
    }
}

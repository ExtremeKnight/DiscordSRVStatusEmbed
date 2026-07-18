package me.example.statusembed.suggestions;

import me.example.statusembed.StatusEmbed;

/** Configuration and validation boundary for suggestion destinations and threads. */
public final class SuggestionService {
    private final StatusEmbed plugin;

    public SuggestionService(StatusEmbed plugin) { this.plugin = plugin; }

    public boolean enabled() { return !plugin.getConfig().getString("suggestions.channel-id", "").isBlank(); }
    public boolean createThread() { return plugin.getConfig().getBoolean("suggestions.create-thread", true); }
    public String channelId() { return plugin.getConfig().getString("suggestions.channel-id", ""); }
}

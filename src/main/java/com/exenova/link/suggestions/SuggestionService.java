package com.exenova.link.suggestions;

import com.exenova.link.ExeNovaLink;

/** Configuration and validation boundary for suggestion destinations and threads. */
public final class SuggestionService {
    private final ExeNovaLink plugin;

    public SuggestionService(ExeNovaLink plugin) { this.plugin = plugin; }

    public boolean enabled() { return !plugin.getConfig().getString("suggestions.channel-id", "").isBlank(); }
    public boolean createThread() { return plugin.getConfig().getBoolean("suggestions.create-thread", true); }
    public String channelId() { return plugin.getConfig().getString("suggestions.channel-id", ""); }
}

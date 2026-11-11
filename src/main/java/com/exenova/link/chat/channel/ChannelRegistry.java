package com.exenova.link.chat.channel;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The set of configured channels and lookups over them. Built from the
 * {@code chat.channels} config section; the parsing is also reachable directly
 * for tests via {@link #fromMap}.
 */
public final class ChannelRegistry {
    private final Map<String, Channel> byName = new LinkedHashMap<>();
    private final String defaultChannel;

    private ChannelRegistry(List<Channel> channels, String defaultChannel) {
        for (Channel channel : channels) byName.put(channel.name(), channel);
        this.defaultChannel = defaultChannel == null ? "" : defaultChannel.toLowerCase(Locale.ROOT);
    }

    /** Builds from the {@code chat.channels} section and {@code chat.default-channel}. */
    public static ChannelRegistry fromConfig(ConfigurationSection chat) {
        List<Channel> channels = new ArrayList<>();
        ConfigurationSection section = chat == null ? null : chat.getConfigurationSection("channels");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection c = section.getConfigurationSection(key);
                if (c == null) continue;
                channels.add(new Channel(key, c.getStringList("aliases"), c.getString("format"),
                        c.getInt("radius", 0), c.getString("permission", ""),
                        c.getString("discord-channel-id", ""), c.getBoolean("auto-join", true)));
            }
        }
        if (channels.isEmpty()) channels.add(new Channel("global", List.of("g"), null, 0, "", "", true));
        String def = chat == null ? null : chat.getString("default-channel", channels.get(0).name());
        return new ChannelRegistry(channels, def);
    }

    /** Test-friendly builder: name → {aliases,format,radius,permission,discord,autoJoin} already parsed. */
    public static ChannelRegistry fromMap(List<Channel> channels, String defaultChannel) {
        return new ChannelRegistry(channels, defaultChannel);
    }

    public Optional<Channel> byExactName(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Resolves a channel by name or alias. */
    public Optional<Channel> resolve(String query) {
        return byName.values().stream().filter(channel -> channel.matches(query)).findFirst();
    }

    /** The channel bridged to a given Discord channel ID, if any. */
    public Optional<Channel> byDiscordChannel(String discordChannelId) {
        if (discordChannelId == null || discordChannelId.isBlank()) return Optional.empty();
        return byName.values().stream().filter(channel -> discordChannelId.equals(channel.discordChannelId())).findFirst();
    }

    public Channel defaultChannel() {
        Channel byConfig = byName.get(defaultChannel);
        return byConfig != null ? byConfig : byName.values().iterator().next();
    }

    public List<Channel> all() { return List.copyOf(byName.values()); }

    /** Channels a player may use. */
    public List<Channel> usableBy(org.bukkit.permissions.Permissible who) {
        return byName.values().stream().filter(channel -> channel.usableBy(who)).toList();
    }
}

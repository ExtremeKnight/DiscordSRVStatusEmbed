package com.exenova.link.config;

import com.exenova.link.ExeNovaLink;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Owns default merging and startup validation for plugin configuration. */
public final class ConfigService {
    private final ExeNovaLink plugin;

    public ConfigService(ExeNovaLink plugin) { this.plugin = plugin; }

    public void prepare() {
        mergeMissingDefaults();
        validateCoreSettings();
    }

    private void mergeMissingDefaults() {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            mergeSection(defaults, "");
            plugin.getConfig().set("config-version", Math.max(2, plugin.getConfig().getInt("config-version", 1)));
            plugin.saveConfig();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not merge configuration defaults: " + exception.getMessage());
        }
    }

    private void mergeSection(YamlConfiguration defaults, String path) {
        ConfigurationSection section = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (defaults.isConfigurationSection(childPath)) mergeSection(defaults, childPath);
            else if (!plugin.getConfig().contains(childPath)) plugin.getConfig().set(childPath, defaults.get(childPath));
        }
    }

    private void validateCoreSettings() {
        validateSnowflake("channel-id");
        validateSnowflake("status-dashboard.channel-id");
        validateSnowflake("reports.channel-id");
        validateSnowflake("verification.channel-id");
        validateSnowflake("verification.role-id");
        for (String path : new String[] {"discord.guild-id", "bridge.channel-id", "console.channel-id", "punishments.channel-id",
                "votes.channel-id", "linking.linked-role-id", "linking.log-channel-id", "suggestions.channel-id", "changelog.channel-id"}) {
            validateSnowflake(path);
        }
        if (plugin.getConfig().getBoolean("console.enabled", false)
                && plugin.getConfig().getStringList("console.allowed-user-ids").isEmpty()
                && plugin.getConfig().getStringList("console.allowed-role-ids").isEmpty()) {
            plugin.getLogger().info("Discord console is read-only: no console.allowed-user-ids / allowed-role-ids set.");
        }
        if (plugin.getConfig().getInt("leaderboards.limit", 10) < 1) {
            plugin.getLogger().warning("leaderboards.limit must be at least 1; using 10 for this session.");
            plugin.getConfig().set("leaderboards.limit", 10);
        }
    }

    private void validateSnowflake(String path) {
        String value = plugin.getConfig().getString(path, "");
        if (!value.isBlank() && !value.matches("\\d{17,20}")) {
            plugin.getLogger().warning("Configuration path '" + path + "' is not a valid Discord snowflake: " + value);
        }
    }
}

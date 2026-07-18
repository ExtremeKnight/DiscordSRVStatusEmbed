package me.example.statusembed.config;

import me.example.statusembed.StatusEmbed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Owns default merging and startup validation for plugin configuration. */
public final class ConfigService {
    private final StatusEmbed plugin;

    public ConfigService(StatusEmbed plugin) { this.plugin = plugin; }

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

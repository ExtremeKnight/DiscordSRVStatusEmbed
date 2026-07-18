package me.example.statusembed.storage;

import me.example.statusembed.StatusEmbed;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** Persists staff notes separately from the main plugin configuration. */
public final class NotesRepository {
    private final StatusEmbed plugin;
    private final File file;
    private final YamlConfiguration configuration;

    public NotesRepository(StatusEmbed plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-notes.yml");
        this.configuration = YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration configuration() { return configuration; }

    public void save() {
        try { configuration.save(file); }
        catch (IOException exception) { plugin.getLogger().warning("Could not save staff notes: " + exception.getMessage()); }
    }
}

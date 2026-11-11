package com.exenova.link.storage;

import com.exenova.link.ExeNovaLink;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Creates, lists, prunes and restores validated configuration backups. */
public final class BackupService {
    private static final Pattern BACKUP_NAME = Pattern.compile("config-\\d+\\.yml");
    private final ExeNovaLink plugin;

    public BackupService(ExeNovaLink plugin) { this.plugin = plugin; }

    /** True when {@code fileName} is a plain backup name made by this service (no paths). */
    public static boolean isValidBackupName(String fileName) {
        return fileName != null && BACKUP_NAME.matcher(fileName).matches();
    }

    /** Synchronized so two backups in the same millisecond cannot race on one file name. */
    public synchronized String create() throws IOException {
        File directory = directory();
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create backups folder");
        long stamp = System.currentTimeMillis();
        File target;
        do { target = new File(directory, "config-" + stamp++ + ".yml"); } while (target.exists());
        Files.copy(new File(plugin.getDataFolder(), "config.yml").toPath(), target.toPath());
        return target.getName();
    }

    /** Deletes all but the newest {@code keep} backups. */
    public synchronized void prune(int keep) throws IOException {
        List<File> backups = list();
        for (int i = Math.max(1, keep); i < backups.size(); i++) Files.deleteIfExists(backups.get(i).toPath());
    }

    /** Backups ordered newest first. */
    public List<File> list() {
        File[] files = directory().listFiles((dir, name) -> isValidBackupName(name));
        if (files == null) return new ArrayList<>();
        List<File> backups = new ArrayList<>(Arrays.asList(files));
        backups.sort(Comparator.comparingLong(File::lastModified).reversed());
        return backups;
    }

    public synchronized void restore(String fileName) throws IOException {
        if (!isValidBackupName(fileName)) throw new IOException("Invalid backup filename");
        File source = new File(directory(), fileName);
        if (!source.isFile()) throw new IOException("Backup not found: " + fileName);
        Files.copy(source.toPath(), new File(plugin.getDataFolder(), "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private File directory() { return new File(plugin.getDataFolder(), "backups"); }
}

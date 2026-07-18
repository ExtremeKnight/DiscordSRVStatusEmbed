package me.example.statusembed.scheduler;

import me.example.statusembed.StatusEmbed;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Central lifecycle owner for repeating Bukkit tasks created by the plugin. */
public final class SchedulerService {
    private final StatusEmbed plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    public SchedulerService(StatusEmbed plugin) { this.plugin = plugin; }

    public BukkitTask repeatSync(long delay, long period, Runnable action) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, action, delay, period);
        tasks.add(task);
        return task;
    }

    public BukkitTask repeatAsync(long delay, long period, Runnable action) {
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, action, delay, period);
        tasks.add(task);
        return task;
    }

    public void cancelAll() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }
}

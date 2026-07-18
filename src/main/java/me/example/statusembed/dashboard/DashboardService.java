package me.example.statusembed.dashboard;

import me.example.statusembed.StatusEmbed;
import org.bukkit.scheduler.BukkitTask;

/** Owns the dashboard refresh schedule while the legacy renderer is migrated. */
public final class DashboardService {
    private final StatusEmbed plugin;
    private BukkitTask task;

    public DashboardService(StatusEmbed plugin) { this.plugin = plugin; }

    public void start() {
        if (!plugin.getConfig().getBoolean("status-dashboard.enabled", false)) return;
        long minutes = Math.max(1, plugin.getConfig().getLong("status-dashboard.interval-minutes", 10));
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, plugin::refreshDashboard, 40L, minutes * 60L * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}

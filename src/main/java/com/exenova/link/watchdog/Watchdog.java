package com.exenova.link.watchdog;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.EmbedBuilder;
import org.bukkit.Bukkit;

import java.awt.Color;
import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * Periodically samples TPS, RAM and CPU and posts a Discord alert to a staff
 * channel when health changes (OK → WARNING/CRITICAL and back). Only transitions
 * are posted, so a sustained problem doesn't spam. The threshold logic lives in
 * {@link HealthLevel}; this class only samples, compares and sends.
 */
public final class Watchdog {
    private final ExeNovaLink plugin;
    private volatile HealthLevel last = HealthLevel.OK;

    public Watchdog(ExeNovaLink plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("watchdog.enabled", false); }

    public long intervalTicks() {
        return Math.max(10, plugin.getConfig().getLong("watchdog.interval-seconds", 30)) * 20L;
    }

    /** Runs on the main thread (needs Bukkit.getTPS); cheap. */
    public void check() {
        if (!enabled() || !plugin.isDiscordReady()) return;
        double tps = Math.min(20.0, safeTps());
        int ram = ramPercent();
        int cpu = cpuPercent();
        HealthLevel now = HealthLevel.evaluate(tps, ram, cpu, thresholds());
        HealthLevel previous = last;
        if (now == previous) return;
        last = now;
        // Only announce a real problem or a recovery from one.
        if (previous == HealthLevel.OK && now == HealthLevel.WARNING
                && !plugin.getConfig().getBoolean("watchdog.announce-warnings", true)) return;
        post(now, tps, ram, cpu);
    }

    private void post(HealthLevel level, double tps, int ram, int cpu) {
        Color color = switch (level) {
            case OK -> new Color(0x57F287);
            case WARNING -> new Color(0xFEE75C);
            case CRITICAL -> new Color(0xED4245);
        };
        String title = switch (level) {
            case OK -> "✅ Server recovered";
            case WARNING -> "⚠ Server under load";
            case CRITICAL -> "🔴 Server health critical";
        };
        EmbedBuilder embed = new EmbedBuilder().setTitle(title).setColor(color).setTimestamp(Instant.now())
                .addField("TPS", String.format(java.util.Locale.ROOT, "%.1f", tps), true)
                .addField("RAM", ram + "%", true)
                .addField("CPU", cpu < 0 ? "n/a" : cpu + "%", true)
                .addField("Players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(), true);
        String mention = plugin.getConfig().getString("watchdog.mention-role-id", "");
        if (level != HealthLevel.OK && mention.matches("\\d{17,20}")) {
            plugin.postToChannelWithContent("watchdog.channel-id", "<@&" + mention + ">", embed.build());
        } else {
            plugin.postToChannel("watchdog.channel-id", embed.build());
        }
    }

    private HealthLevel.Thresholds thresholds() {
        var c = plugin.getConfig();
        return new HealthLevel.Thresholds(
                c.getDouble("watchdog.tps-warn", 18.0), c.getDouble("watchdog.tps-critical", 15.0),
                c.getInt("watchdog.ram-warn-percent", 85), c.getInt("watchdog.ram-critical-percent", 95),
                c.getInt("watchdog.cpu-warn-percent", 85), c.getInt("watchdog.cpu-critical-percent", 95));
    }

    private double safeTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return tps == null || tps.length == 0 ? 20.0 : tps[0];
        } catch (Throwable t) { return 20.0; }
    }

    private int ramPercent() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = Math.max(1, runtime.maxMemory());
        return (int) Math.min(100, Math.round(used * 100.0 / max));
    }

    private int cpuPercent() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getProcessCpuLoad();
            if (load >= 0) return (int) Math.round(Math.min(1, load) * 100);
        }
        return -1;
    }
}

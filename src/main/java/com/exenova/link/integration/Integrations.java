package com.exenova.link.integration;

import com.exenova.link.ExeNovaLink;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single entry point for every optional plugin integration. Each hook is only
 * constructed when its plugin is installed and enabled, and its class is only
 * loaded at that moment, so a missing plugin never causes NoClassDefFoundError.
 *
 * <p>Several capabilities deliberately avoid plugin-specific APIs so they work
 * with as many plugins as possible: vanish uses the de-facto standard
 * {@code vanished} metadata (SuperVanish, PremiumVanish, EssentialsX, CMI...),
 * and chat formats go through PlaceholderAPI (Vault, LuckPerms, Towny...).</p>
 */
public final class Integrations {
    private final ExeNovaLink plugin;
    private final Map<String, Object> active = new LinkedHashMap<>();
    private PlaceholderSupport placeholders;
    private EssentialsHook essentials;
    private LuckPermsHook luckPerms;
    private DiscordSrvHook discordSrv;
    private FloodgateHook floodgate;

    public Integrations(ExeNovaLink plugin) { this.plugin = plugin; }

    public void enable() {
        placeholders = hook("PlaceholderAPI", PlaceholderSupport::new);
        essentials = hook("Essentials", () -> new EssentialsHook(Bukkit.getPluginManager().getPlugin("Essentials")));
        luckPerms = hook("LuckPerms", LuckPermsHook::new);
        discordSrv = hook("DiscordSRV", () -> new DiscordSrvHook(plugin));
        floodgate = hook("floodgate", FloodgateHook::new);
        hook("AdvancedBan", () -> new ReflectiveEventHook(plugin, "me.leoko.advancedban.bukkit.event.PunishmentEvent", ReflectiveEventHook::describeAdvancedBan));
        hook("Votifier", () -> new ReflectiveEventHook(plugin, "com.vexsoftware.votifier.model.VotifierEvent", ReflectiveEventHook::describeVote));
        for (String name : List.of("SuperVanish", "PremiumVanish", "CMI", "Geyser-Spigot", "LiteBans")) {
            if (enabled(name)) active.put(name, Boolean.TRUE); // handled generically (metadata / command watcher)
        }
        plugin.getLogger().info(active.isEmpty() ? "No optional integrations detected."
                : "Integrations active: " + String.join(", ", active.keySet()));
    }

    public void disable() {
        for (Object hook : active.values()) if (hook instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
        active.clear();
        placeholders = null; essentials = null; luckPerms = null; discordSrv = null; floodgate = null;
    }

    private <T> T hook(String pluginName, Supplier<T> factory) {
        if (!enabled(pluginName) || !plugin.getConfig().getBoolean("integrations." + pluginName.toLowerCase(java.util.Locale.ROOT) + ".enabled", true)) return null;
        try {
            T hook = factory.get();
            active.put(pluginName, hook);
            return hook;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not hook into " + pluginName + " (unsupported version?): " + throwable);
            return null;
        }
    }

    private static boolean enabled(String name) {
        org.bukkit.plugin.Plugin other = Bukkit.getPluginManager().getPlugin(name);
        return other != null && other.isEnabled();
    }

    public Set<String> activeNames() { return Collections.unmodifiableSet(active.keySet()); }

    // ---- capabilities -----------------------------------------------------

    /** Applies PlaceholderAPI placeholders when installed; otherwise returns the text unchanged. */
    public String placeholders(OfflinePlayer player, String text) {
        return placeholders == null || text == null ? text : placeholders.apply(player, text);
    }

    /** True for players hidden by any vanish plugin that follows the "vanished" metadata convention. */
    public boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) if (value.asBoolean()) return true;
        return essentials != null && essentials.isVanished(player);
    }

    public boolean isMuted(Player player) { return essentials != null && essentials.isMuted(player); }
    public boolean isAfk(Player player) { return essentials != null && essentials.isAfk(player); }

    /** Bedrock (Geyser/Floodgate) players, detected via the Floodgate API or Floodgate's UUID scheme. */
    public boolean isBedrock(UUID uuid) {
        if (floodgate != null) return floodgate.isBedrock(uuid);
        return uuid.getMostSignificantBits() == 0; // Floodgate's XUID-based UUIDs
    }

    public Optional<LuckPermsHook> luckPerms() { return Optional.ofNullable(luckPerms); }

    /** Discord ID linked through DiscordSRV, if DiscordSRV is installed and has a link for this player. */
    public Optional<String> discordSrvLinkedId(UUID uuid) {
        return discordSrv == null ? Optional.empty() : Optional.ofNullable(discordSrv.discordId(uuid));
    }

    public Optional<UUID> discordSrvLinkedUuid(String discordId) {
        return discordSrv == null ? Optional.empty() : Optional.ofNullable(discordSrv.uuid(discordId));
    }

    public boolean discordSrvBridgeActive() { return discordSrv != null && discordSrv.chatBridgeConfigured(); }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (String name : active.keySet()) lines.add(name);
        return lines;
    }

    /** Calls a no-arg method reflectively, returning null on any failure. */
    static Object call(Object target, String method, Object... args) {
        if (target == null) return null;
        try {
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) return candidate.invoke(target, args);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }
}

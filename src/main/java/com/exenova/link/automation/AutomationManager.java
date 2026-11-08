package com.exenova.link.automation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import com.exenova.link.ExeNovaLink;
import com.exenova.link.discord.DiscordTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Loads and dispatches configuration-driven automations. Bukkit work is always
 * executed on the server thread; Discord work uses JDA's asynchronous queue APIs.
 */
public final class AutomationManager implements Listener {
    private final ExeNovaLink plugin;
    private final Logger logger;
    private final TriggerRegistry triggers = new TriggerRegistry();
    private final ActionRegistry actions = new ActionRegistry();
    private final CooldownManager cooldowns = new CooldownManager();
    private final Map<String, Automation> automations = new ConcurrentHashMap<>();
    private final Map<String, Runnable> customActions = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> dashboardDispatching = ThreadLocal.withInitial(() -> false);
    /** Nesting depth of TRIGGER_AUTOMATION chains on the current thread; guards against a->b->a loops. */
    private final ThreadLocal<Integer> chainDepth = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_CHAIN_DEPTH = 8;
    private final List<BukkitTask> scheduledTasks = new ArrayList<>();
    private final AutomationExecutor executor;
    private final DiscordTransport discordTransport;

    public AutomationManager(ExeNovaLink plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.executor = new AutomationExecutor(logger, actions);
        this.discordTransport = new DiscordTransport(plugin);
        registerBuiltInActions();
    }

    public void load() {
        cancelScheduledTasks();
        automations.clear();
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("automations");
        if (section == null) {
            logger.info("No automations configured.");
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                Automation automation = parse(id, section.getConfigurationSection(id));
                if (automation != null) automations.put(id, automation);
            } catch (RuntimeException exception) {
                logger.warning("Invalid automation '" + id + "': " + exception.getMessage());
            }
        }
        scheduleIntervals();
        logger.info("Loaded " + automations.size() + " valid automation(s).");
    }

    /** Re-reads automations from the (already reloaded) plugin config and reschedules interval automations. */
    public void reload() {
        cooldowns.clear();
        load();
    }

    public void registerAction(String type, Action action) { actions.register(type, action); }
    public void registerCustomAction(String id, Runnable action) { customActions.put(id, action); }
    public Map<String, Automation> automations() { return Collections.unmodifiableMap(automations); }

    public void dispatch(Trigger trigger, Object event, JDA jda) {
        if (trigger == Trigger.DASHBOARD_REFRESH && dashboardDispatching.get()) return;
        if (trigger == Trigger.DASHBOARD_REFRESH) dashboardDispatching.set(true);
        try {
        for (Automation automation : automations.values()) {
            if (!automation.enabled() || automation.trigger() != trigger) continue;
            AutomationContext context = new AutomationContext(plugin, event, jda);
            // Check conditions before taking the cooldown so an event that is
            // filtered out (e.g. a non-first join) doesn't burn the cooldown.
            if (!conditionsPass(automation, context)) continue;
            String actor = context.player() != null ? context.player().getUniqueId().toString()
                    : context.variables().getOrDefault("discord_user", "global");
            if (!cooldowns.tryAcquire(automation.id() + ":" + actor, automation.cooldownSeconds())) continue;
            Runnable run = () -> executor.execute(automation, context);
            if (Bukkit.isPrimaryThread()) run.run(); else Bukkit.getScheduler().runTask(plugin, run);
        }
        } finally {
            if (trigger == Trigger.DASHBOARD_REFRESH) dashboardDispatching.set(false);
        }
    }

    public void dispatch(Trigger trigger, Object event) { dispatch(trigger, event, null); }

    @EventHandler public void onJoin(PlayerJoinEvent event) { dispatch(Trigger.PLAYER_JOIN, event); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { dispatch(Trigger.PLAYER_QUIT, event); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { dispatch(Trigger.PLAYER_DEATH, event); }
    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent event) { dispatch(Trigger.PLAYER_ADVANCEMENT_DONE, event); }
    @EventHandler(ignoreCancelled = true) public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) { dispatch(Trigger.ASYNC_PLAYER_CHAT, event); }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent event) { dispatch(Trigger.PLAYER_COMMAND_PREPROCESS, event); }
    @EventHandler public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP) dispatch(Trigger.SERVER_STARTUP, event);
    }

    public void shutdown() {
        cancelScheduledTasks();
        cooldowns.clear();
        automations.clear();
    }

    private void cancelScheduledTasks() {
        scheduledTasks.forEach(BukkitTask::cancel);
        scheduledTasks.clear();
    }

    private void scheduleIntervals() {
        for (Automation automation : automations.values()) {
            if (!automation.enabled() || automation.trigger() != Trigger.SCHEDULED_INTERVAL || automation.intervalSeconds() <= 0) continue;
            long ticks = Math.max(1L, automation.intervalSeconds() * 20L);
            scheduledTasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> dispatch(Trigger.SCHEDULED_INTERVAL, null), ticks, ticks));
        }
    }

    private Automation parse(String id, ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("definition is missing");
        boolean enabled = section.getBoolean("enabled", true);
        String type = section.getString("trigger.type", "");
        Trigger trigger;
        try { trigger = Trigger.valueOf(type.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unknown trigger '" + type + "'"); }
        if (!triggers.supports(trigger)) throw new IllegalArgumentException("unsupported trigger '" + type + "'");
        List<Automation.ActionDefinition> definitions = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("actions")) {
            Map<String, Object> values = new HashMap<>();
            Object typeValue = raw.get("type");
            if (typeValue == null) throw new IllegalArgumentException("action type is missing");
            for (Map.Entry<?, ?> entry : raw.entrySet()) values.put(String.valueOf(entry.getKey()), entry.getValue());
            Automation.ActionDefinition definition = new Automation.ActionDefinition(String.valueOf(typeValue), values);
            validateAction(id, definition);
            definitions.add(definition);
        }
        Map<String, Object> conditions = new HashMap<>();
        ConfigurationSection conditionsSection = section.getConfigurationSection("conditions");
        if (conditionsSection != null) for (String key : conditionsSection.getKeys(false)) conditions.put(key, conditionsSection.get(key));
        long interval = section.getLong("interval-seconds", 0);
        if (trigger == Trigger.SCHEDULED_INTERVAL && interval <= 0) throw new IllegalArgumentException("scheduled automation requires interval-seconds > 0");
        return new Automation(id, enabled, trigger, conditions, definitions,
                section.getLong("cooldown-seconds", 0), interval);
    }

    private void validateAction(String automationId, Automation.ActionDefinition definition) {
        if (!actions.contains(definition.type())) throw new IllegalArgumentException("unknown action '" + definition.type() + "'");
        String type = definition.type().toUpperCase(Locale.ROOT);
        if (type.equals("CONSOLE_COMMAND") || type.equals("PLAYER_COMMAND")) require(automationId, definition, "command");
        if (type.equals("DISCORD_MESSAGE") || type.equals("DISCORD_EMBED")) require(automationId, definition, "channel");
        if (type.equals("GIVE_ITEMS")) {
            Material material = Material.matchMaterial(value(definition, "material"));
            if (material == null) throw new IllegalArgumentException("unknown material in automation '" + automationId + "'");
            if (integer(definition, "amount", 1) < 1) throw new IllegalArgumentException("item amount must be positive");
        }
        if (type.equals("GIVE_XP") && integer(definition, "amount", 0) < 0) throw new IllegalArgumentException("XP amount cannot be negative");
        if (type.equals("PLAY_SOUND") && sound(value(definition, "sound")) == null) throw new IllegalArgumentException("unknown sound '" + value(definition, "sound") + "'");
        if (type.equals("TELEPORT_PLAYER")) require(automationId, definition, "world");
        if (type.equals("TRIGGER_AUTOMATION") || type.equals("CUSTOM_JAVA_ACTION")) require(automationId, definition, "id");
        if (type.equals("ASSIGN_DISCORD_ROLE") || type.equals("REMOVE_DISCORD_ROLE")) {
            require(automationId, definition, "guild");
            require(automationId, definition, "user");
            require(automationId, definition, "role");
        }
    }

    private void require(String automationId, Automation.ActionDefinition definition, String key) {
        if (value(definition, key).isBlank()) throw new IllegalArgumentException("automation '" + automationId + "' requires action field '" + key + "'");
    }

    private boolean conditionsPass(Automation automation, AutomationContext context) {
        Object permission = automation.conditions().get("permission");
        if (permission instanceof String permissionName && !permissionName.isBlank()) {
            Player player = context.player();
            if (player == null || !player.hasPermission(permissionName)) return false;
        }
        Object firstJoin = automation.conditions().get("first_join");
        if (Boolean.TRUE.equals(firstJoin) && context.event() instanceof PlayerJoinEvent event && !event.getPlayer().hasPlayedBefore()) return true;
        return !Boolean.TRUE.equals(firstJoin);
    }

    private void registerBuiltInActions() {
        actions.register("CONSOLE_COMMAND", (context, definition) -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), context.replace(value(definition, "command"))));
        actions.register("PLAYER_COMMAND", (context, definition) -> { if (context.player() != null) context.player().performCommand(context.replace(value(definition, "command"))); });
        actions.register("BROADCAST_MESSAGE", (context, definition) -> Bukkit.broadcastMessage(context.replace(value(definition, "message"))));
        actions.register("GIVE_XP", (context, definition) -> { if (context.player() != null) context.player().giveExp(integer(definition, "amount", 0)); });
        actions.register("GIVE_ITEMS", (context, definition) -> { if (context.player() != null) { Material material = Material.matchMaterial(value(definition, "material")); if (material != null) context.player().getInventory().addItem(new ItemStack(material, integer(definition, "amount", 1))); } });
        actions.register("PLAY_SOUND", (context, definition) -> {
            Sound sound = sound(value(definition, "sound"));
            if (context.player() != null && sound != null) context.player().playSound(context.player().getLocation(), sound, 1f, 1f);
        });
        actions.register("TELEPORT_PLAYER", (context, definition) -> {
            org.bukkit.World world = Bukkit.getWorld(value(definition, "world"));
            if (context.player() == null) return;
            if (world == null) { logger.warning("TELEPORT_PLAYER: world '" + value(definition, "world") + "' is not loaded."); return; }
            context.player().teleport(world.getSpawnLocation());
        });
        actions.register("SAVE_CONFIGURATION", (context, definition) -> plugin.saveConfig());
        actions.register("UPDATE_DASHBOARD", (context, definition) -> dispatch(Trigger.DASHBOARD_REFRESH, context.event(), context.jda()));
        actions.register("TRIGGER_AUTOMATION", (context, definition) -> {
            String id = value(definition, "id");
            Automation target = automations.get(id);
            if (target == null) { logger.warning("TRIGGER_AUTOMATION: no automation named '" + id + "'."); return; }
            int depth = chainDepth.get();
            if (depth >= MAX_CHAIN_DEPTH) {
                logger.warning("TRIGGER_AUTOMATION: chain to '" + id + "' stopped after " + MAX_CHAIN_DEPTH + " levels (automations trigger each other in a loop?).");
                return;
            }
            chainDepth.set(depth + 1);
            try { executor.execute(target, context); }
            finally { chainDepth.set(depth); }
        });
        actions.register("CUSTOM_JAVA_ACTION", (context, definition) -> { Runnable action = customActions.get(value(definition, "id")); if (action != null) action.run(); else logger.warning("No registered custom Java action '" + value(definition, "id") + "'."); });
        actions.register("DISCORD_MESSAGE", this::sendDiscordMessage);
        actions.register("DISCORD_EMBED", this::sendDiscordEmbed);
        actions.register("LOG_AUDIT", (context, definition) -> logger.info("[AUTOMATION AUDIT] " + context.replace(value(definition, "message"))));
        actions.register("ASSIGN_DISCORD_ROLE", (context, definition) -> changeDiscordRole(context, definition, true));
        actions.register("REMOVE_DISCORD_ROLE", (context, definition) -> changeDiscordRole(context, definition, false));
    }

    private void sendDiscordMessage(AutomationContext context, Automation.ActionDefinition definition) {
        MessageChannel channel = discordTransport.resolve(context.jda(), "automation-channels", value(definition, "channel"));
        if (channel != null) channel.sendMessage(context.replace(value(definition, "message"))).queue();
    }

    private void sendDiscordEmbed(AutomationContext context, Automation.ActionDefinition definition) {
        MessageChannel channel = discordTransport.resolve(context.jda(), "automation-channels", value(definition, "channel"));
        if (channel == null) return;
        EmbedBuilder embed = new EmbedBuilder().setTitle(context.replace(value(definition, "title"))).setDescription(context.replace(value(definition, "description")));
        channel.sendMessageEmbeds(embed.build()).queue();
    }

    private void changeDiscordRole(AutomationContext context, Automation.ActionDefinition definition, boolean add) {
        if (context.jda() == null) return;
        String guildId = context.replace(value(definition, "guild"));
        String userId = context.replace(value(definition, "user"));
        String roleId = context.replace(value(definition, "role"));
        Guild guild = context.jda().getGuildById(guildId);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (guild == null || role == null || !userId.matches("\\d{17,20}")) {
            logger.warning("Discord role action requires valid guild, user, and role IDs.");
            return;
        }
        if (add) guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(userId), role).queue();
        else guild.removeRoleFromMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(userId), role).queue();
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        try {
            org.bukkit.NamespacedKey key = trimmed.contains(":")
                    ? org.bukkit.NamespacedKey.fromString(trimmed.toLowerCase(Locale.ROOT))
                    : org.bukkit.NamespacedKey.minecraft(trimmed.toLowerCase(Locale.ROOT).replace('_', '.'));
            Sound sound = key == null ? null : org.bukkit.Registry.SOUNDS.get(key);
            if (sound != null) return sound;
        } catch (RuntimeException ignored) { }
        try { return Sound.valueOf(trimmed.toUpperCase(Locale.ROOT)); } catch (RuntimeException ignored) { return null; }
    }

    private String value(Automation.ActionDefinition definition, String key) { return String.valueOf(definition.values().getOrDefault(key, "")); }
    private int integer(Automation.ActionDefinition definition, String key, int fallback) { try { return Integer.parseInt(value(definition, key)); } catch (NumberFormatException ignored) { return fallback; } }
}

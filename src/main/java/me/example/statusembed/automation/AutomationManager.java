package me.example.statusembed.automation;

import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageChannel;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import me.example.statusembed.StatusEmbed;
import me.example.statusembed.discord.DiscordTransport;

import java.awt.Color;
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
    private final StatusEmbed plugin;
    private final Logger logger;
    private final TriggerRegistry triggers = new TriggerRegistry();
    private final ActionRegistry actions = new ActionRegistry();
    private final CooldownManager cooldowns = new CooldownManager();
    private final Map<String, Automation> automations = new ConcurrentHashMap<>();
    private final Map<String, Runnable> customActions = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> dashboardDispatching = ThreadLocal.withInitial(() -> false);
    private final List<BukkitTask> scheduledTasks = new ArrayList<>();
    private final AutomationExecutor executor;
    private final DiscordTransport discordTransport;

    public AutomationManager(StatusEmbed plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.executor = new AutomationExecutor(logger, actions);
        this.discordTransport = new DiscordTransport(plugin);
        registerBuiltInActions();
    }

    public void load() {
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

    public void registerAction(String type, Action action) { actions.register(type, action); }
    public void registerCustomAction(String id, Runnable action) { customActions.put(id, action); }
    public Map<String, Automation> automations() { return Collections.unmodifiableMap(automations); }

    public void dispatch(Trigger trigger, Object event, JDA jda) {
        if (trigger == Trigger.DASHBOARD_REFRESH && dashboardDispatching.get()) return;
        if (trigger == Trigger.DASHBOARD_REFRESH) dashboardDispatching.set(true);
        try {
        for (Automation automation : automations.values()) {
            if (!automation.enabled() || automation.trigger() != trigger) continue;
            String actor = event instanceof org.bukkit.event.player.PlayerEvent e ? e.getPlayer().getUniqueId().toString() : "global";
            if (!cooldowns.tryAcquire(automation.id() + ":" + actor, automation.cooldownSeconds())) continue;
            AutomationContext context = new AutomationContext(plugin, event, jda);
            if (!conditionsPass(automation, context)) continue;
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
    @EventHandler public void onChat(AsyncPlayerChatEvent event) { dispatch(Trigger.ASYNC_PLAYER_CHAT, event); }
    @EventHandler public void onCommand(PlayerCommandPreprocessEvent event) { dispatch(Trigger.PLAYER_COMMAND_PREPROCESS, event); }
    @EventHandler public void onServerLoad(ServerLoadEvent event) { dispatch(Trigger.SERVER_STARTUP, event); }

    public void shutdown() {
        scheduledTasks.forEach(BukkitTask::cancel);
        scheduledTasks.clear();
        cooldowns.clear();
        automations.clear();
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
            definitions.add(new Automation.ActionDefinition(String.valueOf(typeValue), values));
        }
        Map<String, Object> conditions = new HashMap<>();
        ConfigurationSection conditionsSection = section.getConfigurationSection("conditions");
        if (conditionsSection != null) for (String key : conditionsSection.getKeys(false)) conditions.put(key, conditionsSection.get(key));
        return new Automation(id, enabled, trigger, conditions, definitions,
                section.getLong("cooldown-seconds", 0), section.getLong("interval-seconds", 0));
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
        actions.register("GIVE_ITEMS", (context, definition) -> { if (context.player() != null) context.player().getInventory().addItem(new ItemStack(Material.matchMaterial(value(definition, "material")), integer(definition, "amount", 1))); });
        actions.register("PLAY_SOUND", (context, definition) -> { if (context.player() != null) context.player().playSound(context.player().getLocation(), Sound.valueOf(value(definition, "sound").toUpperCase(Locale.ROOT)), 1f, 1f); });
        actions.register("TELEPORT_PLAYER", (context, definition) -> { if (context.player() != null) { String world = value(definition, "world"); if (Bukkit.getWorld(world) != null) context.player().teleport(Bukkit.getWorld(world).getSpawnLocation()); } });
        actions.register("SAVE_CONFIGURATION", (context, definition) -> plugin.saveConfig());
        actions.register("UPDATE_DASHBOARD", (context, definition) -> dispatch(Trigger.DASHBOARD_REFRESH, context.event(), context.jda()));
        actions.register("TRIGGER_AUTOMATION", (context, definition) -> { String id = value(definition, "id"); Automation a = automations.get(id); if (a != null) executor.execute(a, context); });
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
        if (add) guild.addRoleToMember(userId, role).queue();
        else guild.removeRoleFromMember(userId, role).queue();
    }

    private String value(Automation.ActionDefinition definition, String key) { return String.valueOf(definition.values().getOrDefault(key, "")); }
    private int integer(Automation.ActionDefinition definition, String key, int fallback) { try { return Integer.parseInt(value(definition, key)); } catch (NumberFormatException ignored) { return fallback; } }
}

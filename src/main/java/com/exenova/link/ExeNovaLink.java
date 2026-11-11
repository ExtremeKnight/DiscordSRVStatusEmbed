package com.exenova.link;

import com.exenova.link.automation.AutomationManager;
import com.exenova.link.commands.CommandRouter;
import com.exenova.link.config.ConfigService;
import com.exenova.link.scheduler.SchedulerService;
import com.exenova.link.storage.AuditLogService;
import com.exenova.link.storage.NotesRepository;
import com.exenova.link.storage.BackupService;
import com.exenova.link.verification.VerificationService;
import com.exenova.link.dashboard.DashboardService;
import com.exenova.link.reports.ReportStateStore;
import com.exenova.link.suggestions.SuggestionService;
import com.exenova.link.placeholder.DiscordStatusPlaceholders;
import com.exenova.link.discord.DiscordBot;
import com.exenova.link.discord.SlashCommandRouter;
import com.exenova.link.discord.SlashHandler;
import com.exenova.link.bridge.ChatBridge;
import com.exenova.link.console.ConsoleChannel;
import com.exenova.link.dashboard.ChannelStats;
import com.exenova.link.integration.Integrations;
import com.exenova.link.integration.PunishmentWatcher;
import com.exenova.link.linking.LinkCommands;
import com.exenova.link.linking.LinkManager;
import com.exenova.link.linking.RoleSync;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bukkit.permissions.Permissible;
import com.exenova.link.menu.PluginMenu;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ExeNovaLink extends JavaPlugin implements Listener {

    private AutomationManager automationManager;
    private ConfigService configService;
    private SchedulerService schedulerService;
    private AuditLogService auditLogService;

    private volatile boolean discordReady = false;
    private boolean serverStarted = false;
    /** JDA listener owned by this plugin instance; removed in onDisable so a reload never leaves a stale duplicate behind. */
    private ListenerAdapter jdaListener;
    private BukkitTask logPurgeTask;
    private static final int MAX_EMBED_FIELDS = 25;
    private static final Color EMBED_COLOR = Color.decode("#5865F2");
    private final ReportStateStore reportState = new ReportStateStore();
    private final Map<String, Long> discordCommandCooldowns = new ConcurrentHashMap<>();
    private NotesRepository notesRepository;
    private BackupService backupService;
    private VerificationService verificationService;
    private SuggestionService suggestionService;
    private DashboardService dashboardService;
    private final CommandRouter commandRouter = new CommandRouter();
    private DiscordBot discordBot;
    private SlashCommandRouter slashRouter;
    private Integrations integrations;
    private LinkManager linkManager;
    private LinkCommands linkCommands;
    private RoleSync roleSync;
    private ChatBridge chatBridge;
    private com.exenova.link.chat.ChatFormatter chatFormatter;
    private com.exenova.link.chat.channel.ChatChannels chatChannels;
    private ConsoleChannel consoleChannel;
    private ChannelStats channelStats;
    private com.exenova.link.watchdog.Watchdog watchdog;
    private com.exenova.link.chat.pm.PrivateMessages privateMessages;

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        configService = new ConfigService(this);
        configService.prepare();
        schedulerService = new SchedulerService(this);
        auditLogService = new AuditLogService(this);
        String qrFile = getConfig().getString("donations.gcash-qr", "gcash-qr.jpg");
        if (isSafeFileName(qrFile) && !new File(getDataFolder(), qrFile).exists()) {
            saveResource("gcash-qr.jpg", false);
            if (!"gcash-qr.jpg".equals(qrFile)) {
                getLogger().warning("donations.gcash-qr is custom; copy that image into the plugin data folder.");
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        automationManager = new AutomationManager(this);
        automationManager.load();
        Bukkit.getPluginManager().registerEvents(automationManager, this);
        notesRepository = new NotesRepository(this);
        backupService = new BackupService(this);
        verificationService = new VerificationService(this);
        suggestionService = new SuggestionService(this);
        dashboardService = new DashboardService(this);
        registerCommandHandlers();
        verifyRegisteredCommands();
        registerPlaceholderExpansion();
        integrations = new Integrations(this);
        integrations.enable();
        linkManager = new LinkManager(
                com.exenova.link.linking.store.LinkStoreFactory.create(
                        getDataFolder(), getConfig().getConfigurationSection("linking.storage"), getLogger()),
                getLogger());
        linkManager.setFallback(integrations::discordSrvLinkedId, integrations::discordSrvLinkedUuid);
        linkManager.load();
        roleSync = new RoleSync(this);
        linkCommands = new LinkCommands(this);
        chatBridge = new ChatBridge(this);
        chatChannels = new com.exenova.link.chat.channel.ChatChannels(this);
        privateMessages = new com.exenova.link.chat.pm.PrivateMessages(this);
        chatFormatter = new com.exenova.link.chat.ChatFormatter(this, chatChannels);
        consoleChannel = new ConsoleChannel(this);
        channelStats = new ChannelStats(this);
        watchdog = new com.exenova.link.watchdog.Watchdog(this);
        Bukkit.getPluginManager().registerEvents(linkCommands, this);
        Bukkit.getPluginManager().registerEvents(privateMessages, this);
        Bukkit.getPluginManager().registerEvents(chatBridge, this);
        Bukkit.getPluginManager().registerEvents(chatFormatter, this);
        Bukkit.getPluginManager().registerEvents(chatChannels, this);
        if (chatFormatter.enabled()) getLogger().info("In-game chat formatter is enabled.");
        Bukkit.getPluginManager().registerEvents(new PunishmentWatcher(this), this);

        discordBot = new DiscordBot(this);
        slashRouter = new SlashCommandRouter(getLogger());
        slashRouter.registerAll(this);
        slashRouter.registerAll(linkCommands);
        discordBot.addListener(slashRouter);
        discordBot.addListener(chatBridge);
        discordBot.addListener(consoleChannel);
        discordBot.addListener(linkCommands);
        discordBot.start(this::getSlashCommands, this::onDiscordReady);

        startBackgroundTasks();

        getLogger().info("ExeNovaLink has been enabled!");
    }

    /**
     * Starts every repeating task whose schedule depends on config.yml. Kept
     * separate from onEnable so a config reload can stop and restart them
     * with the new intervals instead of leaving the old schedule running.
     */
    private void startBackgroundTasks() {
        scheduleLogPurgeTask();
        scheduleStatusDashboard();
        scheduleAutoBackups();
        schedulerService.repeatSync(20L * 60L, 20L * 60L, this::expireReportState);
        schedulerService.repeatAsync(20L * 60L, 20L * 60L, this::pruneCooldowns);
        if (getConfig().getBoolean("bot-presence.enabled", true)) {
            long presenceMinutes = Math.max(1, getConfig().getLong("bot-presence.interval-minutes", 5));
            schedulerService.repeatAsync(20L * 10L, 20L * 60L * presenceMinutes, this::updateBotPresence);
        }
        schedulerService.repeatAsync(40L, 40L, () -> { if (consoleChannel != null) consoleChannel.flush(); });
        long roleSyncMinutes = Math.max(1, getConfig().getLong("role-sync.interval-minutes", 15));
        schedulerService.repeatAsync(20L * 60L, 20L * 60L * roleSyncMinutes, () -> { if (roleSync != null && isDiscordReady()) roleSync.syncOnline(); });
        schedulerService.repeatAsync(20L * 30L, channelStats.intervalTicks(), () -> { if (isDiscordReady()) channelStats.update(); });
        if (watchdog.enabled()) schedulerService.repeatSync(watchdog.intervalTicks(), watchdog.intervalTicks(), watchdog::check);
    }

    private void stopBackgroundTasks() {
        if (schedulerService != null) schedulerService.cancelAll();
        if (dashboardService != null) dashboardService.stop();
        logPurgeTask = null;
    }

    /**
     * Full reload used by /discordstatusreload and /discordstatusrestore:
     * re-reads config.yml, re-validates it, reloads automations and restarts
     * scheduled tasks so interval changes take effect without a restart.
     */
    private void reloadPlugin() {
        stopBackgroundTasks();
        reloadConfig();
        configService.prepare();
        if (automationManager != null) automationManager.reload();
        if (chatChannels != null) chatChannels.reload();
        startBackgroundTasks();
        if (discordBot != null && discordBot.jda() == null) {
            // No bot yet (e.g. the token was only just added): log in now.
            discordBot.start(this::getSlashCommands, this::onDiscordReady);
        } else if (isDiscordReady()) {
            discordBot.refreshCommands(getSlashCommands());
            chatBridge.connect();
            consoleChannel.start();
            publishChangelog();
            updateBotPresence();
        }
    }

    /**
     * First start after renaming from DiscordSRVStatusEmbed: copy the old
     * data folder (config, notes, backups, audit log, QR image) so existing
     * servers keep every setting. The old folder is left untouched.
     */
    private void migrateLegacyDataFolder() {
        File legacy = new File(getDataFolder().getParentFile(), "DiscordSRVStatusEmbed");
        if (getDataFolder().exists() || !legacy.isDirectory()) return;
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(legacy.toPath())) {
            for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) paths::iterator) {
                java.nio.file.Path target = getDataFolder().toPath().resolve(legacy.toPath().relativize(source));
                if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(target);
                else java.nio.file.Files.copy(source, target);
            }
            getLogger().info("Migrated settings from plugins/DiscordSRVStatusEmbed. You can delete that folder and the old jar once everything works.");
        } catch (IOException exception) {
            getLogger().warning("Could not migrate plugins/DiscordSRVStatusEmbed: " + exception.getMessage());
        }
    }

    // ---- accessors used by modules ----

    public Integrations integrations() { return integrations; }
    public LinkManager links() { return linkManager; }
    public RoleSync roleSync() { return roleSync; }
    public AutomationManager automations() { return automationManager; }

    /** True if {@code subject} has {@code exenova.<node>} or the legacy {@code discordstatus.<node>}. */
    public static boolean can(Permissible subject, String node) {
        return subject.hasPermission("exenova." + node) || subject.hasPermission("discordstatus." + node);
    }

    /** Posts an embed to the channel whose ID is stored at {@code configPath}; silently skips if unset. */
    public void postToChannel(String configPath, MessageEmbed embed) {
        JDA jda = jda();
        String id = getConfig().getString(configPath, "");
        TextChannel channel = jda == null || !id.matches("\\d{17,20}") ? null : jda.getTextChannelById(id);
        if (channel != null) channel.sendMessageEmbeds(embed).queue(null,
                failure -> getLogger().warning("Could not post to " + configPath + ": " + failure.getMessage()));
    }

    /** As {@link #postToChannel} but with leading message content (e.g. a role ping). */
    public void postToChannelWithContent(String configPath, String content, MessageEmbed embed) {
        JDA jda = jda();
        String id = getConfig().getString(configPath, "");
        TextChannel channel = jda == null || !id.matches("\\d{17,20}") ? null : jda.getTextChannelById(id);
        if (channel != null) channel.sendMessage(content).addEmbeds(embed).queue(null,
                failure -> getLogger().warning("Could not post to " + configPath + ": " + failure.getMessage()));
    }

    /** The plugin's own JDA instance, or null while the bot is offline. */
    public JDA jda() { return discordBot == null ? null : discordBot.jda(); }

    public boolean isDiscordReady() { return discordReady && discordBot != null && discordBot.isReady(); }

    /** Runs on the main thread once our bot has logged in. */
    private void onDiscordReady() {
        discordReady = true;
        getLogger().info("Discord bot is online as " + jda().getSelfUser().getName() + ".");
        registerJdaMessageListenerIfNeeded();
        setupVerificationPanel();
        publishChangelog();
        logStartupDiagnostics();
        updateBotPresence();
        chatBridge.connect();
        consoleChannel.start();
        if (serverStarted) chatBridge.sendServerNotice(true, false);

        // If server has already started, send the embed now
        if (serverStarted && getConfig().getBoolean("server-start.enabled")) {
            sendEmbed(
                    getConfig().getString("server-start.title"),
                    getConfig().getString("server-start.description"),
                    getConfig().getString("server-start.color"),
                    getConfig().getConfigurationSection("server-start.author"),
                    getConfig().getConfigurationSection("server-start.footer"),
                    getConfig().getBoolean("server-start.timestamp"),
                    false
            );
        }
    }

    @EventHandler
    public void onServerStart(ServerLoadEvent event) {
        // ServerLoadEvent also fires after a Bukkit /reload; only a real
        // startup should announce "server started" in Discord.
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
        serverStarted = true;
        if (isDiscordReady()) chatBridge.sendServerNotice(true, false);

        if (!getConfig().getBoolean("server-start.enabled"))
            return;

        // Only send if the bot is already online, otherwise onDiscordReady() sends it
        if (discordReady) {
            sendEmbed(
                    getConfig().getString("server-start.title"),
                    getConfig().getString("server-start.description"),
                    getConfig().getString("server-start.color"),
                    getConfig().getConfigurationSection("server-start.author"),
                    getConfig().getConfigurationSection("server-start.footer"),
                    getConfig().getBoolean("server-start.timestamp"),
                    false
            );
        } else {
            getLogger().info("Server started but the Discord bot is not online yet; the start embed will be sent once it connects.");
        }
    }

    @Override
    public void onDisable() {
        if (automationManager != null) {
            automationManager.dispatch(com.exenova.link.automation.Trigger.SERVER_SHUTDOWN, null,
                    discordReady ? jda() : null);
            automationManager.shutdown();
            automationManager = null;
        }
        stopBackgroundTasks();
        if (consoleChannel != null) consoleChannel.stop();
        if (chatBridge != null && isDiscordReady()) chatBridge.sendServerNotice(false, true);
        if (chatBridge != null) chatBridge.unregister();
        if (chatFormatter != null) chatFormatter.unregister();
        if (chatChannels != null) chatChannels.unregister();
        if (linkManager != null) linkManager.close();
        if (integrations != null) integrations.disable();
        schedulerService = null;
        dashboardService = null;
        unregisterJdaMessageListener();
        reportState.clearAll();
        auditLogService = null;
        notesRepository = null;
        backupService = null;
        verificationService = null;
        suggestionService = null;

        if (getConfig().getBoolean("server-stop.enabled")) {
            if (!isDiscordReady()) {
                getLogger().info("Skipping server-stop embed: the Discord bot is not online.");
            } else {
                // onDisable runs during JVM shutdown, so the request is sent
                // with a bounded blocking call before the bot disconnects.
                sendEmbed(
                        getConfig().getString("server-stop.title"),
                        getConfig().getString("server-stop.description"),
                        getConfig().getString("server-stop.color"),
                        getConfig().getConfigurationSection("server-stop.author"),
                        getConfig().getConfigurationSection("server-stop.footer"),
                        getConfig().getBoolean("server-stop.timestamp"),
                        true
                );
            }
        }

        discordReady = false;
        if (discordBot != null) {
            discordBot.shutdown();
            discordBot = null;
        }
    }

    /**
     * Registers each in-game command's logic with {@link CommandRouter} once,
     * at startup. Behavior is unchanged from before this refactor - this just
     * moves each branch out of one large if/else chain in {@link #onCommand}
     * so individual commands can be found, read, and tested independently.
     */
    private void registerCommandHandlers() {
        commandRouter.register("discordstatusreload", (sender, cmd, label, args) -> {
            if (!can(sender, "reload")) {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            reloadPlugin();
            sender.sendMessage("§aExeNovaLink configuration, automations and schedules reloaded!");
            audit("RELOAD actor=" + sender.getName());
            return true;
        });

        commandRouter.register("dsreport", (sender, cmd, label, args) -> {
            if (!getConfig().getBoolean("commands.report.enabled", true)) {
                sender.sendMessage("§cReporting is currently disabled.");
                return true;
            }
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            Player reporter = (Player) sender;
            if (args.length == 1) {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null || target.equals(reporter)) {
                    sender.sendMessage("§cThat player is not online. Use /dsreport to choose an online player.");
                    return true;
                }
                reportState.begin(reporter.getUniqueId(), target.getUniqueId(), System.currentTimeMillis());
                openReportReasonMenu(reporter);
            } else openReportPlayerMenu(reporter);
            return true;
        });

        commandRouter.register("dsnote", (sender, cmd, label, args) -> {
            if (!can(sender, "notes")) { sender.sendMessage("§cYou do not have permission to manage staff notes."); return true; }
            handleNoteCommand(sender, args);
            return true;
        });

        commandRouter.register("dswatch", (sender, cmd, label, args) -> {
            if (!can(sender, "watchlist")) { sender.sendMessage("§cYou do not have permission to manage the watchlist."); return true; }
            handleWatchCommand(sender, args);
            return true;
        });

        commandRouter.register("discordstatusdiagnostics", (sender, cmd, label, args) -> {
            if (!can(sender, "config")) { sender.sendMessage("§cYou do not have permission to view diagnostics."); return true; }
            sendDiagnostics(sender);
            return true;
        });

        commandRouter.register("discordstatusconfig", (sender, cmd, label, args) -> {
            if (!can(sender, "config")) { sender.sendMessage("§cYou do not have permission to edit plugin settings."); return true; }
            if (!(sender instanceof Player)) { sender.sendMessage("This configuration menu can only be opened in-game."); return true; }
            openConfigMenu((Player) sender);
            return true;
        });

        commandRouter.register("discordstatusbackup", (sender, cmd, label, args) -> {
            if (!can(sender, "config")) { sender.sendMessage("§cYou do not have permission to back up settings."); return true; }
            backupConfig(sender);
            return true;
        });

        commandRouter.register("discordstatusrestore", (sender, cmd, label, args) -> {
            if (!can(sender, "config")) { sender.sendMessage("§cYou do not have permission to restore settings."); return true; }
            if (args.length != 1) { sender.sendMessage("§eUsage: /discordstatusrestore <backup-file.yml>"); return true; }
            restoreConfig(sender, args[0]);
            return true;
        });
    }

    /** /exenova subcommands, mapped onto the handlers above (shared with the legacy command names). */
    private static final Map<String, String> EXENOVA_SUBCOMMANDS = Map.of(
            "reload", "discordstatusreload",
            "diagnostics", "discordstatusdiagnostics",
            "diag", "discordstatusdiagnostics",
            "config", "discordstatusconfig",
            "backup", "discordstatusbackup",
            "restore", "discordstatusrestore");

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "channel": return chatChannels.onCommand(sender, args);
            case "msg": return privateMessages.onMsg(sender, args);
            case "reply": return privateMessages.onReply(sender, args);
            case "ignore": return privateMessages.onIgnore(sender, args);
            case "socialspy": return privateMessages.onSocialSpy(sender, args);
            case "link": return linkCommands.onLinkCommand(sender, args);
            case "unlink": return linkCommands.onUnlinkCommand(sender);
            case "discord": return linkCommands.onDiscordCommand(sender);
            case "exenova": return onExeNovaCommand(sender, label, args);
            default: return commandRouter.route(sender, cmd, label, args);
        }
    }

    private boolean onExeNovaCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(java.util.Locale.ROOT);
        String[] rest = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (EXENOVA_SUBCOMMANDS.containsKey(sub)) {
            org.bukkit.command.PluginCommand legacy = getCommand(EXENOVA_SUBCOMMANDS.get(sub));
            return legacy != null && commandRouter.route(sender, legacy, label, rest);
        }
        if (sub.equals("integrations")) {
            if (!can(sender, "config")) { sender.sendMessage("§cYou do not have permission to do that."); return true; }
            sender.sendMessage("§6Active integrations: §f" + (integrations.activeNames().isEmpty() ? "none" : String.join(", ", integrations.activeNames())));
            return true;
        }
        if (sub.equals("status")) {
            sender.sendMessage("§9§lExeNovaLink §7v" + getPluginMeta().getVersion() + " §8• §7by ExeNova");
            sender.sendMessage("§7Discord bot: " + (isDiscordReady() ? "§aonline as " + jda().getSelfUser().getName() : discordBot != null && !discordBot.token().isEmpty() ? "§econnecting / offline" : "§cno token set (discord.bot-token)"));
            sender.sendMessage("§7Linked accounts: §f" + linkManager.size() + " §8• §7Chat bridge: " + (getConfig().getBoolean("bridge.enabled") ? "§aon" : "§coff")
                    + " §8• §7Console: " + (getConfig().getBoolean("console.enabled") ? "§aon" : "§coff"));
            if (can(sender, "config")) sender.sendMessage("§7Subcommands: §freload, diagnostics, config, backup, restore <file>, integrations");
            return true;
        }
        sender.sendMessage("§eUsage: /" + label + " <status|reload|diagnostics|config|backup|restore|integrations>");
        return true;
    }
    
    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("discordstatusreload")) {
            return java.util.Collections.emptyList();
        }
        String name = cmd.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("exenova")) {
            if (args.length == 1) {
                List<String> subs = new ArrayList<>(List.of("status"));
                if (can(sender, "config")) subs.addAll(List.of("reload", "diagnostics", "config", "backup", "restore", "integrations"));
                return matching(subs, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("restore") && can(sender, "config") && backupService != null) {
                return matching(backupService.list().stream().map(File::getName).collect(java.util.stream.Collectors.toList()), args[1]);
            }
            return java.util.Collections.emptyList();
        }
        switch (name) {
            case "channel":
                return args.length == 1 && chatChannels != null ? chatChannels.complete(sender, args[0]) : java.util.Collections.emptyList();
            case "msg":
            case "ignore":
                return args.length == 1 ? onlinePlayerNames(sender, args[0]) : java.util.Collections.emptyList();
            case "dsreport":
                return args.length == 1 ? onlinePlayerNames(sender, args[0]) : java.util.Collections.emptyList();
            case "dsnote":
                if (!can(sender, "notes")) return java.util.Collections.emptyList();
                if (args.length == 1) return matching(Arrays.asList("add", "list", "clear"), args[0]);
                return args.length == 2 ? onlinePlayerNames(sender, args[1]) : java.util.Collections.emptyList();
            case "dswatch":
                if (!can(sender, "watchlist")) return java.util.Collections.emptyList();
                if (args.length == 1) return matching(Arrays.asList("add", "remove", "list"), args[0]);
                if (args.length == 2 && args[0].equalsIgnoreCase("remove")) return matching(getConfig().getStringList("watchlist.players"), args[1]);
                return args.length == 2 && args[0].equalsIgnoreCase("add") ? onlinePlayerNames(sender, args[1]) : java.util.Collections.emptyList();
            case "discordstatusrestore":
                if (!can(sender, "config") || args.length != 1 || backupService == null) return java.util.Collections.emptyList();
                return matching(backupService.list().stream().map(File::getName).collect(java.util.stream.Collectors.toList()), args[0]);
            default:
                return java.util.Collections.emptyList();
        }
    }

    private List<String> onlinePlayerNames(CommandSender sender, String prefix) {
        return matching(Bukkit.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName).collect(java.util.stream.Collectors.toList()), prefix);
    }

    private static List<String> matching(Collection<String> options, String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(java.util.Locale.ROOT).startsWith(lower)).collect(java.util.stream.Collectors.toList());
    }

    // === Slash commands (routed by SlashCommandRouter via @SlashHandler) ===

    private void verifyRegisteredCommands() {
        for (String name : Arrays.asList("exenova", "link", "unlink", "discord", "dsreport", "dsnote", "dswatch", "discordstatusdiagnostics", "discordstatusconfig", "discordstatusbackup", "discordstatusrestore", "discordstatusreload")) {
            if (getCommand(name) == null) getLogger().severe("Command /" + name + " was not registered. Install the current JAR and fully restart the server.");
        }
    }

    /**
     * Registers %exenovalink_*% placeholders with PlaceholderAPI if,
     * and only if, PlaceholderAPI is actually installed. PlaceholderAPI is a
     * soft-dependency: the class extending PlaceholderExpansion is only
     * touched inside this method, so nothing here loads (and nothing
     * throws NoClassDefFoundError) on a server without it.
     */
    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping %exenovalink_*% placeholder registration.");
            return;
        }
        try {
            new DiscordStatusPlaceholders(this, "exenovalink").register();
            // Legacy identifier so existing %discordstatusembed_*% scoreboards keep working.
            new DiscordStatusPlaceholders(this, "discordstatusembed").register();
            getLogger().info("Registered PlaceholderAPI expansion: %exenovalink_*%");
        } catch (Throwable t) {
            getLogger().warning("Could not register the PlaceholderAPI expansion: " + t.getMessage());
        }
    }

    public List<CommandData> getSlashCommands() {
        List<CommandData> commands = new ArrayList<>();
        if (getConfig().getBoolean("server-info.enabled", true)) {
            commands.add(Commands.slash("help", "Show all available server commands"));
            commands.add(Commands.slash("ip", "Get the Java Edition server address"));
            commands.add(Commands.slash("server", "Get the Java Edition server address"));
            commands.add(Commands.slash("java", "Get the Java Edition server address"));
            commands.add(Commands.slash("bedrock", "Get the Bedrock Edition server address"));
            commands.add(Commands.slash("bedrockip", "Get the Bedrock Edition address and port"));
            commands.add(Commands.slash("version", "Get the Minecraft server version"));
            commands.add(Commands.slash("playerlist", "List currently online players"));
            commands.add(Commands.slash("players", "List currently online players"));
            commands.add(Commands.slash("status", "Show the server status"));
            commands.add(Commands.slash("motd", "Show the server MOTD"));
            commands.add(Commands.slash("ping", "Show bot latency"));
        }
        commands.add(Commands.slash("purge", "Delete recent messages")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Number of messages to delete", true).setRequiredRange(1, 100)));
        commands.add(Commands.slash("prefix", "Show or change the legacy command prefix")
                .addOptions(new OptionData(OptionType.STRING, "value", "New prefix (owner only)", false)));
        commands.add(Commands.slash("suggest", "Learn how to submit a suggestion"));
        commands.add(Commands.slash("gcash", "Show donation details"));
        commands.add(Commands.slash("faq", "Show frequently asked questions"));
        commands.add(Commands.slash("staff", "Show the server staff team"));
        commands.add(Commands.slash("report", "Learn how to report a player"));
        commands.add(Commands.slash("playtime", "Show a player's playtime")
                .addOptions(new OptionData(OptionType.STRING, "player", "Minecraft player name", true)));
        commands.add(Commands.slash("seen", "Show when a player was last seen")
                .addOptions(new OptionData(OptionType.STRING, "player", "Minecraft player name", true)));
        commands.add(Commands.slash("stats", "Show player statistics")
                .addOptions(new OptionData(OptionType.STRING, "player", "Minecraft player name", true)));
        commands.add(Commands.slash("leaderboard", "Show the configured leaderboard"));
        if (linkCommands != null) commands.addAll(linkCommands.slashCommands());
        commands.add(Commands.slash("profile", "Show a player profile")
                .addOptions(new OptionData(OptionType.STRING, "player", "Minecraft player name", true)));
        return commands;
    }

    @SlashHandler("java")
    public void onJavaSlashCommand(SlashCommandInteractionEvent event) {
        event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue();
    }

    @SlashHandler("ip")
    public void onIpSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue(); }

    @SlashHandler("server")
    public void onServerSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue(); }

    @SlashHandler("help")
    public void onHelpSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildHelpEmbed()).queue(); }

    @SlashHandler("bedrock")
    public void onBedrockSlashCommand(SlashCommandInteractionEvent event) {
        event.getHook().sendMessageEmbeds(buildBedrockInfoEmbed()).queue();
    }

    @SlashHandler("bedrockip")
    public void onBedrockIpSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildBedrockInfoEmbed()).queue(); }

    @SlashHandler("version")
    public void onVersionSlashCommand(SlashCommandInteractionEvent event) {
        event.getHook().sendMessageEmbeds(buildVersionEmbed()).queue();
    }

    @SlashHandler("playerlist")
    public void onPlayerListSlashCommand(SlashCommandInteractionEvent event) {
        fetchPlayerListEmbedAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue());
    }

    @SlashHandler("players")
    public void onPlayersSlashCommand(SlashCommandInteractionEvent event) { fetchPlayerListEmbedAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue()); }

    @SlashHandler("status")
    public void onStatusSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildStatusEmbed()).queue(); }

    @SlashHandler("motd")
    public void onMotdSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildMotdEmbed()).queue(); }

    @SlashHandler("ping")
    public void onPingSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildPingEmbed()).queue(); }

    @SlashHandler("purge")
    public void onPurgeSlashCommand(SlashCommandInteractionEvent event) {
        int amount = event.getOption("amount") == null ? 0 : (int) event.getOption("amount").getAsLong();
        if (!isDiscordOwner(event.getUser().getId())) {
            event.getHook().sendMessage("You are not authorized to use this command.").queue();
            return;
        }
        purgeChannel(event.getChannel(), amount, message -> event.getHook().sendMessage(message).queue());
    }

    @SlashHandler("prefix")
    public void onPrefixSlashCommand(SlashCommandInteractionEvent event) {
        if (event.getOption("value") == null) {
            event.getHook().sendMessage("Current prefix: `" + getConfig().getString("server-info.prefix", "!") + "`").queue();
            return;
        }
        if (!isDiscordOwner(event.getUser().getId())) {
            event.getHook().sendMessage("You are not authorized to change the prefix.").queue();
            return;
        }
        event.getHook().sendMessage(setPrefix(event.getOption("value").getAsString())).queue();
    }

    @SlashHandler("suggest")
    public void onSuggestSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildSuggestionPromptEmbed()).queue(); }

    @SlashHandler("gcash")
    public void onGcashSlashCommand(SlashCommandInteractionEvent event) {
        File qrCode = getDonationQrFile();
        if (qrCode.isFile()) event.getHook().sendFiles(FileUpload.fromData(qrCode, qrCode.getName())).addEmbeds(buildDonationEmbed()).queue();
        else event.getHook().sendMessageEmbeds(buildDonationEmbed()).queue();
    }

    @SlashHandler("faq")
    public void onFaqSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildFaqEmbed()).queue(); }

    @SlashHandler("staff")
    public void onStaffSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildStaffEmbed()).queue(); }

    @SlashHandler("report")
    public void onReportSlashCommand(SlashCommandInteractionEvent event) { event.getHook().sendMessageEmbeds(buildReportInfoEmbed()).queue(); }

    @SlashHandler("leaderboard")
    public void onLeaderboardSlashCommand(SlashCommandInteractionEvent event) { fetchLeaderboardAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue()); }

    @SlashHandler("profile")
    public void onProfileSlashCommand(SlashCommandInteractionEvent event) {
        event.getHook().sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Player Profile")
                .setDescription("Use `!profile <player>` to view a full player profile.").setColor(EMBED_COLOR).build()).queue();
    }

    @SlashHandler("playtime")
    public void onPlaytimeSlashCommand(SlashCommandInteractionEvent event) { sendSlashPlayerEmbed(event, "playtime"); }

    @SlashHandler("seen")
    public void onSeenSlashCommand(SlashCommandInteractionEvent event) { sendSlashPlayerEmbed(event, "seen"); }

    @SlashHandler("stats")
    public void onStatsSlashCommand(SlashCommandInteractionEvent event) { sendSlashPlayerEmbed(event, "stats"); }

    private void sendSlashPlayerEmbed(SlashCommandInteractionEvent event, String type) {
        net.dv8tion.jda.api.interactions.commands.OptionMapping option = event.getOption("player");
        String requested = option == null ? "" : sanitizeUserInput(option.getAsString(), 16);
        if (!requested.matches("[A-Za-z0-9_]{3,16}")) {
            event.getHook().sendMessage("Enter a valid Minecraft player name.").setEphemeral(true).queue();
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            OfflinePlayer player = resolveOfflinePlayer(requested);
            if (player == null || !player.hasPlayedBefore()) {
                event.getHook().sendMessage("Player `" + requested + "` has never joined ExeSMP.").setEphemeral(true).queue();
                return;
            }
            net.dv8tion.jda.api.entities.MessageEmbed embed = switch (type) {
                case "playtime" -> buildPlaytimeEmbed(player);
                case "seen" -> buildSeenEmbed(player);
                case "stats" -> buildStatsEmbed(player);
                default -> new EmbedBuilder().setTitle("Player Profile").setDescription("Use the prefix profile command for the complete profile.").setColor(EMBED_COLOR).build();
            };
            event.getHook().sendMessageEmbeds(embed).queue();
        });
    }

    // === "!" prefix chat commands, e.g. "!java" ===
    // Slash commands are routed above; the "!"-prefixed
    // text commands are handled with our own JDA message listener registered
    // directly on our bot's JDA instance.

    private void unregisterJdaMessageListener() {
        if (jdaListener == null) return;
        try {
            JDA jda = jda();
            if (jda != null) jda.removeEventListener(jdaListener);
        } catch (Exception exception) {
            getLogger().warning("Could not unregister the Discord message listener: " + exception.getMessage());
        }
        jdaListener = null;
    }

    private synchronized void registerJdaMessageListenerIfNeeded() {
        if (jdaListener != null) {
            return;
        }

        JDA jda = jda();
        if (jda == null) {
            return;
        }

        jdaListener = new ListenerAdapter() {
            @Override
            public void onMessageReceived(MessageReceivedEvent event) {
                if (event.getAuthor().isBot()) {
                    return;
                }

                if (relayAnnouncement(event)) {
                    return;
                }

                if (!getConfig().getBoolean("server-info.enabled", true)) {
                    return;
                }

                String prefix = getConfig().getString("server-info.prefix", "!");
                if (prefix == null || prefix.isEmpty()) {
                    return;
                }

                String raw = event.getMessage().getContentRaw();
                if (!raw.startsWith(prefix)) {
                    return;
                }

                String withoutPrefix = raw.substring(prefix.length()).trim();
                if (withoutPrefix.isEmpty()) {
                    return;
                }

                String command = withoutPrefix.split("\\s+")[0].toLowerCase();
                MessageChannel channel = event.getChannel();
                if (!tryConsumeDiscordCommand(event.getAuthor().getId())) {
                    channel.sendMessageEmbeds(new EmbedBuilder().setTitle("⌛ Slow down")
                            .setDescription("Please wait a few seconds before using another command.")
                            .setColor(Color.decode("#FEE75C")).build()).queue();
                    return;
                }

                switch (command) {
                    case "help":
                        channel.sendMessageEmbeds(buildHelpEmbed()).queue();
                        break;
                    case "ip":
                    case "server":
                    case "java":
                        channel.sendMessageEmbeds(buildJavaInfoEmbed()).queue();
                        break;
                    case "bedrock":
                    case "bedrockip":
                        channel.sendMessageEmbeds(buildBedrockInfoEmbed()).queue();
                        break;
                    case "version":
                        channel.sendMessageEmbeds(buildVersionEmbed()).queue();
                        break;
                    case "playerlist":
                    case "players":
                        fetchPlayerListEmbedAsync(embed -> channel.sendMessageEmbeds(embed).queue());
                        break;
                    case "status":
                        channel.sendMessageEmbeds(buildStatusEmbed()).queue();
                        break;
                    case "motd":
                        channel.sendMessageEmbeds(buildMotdEmbed()).queue();
                        break;
                    case "ping":
                        channel.sendMessageEmbeds(buildPingEmbed()).queue();
                        break;
                    case "purge":
                        if (!isDiscordOwner(event.getAuthor().getId())) { channel.sendMessage("You are not authorized to use this command.").queue(); break; }
                        purgeChannel(channel, parseAmount(withoutPrefix.substring(command.length()).trim()), message -> channel.sendMessage(message).queue());
                        break;
                    case "prefix":
                        handlePrefixTextCommand(channel, event.getAuthor().getId(), withoutPrefix.substring(command.length()).trim());
                        break;
                    case "suggest":
                        handleSuggestion(channel, withoutPrefix.substring(command.length()).trim());
                        break;
                    case "gcash":
                        sendDonationEmbed(channel);
                        break;
                    case "faq":
                        channel.sendMessageEmbeds(buildFaqEmbed()).queue();
                        break;
                    case "staff":
                        channel.sendMessageEmbeds(buildStaffEmbed()).queue();
                        break;
                    case "report":
                        channel.sendMessageEmbeds(buildReportInfoEmbed()).queue();
                        break;
                    case "playtime":
                    case "seen":
                    case "stats":
                        fetchPlayerStatisticAsync(channel, command, withoutPrefix.substring(command.length()).trim());
                        break;
                    case "leaderboard":
                        fetchLeaderboardAsync(embed -> channel.sendMessageEmbeds(embed).queue(), withoutPrefix.substring(command.length()).trim());
                        break;
                    case "profile":
                        fetchPlayerProfileAsync(channel, withoutPrefix.substring(command.length()).trim());
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onButtonInteraction(ButtonInteractionEvent event) {
                handleDiscordButton(event);
            }
        };
        jda.addEventListener(jdaListener);
        getLogger().info("Registered \"" + getConfig().getString("server-info.prefix", "!") + "\" prefix command listener.");
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildJavaInfoEmbed() {
        String ip = getConfig().getString("server-info.java-ip", "not configured");
        int port = getConfig().getInt("server-info.java-port", 25565);
        String address = port == 25565 ? ip : (ip + ":" + port);

        return new EmbedBuilder()
                .setTitle("◆ Java Edition")
                .setDescription("**Address:** `" + address + "`")
                .setColor(EMBED_COLOR)
                .build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildBedrockInfoEmbed() {
        if (!getConfig().getBoolean("server-info.bedrock-enabled", false)) {
            return new EmbedBuilder()
                    .setTitle("◆ Bedrock Edition")
                    .setDescription("Bedrock Edition is not currently available on this server.")
                    .setColor(Color.decode("#ED4245"))
                    .build();
        }

        String ip = getConfig().getString("server-info.bedrock-ip", "not configured");
        int port = getConfig().getInt("server-info.bedrock-port", 19132);

        return new EmbedBuilder()
                .setTitle("◆ Bedrock Edition")
                .setDescription("**Address:** `" + ip + "`\n**Port:** `" + port + "`")
                .setColor(EMBED_COLOR)
                .build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildVersionEmbed() {
        String configured = getConfig().getString("server-info.version", "");
        String version = (configured != null && !configured.isEmpty())
                ? configured
                : Bukkit.getServer().getBukkitVersion();

        return new EmbedBuilder()
                .setTitle("◆ Minecraft Version")
                .setDescription("`" + version + "`")
                .setColor(EMBED_COLOR)
                .build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildHelpEmbed() {
        return new EmbedBuilder()
                .setTitle("❖ ExeSMP Command Guide")
                .setDescription("Use the `!` prefix or Discord slash commands. Every command returns a polished embed.")
                .addField("🖥 Server Info", "`!ip`, `!server`, `!java` — Java address\n`!bedrock`, `!bedrockip` — Bedrock address\n`!status` — server availability\n`!motd`, `!version`, `!ping`", false)
                .addField("👥 Players", "`!players` / `!playerlist` — online players, ping and avatar\n`!profile <player>` — complete player overview\n`!playtime <player>`, `!seen <player>`, `!stats <player>`\n`!leaderboard [playtime|kills|deaths|mobs|mined]`", false)
                .addField("💡 Community", "`!suggest <idea>` — submit an idea for voting\n`!faq`, `!staff` — configurable server information\n`!report` — reporting instructions; use `/dsreport` in-game", false)
                .addField("🔗 Account", "`/link` — link your Discord and Minecraft accounts (or `/link` in game)\n`/unlink` — remove the link", false)
                .addField("💙 Donations", "`!gcash` — view the GCash QR code and donation details", false)
                .setFooter("ExeSMP • Powered by ExeNovaLink • Need help? Contact the staff team.")
                .setColor(EMBED_COLOR).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildStatusEmbed() {
        int online = Bukkit.getOnlinePlayers().size();
        return new EmbedBuilder().setTitle("❖ Server Status")
                .setDescription("**Status:** `ONLINE`\n**Players:** `" + online + "/" + Bukkit.getMaxPlayers() + "`")
                .setColor(Color.decode("#57F287")).setTimestamp(Instant.now()).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildMotdEmbed() {
        String motd = Bukkit.getMotd();
        return new EmbedBuilder().setTitle("❖ Server MOTD")
                .setDescription("```" + (motd == null ? "Not configured" : motd) + "```")
                .setColor(EMBED_COLOR).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildPingEmbed() {
        JDA jda = jda();
        String latency = jda == null ? "Unavailable" : jda.getGatewayPing() + " ms";
        return new EmbedBuilder().setTitle("❖ Bot Latency").setDescription("🏓 `" + latency + "`")
                .setColor(EMBED_COLOR).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildSuggestionPromptEmbed() {
        return new EmbedBuilder().setTitle("💡 Submit a Suggestion")
                .addField("▪ What You Can Suggest", "► New server features\n► Improvements & bug fixes\n► Events & community activities\n► Discord channels & bot features\n► Quality-of-life improvements", false)
                .addField("▪ Your Feedback Matters", "Every suggestion will be reviewed and considered.\nYour ideas help improve the server for everyone.", false)
                .setDescription("Submit with `!suggest <your idea>`.")
                .setColor(EMBED_COLOR).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildDonationEmbed() {
        String number = getConfig().getString("donations.gcash-number", "Configure donations.gcash-number");
        String name = getConfig().getString("donations.account-name", "Configure donations.account-name");
        String qrName = getConfig().getString("donations.gcash-qr", "gcash-qr.jpg");
        return new EmbedBuilder().setTitle("GCash Donation")
                .setDescription("**GCash Number:** **" + number + "**\nPlease scan the attached QR code for easier payment.\n\nDonations help support server hosting and development. Thank you!")
                .addField("GCash Number", "`" + number + "`", true)
                .addField("Account Name", name, true)
                .setImage("attachment://" + qrName).setColor(getEmbedColor("embeds.donation-color", "#5865F2")).build();
    }

    private void sendDonationEmbed(MessageChannel channel) {
        File qrCode = getDonationQrFile();
        if (qrCode.isFile()) {
            channel.sendFiles(FileUpload.fromData(qrCode, qrCode.getName())).setEmbeds(buildDonationEmbed()).queue();
        } else {
            getLogger().warning("GCash QR image is missing from the plugin data folder.");
            channel.sendMessageEmbeds(buildDonationEmbed()).queue();
        }
    }

    private File getDonationQrFile() {
        String name = getConfig().getString("donations.gcash-qr", "gcash-qr.jpg");
        return new File(getDataFolder(), isSafeFileName(name) ? name : "gcash-qr.jpg");
    }

    private boolean isSafeFileName(String fileName) {
        return fileName != null && !fileName.isEmpty() && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\");
    }

    private Color getEmbedColor(String path, String fallback) {
        try { return parseColor(getConfig().getString(path, fallback)); }
        catch (Exception ignored) { return Color.decode(fallback); }
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildFaqEmbed() {
        EmbedBuilder embed = new EmbedBuilder().setTitle("❖ Frequently Asked Questions").setColor(getEmbedColor("embeds.info-color", "#5865F2"));
        org.bukkit.configuration.ConfigurationSection faq = getConfig().getConfigurationSection("faq");
        if (faq == null || faq.getKeys(false).isEmpty()) return embed.setDescription("No FAQ entries have been configured yet.").build();
        faq.getKeys(false).stream().limit(MAX_EMBED_FIELDS).forEach(key -> embed.addField(key.toUpperCase(), faq.getString(key, ""), false));
        return embed.build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildStaffEmbed() {
        EmbedBuilder embed = new EmbedBuilder().setTitle("❖ ExeSMP Staff Team").setColor(getEmbedColor("embeds.info-color", "#5865F2"));
        org.bukkit.configuration.ConfigurationSection staff = getConfig().getConfigurationSection("staff");
        if (staff == null || staff.getKeys(false).isEmpty()) return embed.setDescription("No staff roster has been configured yet.").build();
        for (String role : staff.getKeys(false).stream().limit(MAX_EMBED_FIELDS).collect(java.util.stream.Collectors.toList())) {
            if (role.isEmpty()) continue;
            List<String> members = staff.getStringList(role).stream().map(String::trim).filter(member -> !member.isEmpty()).collect(java.util.stream.Collectors.toList());
            String displayRole = role.replace('-', ' ').replace('_', ' ');
            displayRole = displayRole.substring(0, 1).toUpperCase() + displayRole.substring(1);
            embed.addField(displayRole, members.isEmpty() ? "—" : String.join("\n", members), true);
        }
        return embed.build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildReportInfoEmbed() {
        return new EmbedBuilder().setTitle("❖ Report a Player")
                .setDescription(getConfig().getString("reports.discord-instructions", "To report a player, use `/dsreport` in Minecraft or contact the support channel."))
                .setColor(getEmbedColor("embeds.info-color", "#5865F2")).build();
    }

    private void handleSuggestion(MessageChannel source, String suggestion) {
        suggestion = sanitizeUserInput(suggestion, 1000);
        if (suggestion.isEmpty()) {
            source.sendMessageEmbeds(buildSuggestionPromptEmbed()).queue();
            return;
        }
        final String submittedSuggestion = suggestion;
        String channelId = suggestionService == null ? getConfig().getString("suggestions.channel-id", "") : suggestionService.channelId();
        JDA jda = jda();
        TextChannel target = null;
        if (jda != null && channelId.matches("\\d{17,20}")) {
            target = jda.getTextChannelById(channelId);
        }
        if (target == null) {
            source.sendMessageEmbeds(new EmbedBuilder().setTitle("💡 Suggestions unavailable")
                    .setDescription("The suggestions channel has not been configured yet.").setColor(Color.decode("#ED4245")).build()).queue();
            return;
        }
        target.sendMessageEmbeds(new EmbedBuilder().setTitle("💡 New Suggestion")
                .setDescription(suggestion).setFooter("Submitted by a community member • Reply here to discuss").setColor(EMBED_COLOR).build())
                .setActionRow(Button.success("suggestion:accepted", "Accept"), Button.danger("suggestion:rejected", "Reject"), Button.primary("suggestion:considering", "Considering"), Button.secondary("suggestion:planned", "Planned")).queue(message -> {
                message.addReaction(Emoji.fromUnicode("⭐")).queue();
                    message.addReaction(Emoji.fromUnicode("❌")).queue();
                    createSuggestionThread(message, submittedSuggestion);
                });
        source.sendMessageEmbeds(new EmbedBuilder().setTitle("💡 Suggestion submitted")
                .setDescription("Thank you! Your idea has been posted for community voting.").setColor(Color.decode("#57F287")).build()).queue();
    }

    private void handleDiscordButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.equals("verify:minecraft")) {
            handleMinecraftVerification(event);
            return;
        }
        if (!(id.startsWith("report:") || id.startsWith("suggestion:"))) return;
        if (!isDiscordModerator(event.getMember(), event.getUser().getId())) {
            event.deferReply(true).setContent("You are not authorized to manage this item.").queue();
            return;
        }
        String action = id.substring(id.indexOf(':') + 1);
        String label = action.substring(0, 1).toUpperCase() + action.substring(1);
        try {
            EmbedBuilder embed = event.getMessage().getEmbeds().isEmpty() ? new EmbedBuilder() : new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            embed.addField("Status", label, true).addField("Handled by", event.getUser().getName(), true).setTimestamp(Instant.now());
            event.editMessageEmbeds(embed.build()).setComponents().queue();
            audit("DISCORD_BUTTON user=" + event.getUser().getId() + " action=" + id + " message=" + event.getMessageId());
            if (automationManager != null) {
                JDA jda = jda();
                automationManager.dispatch(com.exenova.link.automation.Trigger.DISCORD_BUTTON, event, jda);
                // Only accept/reject are decisions; "considering" and "planned"
                // are status updates and must not fire the "denied" automation.
                if (id.equals("suggestion:accepted")) automationManager.dispatch(com.exenova.link.automation.Trigger.SUGGESTION_ACCEPTED, event, jda);
                else if (id.equals("suggestion:rejected")) automationManager.dispatch(com.exenova.link.automation.Trigger.SUGGESTION_DENIED, event, jda);
            }
        } catch (Exception exception) {
            getLogger().warning("Could not update interactive message: " + exception.getMessage());
        }
    }

    private void createSuggestionThread(Message message, String suggestion) {
        if (!getConfig().getBoolean("suggestions.create-thread", true)) return;
        String title = sanitizeUserInput(suggestion, 85);
        if (title.isEmpty()) title = "Suggestion discussion";
        message.createThreadChannel("💡 " + title).queue(
                thread -> getLogger().fine("Created a discussion thread for suggestion " + message.getId() + "."),
                failure -> getLogger().warning("Could not create suggestion thread (needs Create Public Threads): " + failure.getMessage()));
    }

    private boolean isDiscordModerator(String userId) {
        return getConfig().getStringList("moderation.staff-user-ids").contains(userId);
    }

    /** Staff check by user ID or by any role listed in moderation.staff-role-ids. */
    public boolean isDiscordModerator(Member member, String userId) {
        if (isDiscordModerator(userId)) return true;
        List<String> roles = getConfig().getStringList("moderation.staff-role-ids");
        return member != null && !roles.isEmpty() && member.getRoles().stream().anyMatch(role -> roles.contains(role.getId()));
    }

    private boolean isDiscordOwner(String userId) {
        return getConfig().getStringList("discord.owner-ids").contains(userId);
    }

    private int parseAmount(String raw) {
        try { return Integer.parseInt(raw.trim()); } catch (Exception ignored) { return 0; }
    }

    private String setPrefix(String raw) {
        String prefix = sanitizeUserInput(raw, 5);
        if (prefix.isEmpty()) return "Prefix cannot be empty.";
        if (prefix.contains(" ")) return "Prefix cannot contain spaces.";
        getConfig().set("server-info.prefix", prefix);
        saveConfig();
        audit("PREFIX_CHANGED prefix=" + prefix);
        return "Prefix changed to `" + prefix + "`.";
    }

    private void handlePrefixTextCommand(MessageChannel channel, String userId, String value) {
        if (value.isEmpty()) { channel.sendMessage("Current prefix: `" + getConfig().getString("server-info.prefix", "!") + "`").queue(); return; }
        if (!isDiscordOwner(userId)) { channel.sendMessage("You are not authorized to change the prefix.").queue(); return; }
        channel.sendMessage(setPrefix(value)).queue();
    }

    private void purgeChannel(MessageChannel source, int amount, java.util.function.Consumer<String> reply) {
        if (!(source instanceof TextChannel)) { reply.accept("This command can only be used in a text channel."); return; }
        if (amount < 1 || amount > 100) { reply.accept("Amount must be between 1 and 100."); return; }
        TextChannel channel = (TextChannel) source;
        channel.getHistory().retrievePast(amount).queue(messages -> {
            if (messages.isEmpty()) { reply.accept("No messages were found to delete."); return; }
            java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.now().minusDays(14);
            List<Message> recent = messages.stream().filter(message -> message.getTimeCreated().isAfter(cutoff)).collect(java.util.stream.Collectors.toList());
            List<Message> old = messages.stream().filter(message -> !message.getTimeCreated().isAfter(cutoff)).collect(java.util.stream.Collectors.toList());
            java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger((recent.isEmpty() ? 0 : 1) + old.size());
            Runnable done = () -> { if (remaining.decrementAndGet() == 0) channel.sendMessage("Deleted " + messages.size() + " message(s).").queue(message -> message.delete().queueAfter(5, TimeUnit.SECONDS)); };
            if (!recent.isEmpty()) {
                java.util.concurrent.CompletableFuture.allOf(channel.purgeMessages(recent).toArray(new java.util.concurrent.CompletableFuture<?>[0]))
                        .whenComplete((ignoredResult, ignoredError) -> done.run());
            }
            for (Message message : old) message.delete().queue(success -> done.run(), failure -> done.run());
        }, failure -> reply.accept("Could not read channel history: " + failure.getMessage()));
    }

    private void publishChangelog() {
        if (!getConfig().getBoolean("changelog.enabled", true)) return;
        String channelId = getConfig().getString("changelog.channel-id", "");
        JDA jda = jda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        if (channel == null) { getLogger().warning("Changelog channel is invalid or inaccessible."); return; }
        List<Map<?, ?>> entries = getConfig().getMapList("changelog.entries");
        List<String> published = getConfig().getStringList("changelog.published-versions");
        List<String> pending = entries.stream().map(entry -> String.valueOf(entry.get("version")))
                .filter(version -> !published.contains(version)).collect(java.util.stream.Collectors.toList());
        if (!pending.isEmpty()) sendUpdatedChangelog(channel, entries, pending);
    }

    private void sendUpdatedChangelog(TextChannel channel, List<Map<?, ?>> entries, List<String> pending) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("ExeSMP Changelog")
                .setDescription(buildChangelogDescription(entries)).setColor(getEmbedColor("embeds.info-color", "#5865F2")).setTimestamp(Instant.now());
        String messageId = getConfig().getString("changelog.message-id", "");
        if (!messageId.matches("\\d{17,20}")) { sendNewChangelog(channel, embed, pending); return; }
        JDA jda = jda();
        channel.retrieveMessageById(messageId).queue(message -> {
            if (jda != null && !message.getAuthor().getId().equals(jda.getSelfUser().getId())) { sendNewChangelog(channel, embed, pending); return; }
            message.editMessageEmbeds(embed.build()).queue(
                    success -> Bukkit.getScheduler().runTask(this, () -> markChangelogPublished(pending)),
                    failure -> sendNewChangelog(channel, embed, pending));
        }, failure -> sendNewChangelog(channel, embed, pending));
    }

    private String buildChangelogDescription(List<Map<?, ?>> entries) {
        String description = entries.stream().map(entry -> {
            Object raw = entry.get("changes");
            List<String> changes = raw instanceof List ? ((List<?>) raw).stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()) : java.util.Collections.singletonList(String.valueOf(raw));
            return "**" + entry.get("version") + "**\n" + changes.stream().map(change -> "• " + change).collect(java.util.stream.Collectors.joining("\n"));
        }).collect(java.util.stream.Collectors.joining("\n\n"));
        return description.length() > 4000 ? description.substring(description.length() - 4000) : description;
    }

    private void sendNewChangelog(TextChannel channel, EmbedBuilder embed, List<String> pending) {
        channel.sendMessageEmbeds(embed.build()).queue(message -> Bukkit.getScheduler().runTask(this, () -> {
            getConfig().set("changelog.message-id", message.getId());
            markChangelogPublished(pending);
        }), failure -> getLogger().warning("Could not publish changelog: " + failure.getMessage()));
    }

    private void markChangelogPublished(List<String> versions) {
        List<String> published = new ArrayList<>(getConfig().getStringList("changelog.published-versions"));
        for (String version : versions) if (!published.contains(version)) published.add(version);
        getConfig().set("changelog.published-versions", published);
        saveConfig();
    }

    private void setupVerificationPanel() {
        if (!getConfig().getBoolean("verification.enabled", true)) return;
        String channelId = getConfig().getString("verification.channel-id", "");
        JDA jda = jda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        if (channel == null) {
            getLogger().warning("Verification is enabled but verification.channel-id is invalid or inaccessible.");
            return;
        }
        String messageId = getConfig().getString("verification.message-id", "");
        if (messageId.matches("\\d{17,20}")) return;
        channel.sendMessageEmbeds(new EmbedBuilder().setTitle("✅ Minecraft Verification")
                .setDescription("Click the button below to get the **Minecraft** role and access the server channels.")
                .setColor(getEmbedColor("embeds.info-color", "#5865F2")).build())
                .setActionRow(Button.success("verify:minecraft", "Verify Minecraft")).queue(message -> Bukkit.getScheduler().runTask(this, () -> {
                    getConfig().set("verification.message-id", message.getId());
                    saveConfig();
                }), failure -> getLogger().warning("Could not create verification panel: " + failure.getMessage()));
    }

    private void handleMinecraftVerification(ButtonInteractionEvent event) {
        String roleId = getConfig().getString("verification.role-id", "");
        if (event.getGuild() == null || event.getMember() == null || !roleId.matches("\\d{17,20}")) {
            event.deferReply(true).setContent("Verification is not configured correctly. Please contact staff.").queue();
            return;
        }
        net.dv8tion.jda.api.entities.Role role = event.getGuild().getRoleById(roleId);
        if (role == null) {
            event.deferReply(true).setContent("The Minecraft role could not be found. Please contact staff.").queue();
            return;
        }
        if (event.getMember().getRoles().contains(role)) {
            event.deferReply(true).setContent("You already have the Minecraft role.").queue();
            return;
        }
        // Acknowledge within Discord's 3s window, then report the real outcome.
        event.deferReply(true).queue();
        JDA jda = jda();
        boolean sent = verificationService != null && verificationService.assignRole(jda, event.getGuild().getId(), event.getUser().getId(),
                () -> {
                    event.getHook().sendMessage("Verification complete — the Minecraft role has been assigned.").setEphemeral(true).queue();
                    audit("VERIFY user=" + event.getUser().getId());
                    if (automationManager != null) automationManager.dispatch(com.exenova.link.automation.Trigger.VERIFICATION_COMPLETE, event, jda);
                },
                failure -> {
                    getLogger().warning("Could not assign the Minecraft role to " + event.getUser().getId()
                            + " (is the bot's role above it, with Manage Roles?): " + failure.getMessage());
                    event.getHook().sendMessage("Sorry, the role could not be assigned. Please contact staff.").setEphemeral(true).queue();
                });
        if (!sent) {
            getLogger().warning("Verification is disabled or misconfigured; could not assign the Minecraft role.");
            event.getHook().sendMessage("Verification is not available right now. Please contact staff.").setEphemeral(true).queue();
        }
    }

    // Bukkit's player list can only be safely read on the main server thread,
    // but this is normally called from a JDA callback thread. Hop to the main
    // thread to read it, then hand the built embed back via the callback.
    private void fetchPlayerListEmbedAsync(java.util.function.Consumer<net.dv8tion.jda.api.entities.MessageEmbed> callback) {
        Bukkit.getScheduler().runTask(this, () -> {
            Collection<? extends Player> players = Bukkit.getOnlinePlayers();
            int max = Bukkit.getMaxPlayers();

            EmbedBuilder embedBuilder = new EmbedBuilder()
                    .setTitle("◆ Online Players (" + players.size() + "/" + max + ")")
                    .setColor(EMBED_COLOR);

            if (players.isEmpty()) {
                embedBuilder.setDescription("No players are currently online.");
            } else {
                List<Player> playerList = new ArrayList<>(players);
                playerList.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
                // Discord rejects embeds with more than 25 fields, which made
                // !players fail outright on busy servers.
                int shown = playerList.size() > MAX_EMBED_FIELDS ? MAX_EMBED_FIELDS - 1 : playerList.size();
                for (Player player : playerList.subList(0, shown)) {
                    String avatar = getAvatarUrl(player.getName());
                    embedBuilder.addField("👤 " + player.getName(), "**Ping:** `" + getPlayerPing(player) + " ms`\n[View skin/avatar](" + avatar + ")", true);
                }
                if (shown < playerList.size()) {
                    String rest = playerList.subList(shown, playerList.size()).stream().map(Player::getName).collect(java.util.stream.Collectors.joining(", "));
                    if (rest.length() > 1000) rest = rest.substring(0, 997) + "...";
                    embedBuilder.addField("…and " + (playerList.size() - shown) + " more", rest, false);
                }
                // Discord allows one thumbnail per embed. Showing the first
                // player's skin keeps the list visual while each field links
                // directly to the corresponding avatar.
                embedBuilder.setThumbnail(getAvatarUrl(playerList.get(0).getName()));
            }

            callback.accept(embedBuilder.build());
        });
    }

    private String getAvatarUrl(String playerName) {
        OfflinePlayer known = Bukkit.getPlayerExact(playerName);
        return avatarUrl(playerName, known == null ? null : known.getUniqueId());
    }

    /** Replaces :shortcode: emoji in a chat message, if enabled and the player may use them. */
    public String applyEmoji(Player player, String message) {
        if (!getConfig().getBoolean("chat.emoji.enabled", true) || !player.hasPermission("exenova.chat.emoji")) return message;
        org.bukkit.configuration.ConfigurationSection section = getConfig().getConfigurationSection("chat.emoji.list");
        if (section == null) return message;
        Map<String, String> map = new java.util.HashMap<>();
        for (String key : section.getKeys(false)) map.put(key, section.getString(key, ""));
        return com.exenova.link.chat.Emoji.apply(message, map);
    }

    /** Skin/avatar URL; Bedrock (Floodgate) players use avatars.bedrock-provider-url. */
    public String avatarUrl(String playerName, UUID uuid) {
        boolean bedrock = uuid != null && integrations != null && integrations.isBedrock(uuid);
        String provider = bedrock
                ? getConfig().getString("avatars.bedrock-provider-url", "https://mc-heads.net/avatar/MHF_Steve/100")
                : getConfig().getString("avatars.provider-url", "https://mc-heads.net/avatar/{player}/100");
        return provider.replace("{player}", playerName).replace("{uuid}", uuid == null ? playerName : uuid.toString());
    }

    private int getPlayerPing(Player player) {
        try {
            return player.getPing();
        } catch (NoSuchMethodError ignored) {
            return -1;
        }
    }

    private void fetchPlayerStatisticAsync(MessageChannel channel, String command, String playerName) {
        playerName = sanitizeUserInput(playerName, 16);
        if (playerName.isEmpty()) {
            channel.sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Player required")
                    .setDescription("Usage: `!" + command + " <player>`").setColor(Color.decode("#ED4245")).build()).queue();
            return;
        }
        if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
            channel.sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Invalid player name").setDescription("Use a valid Minecraft username.").setColor(Color.decode("#ED4245")).build()).queue();
            return;
        }
        final String requestedPlayerName = playerName;
        Bukkit.getScheduler().runTask(this, () -> {
            OfflinePlayer player = resolveOfflinePlayer(requestedPlayerName);
            net.dv8tion.jda.api.entities.MessageEmbed embed;
            if (player == null) {
                embed = new EmbedBuilder().setTitle("❖ Player not found").setDescription("`" + requestedPlayerName + "` has not joined this server.").setColor(Color.decode("#ED4245")).build();
            } else if (command.equals("playtime")) embed = buildPlaytimeEmbed(player);
            else if (command.equals("seen")) embed = buildSeenEmbed(player);
            else embed = buildStatsEmbed(player);
            channel.sendMessageEmbeds(embed).queue();
        });
    }

    private void fetchPlayerProfileAsync(MessageChannel channel, String playerName) {
        playerName = sanitizeUserInput(playerName, 16);
        if (!playerName.matches("[A-Za-z0-9_]{3,16}")) {
            channel.sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Player required").setDescription("Usage: `!profile <player>`").setColor(Color.decode("#ED4245")).build()).queue();
            return;
        }
        final String requestedName = playerName;
        Bukkit.getScheduler().runTask(this, () -> {
            OfflinePlayer player = resolveOfflinePlayer(requestedName);
            if (player == null) {
                channel.sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Player not found").setDescription("`" + requestedName + "` has not joined this server.").setColor(Color.decode("#ED4245")).build()).queue();
                return;
            }
            Player online = player.getPlayer();
            EmbedBuilder embed = new EmbedBuilder().setTitle("❖ Player Profile • " + player.getName())
                    .addField("Playtime", formatDuration(stat(player, Statistic.PLAY_ONE_MINUTE) / 20L / 60L), true)
                    .addField("First joined", formatDate(player.getFirstPlayed()), true)
                    .addField("Last seen", player.isOnline() ? "Online now" : formatAgo(player.getLastSeen()), true)
                    .addField("Player kills", String.valueOf(stat(player, Statistic.PLAYER_KILLS)), true)
                    .addField("Deaths", String.valueOf(stat(player, Statistic.DEATHS)), true)
                    .addField("Mobs killed", String.valueOf(stat(player, Statistic.MOB_KILLS)), true)
                    .addField("Blocks mined", String.valueOf(totalMined(player)), true)
                    .addField("Status", player.isOnline() ? "🟢 Online" : "⚫ Offline", true)
                    .setThumbnail(getAvatarUrl(player.getName())).setColor(getEmbedColor("embeds.stats-color", "#5865F2"));
            if (online != null) embed.addField("Current location", online.getWorld().getName() + " • " + Math.round(online.getLocation().getX()) + ", " + Math.round(online.getLocation().getY()) + ", " + Math.round(online.getLocation().getZ()) + " • " + getPlayerPing(online) + " ms", false);
            channel.sendMessageEmbeds(embed.build()).queue();
        });
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildPlaytimeEmbed(OfflinePlayer player) {
        long ticks = stat(player, Statistic.PLAY_ONE_MINUTE);
        long minutes = ticks / 20L / 60L;
        return new EmbedBuilder().setTitle("❖ Playtime • " + player.getName())
                .setDescription("**Total:** `" + formatDuration(minutes) + "`\n**Hours:** `" + (minutes / 60) + "`\n**Minutes:** `" + (minutes % 60) + "`")
                .setThumbnail(getAvatarUrl(player.getName())).setColor(getEmbedColor("embeds.stats-color", "#5865F2")).build();
    }

    /** Known player by name (online first, then players who have joined before); never a web lookup. */
    public OfflinePlayer findPlayer(String requestedName) { return resolveOfflinePlayer(requestedName); }

    private OfflinePlayer resolveOfflinePlayer(String requestedName) {
        for (Player online : Bukkit.getOnlinePlayers()) if (online.getName().equalsIgnoreCase(requestedName)) return online;
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(requestedName) && offline.hasPlayedBefore()) return offline;
        }
        return null;
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildSeenEmbed(OfflinePlayer player) {
        long lastLogin = player.getLastLogin();
        long lastSeen = player.isOnline() ? System.currentTimeMillis() : player.getLastSeen();
        String status = player.isOnline() ? "Currently online" : formatAgo(lastSeen);
        return new EmbedBuilder().setTitle("❖ Last Seen • " + player.getName())
                .addField("Last login", formatDate(lastLogin), true).addField("Last logout", formatDate(player.getLastSeen()), true)
                .addField("Time since last seen", status, false).setThumbnail(getAvatarUrl(player.getName()))
                .setColor(getEmbedColor("embeds.stats-color", "#5865F2")).build();
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildStatsEmbed(OfflinePlayer player) {
        long distanceCm = stat(player, Statistic.WALK_ONE_CM) + stat(player, Statistic.SPRINT_ONE_CM) + stat(player, Statistic.FLY_ONE_CM) + stat(player, Statistic.SWIM_ONE_CM);
        return new EmbedBuilder().setTitle("❖ Player Stats • " + player.getName())
                .addField("Playtime", formatDuration(stat(player, Statistic.PLAY_ONE_MINUTE) / 20L / 60L), true)
                .addField("Player kills", String.valueOf(stat(player, Statistic.PLAYER_KILLS)), true)
                .addField("Deaths", String.valueOf(stat(player, Statistic.DEATHS)), true)
                .addField("Mobs killed", String.valueOf(stat(player, Statistic.MOB_KILLS)), true)
                .addField("Blocks mined", String.valueOf(totalMined(player)), true)
                .addField("Distance traveled", String.format("%.2f km", distanceCm / 100000.0), true)
                .setThumbnail(getAvatarUrl(player.getName())).setColor(getEmbedColor("embeds.stats-color", "#5865F2")).build();
    }

    private long stat(OfflinePlayer player, Statistic statistic) {
        try { return player.getStatistic(statistic); } catch (IllegalArgumentException ignored) { return 0; }
    }

    /** Non-legacy block materials, computed once; Material.values() also contains ~1,000 legacy entries. */
    private static volatile List<Material> minableBlocks;

    @SuppressWarnings("deprecation")
    private static List<Material> minableBlocks() {
        List<Material> blocks = minableBlocks;
        if (blocks == null) {
            List<Material> found = new ArrayList<>();
            for (Material material : Material.values()) if (!material.isLegacy() && material.isBlock()) found.add(material);
            blocks = minableBlocks = java.util.Collections.unmodifiableList(found);
        }
        return blocks;
    }

    private long totalMined(OfflinePlayer player) {
        long total = 0;
        for (Material material : minableBlocks()) {
            try { total += player.getStatistic(Statistic.MINE_BLOCK, material); } catch (IllegalArgumentException ignored) { }
        }
        return total;
    }

    private static String titleCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean upper = true;
        for (char c : text.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = c == ' ';
        }
        return out.toString();
    }

    private void fetchLeaderboardAsync(java.util.function.Consumer<net.dv8tion.jda.api.entities.MessageEmbed> callback) {
        fetchLeaderboardAsync(callback, "");
    }

    private void fetchLeaderboardAsync(java.util.function.Consumer<net.dv8tion.jda.api.entities.MessageEmbed> callback, String requestedType) {
        Bukkit.getScheduler().runTask(this, () -> {
            String type = normalizeLeaderboardType(requestedType);
            int limit = Math.max(1, getConfig().getInt("leaderboards.limit", 10));
            // Each statistic lookup on an offline player can hit their stats
            // file, so compute every value exactly once rather than inside the
            // sort comparator (which re-read it O(n log n) times).
            List<Map.Entry<String, Long>> ranked = new ArrayList<>();
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() == null) continue;
                long value = leaderboardValue(player, type);
                if (value > 0) ranked.add(Map.entry(player.getName(), value));
            }
            ranked.sort(Map.Entry.<String, Long>comparingByValue().reversed());
            EmbedBuilder embed = new EmbedBuilder().setTitle("❖ " + titleCase(type.replace('_', ' ')) + " Leaderboard")
                    .setColor(getEmbedColor("embeds.stats-color", "#5865F2"));
            StringBuilder description = new StringBuilder();
            int rank = 1;
            for (Map.Entry<String, Long> entry : ranked) {
                if (rank > limit) break;
                description.append('`').append(rank++).append(".` **").append(entry.getKey()).append("** — ")
                        .append(formatLeaderboardValue(entry.getValue(), type)).append('\n');
            }
            callback.accept(embed.setDescription(description.length() == 0 ? "No recorded statistics yet." : description.toString()).build());
        });
    }

    private String normalizeLeaderboardType(String requestedType) {
        String type = sanitizeUserInput(requestedType, 20).toUpperCase().replace(' ', '_');
        if (type.isEmpty()) return getConfig().getString("leaderboards.default-type", "PLAYTIME").toUpperCase();
        if (type.equals("MOBS")) type = "MOB_KILLS";
        if (type.equals("MINED")) type = "BLOCKS_MINED";
        if (Arrays.asList("PLAYTIME", "KILLS", "DEATHS", "MOB_KILLS", "BLOCKS_MINED").contains(type)) return type;
        return getConfig().getString("leaderboards.default-type", "PLAYTIME").toUpperCase();
    }

    private long leaderboardValue(OfflinePlayer player, String type) {
        switch (type) {
            case "KILLS": return stat(player, Statistic.PLAYER_KILLS);
            case "DEATHS": return stat(player, Statistic.DEATHS);
            case "BLOCKS_MINED": return totalMined(player);
            case "MOB_KILLS": return stat(player, Statistic.MOB_KILLS);
            case "PLAYTIME": default: return stat(player, Statistic.PLAY_ONE_MINUTE);
        }
    }

    private String formatLeaderboardValue(long value, String type) { return type.equals("PLAYTIME") ? formatDuration(value / 20L / 60L) : String.valueOf(value); }
    private String formatDuration(long minutes) { return (minutes / 60) + "h " + (minutes % 60) + "m"; }
    private String formatDate(long epoch) { return epoch <= 0 ? "Never" : "<t:" + (epoch / 1000L) + ":F>"; }
    private String formatAgo(long epoch) { return epoch <= 0 ? "Never" : "<t:" + (epoch / 1000L) + ":R>"; }

    private boolean relayAnnouncement(MessageReceivedEvent event) {
        if (!getConfig().getBoolean("announcements.enabled", false)) return false;
        String sourceId = getConfig().getString("announcements.source-channel-id", "");
        if (!event.getChannel().getId().equals(sourceId)) return false;
        String targetId = getConfig().getString("announcements.target-channel-id", "");
        JDA jda = jda();
        TextChannel target = jda == null || !targetId.matches("\\d{17,20}") ? null : jda.getTextChannelById(targetId);
        if (target == null) return true;
        EmbedBuilder embed = new EmbedBuilder().setTitle("📢 Minecraft Announcement")
                .setDescription(event.getMessage().getContentRaw().isEmpty() ? "(Attachment)" : event.getMessage().getContentRaw())
                .setAuthor(event.getAuthor().getName(), null, event.getAuthor().getEffectiveAvatarUrl())
                .setColor(getEmbedColor("embeds.announcement-color", "#FEE75C")).setTimestamp(event.getMessage().getTimeCreated().toInstant());
        if (!event.getMessage().getAttachments().isEmpty()) {
            Message.Attachment first = event.getMessage().getAttachments().get(0);
            if (first.isImage()) embed.setImage(first.getProxyUrl());
            String links = event.getMessage().getAttachments().stream().map(a -> "[" + a.getFileName() + "](" + a.getUrl() + ")").collect(java.util.stream.Collectors.joining(" • "));
            embed.addField("Attachments", links, false);
        }
        String icon = getConfig().getString("announcements.server-icon-url", "");
        if (!icon.isEmpty()) embed.setThumbnail(icon);
        target.sendMessageEmbeds(embed.build()).queue();
        return true;
    }

    private void openReportPlayerMenu(Player reporter) {
        Inventory menu = PluginMenu.create(PluginMenu.Type.REPORT_PLAYER, 54, "Report: Choose Player");
        int candidates = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(reporter)) continue;
            if (candidates >= menu.getSize()) break;
            candidates++;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(candidate);
            meta.setDisplayName("§e" + candidate.getName());
            head.setItemMeta(meta);
            menu.addItem(head);
        }
        if (candidates == 0) reporter.sendMessage("§cThere are no other online players to report. You can use /dsreport <player> when they are online.");
        else reporter.openInventory(menu);
    }

    private void openReportReasonMenu(Player reporter) {
        Inventory menu = PluginMenu.create(PluginMenu.Type.REPORT_REASON, 27, "Report: Choose Reason");
        List<String> reasons = getConfig().getStringList("reports.categories");
        if (reasons.isEmpty()) {
            // Without categories the menu would be empty and the report could
            // never be completed.
            reasons = Arrays.asList("Cheating", "Griefing", "Harassment", "Other");
        }
        for (String reason : reasons.subList(0, Math.min(reasons.size(), menu.getSize()))) menu.addItem(namedItem(Material.PAPER, "§e" + reason, "§7Click to select this reason"));
        reporter.openInventory(menu);
    }

    private ItemStack namedItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(java.util.Collections.singletonList(lore));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onReportInventoryClick(InventoryClickEvent event) {
        PluginMenu.Type menu = PluginMenu.typeOf(event.getView().getTopInventory());
        if (menu != PluginMenu.Type.REPORT_PLAYER && menu != PluginMenu.Type.REPORT_REASON) return;
        // Cancel before any early return so items can't be shift-clicked or
        // swapped into/out of the menu.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player) || event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;
        Player reporter = (Player) event.getWhoClicked();
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (menu == PluginMenu.Type.REPORT_PLAYER) {
            if (!(event.getCurrentItem().getItemMeta() instanceof SkullMeta)) return;
            OfflinePlayer target = ((SkullMeta) event.getCurrentItem().getItemMeta()).getOwningPlayer();
            if (target == null) return;
            if (target.getUniqueId().equals(reporter.getUniqueId())) return;
            reportState.begin(reporter.getUniqueId(), target.getUniqueId(), System.currentTimeMillis());
            openReportReasonMenu(reporter);
        } else {
            String reason = event.getCurrentItem().getItemMeta().getDisplayName().replace("§e", "");
            if (!reportState.targets().containsKey(reporter.getUniqueId())) { reporter.closeInventory(); return; }
            reportState.reasons().put(reporter.getUniqueId(), reason);
            // Closing an inventory from inside its own click event is unsafe; do it next tick.
            Bukkit.getScheduler().runTask(this, () -> reporter.closeInventory());
            if (getConfig().getBoolean("reports.request-details", true)) {
                reportState.awaitingDetails().add(reporter.getUniqueId());
                reporter.sendMessage("§eType any extra report details in chat, or type §fskip§e to submit now.");
            } else submitReport(reporter, "No additional details provided.");
        }
    }

    // LOWEST so the details are captured (and the message cancelled) before
    // any chat plugin or the Discord bridge sees them: report details are
    // private and must never be broadcast or relayed to Discord.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onReportChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player reporter = event.getPlayer();
        if (!reportState.awaitingDetails().remove(reporter.getUniqueId())) return;
        event.setCancelled(true);
        String typed = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
        String details = typed.equalsIgnoreCase("skip") ? "No additional details provided." : typed;
        Bukkit.getScheduler().runTask(this, () -> submitReport(reporter, details));
    }

    @EventHandler
    public void onPluginMenuDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (PluginMenu.typeOf(event.getView().getTopInventory()) == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    private void submitReport(Player reporter, String details) {
        java.util.UUID targetId = reportState.targets().remove(reporter.getUniqueId());
        String reason = reportState.reasons().remove(reporter.getUniqueId());
        reportState.started().remove(reporter.getUniqueId());
        if (targetId == null || reason == null) return;
        details = sanitizeUserInput(details, 800);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        reporter.sendMessage("§aYour report has been submitted. Thank you for helping keep the server safe.");
        for (Player staff : Bukkit.getOnlinePlayers()) if (can(staff, "reports.notify")) {
            staff.sendMessage("§c[Report] §f" + reporter.getName() + " reported " + target.getName() + " for " + reason + ".");
        }
        String channelId = getConfig().getString("reports.channel-id", "");
        JDA jda = jda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        Player targetOnline = target.getPlayer();
        String location = targetOnline == null ? "Offline" : targetOnline.getWorld().getName() + " • " + Math.round(targetOnline.getLocation().getX()) + ", " + Math.round(targetOnline.getLocation().getY()) + ", " + Math.round(targetOnline.getLocation().getZ());
        if (channel != null) channel.sendMessageEmbeds(new EmbedBuilder().setTitle("🚨 New Player Report")
                .addField("Reporter", reporter.getName(), true).addField("Reported player", target.getName() == null ? targetId.toString() : target.getName(), true)
                .addField("Reason", reason, true).addField("Target location", location, true)
                .addField("Online players", String.valueOf(Bukkit.getOnlinePlayers().size()), true)
                .addField("Details", details, false).setTimestamp(Instant.now())
                .setColor(getEmbedColor("embeds.report-color", "#ED4245")).build())
                .setActionRow(Button.primary("report:claimed", "Claim"), Button.primary("report:investigating", "Investigating"), Button.danger("report:invalid", "Invalid"), Button.danger("report:punished", "Punished"), Button.secondary("report:closed", "Closed")).queue();
        audit("REPORT reporter=" + reporter.getName() + " target=" + (target.getName() == null ? targetId : target.getName()) + " reason=" + reason);
        if (automationManager != null) automationManager.dispatch(com.exenova.link.automation.Trigger.REPORT_SUBMITTED, reporter, jda);
    }

    private boolean tryConsumeDiscordCommand(String userId) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, getConfig().getLong("security.command-cooldown-seconds", 3)) * 1000L;
        Long previous = discordCommandCooldowns.put(userId, now);
        if (previous != null && now - previous < cooldown) {
            discordCommandCooldowns.put(userId, previous);
            return false;
        }
        return true;
    }

    private void pruneCooldowns() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000L;
        discordCommandCooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private String sanitizeUserInput(String input, int maxLength) {
        if (input == null) return "";
        String cleaned = input.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private void openConfigMenu(Player player) {
        Inventory menu = PluginMenu.create(PluginMenu.Type.SETTINGS, 27, "Discord Status Settings");
        menu.setItem(11, namedItem(Material.BELL, "§eAnnouncements: " + enabledLabel("announcements.enabled"), "§7Toggle Discord announcement relay"));
        menu.setItem(13, namedItem(Material.REDSTONE_TORCH, "§eReports: " + enabledLabel("commands.report.enabled"), "§7Toggle in-game reporting"));
        menu.setItem(15, namedItem(Material.COMMAND_BLOCK, "§ePrefix commands: " + enabledLabel("server-info.enabled"), "§7Toggle legacy ! commands"));
        menu.setItem(22, namedItem(Material.LIME_DYE, "§aSave changes", "§7Changes are saved immediately"));
        player.openInventory(menu);
    }

    private String enabledLabel(String path) { return getConfig().getBoolean(path, true) ? "§aON" : "§cOFF"; }

    @EventHandler
    public void onConfigInventoryClick(InventoryClickEvent event) {
        if (PluginMenu.typeOf(event.getView().getTopInventory()) != PluginMenu.Type.SETTINGS) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        Player player = (Player) event.getWhoClicked();
        if (!can(player, "config")) { player.closeInventory(); return; }
        String path = event.getRawSlot() == 11 ? "announcements.enabled" : event.getRawSlot() == 13 ? "commands.report.enabled" : event.getRawSlot() == 15 ? "server-info.enabled" : null;
        if (path == null) return;
        boolean next = !getConfig().getBoolean(path, true);
        getConfig().set(path, next);
        saveConfig();
        audit("CONFIG actor=" + player.getName() + " " + path + "=" + next);
        player.sendMessage("§aUpdated " + path + " to " + next + ".");
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) openConfigMenu(player); });
    }

    private void backupConfig(CommandSender sender) {
        try {
            String name = backupService.create();
            sender.sendMessage("§aConfiguration backup created: " + name);
            audit("BACKUP actor=" + sender.getName() + " file=" + name);
        } catch (IOException exception) { sender.sendMessage("§cCould not create backup: " + exception.getMessage()); getLogger().warning("Config backup failed: " + exception.getMessage()); }
    }

    private void restoreConfig(CommandSender sender, String fileName) {
        if (!BackupService.isValidBackupName(fileName)) { sender.sendMessage("§cInvalid backup filename. Use a file made by /discordstatusbackup."); return; }
        try {
            backupService.restore(fileName);
            reloadPlugin();
            sender.sendMessage("§aConfiguration restored and reloaded.");
            audit("RESTORE actor=" + sender.getName() + " file=" + fileName);
        } catch (IOException exception) { sender.sendMessage("§cCould not restore backup: " + exception.getMessage()); getLogger().warning("Config restore failed: " + exception.getMessage()); }
    }

    public void audit(String entry) {
        if (auditLogService != null) auditLogService.write(entry);
    }

    private void expireReportState() {
        long cutoff = System.currentTimeMillis() - Math.max(5L, getConfig().getLong("reports.state-timeout-minutes", 30L)) * 60_000L;
        reportState.expireBefore(cutoff);
    }

    private void logStartupDiagnostics() {
        getLogger().info("Startup diagnostics: Discord=" + (discordReady ? "READY" : "WAITING")
                + ", reports=" + channelStatus("reports.channel-id")
                + ", suggestions=" + channelStatus("suggestions.channel-id")
                + ", verification=" + channelStatus("verification.channel-id")
                + ", dashboard=" + channelStatus("status-dashboard.channel-id"));
    }

    private void handleNoteCommand(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§eUsage: /dsnote <add|list|clear> <player> [note]"); return; }
        String action = args[0].toLowerCase(java.util.Locale.ROOT);
        if (!args[1].matches("[A-Za-z0-9_]{3,16}")) { sender.sendMessage("§cUse a valid Minecraft username."); return; }
        // resolveOfflinePlayer only checks known players; Bukkit.getOfflinePlayer(String)
        // can block the main thread on a Mojang web lookup for unknown names.
        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) { sender.sendMessage("§c" + args[1] + " has never joined this server."); return; }
        String path = "notes." + target.getUniqueId();
        if (action.equals("list")) {
            List<String> entries = notesRepository.configuration().getStringList(path);
            sender.sendMessage("§6Staff notes for §e" + args[1] + "§6: " + (entries.isEmpty() ? "§7none" : ""));
            for (String entry : entries) sender.sendMessage("§7• " + entry);
            return;
        }
        if (action.equals("clear")) {
            notesRepository.configuration().set(path, null); notesRepository.save(); sender.sendMessage("§aCleared staff notes for " + args[1] + "."); audit("NOTE_CLEAR actor=" + sender.getName() + " player=" + args[1]); return;
        }
        if (!action.equals("add") || args.length < 3) { sender.sendMessage("§eUsage: /dsnote add <player> <note>"); return; }
        String note = sanitizeUserInput(String.join(" ", Arrays.copyOfRange(args, 2, args.length)), 400);
        if (note.isEmpty()) { sender.sendMessage("§cNote cannot be empty."); return; }
        List<String> entries = notesRepository.configuration().getStringList(path);
        entries.add(Instant.now() + " | " + sender.getName() + " | " + note);
        notesRepository.configuration().set(path, entries); notesRepository.save();
        sender.sendMessage("§aStaff note saved for " + args[1] + "."); audit("NOTE_ADD actor=" + sender.getName() + " player=" + args[1]);
    }

    private void handleWatchCommand(CommandSender sender, String[] args) {
        if (args.length < 1) { sender.sendMessage("§eUsage: /dswatch <add|remove|list> [player]"); return; }
        List<String> watched = new ArrayList<>(getConfig().getStringList("watchlist.players"));
        String action = args[0].toLowerCase();
        if (action.equals("list")) { sender.sendMessage("§6Watchlist: §e" + (watched.isEmpty() ? "none" : String.join(", ", watched))); return; }
        if (args.length != 2) { sender.sendMessage("§eUsage: /dswatch " + action + " <player>"); return; }
        String player = sanitizeUserInput(args[1], 16);
        if (!player.matches("[A-Za-z0-9_]{3,16}")) { sender.sendMessage("§cUse a valid Minecraft username."); return; }
        watched.removeIf(name -> name.equalsIgnoreCase(player));
        if (action.equals("add")) watched.add(player);
        else if (!action.equals("remove")) { sender.sendMessage("§eUsage: /dswatch <add|remove|list> [player]"); return; }
        getConfig().set("watchlist.players", watched); saveConfig();
        sender.sendMessage("§aWatchlist updated."); audit("WATCHLIST actor=" + sender.getName() + " action=" + action + " player=" + player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        logServerEvent("Player joined", player.getName());
        if (getConfig().getStringList("watchlist.players").stream().anyMatch(name -> name.equalsIgnoreCase(player.getName()))) {
            for (Player staff : Bukkit.getOnlinePlayers()) if (can(staff, "watchlist")) staff.sendMessage("§c⚠ Watchlisted player joined: §f" + player.getName());
            sendEventLog("⚠ Watchlisted player joined", player.getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        reportState.clear(id);
        logServerEvent("Player left", event.getPlayer().getName());
    }

    private void logServerEvent(String title, String detail) {
        if (!getConfig().getBoolean("incident-log.enabled", false)) return;
        sendEventLog(title, detail);
    }

    private void sendEventLog(String title, String detail) {
        String channelId = getConfig().getString("incident-log.channel-id", "");
        JDA jda = jda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessageEmbeds(new EmbedBuilder().setTitle("📋 " + title).setDescription(detail).setTimestamp(Instant.now()).setColor(getEmbedColor("embeds.info-color", "#5865F2")).build()).queue();
    }

    private void scheduleStatusDashboard() { if (dashboardService != null) dashboardService.start(); }

    /**
     * Updates the bot's Discord activity (the "Playing ..." text under its
     * name) to reflect live player count. Runs on the scheduler's async
     * thread pool; reading Bukkit.getOnlinePlayers() off the main thread
     * here follows the same established pattern already used by
     * buildStatusDashboardEmbed() elsewhere in this class.
     */
    private void updateBotPresence() {
        if (!discordReady || !getConfig().getBoolean("bot-presence.enabled", true)) return;
        JDA jda = jda();
        if (jda == null) return;
        String template = getConfig().getString("bot-presence.template", "%online%/%max% players online");
        String text = template.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));
        try {
            jda.getPresence().setActivity(Activity.playing(text));
        } catch (Exception exception) {
            getLogger().warning("Could not update bot presence: " + exception.getMessage());
        }
    }

    public void refreshDashboard() {
        if (automationManager != null) automationManager.dispatch(com.exenova.link.automation.Trigger.DASHBOARD_REFRESH, null, jda());
        String channelId = getConfig().getString("status-dashboard.channel-id", "");
        JDA jda = jda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        if (channel == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            EmbedBuilder embed = buildStatusDashboardEmbed();
            String messageId = getConfig().getString("status-dashboard.message-id", "");
            if (messageId.matches("\\d{17,20}")) {
                channel.retrieveMessageById(messageId).queue(message -> {
                    if (!message.getAuthor().getId().equals(jda.getSelfUser().getId())) {
                        getLogger().warning("Configured dashboard message is not owned by this bot. Sending a new dashboard message.");
                        sendDashboard(channel, embed);
                        return;
                    }
                    message.editMessageEmbeds(embed.build()).queue(success -> { }, failure -> {
                        getLogger().warning("Dashboard update failed; sending a replacement message: " + failure.getMessage());
                        sendDashboard(channel, embed);
                    });
                }, failure -> sendDashboard(channel, embed));
            }
            else sendDashboard(channel, embed);
        });
    }

    private void sendDashboard(TextChannel channel, EmbedBuilder embed) {
        channel.sendMessageEmbeds(embed.build()).queue(message -> Bukkit.getScheduler().runTask(this, () -> { getConfig().set("status-dashboard.message-id", message.getId()); saveConfig(); }));
    }

    private EmbedBuilder buildStatusDashboardEmbed() {
        long uptime = (System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()) / 60_000L;
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        int cpu = getProcessCpuPercent();
        int ram = (int) Math.min(100, Math.round((usedMb * 100.0) / Math.max(1, Runtime.getRuntime().maxMemory() / 1024 / 1024)));
        int network = isDiscordReady() ? 100 : 0;
        double tps = Bukkit.getTPS()[0];
        String health = tps >= 19.0 && cpu < 90 && ram < 90 && isDiscordReady() ? "Excellent" : tps >= 17.0 ? "Fair" : "Needs attention";
        String healthIcon = health.equals("Excellent") ? "🟢" : health.equals("Fair") ? "🟡" : "🔴";
        long now = Instant.now().getEpochSecond();
        String description = "╭━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╮\n"
                + "┃            🌐 SERVER STATUS             ┃\n"
                + "╰━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╯\n\n"
                + "🟢 **Status**\n▸ Online\n🟩 All systems operational.\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "👥 **Players**\n▸ " + Bukkit.getOnlinePlayers().size() + " / " + Bukkit.getMaxPlayers() + " Connected\n\n"
                + "⏱️ **Uptime**\n▸ " + (uptime / 60) + " Hours • " + (uptime % 60) + " Minutes\n\n"
                + "💾 **Memory**\n▸ " + String.format("%,d", usedMb) + " MB Used\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + statusDot(cpu) + " CPU        " + progressBar(cpu) + " " + cpu + "%\n"
                + statusDot(ram) + " RAM        " + progressBar(ram) + " " + ram + "%\n"
                + (network == 100 ? "🟢" : "🔴") + " Network    " + progressBar(network) + " " + network + "%\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + healthIcon + " Server Health: **" + health + "**\n"
                + "🕒 Last Updated: <t:" + now + ":f>";
        return new EmbedBuilder().setDescription(description).setColor(health.equals("Excellent") ? Color.decode("#57F287") : health.equals("Fair") ? Color.decode("#FEE75C") : Color.decode("#ED4245"));
    }

    private int getProcessCpuPercent() {
        java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            double load = ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuLoad();
            if (load >= 0) return (int) Math.round(Math.min(1, load) * 100);
        }
        return 0;
    }

    private String progressBar(int percent) {
        int filled = Math.max(0, Math.min(10, (int) Math.round(percent / 10.0)));
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private String statusDot(int percent) { return percent < 80 ? "🟢" : percent < 95 ? "🟡" : "🔴"; }

    private void scheduleAutoBackups() {
        if (!getConfig().getBoolean("backups.auto.enabled", false)) return;
        long hours = Math.max(1, getConfig().getLong("backups.auto.interval-hours", 24));
        schedulerService.repeatAsync(hours * 3600L * 20L, hours * 3600L * 20L, this::autoBackupConfig);
    }

    private void autoBackupConfig() {
        BackupService backups = backupService;
        if (backups == null) return;
        try {
            backups.create();
            backups.prune(getConfig().getInt("backups.auto.keep", 10));
        } catch (IOException exception) { getLogger().warning("Automatic backup failed: " + exception.getMessage()); }
    }

    private void sendDiagnostics(CommandSender sender) {
        sender.sendMessage("§6ExeNovaLink diagnostics");
        sender.sendMessage("§7Discord connection: " + (isDiscordReady() ? "§aREADY" : "§cNOT READY"));
        sender.sendMessage("§7Reports channel: " + channelStatus("reports.channel-id"));
        sender.sendMessage("§7Suggestions channel: " + channelStatus("suggestions.channel-id"));
        sender.sendMessage("§7Announcements relay: " + (getConfig().getBoolean("announcements.enabled") ? "§aENABLED" : "§eDISABLED"));
        sender.sendMessage("§7Status dashboard: " + (getConfig().getBoolean("status-dashboard.enabled") ? "§aENABLED" : "§eDISABLED"));
    }

    private String channelStatus(String path) { return getConfig().getString(path, "").matches("\\d{17,20}") ? "§aCONFIGURED" : "§cINVALID / MISSING"; }

    // === Periodic log channel purging ===

    private void scheduleLogPurgeTask() {
        if (!getConfig().getBoolean("log-purge.enabled", false)) {
            return;
        }

        long intervalMinutes = getConfig().getLong("log-purge.interval-minutes", 5);
        if (intervalMinutes <= 0) {
            getLogger().warning("log-purge.interval-minutes must be greater than 0; log purging is disabled.");
            return;
        }

        long intervalTicks = intervalMinutes * 60L * 20L; // minutes -> ticks (20 ticks/sec)

        // Runs asynchronously since it only performs Discord network calls and
        // never touches Bukkit API - it must NOT run on the main thread.
        logPurgeTask = schedulerService.repeatAsync(intervalTicks, intervalTicks, this::purgeLogChannel);
        getLogger().info("Log channel purging enabled: every " + intervalMinutes + " minute(s).");
    }

    private void purgeLogChannel() {
        if (!discordReady) {
            return;
        }

        String channelId = getConfig().getString("log-purge.channel-id");
        if (channelId == null || channelId.isEmpty() || channelId.equals("YOUR_LOGS_CHANNEL_ID")) {
            getLogger().warning("log-purge.channel-id not set in config.yml! Skipping this purge run.");
            return;
        }

        try {
            Long.parseLong(channelId);
        } catch (NumberFormatException e) {
            getLogger().severe("Invalid log-purge.channel-id format: " + channelId);
            return;
        }

        JDA jda = jda();
        if (jda == null) {
            return;
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            getLogger().warning("Could not find log-purge channel with ID: " + channelId);
            return;
        }

        int perRequestLimit = 100; // Discord's max messages per history request
        int maxBatches = Math.max(1, getConfig().getInt("log-purge.max-batches-per-run", 5));
        int totalPurged = 0;

        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                List<Message> messages = channel.getHistory().retrievePast(perRequestLimit).complete();
                if (messages.isEmpty()) {
                    break;
                }

                // purgeMessages() is asynchronous. Wait for this batch to be
                // deleted before fetching the next one, otherwise the next
                // history request returns the very same messages again and
                // every batch re-deletes (and double-counts) one page.
                java.util.concurrent.CompletableFuture.allOf(
                        channel.purgeMessages(messages).toArray(new java.util.concurrent.CompletableFuture<?>[0]))
                        .get(60, TimeUnit.SECONDS);
                totalPurged += messages.size();

                if (messages.size() < perRequestLimit) {
                    // Fewer messages than requested means the channel is now
                    // (almost) empty - no need to keep looping.
                    break;
                }
            }

            if (totalPurged > 0) {
                getLogger().info("Purged " + totalPurged + " message(s) from the log channel (" + channelId + ").");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            getLogger().warning("Failed to purge log channel messages after " + totalPurged + " deletion(s): " + e.getMessage());
        }
    }

    private void sendEmbed(String title, String description, String hexColor, 
                          org.bukkit.configuration.ConfigurationSection author, 
                          org.bukkit.configuration.ConfigurationSection footer, 
                          boolean includeTimestamp, boolean blocking) {

        String channelId = getConfig().getString("channel-id");

        if (channelId == null || channelId.isEmpty() || channelId.equals("YOUR_DISCORD_CHANNEL_ID")) {
            getLogger().warning("Channel ID not set in config.yml! Please configure a valid Discord channel ID.");
            return;
        }

        // Validate the channel ID format
        try {
            Long.parseLong(channelId);
        } catch (NumberFormatException e) {
            getLogger().severe("Invalid channel ID format: " + channelId + ". Please use a valid Discord channel ID.");
            return;
        }

        try {
            JDA jda = jda();
            if (jda == null) {
                getLogger().severe("The Discord bot is not connected yet.");
                return;
            }
            
            // Get the text channel
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                getLogger().warning("Could not find text channel with ID: " + channelId + ". Make sure the channel exists and the bot has access to it.");
                return;
            }

            // Build the embed using standard JDA EmbedBuilder
            EmbedBuilder embedBuilder = new EmbedBuilder();
            
            // Set basic properties
            if (title != null) {
                embedBuilder.setTitle(title);
            }
            
            if (description != null) {
                embedBuilder.setDescription(description);
            }
            
            // Handle color
            if (hexColor != null) {
                try {
                    Color color = parseColor(hexColor);
                    embedBuilder.setColor(color);
                } catch (NumberFormatException e) {
                    getLogger().warning("Invalid color format: " + hexColor + ", using default color.");
                    embedBuilder.setColor(Color.GRAY);
                }
            }
            
            // Handle author if provided
            if (author != null) {
                String authorName = author.getString("name");
                String authorIconUrl = author.getString("icon-url");
                
                if (authorName != null) {
                    // Replace placeholders in author icon URL if needed
                    if (authorIconUrl != null) {
                        authorIconUrl = replacePlaceholders(authorIconUrl);
                    }
                    
                    if (authorIconUrl != null && !authorIconUrl.isEmpty()) {
                        embedBuilder.setAuthor(authorName, null, authorIconUrl);
                    } else {
                        embedBuilder.setAuthor(authorName);
                    }
                }
            }
            
            // Handle footer if provided
            if (footer != null) {
                String footerText = footer.getString("text");
                String footerIconUrl = footer.getString("icon-url");
                
                if (footerText != null) {
                    // Replace placeholders in footer icon URL if needed
                    if (footerIconUrl != null) {
                        footerIconUrl = replacePlaceholders(footerIconUrl);
                    }
                    
                    if (footerIconUrl != null && !footerIconUrl.isEmpty()) {
                        embedBuilder.setFooter(footerText, footerIconUrl);
                    } else {
                        embedBuilder.setFooter(footerText);
                    }
                }
            }
            
            // Handle timestamp if enabled
            if (includeTimestamp) {
                embedBuilder.setTimestamp(Instant.now());
            }
            
            if (blocking) {
                // During onDisable() the JVM is already shutting down, so a
                // fire-and-forget .queue() call may never actually reach
                // Discord. Wait for it here so it has a chance to finish
                // before the plugin/server process exits - but with a hard
                // timeout via submit().get(), since an indefinite complete()
                // can be aborted by the server's own shutdown/watchdog
                // interrupting the main thread mid-wait (which surfaces as a
                // JDA "interrupted" I/O error and can stall shutdown).
                try {
                    channel.sendMessageEmbeds(embedBuilder.build())
                            .submit()
                            .get(5, TimeUnit.SECONDS);
                    getLogger().info("Sent status embed to Discord channel: " + channelId);
                } catch (TimeoutException e) {
                    getLogger().warning("Timed out waiting to confirm the shutdown embed was delivered (server is stopping, this is often unavoidable).");
                } catch (Exception e) {
                    getLogger().warning("Could not confirm the shutdown embed was delivered (server is stopping, this is often unavoidable): " + e.getMessage());
                }
            } else {
                // Send the embed with proper success/failure callbacks
                channel.sendMessageEmbeds(embedBuilder.build()).queue(
                    success -> getLogger().info("Discord accepted the embed."),
                    failure -> {
                        getLogger().severe("Discord rejected the embed! Error: " + failure.getMessage());
                        failure.printStackTrace();
                    }
                );

                getLogger().info("Queued status embed to Discord channel: " + channelId);
            }
            
        } catch (Exception e) {
            getLogger().severe("Error sending Discord embed: " + e.getMessage());
            if (e.getCause() != null) {
                getLogger().severe("Caused by: " + e.getCause().getMessage());
            }
            e.printStackTrace();
        }
    }
    
    // Helper method to parse color with better error handling
    private Color parseColor(String hexColor) throws NumberFormatException {
        if (hexColor.startsWith("#")) {
            hexColor = hexColor.substring(1);
        }
        
        if (hexColor.length() != 6) {
            throw new NumberFormatException("Invalid hex color format: " + hexColor);
        }
        
        return Color.decode("#" + hexColor);
    }
    
    private String replacePlaceholders(String input) {
        // Replace bot avatar URL placeholder
        if (input != null && input.contains("%botavatarurl%")) {
            try {
                JDA jda = jda();
                if (jda != null) {
                    String avatarUrl = jda.getSelfUser().getAvatarUrl();
                    if (avatarUrl != null) {
                        input = input.replace("%botavatarurl%", avatarUrl);
                    } else {
                        // Fallback to default avatar if none is set
                        input = input.replace("%botavatarurl%", "https://cdn.discordapp.com/embed/avatars/0.png");
                    }
                }
            } catch (Exception e) {
                // If we can't get the avatar URL, use a default one
                input = input.replace("%botavatarurl%", "https://cdn.discordapp.com/embed/avatars/0.png");
            }
        }
        
        return input;
    }
}

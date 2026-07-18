package me.example.statusembed;

import me.example.statusembed.automation.AutomationManager;
import me.example.statusembed.config.ConfigService;
import me.example.statusembed.scheduler.SchedulerService;
import me.example.statusembed.storage.AuditLogService;
import me.example.statusembed.storage.NotesRepository;
import me.example.statusembed.verification.VerificationService;
import me.example.statusembed.dashboard.DashboardService;
import me.example.statusembed.reports.ReportStateStore;
import me.example.statusembed.suggestions.SuggestionService;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.commands.PluginSlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommand;
import github.scarsz.discordsrv.api.commands.SlashCommandProvider;
import github.scarsz.discordsrv.api.events.DiscordReadyEvent;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageChannel;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.SlashCommandEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.MessageReceivedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.CommandData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.OptionType;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.commands.build.OptionData;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.Button;
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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class StatusEmbed extends JavaPlugin implements Listener, SlashCommandProvider {

    private AutomationManager automationManager;
    private ConfigService configService;
    private SchedulerService schedulerService;
    private AuditLogService auditLogService;

    private boolean discordReady = false;
    private boolean serverStarted = false;
    private boolean jdaListenerRegistered = false;
    private BukkitTask logPurgeTask;
    private BukkitTask statusDashboardTask;
    private BukkitTask autoBackupTask;
    private static final Color EMBED_COLOR = Color.decode("#5865F2");
    private final ReportStateStore reportState = new ReportStateStore();
    private final Map<String, Long> discordCommandCooldowns = new ConcurrentHashMap<>();
    private NotesRepository notesRepository;
    private VerificationService verificationService;
    private SuggestionService suggestionService;
    private DashboardService dashboardService;

    @Override
    public void onEnable() {
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

        // Check if DiscordSRV is available
        if (Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) {
            getLogger().severe("DiscordSRV not found! This plugin requires DiscordSRV to work.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        automationManager = new AutomationManager(this);
        automationManager.load();
        Bukkit.getPluginManager().registerEvents(automationManager, this);
        notesRepository = new NotesRepository(this);
        verificationService = new VerificationService(this);
        suggestionService = new SuggestionService(this);
        dashboardService = new DashboardService(this);
        verifyRegisteredCommands();
        DiscordSRV.api.subscribe(this);

        // DiscordSRV may have already finished connecting to Discord and
        // fired DiscordReadyEvent before this plugin's onEnable() ran (plugin
        // load/enable order isn't instantaneous), in which case that one-time
        // event was already missed. Check the static flag directly as a
        // fallback so we don't wait forever for an event that already fired.
        if (DiscordSRV.isReady) {
            discordReady = true;
            getLogger().info("DiscordSRV was already ready when this plugin enabled.");
            registerJdaMessageListenerIfNeeded();
            setupVerificationPanel();
            publishChangelog();
        }

        scheduleLogPurgeTask();
        scheduleStatusDashboard();
        scheduleAutoBackups();
        schedulerService.repeatSync(20L * 60L, 20L * 60L, this::expireReportState);
        schedulerService.repeatAsync(20L * 60L, 20L * 60L, this::pruneCooldowns);

        getLogger().info("DiscordSRVStatusEmbed has been enabled!");
    }

    @Subscribe
    public void onDiscordReady(DiscordReadyEvent event) {
        discordReady = true;
        getLogger().info("DiscordSRV is ready, JDA is connected.");
        registerJdaMessageListenerIfNeeded();
        setupVerificationPanel();
        publishChangelog();
        logStartupDiagnostics();

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
        serverStarted = true;

        if (!getConfig().getBoolean("server-start.enabled"))
            return;

        // Only send if DiscordSRV is already ready, otherwise wait for DiscordReadyEvent
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
            getLogger().info("Server started but DiscordSRV not ready yet, waiting for DiscordReadyEvent...");
        }
    }

    @Override
    public void onDisable() {
        if (automationManager != null) {
            automationManager.shutdown();
            automationManager = null;
        }
        if (schedulerService != null) {
            schedulerService.cancelAll();
            schedulerService = null;
        }
        auditLogService = null;
        notesRepository = null;
        verificationService = null;
        suggestionService = null;
        DiscordSRV.api.unsubscribe(this);

        if (logPurgeTask != null) {
            logPurgeTask.cancel();
            logPurgeTask = null;
        }
        if (statusDashboardTask != null) statusDashboardTask.cancel();
        if (dashboardService != null) {
            dashboardService.stop();
            dashboardService = null;
        }
        if (autoBackupTask != null) autoBackupTask.cancel();

        if (!getConfig().getBoolean("server-stop.enabled"))
            return;

        // Only attempt to notify Discord if DiscordSRV actually got ready at some point
        if (!discordReady) {
            getLogger().info("Skipping server-stop embed: DiscordSRV was never ready.");
            return;
        }

        // onDisable runs during JVM shutdown, so the async JDA request must be
        // sent with a blocking call - fire-and-forget .queue() calls are not
        // guaranteed to complete before the process exits.
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

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("discordstatusreload")) {
            if (!sender.hasPermission("discordstatus.reload") && !sender.isOp()) {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            
            reloadConfig();
            if (discordReady) publishChangelog();
            sender.sendMessage("§aDiscordSRVStatusEmbed configuration reloaded!");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("dsreport")) {
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
                reportState.targets().put(reporter.getUniqueId(), target.getUniqueId());
                reportState.started().put(reporter.getUniqueId(), System.currentTimeMillis());
                openReportReasonMenu(reporter);
            } else openReportPlayerMenu(reporter);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("dsnote")) {
            if (!sender.hasPermission("discordstatus.notes")) { sender.sendMessage("§cYou do not have permission to manage staff notes."); return true; }
            handleNoteCommand(sender, args);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("dswatch")) {
            if (!sender.hasPermission("discordstatus.watchlist")) { sender.sendMessage("§cYou do not have permission to manage the watchlist."); return true; }
            handleWatchCommand(sender, args);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("discordstatusdiagnostics")) {
            if (!sender.hasPermission("discordstatus.config")) { sender.sendMessage("§cYou do not have permission to view diagnostics."); return true; }
            sendDiagnostics(sender);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("discordstatusconfig")) {
            if (!sender.hasPermission("discordstatus.config")) { sender.sendMessage("§cYou do not have permission to edit plugin settings."); return true; }
            if (!(sender instanceof Player)) { sender.sendMessage("This configuration menu can only be opened in-game."); return true; }
            openConfigMenu((Player) sender);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("discordstatusbackup")) {
            if (!sender.hasPermission("discordstatus.config")) { sender.sendMessage("§cYou do not have permission to back up settings."); return true; }
            backupConfig(sender);
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("discordstatusrestore")) {
            if (!sender.hasPermission("discordstatus.config")) { sender.sendMessage("§cYou do not have permission to restore settings."); return true; }
            if (args.length != 1) { sender.sendMessage("§eUsage: /discordstatusrestore <backup-file.yml>"); return true; }
            restoreConfig(sender, args[0]);
            return true;
        }
        return false;
    }
    
    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("discordstatusreload")) {
            return java.util.Collections.emptyList();
        }
        if ((cmd.getName().equalsIgnoreCase("dsreport") || cmd.getName().equalsIgnoreCase("dsnote") || cmd.getName().equalsIgnoreCase("dswatch")) && args.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase())).collect(java.util.stream.Collectors.toList());
        return null;
    }

    // === Server info slash commands (DiscordSRV's own slash command API) ===

    private void verifyRegisteredCommands() {
        for (String name : Arrays.asList("dsreport", "dsnote", "dswatch", "discordstatusdiagnostics", "discordstatusconfig", "discordstatusbackup", "discordstatusrestore", "discordstatusreload")) {
            if (getCommand(name) == null) getLogger().severe("Command /" + name + " was not registered. Install the current JAR and fully restart the server.");
            else getLogger().info("Registered in-game command /" + name + ".");
        }
    }

    @Override
    public Set<PluginSlashCommand> getSlashCommands() {
        Set<PluginSlashCommand> commands = new HashSet<>();
        if (getConfig().getBoolean("server-info.enabled", true)) {
            commands.add(new PluginSlashCommand(this, new CommandData("help", "Show all available server commands")));
            commands.add(new PluginSlashCommand(this, new CommandData("ip", "Get the Java Edition server address")));
            commands.add(new PluginSlashCommand(this, new CommandData("server", "Get the Java Edition server address")));
            commands.add(new PluginSlashCommand(this, new CommandData("java", "Get the Java Edition server address")));
            commands.add(new PluginSlashCommand(this, new CommandData("bedrock", "Get the Bedrock Edition server address")));
            commands.add(new PluginSlashCommand(this, new CommandData("bedrockip", "Get the Bedrock Edition address and port")));
            commands.add(new PluginSlashCommand(this, new CommandData("version", "Get the Minecraft server version")));
            commands.add(new PluginSlashCommand(this, new CommandData("playerlist", "List currently online players")));
            commands.add(new PluginSlashCommand(this, new CommandData("players", "List currently online players")));
            commands.add(new PluginSlashCommand(this, new CommandData("status", "Show the server status")));
            commands.add(new PluginSlashCommand(this, new CommandData("motd", "Show the server MOTD")));
            commands.add(new PluginSlashCommand(this, new CommandData("ping", "Show bot latency")));
        }
        commands.add(new PluginSlashCommand(this, new CommandData("purge", "Delete recent messages")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Number of messages to delete", true).setRequiredRange(1, 100))));
        commands.add(new PluginSlashCommand(this, new CommandData("prefix", "Show or change the legacy command prefix")
                .addOptions(new OptionData(OptionType.STRING, "value", "New prefix (owner only)", false))));
        commands.add(new PluginSlashCommand(this, new CommandData("suggest", "Learn how to submit a suggestion")));
        commands.add(new PluginSlashCommand(this, new CommandData("gcash", "Show donation details")));
        commands.add(new PluginSlashCommand(this, new CommandData("faq", "Show frequently asked questions")));
        commands.add(new PluginSlashCommand(this, new CommandData("staff", "Show the server staff team")));
        commands.add(new PluginSlashCommand(this, new CommandData("report", "Learn how to report a player")));
        commands.add(new PluginSlashCommand(this, new CommandData("playtime", "Show a player's playtime (use !playtime <player>)")));
        commands.add(new PluginSlashCommand(this, new CommandData("seen", "Show when a player was last seen (use !seen <player>)")));
        commands.add(new PluginSlashCommand(this, new CommandData("stats", "Show player stats (use !stats <player>)")));
        commands.add(new PluginSlashCommand(this, new CommandData("leaderboard", "Show the configured leaderboard")));
        commands.add(new PluginSlashCommand(this, new CommandData("profile", "Show a player profile (use !profile <player>)")));
        return commands;
    }

    @SlashCommand(path = "java", deferReply = true)
    public void onJavaSlashCommand(SlashCommandEvent event) {
        event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue();
    }

    @SlashCommand(path = "ip", deferReply = true)
    public void onIpSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue(); }

    @SlashCommand(path = "server", deferReply = true)
    public void onServerSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildJavaInfoEmbed()).queue(); }

    @SlashCommand(path = "help", deferReply = true)
    public void onHelpSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildHelpEmbed()).queue(); }

    @SlashCommand(path = "bedrock", deferReply = true)
    public void onBedrockSlashCommand(SlashCommandEvent event) {
        event.getHook().sendMessageEmbeds(buildBedrockInfoEmbed()).queue();
    }

    @SlashCommand(path = "bedrockip", deferReply = true)
    public void onBedrockIpSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildBedrockInfoEmbed()).queue(); }

    @SlashCommand(path = "version", deferReply = true)
    public void onVersionSlashCommand(SlashCommandEvent event) {
        event.getHook().sendMessageEmbeds(buildVersionEmbed()).queue();
    }

    @SlashCommand(path = "playerlist", deferReply = true)
    public void onPlayerListSlashCommand(SlashCommandEvent event) {
        fetchPlayerListEmbedAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue());
    }

    @SlashCommand(path = "players", deferReply = true)
    public void onPlayersSlashCommand(SlashCommandEvent event) { fetchPlayerListEmbedAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue()); }

    @SlashCommand(path = "status", deferReply = true)
    public void onStatusSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildStatusEmbed()).queue(); }

    @SlashCommand(path = "motd", deferReply = true)
    public void onMotdSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildMotdEmbed()).queue(); }

    @SlashCommand(path = "ping", deferReply = true)
    public void onPingSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildPingEmbed()).queue(); }

    @SlashCommand(path = "purge", deferReply = true)
    public void onPurgeSlashCommand(SlashCommandEvent event) {
        int amount = event.getOption("amount") == null ? 0 : (int) event.getOption("amount").getAsLong();
        if (!isDiscordOwner(event.getUser().getId())) {
            event.getHook().sendMessage("You are not authorized to use this command.").queue();
            return;
        }
        purgeChannel(event.getChannel(), amount, event.getHook()::sendMessage);
    }

    @SlashCommand(path = "prefix", deferReply = true)
    public void onPrefixSlashCommand(SlashCommandEvent event) {
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

    @SlashCommand(path = "suggest", deferReply = true)
    public void onSuggestSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildSuggestionPromptEmbed()).queue(); }

    @SlashCommand(path = "gcash", deferReply = true)
    public void onGcashSlashCommand(SlashCommandEvent event) {
        File qrCode = getDonationQrFile();
        if (qrCode.isFile()) event.getHook().sendFile(qrCode, qrCode.getName()).addEmbeds(buildDonationEmbed()).queue();
        else event.getHook().sendMessageEmbeds(buildDonationEmbed()).queue();
    }

    @SlashCommand(path = "faq", deferReply = true)
    public void onFaqSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildFaqEmbed()).queue(); }

    @SlashCommand(path = "staff", deferReply = true)
    public void onStaffSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildStaffEmbed()).queue(); }

    @SlashCommand(path = "report", deferReply = true)
    public void onReportSlashCommand(SlashCommandEvent event) { event.getHook().sendMessageEmbeds(buildReportInfoEmbed()).queue(); }

    @SlashCommand(path = "leaderboard", deferReply = true)
    public void onLeaderboardSlashCommand(SlashCommandEvent event) { fetchLeaderboardAsync(embed -> event.getHook().sendMessageEmbeds(embed).queue()); }

    @SlashCommand(path = "profile", deferReply = true)
    public void onProfileSlashCommand(SlashCommandEvent event) {
        event.getHook().sendMessageEmbeds(new EmbedBuilder().setTitle("❖ Player Profile")
                .setDescription("Use `!profile <player>` to view a full player profile.").setColor(EMBED_COLOR).build()).queue();
    }

    // === "!" prefix chat commands, e.g. "!java" ===
    // DiscordSRV only provides the slash-command API above; the "!"-prefixed
    // text commands are handled with our own JDA message listener registered
    // directly on the JDA instance DiscordSRV owns.

    private void registerJdaMessageListenerIfNeeded() {
        if (jdaListenerRegistered) {
            return;
        }

        JDA jda = DiscordSRV.getPlugin().getJda();
        if (jda == null) {
            return;
        }

        jda.addEventListener(new ListenerAdapter() {
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
                        purgeChannel(channel, parseAmount(withoutPrefix.substring(command.length()).trim()), channel::sendMessage);
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
            public void onButtonClick(ButtonClickEvent event) {
                handleDiscordButton(event);
            }
        });

        jdaListenerRegistered = true;
        getLogger().info("Registered \"" + getConfig().getString("server-info.prefix", "!") + "\" prefix command listener.");
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildJavaInfoEmbed() {
        String ip = getConfig().getString("server-info.java-ip", "not configured");
        int port = getConfig().getInt("server-info.java-port", 25565);
        String address = port == 25565 ? ip : (ip + ":" + port);

        return new EmbedBuilder()
                .setTitle("◆ Java Edition")
                .setDescription("**Address:** `" + address + "`")
                .setColor(EMBED_COLOR)
                .build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildBedrockInfoEmbed() {
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

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildVersionEmbed() {
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

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildHelpEmbed() {
        return new EmbedBuilder()
                .setTitle("❖ ExeSMP Command Guide")
                .setDescription("Use the `!` prefix or Discord slash commands. Every command returns a polished embed.")
                .addField("🖥 Server Info", "`!ip`, `!server`, `!java` — Java address\n`!bedrock`, `!bedrockip` — Bedrock address\n`!status` — server availability\n`!motd`, `!version`, `!ping`", false)
                .addField("👥 Players", "`!players` / `!playerlist` — online players, ping and avatar\n`!profile <player>` — complete player overview\n`!playtime <player>`, `!seen <player>`, `!stats <player>`\n`!leaderboard [playtime|kills|deaths|mobs|mined]`", false)
                .addField("💡 Community", "`!suggest <idea>` — submit an idea for voting\n`!faq`, `!staff` — configurable server information\n`!report` — reporting instructions; use `/dsreport` in-game", false)
                .addField("💙 Donations", "`!gcash` — view the GCash QR code and donation details", false)
                .setFooter("ExeSMP • Need help? Contact the staff team.")
                .setColor(EMBED_COLOR).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildStatusEmbed() {
        int online = Bukkit.getOnlinePlayers().size();
        return new EmbedBuilder().setTitle("❖ Server Status")
                .setDescription("**Status:** `ONLINE`\n**Players:** `" + online + "/" + Bukkit.getMaxPlayers() + "`")
                .setColor(Color.decode("#57F287")).setTimestamp(Instant.now()).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildMotdEmbed() {
        String motd = Bukkit.getMotd();
        return new EmbedBuilder().setTitle("❖ Server MOTD")
                .setDescription("```" + (motd == null ? "Not configured" : motd) + "```")
                .setColor(EMBED_COLOR).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildPingEmbed() {
        JDA jda = DiscordSRV.getPlugin().getJda();
        String latency = jda == null ? "Unavailable" : jda.getGatewayPing() + " ms";
        return new EmbedBuilder().setTitle("❖ Bot Latency").setDescription("🏓 `" + latency + "`")
                .setColor(EMBED_COLOR).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildSuggestionPromptEmbed() {
        return new EmbedBuilder().setTitle("💡 Submit a Suggestion")
                .addField("▪ What You Can Suggest", "► New server features\n► Improvements & bug fixes\n► Events & community activities\n► Discord channels & bot features\n► Quality-of-life improvements", false)
                .addField("▪ Your Feedback Matters", "Every suggestion will be reviewed and considered.\nYour ideas help improve the server for everyone.", false)
                .setDescription("Submit with `!suggest <your idea>`.")
                .setColor(EMBED_COLOR).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildDonationEmbed() {
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
            channel.sendFile(qrCode, qrCode.getName()).embed(buildDonationEmbed()).queue();
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

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildFaqEmbed() {
        EmbedBuilder embed = new EmbedBuilder().setTitle("❖ Frequently Asked Questions").setColor(getEmbedColor("embeds.info-color", "#5865F2"));
        org.bukkit.configuration.ConfigurationSection faq = getConfig().getConfigurationSection("faq");
        if (faq == null || faq.getKeys(false).isEmpty()) return embed.setDescription("No FAQ entries have been configured yet.").build();
        for (String key : faq.getKeys(false)) embed.addField(key.toUpperCase(), faq.getString(key, ""), false);
        return embed.build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildStaffEmbed() {
        EmbedBuilder embed = new EmbedBuilder().setTitle("❖ ExeSMP Staff Team").setColor(getEmbedColor("embeds.info-color", "#5865F2"));
        org.bukkit.configuration.ConfigurationSection staff = getConfig().getConfigurationSection("staff");
        if (staff == null || staff.getKeys(false).isEmpty()) return embed.setDescription("No staff roster has been configured yet.").build();
        for (String role : staff.getKeys(false)) {
            List<String> members = staff.getStringList(role).stream().map(String::trim).filter(member -> !member.isEmpty()).collect(java.util.stream.Collectors.toList());
            String displayRole = role.replace('-', ' ').replace('_', ' ');
            displayRole = displayRole.substring(0, 1).toUpperCase() + displayRole.substring(1);
            embed.addField(displayRole, members.isEmpty() ? "—" : String.join("\n", members), true);
        }
        return embed.build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildReportInfoEmbed() {
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
        JDA jda = DiscordSRV.getPlugin().getJda();
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
                message.addReaction("⭐").queue();
                    message.addReaction("❌").queue();
                    createSuggestionThread(message, submittedSuggestion);
                });
        source.sendMessageEmbeds(new EmbedBuilder().setTitle("💡 Suggestion submitted")
                .setDescription("Thank you! Your idea has been posted for community voting.").setColor(Color.decode("#57F287")).build()).queue();
    }

    private void handleDiscordButton(ButtonClickEvent event) {
        String id = event.getComponentId();
        if (id.equals("verify:minecraft")) {
            handleMinecraftVerification(event);
            return;
        }
        if (!(id.startsWith("report:") || id.startsWith("suggestion:"))) return;
        if (!isDiscordModerator(event.getUser().getId())) {
            event.deferReply(true).setContent("You are not authorized to manage this item.").queue();
            return;
        }
        String action = id.substring(id.indexOf(':') + 1);
        String label = action.substring(0, 1).toUpperCase() + action.substring(1);
        try {
            EmbedBuilder embed = event.getMessage().getEmbeds().isEmpty() ? new EmbedBuilder() : new EmbedBuilder(event.getMessage().getEmbeds().get(0));
            embed.addField("Status", label, true).addField("Handled by", event.getUser().getName(), true).setTimestamp(Instant.now());
            event.editMessageEmbeds(embed.build()).setActionRows(java.util.Collections.emptyList()).queue();
            audit("DISCORD_BUTTON user=" + event.getUser().getId() + " action=" + id + " message=" + event.getMessageId());
        } catch (Exception exception) {
            getLogger().warning("Could not update interactive message: " + exception.getMessage());
        }
    }

    /**
     * Creates a real Discord thread when the installed JDA exposes the
     * message thread API. Reflection keeps this plugin loadable with older
     * DiscordSRV builds whose relocated JDA predates threads.
     */
    private void createSuggestionThread(Message message, String suggestion) {
        if (!getConfig().getBoolean("suggestions.create-thread", true)) return;
        try {
            Method createThread = message.getClass().getMethod("createThreadChannel", String.class);
            String title = sanitizeUserInput(suggestion, 85);
            if (title.isEmpty()) title = "Suggestion discussion";
            Object threadAction = createThread.invoke(message, "💡 " + title);
            Method queue = threadAction.getClass().getMethod("queue");
            queue.invoke(threadAction);
            getLogger().info("Created a discussion thread for suggestion message " + message.getId() + ".");
        } catch (NoSuchMethodException exception) {
            getLogger().warning("Suggestion thread creation is unavailable in the installed DiscordSRV/JDA API. Upgrade DiscordSRV to a build exposing Message.createThreadChannel(String).");
        } catch (Exception exception) {
            getLogger().warning("Could not create suggestion thread for message " + message.getId() + ": " + exception.getMessage());
        }
    }

    private boolean isDiscordModerator(String userId) {
        return getConfig().getStringList("moderation.staff-user-ids").contains(userId);
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
            if (!recent.isEmpty()) { channel.purgeMessages(recent); done.run(); }
            for (Message message : old) message.delete().queue(success -> done.run(), failure -> done.run());
        }, failure -> reply.accept("Could not read channel history: " + failure.getMessage()));
    }

    private void publishChangelog() {
        if (!getConfig().getBoolean("changelog.enabled", true)) return;
        String channelId = getConfig().getString("changelog.channel-id", "");
        JDA jda = DiscordSRV.getPlugin().getJda();
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
        JDA jda = DiscordSRV.getPlugin().getJda();
        channel.retrieveMessageById(messageId).queue(message -> {
            if (jda != null && !message.getAuthor().getId().equals(jda.getSelfUser().getId())) { sendNewChangelog(channel, embed, pending); return; }
            message.editMessageEmbeds(embed.build()).queue(success -> markChangelogPublished(pending), failure -> sendNewChangelog(channel, embed, pending));
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

    private void sendNextChangelog(TextChannel channel, Map<?, ?> entry) {
        String version = String.valueOf(entry.get("version"));
        Object rawChanges = entry.get("changes");
        List<String> changes = rawChanges instanceof List ? ((List<?>) rawChanges).stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()) : java.util.Collections.singletonList(String.valueOf(rawChanges));
        channel.sendMessageEmbeds(new EmbedBuilder().setTitle("📋 ExeSMP Changelog • " + version)
                .setDescription(changes.stream().map(change -> "• " + change).collect(java.util.stream.Collectors.joining("\n")))
                .setColor(getEmbedColor("embeds.info-color", "#5865F2")).setTimestamp(Instant.now()).build()).queue(message -> Bukkit.getScheduler().runTask(this, () -> {
                    List<String> updated = new ArrayList<>(getConfig().getStringList("changelog.published-versions"));
                    if (!updated.contains(version)) { updated.add(version); getConfig().set("changelog.published-versions", updated); saveConfig(); }
                    publishChangelog();
                }), failure -> getLogger().warning("Could not publish changelog " + version + ": " + failure.getMessage()));
    }

    private void setupVerificationPanel() {
        if (!getConfig().getBoolean("verification.enabled", true)) return;
        String channelId = getConfig().getString("verification.channel-id", "");
        JDA jda = DiscordSRV.getPlugin().getJda();
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

    private void handleMinecraftVerification(ButtonClickEvent event) {
        String roleId = getConfig().getString("verification.role-id", "");
        if (event.getGuild() == null || event.getMember() == null || !roleId.matches("\\d{17,20}")) {
            event.deferReply(true).setContent("Verification is not configured correctly. Please contact staff.").queue();
            return;
        }
        github.scarsz.discordsrv.dependencies.jda.api.entities.Role role = event.getGuild().getRoleById(roleId);
        if (role == null) {
            event.deferReply(true).setContent("The Minecraft role could not be found. Please contact staff.").queue();
            return;
        }
        if (event.getMember().getRoles().contains(role)) {
            event.deferReply(true).setContent("You already have the Minecraft role.").queue();
            return;
        }
        event.deferReply(true).setContent("Verification complete — the Minecraft role has been assigned.").queue();
        if (verificationService != null && verificationService.assignRole(DiscordSRV.getPlugin().getJda(), event.getGuild().getId(), event.getUser().getId())) {
            audit("VERIFY user=" + event.getUser().getId());
        } else {
            getLogger().warning("Could not assign Minecraft role through VerificationService.");
        }
    }

    // Bukkit's player list can only be safely read on the main server thread,
    // but this is normally called from a JDA callback thread. Hop to the main
    // thread to read it, then hand the built embed back via the callback.
    private void fetchPlayerListEmbedAsync(java.util.function.Consumer<github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed> callback) {
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
                for (Player player : playerList) {
                    String avatar = getAvatarUrl(player.getName());
                    embedBuilder.addField("👤 " + player.getName(), "**Ping:** `" + getPlayerPing(player) + " ms`\n[View skin/avatar](" + avatar + ")", true);
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
        String provider = getConfig().getString("avatars.provider-url", "https://mc-heads.net/avatar/{player}/100");
        return provider.replace("{player}", playerName);
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
            github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed embed;
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

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildPlaytimeEmbed(OfflinePlayer player) {
        long ticks = stat(player, Statistic.PLAY_ONE_MINUTE);
        long minutes = ticks / 20L / 60L;
        return new EmbedBuilder().setTitle("❖ Playtime • " + player.getName())
                .setDescription("**Total:** `" + formatDuration(minutes) + "`\n**Hours:** `" + (minutes / 60) + "`\n**Minutes:** `" + (minutes % 60) + "`")
                .setThumbnail(getAvatarUrl(player.getName())).setColor(getEmbedColor("embeds.stats-color", "#5865F2")).build();
    }

    private OfflinePlayer resolveOfflinePlayer(String requestedName) {
        for (Player online : Bukkit.getOnlinePlayers()) if (online.getName().equalsIgnoreCase(requestedName)) return online;
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(requestedName) && offline.hasPlayedBefore()) return offline;
        }
        return null;
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildSeenEmbed(OfflinePlayer player) {
        long lastLogin = player.getLastLogin();
        long lastSeen = player.isOnline() ? System.currentTimeMillis() : player.getLastSeen();
        String status = player.isOnline() ? "Currently online" : formatAgo(lastSeen);
        return new EmbedBuilder().setTitle("❖ Last Seen • " + player.getName())
                .addField("Last login", formatDate(lastLogin), true).addField("Last logout", formatDate(player.getLastSeen()), true)
                .addField("Time since last seen", status, false).setThumbnail(getAvatarUrl(player.getName()))
                .setColor(getEmbedColor("embeds.stats-color", "#5865F2")).build();
    }

    private github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed buildStatsEmbed(OfflinePlayer player) {
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

    private long totalMined(OfflinePlayer player) {
        long total = 0;
        for (Material material : Material.values()) if (material.isBlock()) {
            try { total += player.getStatistic(Statistic.MINE_BLOCK, material); } catch (IllegalArgumentException ignored) { }
        }
        return total;
    }

    private void fetchLeaderboardAsync(java.util.function.Consumer<github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed> callback) {
        fetchLeaderboardAsync(callback, "");
    }

    private void fetchLeaderboardAsync(java.util.function.Consumer<github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed> callback, String requestedType) {
        Bukkit.getScheduler().runTask(this, () -> {
            String type = normalizeLeaderboardType(requestedType);
            int limit = Math.max(1, getConfig().getInt("leaderboards.limit", 10));
            List<OfflinePlayer> players = new ArrayList<>(Arrays.asList(Bukkit.getOfflinePlayers()));
            players.removeIf(player -> player.getName() == null);
            players.sort(Comparator.comparingLong((OfflinePlayer player) -> leaderboardValue(player, type)).reversed());
            EmbedBuilder embed = new EmbedBuilder().setTitle("❖ " + type.replace('_', ' ') + " Leaderboard")
                    .setColor(getEmbedColor("embeds.stats-color", "#5865F2"));
            StringBuilder description = new StringBuilder();
            int rank = 1;
            for (OfflinePlayer player : players) {
                long value = leaderboardValue(player, type);
                if (value <= 0 || rank > limit) break;
                description.append('`').append(rank++).append(".` **").append(player.getName()).append("** — ")
                        .append(formatLeaderboardValue(value, type)).append('\n');
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
        JDA jda = DiscordSRV.getPlugin().getJda();
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
        Inventory menu = Bukkit.createInventory(null, 54, "Report: Choose Player");
        int candidates = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(reporter)) continue;
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
        Inventory menu = Bukkit.createInventory(null, 27, "Report: Choose Reason");
        List<String> reasons = getConfig().getStringList("reports.categories");
        for (String reason : reasons) menu.addItem(namedItem(Material.PAPER, "§e" + reason, "§7Click to select this reason"));
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
        if (!(event.getWhoClicked() instanceof Player) || event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;
        Player reporter = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        if (!title.equals("Report: Choose Player") && !title.equals("Report: Choose Reason")) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (title.equals("Report: Choose Player")) {
            if (!(event.getCurrentItem().getItemMeta() instanceof SkullMeta)) return;
            OfflinePlayer target = ((SkullMeta) event.getCurrentItem().getItemMeta()).getOwningPlayer();
            if (target == null) return;
            reportState.targets().put(reporter.getUniqueId(), target.getUniqueId());
            reportState.started().put(reporter.getUniqueId(), System.currentTimeMillis());
            openReportReasonMenu(reporter);
        } else {
            String reason = event.getCurrentItem().getItemMeta().getDisplayName().replace("§e", "");
            reportState.reasons().put(reporter.getUniqueId(), reason);
            reporter.closeInventory();
            if (getConfig().getBoolean("reports.request-details", true)) {
                reportState.awaitingDetails().add(reporter.getUniqueId());
                reporter.sendMessage("§eType any extra report details in chat, or type §fskip§e to submit now.");
            } else submitReport(reporter, "No additional details provided.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReportChat(AsyncPlayerChatEvent event) {
        Player reporter = event.getPlayer();
        if (!reportState.awaitingDetails().remove(reporter.getUniqueId())) return;
        event.setCancelled(true);
        String details = event.getMessage().equalsIgnoreCase("skip") ? "No additional details provided." : event.getMessage();
        Bukkit.getScheduler().runTask(this, () -> submitReport(reporter, details));
    }

    @EventHandler
    public void onReportInventoryClose(InventoryCloseEvent event) {
        // Kept intentionally empty: closing before selecting a reason simply cancels the report.
    }

    private void submitReport(Player reporter, String details) {
        java.util.UUID targetId = reportState.targets().remove(reporter.getUniqueId());
        String reason = reportState.reasons().remove(reporter.getUniqueId());
        reportState.started().remove(reporter.getUniqueId());
        if (targetId == null || reason == null) return;
        details = sanitizeUserInput(details, 800);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        reporter.sendMessage("§aYour report has been submitted. Thank you for helping keep the server safe.");
        for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("discordstatus.reports.notify")) {
            staff.sendMessage("§c[Report] §f" + reporter.getName() + " reported " + target.getName() + " for " + reason + ".");
        }
        String channelId = getConfig().getString("reports.channel-id", "");
        JDA jda = DiscordSRV.getPlugin().getJda();
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
        Inventory menu = Bukkit.createInventory(null, 27, "Discord Status Settings");
        menu.setItem(11, namedItem(Material.BELL, "§eAnnouncements: " + enabledLabel("announcements.enabled"), "§7Toggle Discord announcement relay"));
        menu.setItem(13, namedItem(Material.REDSTONE_TORCH, "§eReports: " + enabledLabel("commands.report.enabled"), "§7Toggle in-game reporting"));
        menu.setItem(15, namedItem(Material.COMMAND_BLOCK, "§ePrefix commands: " + enabledLabel("server-info.enabled"), "§7Toggle legacy ! commands"));
        menu.setItem(22, namedItem(Material.LIME_DYE, "§aSave changes", "§7Changes are saved immediately"));
        player.openInventory(menu);
    }

    private String enabledLabel(String path) { return getConfig().getBoolean(path, true) ? "§aON" : "§cOFF"; }

    @EventHandler
    public void onConfigInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("Discord Status Settings") || !(event.getWhoClicked() instanceof Player)) return;
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("discordstatus.config")) { player.closeInventory(); return; }
        String path = event.getRawSlot() == 11 ? "announcements.enabled" : event.getRawSlot() == 13 ? "commands.report.enabled" : event.getRawSlot() == 15 ? "server-info.enabled" : null;
        if (path == null) return;
        boolean next = !getConfig().getBoolean(path, true);
        getConfig().set(path, next);
        saveConfig();
        audit("CONFIG actor=" + player.getName() + " " + path + "=" + next);
        player.sendMessage("§aUpdated " + path + " to " + next + ".");
        openConfigMenu(player);
    }

    private void backupConfig(CommandSender sender) {
        try {
            File backupDir = new File(getDataFolder(), "backups");
            if (!backupDir.exists() && !backupDir.mkdirs()) throw new IOException("Could not create backups folder");
            String name = "config-" + System.currentTimeMillis() + ".yml";
            Files.copy(new File(getDataFolder(), "config.yml").toPath(), new File(backupDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            sender.sendMessage("§aConfiguration backup created: " + name);
            audit("BACKUP actor=" + sender.getName() + " file=" + name);
        } catch (IOException exception) { sender.sendMessage("§cCould not create backup: " + exception.getMessage()); getLogger().warning("Config backup failed: " + exception.getMessage()); }
    }

    private void restoreConfig(CommandSender sender, String fileName) {
        if (!fileName.matches("config-\\d+\\.yml")) { sender.sendMessage("§cInvalid backup filename. Use a file made by /discordstatusbackup."); return; }
        File source = new File(new File(getDataFolder(), "backups"), fileName);
        if (!source.isFile()) { sender.sendMessage("§cBackup not found: " + fileName); return; }
        try {
            Files.copy(source.toPath(), new File(getDataFolder(), "config.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            reloadConfig();
            sender.sendMessage("§aConfiguration restored. Use /discordstatusreload if DiscordSRV was already registering commands.");
            audit("RESTORE actor=" + sender.getName() + " file=" + fileName);
        } catch (IOException exception) { sender.sendMessage("§cCould not restore backup: " + exception.getMessage()); getLogger().warning("Config restore failed: " + exception.getMessage()); }
    }

    private void audit(String entry) {
        if (auditLogService != null) auditLogService.write(entry);
    }

    private void expireReportState() {
        long cutoff = System.currentTimeMillis() - Math.max(5L, getConfig().getLong("reports.state-timeout-minutes", 30L)) * 60_000L;
        reportState.started().entrySet().removeIf(entry -> {
            if (entry.getValue() >= cutoff) return false;
            UUID id = entry.getKey();
            reportState.clear(id);
            return true;
        });
    }

    private void logStartupDiagnostics() {
        getLogger().info("Startup diagnostics: DiscordSRV=" + (discordReady ? "READY" : "WAITING")
                + ", reports=" + channelStatus("reports.channel-id")
                + ", suggestions=" + channelStatus("suggestions.channel-id")
                + ", verification=" + channelStatus("verification.channel-id")
                + ", dashboard=" + channelStatus("status-dashboard.channel-id"));
    }

    private void handleNoteCommand(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§eUsage: /dsnote <add|list|clear> <player> [note]"); return; }
        String action = args[0].toLowerCase();
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
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
            for (Player staff : Bukkit.getOnlinePlayers()) if (staff.hasPermission("discordstatus.watchlist")) staff.sendMessage("§c⚠ Watchlisted player joined: §f" + player.getName());
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
        JDA jda = DiscordSRV.getPlugin().getJda();
        TextChannel channel = jda == null || !channelId.matches("\\d{17,20}") ? null : jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessageEmbeds(new EmbedBuilder().setTitle("📋 " + title).setDescription(detail).setTimestamp(Instant.now()).setColor(getEmbedColor("embeds.info-color", "#5865F2")).build()).queue();
    }

    private void scheduleStatusDashboard() { if (dashboardService != null) dashboardService.start(); }

    public void refreshDashboard() {
        String channelId = getConfig().getString("status-dashboard.channel-id", "");
        JDA jda = DiscordSRV.getPlugin().getJda();
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
        int network = DiscordSRV.isReady ? 100 : 0;
        double tps = Bukkit.getTPS()[0];
        String health = tps >= 19.0 && cpu < 90 && ram < 90 && DiscordSRV.isReady ? "Excellent" : tps >= 17.0 ? "Fair" : "Needs attention";
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
        autoBackupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::autoBackupConfig, hours * 3600L * 20L, hours * 3600L * 20L);
    }

    private void autoBackupConfig() {
        try {
            File backupDir = new File(getDataFolder(), "backups");
            if (!backupDir.exists()) backupDir.mkdirs();
            Files.copy(new File(getDataFolder(), "config.yml").toPath(), new File(backupDir, "config-" + System.currentTimeMillis() + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            File[] backups = backupDir.listFiles((dir, name) -> name.matches("config-\\d+\\.yml"));
            if (backups != null) {
                Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
                int keep = Math.max(1, getConfig().getInt("backups.auto.keep", 10));
                for (int i = keep; i < backups.length; i++) Files.deleteIfExists(backups[i].toPath());
            }
        } catch (IOException exception) { getLogger().warning("Automatic backup failed: " + exception.getMessage()); }
    }

    private void sendDiagnostics(CommandSender sender) {
        sender.sendMessage("§6DiscordSRVStatusEmbed diagnostics");
        sender.sendMessage("§7Discord connection: " + (DiscordSRV.isReady ? "§aREADY" : "§cNOT READY"));
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
        logPurgeTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, this::purgeLogChannel, intervalTicks, intervalTicks
        );
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

        JDA jda = DiscordSRV.getPlugin().getJda();
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

                channel.purgeMessages(messages);
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
        } catch (Exception e) {
            getLogger().warning("Failed to purge log channel messages: " + e.getMessage());
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
            // Get JDA instance from DiscordSRV
            JDA jda = DiscordSRV.getPlugin().getJda();
            if (jda == null) {
                getLogger().severe("JDA instance is null! DiscordSRV may not be fully initialized yet.");
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
                JDA jda = DiscordSRV.getPlugin().getJda();
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

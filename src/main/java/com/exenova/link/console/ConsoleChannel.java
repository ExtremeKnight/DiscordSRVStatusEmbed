package com.exenova.link.console;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Streams the server console to a Discord channel and lets explicitly allowed
 * staff run console commands by typing in that channel.
 *
 * <p>Security: only Discord user IDs / role IDs listed in
 * {@code console.allowed-user-ids} / {@code console.allowed-role-ids} may run
 * commands (nobody by default), commands in {@code console.blocked-commands}
 * are refused, and every command is written to the audit log.</p>
 */
public final class ConsoleChannel extends ListenerAdapter {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern MC_COLORS = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_QUEUED_LINES = 2000;

    private final ExeNovaLink plugin;
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private Appender appender;

    public ConsoleChannel(ExeNovaLink plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("console.enabled", false); }
    private String channelId() { return plugin.getConfig().getString("console.channel-id", ""); }

    public void start() {
        stop();
        if (!enabled()) return;
        if (!channelId().matches("\\d{17,20}")) {
            plugin.getLogger().warning("console.enabled is true but console.channel-id is not set.");
            return;
        }
        appender = new Appender();
        appender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
    }

    public void stop() {
        if (appender == null) return;
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(appender);
        appender.stop();
        appender = null;
        pending.clear();
        queued.set(0);
    }

    /** Sends queued console lines in code-block chunks. Called every couple of seconds from an async task. */
    public void flush() {
        if (appender == null) return;
        JDA jda = plugin.jda();
        TextChannel channel = jda == null ? null : jda.getTextChannelById(channelId());
        if (channel == null) return;
        int lost = dropped.getAndSet(0);
        StringBuilder chunk = new StringBuilder();
        if (lost > 0) chunk.append("[").append(lost).append(" line(s) skipped: console output too fast]\n");
        int messages = 0;
        String line;
        while ((line = pending.poll()) != null) {
            queued.decrementAndGet();
            if (chunk.length() + line.length() + 1 > 1900) {
                send(channel, chunk);
                chunk.setLength(0);
                if (++messages >= 4) break; // stay well under Discord's rate limit; the rest goes next flush
            }
            chunk.append(line, 0, Math.min(line.length(), 1900)).append('\n');
        }
        if (chunk.length() > 0) send(channel, chunk);
    }

    private static void send(TextChannel channel, StringBuilder chunk) {
        channel.sendMessage("```\n" + chunk.toString().replace("```", "`​``") + "```").queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (appender == null || !event.isFromGuild() || !event.getChannel().getId().equals(channelId())) return;
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
        String command = event.getMessage().getContentRaw().trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) return;
        if (!allowed(event.getMember(), event.getAuthor().getId())) {
            event.getMessage().addReaction(Emoji.fromUnicode("⛔")).queue();
            return;
        }
        String root = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (plugin.getConfig().getStringList("console.blocked-commands").stream().anyMatch(root::equalsIgnoreCase)) {
            event.getMessage().reply("That command is blocked from the Discord console.").queue();
            return;
        }
        String finalCommand = command;
        plugin.audit("CONSOLE_COMMAND discord=" + event.getAuthor().getId() + " command=" + finalCommand);
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean ok;
            try { ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand); }
            catch (RuntimeException exception) { ok = false; plugin.getLogger().warning("Console command failed: " + exception.getMessage()); }
            event.getMessage().addReaction(Emoji.fromUnicode(ok ? "✅" : "❓")).queue();
        });
    }

    private boolean allowed(Member member, String userId) {
        if (plugin.getConfig().getStringList("console.allowed-user-ids").contains(userId)) return true;
        List<String> roles = plugin.getConfig().getStringList("console.allowed-role-ids");
        return member != null && !roles.isEmpty() && member.getRoles().stream().anyMatch(role -> roles.contains(role.getId()));
    }

    private String clean(String raw) {
        String text = MC_COLORS.matcher(ANSI.matcher(raw).replaceAll("")).replaceAll("");
        if (plugin.getConfig().getBoolean("console.hide-ip-addresses", true)) text = IPV4.matcher(text).replaceAll("x.x.x.x");
        return text;
    }

    private final class Appender extends AbstractAppender {
        Appender() { super("ExeNovaLink-DiscordConsole", null, null, true, Property.EMPTY_ARRAY); }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            if (message == null || message.isBlank()) return;
            String line = "[" + LocalTime.now().format(TIME) + " " + event.getLevel().name() + "] " + clean(message);
            if (event.getThrown() != null) line += " (" + event.getThrown() + ")";
            if (queued.incrementAndGet() > MAX_QUEUED_LINES) {
                queued.decrementAndGet();
                dropped.incrementAndGet();
                return;
            }
            pending.add(line);
        }
    }
}

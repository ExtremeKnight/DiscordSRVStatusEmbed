package com.exenova.link.bridge;

import com.exenova.link.ExeNovaLink;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import io.papermc.paper.advancement.AdvancementDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.awt.Color;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Two-way Minecraft ↔ Discord chat bridge with join/leave/death/advancement
 * relays. Minecraft chat is posted through a channel webhook (each player's
 * own name and skin) when the bot has Manage Webhooks, otherwise as normal
 * bot messages. Mentions are neutralised globally in {@code DiscordBot}.
 */
public final class ChatBridge extends ListenerAdapter implements Listener {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");
    private static final String WEBHOOK_NAME = "ExeNovaLink Bridge";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final ExeNovaLink plugin;
    private volatile IncomingWebhookClient webhook;

    public ChatBridge(ExeNovaLink plugin) { this.plugin = plugin; }

    private boolean enabled() { return plugin.getConfig().getBoolean("bridge.enabled", false); }
    private String channelId() { return plugin.getConfig().getString("bridge.channel-id", ""); }

    private TextChannel channel() {
        JDA jda = plugin.jda();
        String id = channelId();
        return jda == null || !id.matches("\\d{17,20}") ? null : jda.getTextChannelById(id);
    }

    /** Called when the bot is ready (and after reloads): finds or creates our webhook. */
    public void connect() {
        webhook = null;
        if (!enabled()) return;
        TextChannel channel = channel();
        if (channel == null) {
            plugin.getLogger().warning("bridge.channel-id is missing or the bot cannot see that channel; the chat bridge is idle.");
            return;
        }
        if (!plugin.getConfig().getBoolean("bridge.use-webhooks", true)) return;
        JDA jda = channel.getJDA();
        channel.retrieveWebhooks().queue(hooks -> {
            var existing = hooks.stream().filter(hook -> WEBHOOK_NAME.equals(hook.getName())
                    && hook.getOwnerAsUser() != null && hook.getOwnerAsUser().getIdLong() == jda.getSelfUser().getIdLong()).findFirst();
            if (existing.isPresent()) webhook = WebhookClient.createClient(jda, existing.get().getUrl());
            else channel.createWebhook(WEBHOOK_NAME).queue(
                    created -> webhook = WebhookClient.createClient(jda, created.getUrl()),
                    failure -> plugin.getLogger().warning("Could not create the bridge webhook (needs Manage Webhooks); using normal messages."));
        }, failure -> plugin.getLogger().warning("Could not read webhooks (needs Manage Webhooks); using normal bot messages for the bridge."));
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        webhook = null;
    }

    // ---- Minecraft → Discord ---------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        if (plugin.integrations().isMuted(player) || plugin.integrations().isVanished(player)) return;
        String message = PLAIN.serialize(event.message());
        if (!player.hasPermission("exenova.bridge.bypass-filter")) message = filter(message);
        message = plugin.applyEmoji(player, message);
        if (message.isBlank()) return;

        // Per-channel routing: if the player's active channel bridges to its own
        // Discord channel, send there (as a bot message) instead of the default.
        var channels = plugin.chatChannels();
        if (channels != null && channels.enabled()) {
            String channelDiscordId = channels.active(player).discordChannelId();
            if (channelDiscordId.matches("\\d{17,20}") && !channelDiscordId.equals(channelId())) {
                TextChannel target = plugin.jda() == null ? null : plugin.jda().getTextChannelById(channelDiscordId);
                if (target != null) {
                    String line = plugin.integrations().placeholders(player,
                            plugin.getConfig().getString("bridge.formats.minecraft-to-discord", "**{player}** » {message}"))
                            .replace("{player}", escape(player.getName()));
                    target.sendMessage(truncate(stripColors(line).replace("{message}", escape(message)), 2000)).queue();
                    return;
                }
            }
        }
        sendAsPlayer(player, message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!eventEnabled("join") || plugin.integrations().isVanished(event.getPlayer())) return;
        Player player = event.getPlayer();
        String text = format("bridge.messages.join", "**{player}** joined the server", player)
                + (player.hasPlayedBefore() ? "" : " " + plugin.getConfig().getString("bridge.messages.first-join-suffix", "for the first time! 🎉"));
        sendEvent(player, text, new Color(0x57F287));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!eventEnabled("quit") || plugin.integrations().isVanished(event.getPlayer())) return;
        sendEvent(event.getPlayer(), format("bridge.messages.quit", "**{player}** left the server", event.getPlayer()), new Color(0xED4245));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!eventEnabled("death") || plugin.integrations().isVanished(event.getEntity())) return;
        Component death = event.deathMessage();
        if (death == null) return;
        sendEvent(event.getEntity(), "💀 " + escape(PLAIN.serialize(death)), new Color(0x2B2D31));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!eventEnabled("advancement") || plugin.integrations().isVanished(event.getPlayer())) return;
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null || !display.doesAnnounceToChat()) return; // recipes and hidden criteria
        String title = PLAIN.serialize(display.title());
        sendEvent(event.getPlayer(), "🏆 **" + escape(event.getPlayer().getName()) + "** made the advancement **" + escape(title) + "**", new Color(0xFEE75C));
    }

    /** Server start/stop notices in the bridge channel (separate from the status embed channel). */
    public void sendServerNotice(boolean started, boolean blocking) {
        if (!eventEnabled(started ? "server-start" : "server-stop")) return;
        TextChannel channel = channel();
        if (channel == null) return;
        var action = channel.sendMessageEmbeds(new EmbedBuilder()
                .setDescription(started ? "✅ **The server is online!**" : "🛑 **The server has stopped.**")
                .setColor(started ? new Color(0x57F287) : new Color(0xED4245)).build());
        if (!blocking) { action.queue(); return; }
        try { action.submit().get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private boolean eventEnabled(String key) { return enabled() && plugin.getConfig().getBoolean("bridge.events." + key, true); }

    private String format(String path, String fallback, Player player) {
        String template = plugin.getConfig().getString(path, fallback);
        return plugin.integrations().placeholders(player, template).replace("{player}", escape(player.getName()));
    }

    private void sendAsPlayer(Player player, String message) {
        String content = plugin.getConfig().getBoolean("bridge.escape-markdown", true) ? escape(message) : message;
        content = truncate(content, 1900);
        IncomingWebhookClient client = webhook;
        if (client != null) {
            String name = plugin.integrations().placeholders(player,
                    plugin.getConfig().getString("bridge.webhook-username", "{player}")).replace("{player}", player.getName());
            client.sendMessage(content)
                    .setUsername(webhookName(stripColors(name)))
                    .setAvatarUrl(plugin.avatarUrl(player.getName(), player.getUniqueId()))
                    .queue(null, failure -> webhook = null); // webhook deleted? fall back next time
            return;
        }
        TextChannel channel = channel();
        if (channel == null) return;
        String line = plugin.integrations().placeholders(player, plugin.getConfig().getString("bridge.formats.minecraft-to-discord", "**{player}** » {message}"))
                .replace("{player}", escape(player.getName()));
        channel.sendMessage(truncate(stripColors(line).replace("{message}", content), 2000)).queue();
    }

    private void sendEvent(Player player, String text, Color color) {
        TextChannel channel = channel();
        if (channel == null) return;
        channel.sendMessageEmbeds(new EmbedBuilder()
                .setAuthor(stripColors(text).replace("*", ""), null, plugin.avatarUrl(player.getName(), player.getUniqueId()))
                .setColor(color).build()).queue();
    }

    // ---- Discord → Minecraft ---------------------------------------------

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!enabled() || !event.isFromGuild()) return;
        String incomingId = event.getChannel().getId();
        boolean isDefault = incomingId.equals(channelId());
        // A message may come from the default bridge channel or from a channel
        // mapped to a specific in-game channel.
        var channels = plugin.chatChannels();
        com.exenova.link.chat.channel.Channel gameChannel = channels != null && channels.enabled()
                ? channels.registry().byDiscordChannel(incomingId).orElse(null) : null;
        if (!isDefault && gameChannel == null) return;
        if (event.getAuthor().isBot() || event.isWebhookMessage() || event.getAuthor().isSystem()) return;
        Message message = event.getMessage();
        String text = message.getContentDisplay();
        String prefix = plugin.getConfig().getString("server-info.prefix", "!");
        if (plugin.getConfig().getBoolean("bridge.ignore-prefix-commands", true) && prefix != null && !prefix.isEmpty() && text.startsWith(prefix)) return;
        boolean clickablePreviews = isDefault && plugin.getConfig().getBoolean("bridge.attachment-previews", true);
        if (!message.getAttachments().isEmpty() && !clickablePreviews) {
            // Fallback when previews are off: inline the file names as plain text.
            text = (text + " " + String.join(" ", message.getAttachments().stream().map(a -> "[" + a.getFileName() + "]").toList())).trim();
        }
        if (!message.getStickers().isEmpty()) text = (text + " [sticker]").trim();
        if (clickablePreviews && !message.getAttachments().isEmpty()) {
            broadcastAttachmentPreviews(message.getAttachments());
        }
        if (text.isBlank()) return;
        text = truncate(filter(stripColors(text.replace('\n', ' '))), plugin.getConfig().getInt("bridge.max-discord-message-length", 256));

        Member member = event.getMember();
        String name = member != null ? member.getEffectiveName() : event.getAuthor().getEffectiveName();
        String role = member == null || member.getRoles().isEmpty() ? "" : member.getRoles().get(0).getName();
        String reply = "";
        Message referenced = message.getReferencedMessage();
        if (referenced != null) reply = plugin.getConfig().getString("bridge.formats.reply-prefix", "&8(reply to {name}) ")
                .replace("{name}", stripColors(referenced.getAuthor().getEffectiveName()));

        String template = plugin.getConfig().getString("bridge.formats.discord-to-minecraft", "&9[Discord] &b{name}&8 » &f{reply}{message}");
        // The template's & colour codes are trusted (from config); the user's
        // name/message are inserted as plain text so Discord users can't
        // inject Minecraft formatting.
        Component line = LegacyComponentSerializer.legacyAmpersand().deserialize(template.replace("{reply}", reply))
                .replaceText(literal("{name}", stripColors(name)))
                .replaceText(literal("{role}", stripColors(role)))
                .replaceText(literal("{message}", text));
        com.exenova.link.chat.channel.Channel deliverTo = gameChannel;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (deliverTo != null) {
                // Deliver only to the in-game channel's online subscribers.
                for (Player subscriber : channels.subscribers(deliverTo, null)) subscriber.sendMessage(line);
            } else {
                Bukkit.getServer().broadcast(line);
            }
        });
    }

    private static TextReplacementConfig literal(String token, String value) {
        return TextReplacementConfig.builder().matchLiteral(token).replacement(Component.text(value)).build();
    }

    /**
     * Broadcasts one clickable "[image: name]" line per attachment. By default the
     * click opens the URL in a browser; when the in-chat map renderer (ProtocolLib)
     * is active, clicking an image/GIF instead renders it on a map in-game.
     */
    private void broadcastAttachmentPreviews(List<Message.Attachment> attachments) {
        String prefix = plugin.getConfig().getString("bridge.formats.attachment-prefix", "&9[Discord] &7shared ");
        var inChat = plugin.inChatMaps();
        for (Message.Attachment attachment : attachments) {
            String label = AttachmentPreview.label(attachment.getFileName(), attachment.isImage());
            String url = attachment.getUrl();
            AttachmentPreview.Kind kind = AttachmentPreview.kind(attachment.getFileName(), attachment.isImage());
            boolean renderable = inChat != null && (kind == AttachmentPreview.Kind.IMAGE || kind == AttachmentPreview.Kind.GIF);
            net.kyori.adventure.text.event.ClickEvent click;
            String hover;
            if (renderable) {
                click = net.kyori.adventure.text.event.ClickEvent.runCommand("/exenova viewimage " + inChat.cache(url));
                hover = "Click to view in-game";
            } else {
                click = net.kyori.adventure.text.event.ClickEvent.openUrl(url);
                hover = "Click to open " + attachment.getFileName();
            }
            Component line = LegacyComponentSerializer.legacyAmpersand().deserialize(prefix)
                    .append(Component.text(label, net.kyori.adventure.text.format.NamedTextColor.AQUA)
                            .clickEvent(click)
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(hover))));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getServer().broadcast(line));
        }
    }

    // ---- helpers ---------------------------------------------------------

    private String filter(String text) {
        String result = com.exenova.link.chat.TextFilter.censorWords(text, plugin.getConfig().getStringList("bridge.filtered-words"));
        if (plugin.getConfig().getBoolean("bridge.block-invites", true)) {
            result = com.exenova.link.chat.TextFilter.stripInvites(result, "[invite removed]");
        }
        if (plugin.getConfig().getBoolean("bridge.block-links", false)) {
            result = com.exenova.link.chat.TextFilter.stripUrls(result, "[link removed]");
        }
        return result;
    }

    static String stripColors(String text) { return text == null ? "" : COLOR_CODES.matcher(text).replaceAll(""); }
    static String escape(String text) { return MarkdownSanitizer.escape(text); }

    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Discord webhook names must be 1-80 chars and may not contain "discord" or "clyde". */
    static String webhookName(String name) {
        String cleaned = name.replaceAll("(?i)discord", "d1scord").replaceAll("(?i)clyde", "c1yde").trim();
        if (cleaned.isEmpty()) cleaned = "Player";
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}

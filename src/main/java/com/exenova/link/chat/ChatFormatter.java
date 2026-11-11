package com.exenova.link.chat;

import com.exenova.link.ExeNovaLink;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Optional in-game chat formatter built on Adventure MiniMessage. Off by
 * default so it never clashes with an existing chat plugin (EssentialsX etc.);
 * enable it with {@code chat.formatter.enabled}.
 *
 * <p>Security: the <em>format template</em> is trusted (admin-authored) and is
 * parsed as full MiniMessage. The <em>player's own message</em> is never parsed
 * as MiniMessage — it is inserted as a literal component, so players can't
 * inject {@code <click>}/{@code <hover>}/colour tags. If
 * {@code allow-player-color} is on and the player has {@code exenova.chat.color},
 * their message is parsed with a restricted resolver that permits only colours,
 * decorations and gradients — never click/hover/insertion.</p>
 */
public final class ChatFormatter implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final MiniMessage FULL = MiniMessage.miniMessage();
    /** Colours/decorations/gradient/rainbow only — no click, hover or insertion. */
    private static final MiniMessage SAFE_COLOR = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow()))
            .build();

    private final ExeNovaLink plugin;

    public ChatFormatter(ExeNovaLink plugin) { this.plugin = plugin; }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("chat.formatter.enabled", false);
    }

    public void unregister() { HandlerList.unregisterAll(this); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        String message = PLAIN.serialize(event.message());

        List<String> words = plugin.getConfig().getStringList("chat.formatter.filtered-words");
        message = TextFilter.censorWords(message, words);
        if (plugin.getConfig().getBoolean("chat.formatter.block-invites", true)) {
            message = TextFilter.stripInvites(message, "[link removed]");
        }
        if (message.isBlank()) { event.setCancelled(true); return; }

        boolean color = plugin.getConfig().getBoolean("chat.formatter.allow-player-color", false)
                && player.hasPermission("exenova.chat.color");
        // PlaceholderAPI is applied to the template (trusted); the player's name
        // and message are inserted literally by render(), never parsed as tags.
        String template = formatFor(player);
        String resolvedTemplate = plugin.integrations() == null ? template : plugin.integrations().placeholders(player, template);
        String finalMessage = message;
        event.renderer((source, displayName, msg, viewer) -> render(resolvedTemplate, player.getName(), finalMessage, color));
    }

    private String formatFor(Player player) {
        ConfigurationSection groups = plugin.getConfig().getConfigurationSection("chat.formatter.group-formats");
        if (groups != null) {
            for (String suffix : groups.getKeys(false)) {
                if (player.hasPermission("exenova.chat.group." + suffix)) return groups.getString(suffix, defaultFormat());
            }
        }
        return defaultFormat();
    }

    private String defaultFormat() {
        return plugin.getConfig().getString("chat.formatter.default-format",
                "<gray>{player}</gray> <dark_gray>»</dark_gray> <white>{message}</white>");
    }

    /**
     * Builds the chat component. Visible for testing. {@code template} is parsed
     * as full MiniMessage; {@code message} is inserted literally unless
     * {@code allowColor}, in which case it is parsed with colours/decorations only.
     */
    static Component render(String template, String playerName, String message, boolean allowColor) {
        TagResolver player = Placeholder.unparsed("player", playerName);
        Component messageComponent = allowColor ? SAFE_COLOR.deserialize(message) : Component.text(message);
        TagResolver msg = Placeholder.component("message", messageComponent);
        // Normalise {player}/{message} to MiniMessage tags so admins can use either style.
        String normalised = template.replace("{player}", "<player>").replace("{message}", "<message>");
        return FULL.deserialize(normalised, player, msg);
    }
}

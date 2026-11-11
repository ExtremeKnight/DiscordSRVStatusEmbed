package com.exenova.link.chat.pm;

import com.exenova.link.ExeNovaLink;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Private messaging: {@code /msg}, {@code /reply}, {@code /ignore} and
 * {@code /socialspy}. Server-side only — no Discord involved. State lives in a
 * {@link PrivateMessageService}; this class is the Bukkit glue (commands,
 * formatting, quit cleanup).
 */
public final class PrivateMessages implements Listener {
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ExeNovaLink plugin;
    private final PrivateMessageService service = new PrivateMessageService();

    public PrivateMessages(ExeNovaLink plugin) { this.plugin = plugin; }

    public PrivateMessageService service() { return service; }
    private boolean enabled() { return plugin.getConfig().getBoolean("private-messages.enabled", true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { service.clear(event.getPlayer().getUniqueId()); }

    // ---- command entry points (routed from ExeNovaLink#onCommand) ----

    public boolean onMsg(CommandSender sender, String[] args) {
        if (!check(sender)) return true;
        if (args.length < 2) { sender.sendMessage("§eUsage: /msg <player> <message>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        send((Player) sender, target, args[0], message);
        return true;
    }

    public boolean onReply(CommandSender sender, String[] args) {
        if (!check(sender)) return true;
        if (args.length < 1) { sender.sendMessage("§eUsage: /reply <message>"); return true; }
        UUID targetId = service.replyTarget(((Player) sender).getUniqueId());
        Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
        if (targetId == null) { sender.sendMessage("§cYou have no one to reply to."); return true; }
        send((Player) sender, target, target == null ? "that player" : target.getName(), String.join(" ", args));
        return true;
    }

    public boolean onIgnore(CommandSender sender, String[] args) {
        if (!check(sender)) return true;
        if (args.length != 1) { sender.sendMessage("§eUsage: /ignore <player>"); return true; }
        Player self = (Player) sender;
        UUID targetId = resolveUuid(args[0]);
        if (targetId == null) { sender.sendMessage("§c" + args[0] + " has never joined."); return true; }
        if (targetId.equals(self.getUniqueId())) { sender.sendMessage("§cYou cannot ignore yourself."); return true; }
        if (service.isIgnoring(self.getUniqueId(), targetId)) {
            service.unignore(self.getUniqueId(), targetId);
            sender.sendMessage("§aYou are no longer ignoring " + args[0] + ".");
        } else {
            service.ignore(self.getUniqueId(), targetId);
            sender.sendMessage("§aYou are now ignoring " + args[0] + ".");
        }
        return true;
    }

    public boolean onSocialSpy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player self)) { sender.sendMessage("Players only."); return true; }
        if (!sender.hasPermission("exenova.socialspy")) { sender.sendMessage("§cNo permission."); return true; }
        boolean on = service.toggleSocialSpy(self.getUniqueId());
        sender.sendMessage(on ? "§aSocial spy enabled." : "§eSocial spy disabled.");
        return true;
    }

    private boolean check(CommandSender sender) {
        if (!enabled()) { sender.sendMessage("§cPrivate messages are disabled."); return false; }
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return false; }
        if (!sender.hasPermission("exenova.msg")) { sender.sendMessage("§cNo permission."); return false; }
        return true;
    }

    private void send(Player from, Player target, String targetName, String rawMessage) {
        if (target == null || !target.isOnline()
                || (from instanceof Player viewer && !viewer.canSee(target) && !from.hasPermission("exenova.socialspy"))) {
            from.sendMessage("§c" + targetName + " is not online.");
            return;
        }
        if (target.equals(from)) { from.sendMessage("§cYou cannot message yourself."); return; }
        String message = safe(rawMessage);
        if (message.isBlank()) return;
        if (service.isIgnoring(target.getUniqueId(), from.getUniqueId())) {
            // Don't reveal that they're ignored; look like a normal send.
            from.sendMessage(render("private-messages.format-to", from.getName(), target.getName(), message));
            return;
        }
        from.sendMessage(render("private-messages.format-to", from.getName(), target.getName(), message));
        target.sendMessage(render("private-messages.format-from", from.getName(), target.getName(), message));
        if (plugin.getConfig().getBoolean("private-messages.sound", true)) {
            try { target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f); } catch (RuntimeException ignored) { }
        }
        service.recordConversation(from.getUniqueId(), target.getUniqueId());
        notifySpies(from, target, message);
    }

    private void notifySpies(Player from, Player target, String message) {
        Component spy = render("private-messages.format-spy", from.getName(), target.getName(), message);
        for (UUID id : service.spies()) {
            if (id.equals(from.getUniqueId()) || id.equals(target.getUniqueId())) continue;
            Player spyPlayer = Bukkit.getPlayer(id);
            if (spyPlayer != null) spyPlayer.sendMessage(spy);
        }
    }

    private UUID resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        org.bukkit.OfflinePlayer offline = plugin.findPlayer(name);
        return offline == null ? null : offline.getUniqueId();
    }

    /** Strips colour codes from untrusted text so it can't inject formatting into the legacy template. */
    static String safe(String text) {
        return text == null ? "" : COLOR_CODES.matcher(text).replaceAll("").trim();
    }

    private Component render(String path, String sender, String target, String message) {
        String template = plugin.getConfig().getString(path, "&7{sender} &7-> &7{target}&7: &f{message}");
        String built = template
                .replace("{sender}", safe(sender))
                .replace("{target}", safe(target))
                .replace("{message}", message);
        return LEGACY.deserialize(built);
    }
}

package com.exenova.link.linking;

import com.exenova.link.ExeNovaLink;
import com.exenova.link.automation.Trigger;
import com.exenova.link.discord.SlashHandler;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

/** In-game and Discord commands for account linking, plus the optional "must link to join" gate. */
public final class LinkCommands extends ListenerAdapter implements Listener {
    private final ExeNovaLink plugin;

    public LinkCommands(ExeNovaLink plugin) { this.plugin = plugin; }

    private LinkManager links() { return plugin.links(); }
    private boolean enabled() { return plugin.getConfig().getBoolean("linking.enabled", true); }

    public List<CommandData> slashCommands() {
        if (!enabled()) return List.of();
        return List.of(
                Commands.slash("link", "Link your Discord account to Minecraft")
                        .addOption(OptionType.STRING, "code", "The code shown by /link in Minecraft", false),
                Commands.slash("unlink", "Unlink your Discord account from Minecraft"),
                Commands.slash("whois", "Staff: look up a linked account")
                        .addOption(OptionType.USER, "user", "Discord user", false)
                        .addOption(OptionType.STRING, "player", "Minecraft player name", false));
    }

    // ---- in game ---------------------------------------------------------

    public boolean onLinkCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can link accounts."); return true; }
        if (!enabled()) { player.sendMessage("§cAccount linking is disabled."); return true; }
        if (args.length >= 1) {
            LinkManager.Result result = links().redeemFromMinecraft(player.getUniqueId(), args[0]);
            switch (result) {
                case LINKED, ALREADY_LINKED -> {
                    String discordId = links().discordId(player.getUniqueId()).orElse("");
                    player.sendMessage("§aYour account is now linked to Discord.");
                    linked(player.getUniqueId(), discordId, player.getName());
                }
                case RATE_LIMITED -> player.sendMessage("§cToo many wrong codes. Try again in a few minutes.");
                case INVALID_CODE -> player.sendMessage("§cThat code is invalid or expired. Run /link in Discord for a new one.");
            }
            return true;
        }
        if (links().isLinked(player.getUniqueId())) {
            player.sendMessage("§eYou are already linked. Use §f/unlink§e first to link a different Discord account.");
            return true;
        }
        String code = links().createCodeForMinecraft(player.getUniqueId());
        player.sendMessage(Component.text("Your link code: ", NamedTextColor.GRAY)
                .append(Component.text(code, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(code))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))));
        player.sendMessage("§7In Discord, run §f/link code:" + code + "§7 or DM the bot the code. It expires in 10 minutes.");
        return true;
    }

    public boolean onUnlinkCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Only players can unlink accounts."); return true; }
        links().unlinkMinecraft(player.getUniqueId()).ifPresentOrElse(discordId -> {
            plugin.roleSync().clear(discordId);
            plugin.audit("UNLINK player=" + player.getName() + " discord=" + discordId);
            player.sendMessage("§aYour Discord account has been unlinked.");
        }, () -> player.sendMessage("§eYour account is not linked."));
        return true;
    }

    public boolean onDiscordCommand(CommandSender sender) {
        String invite = plugin.getConfig().getString("discord.invite-url", "");
        sender.sendMessage(invite.isBlank() ? "§eAsk staff for the Discord invite." : "§9Join our Discord: §f" + invite);
        if (sender instanceof Player player && enabled()) {
            sender.sendMessage(links().isLinked(player.getUniqueId()) ? "§aYour account is linked." : "§7Link your account with §f/link§7.");
        }
        return true;
    }

    // ---- Discord ---------------------------------------------------------

    @SlashHandler(value = "link", ephemeral = true)
    public void onLinkSlash(SlashCommandInteractionEvent event) {
        OptionMapping codeOption = event.getOption("code");
        String discordId = event.getUser().getId();
        if (codeOption == null) {
            if (links().minecraftId(discordId).isPresent()) {
                event.getHook().sendMessage("You are already linked. Use `/unlink` first to link another account.").queue();
                return;
            }
            String code = links().createCodeForDiscord(discordId);
            event.getHook().sendMessage("Join the server and type `/link " + code + "` in chat. The code expires in 10 minutes.").queue();
            return;
        }
        redeemFromDiscord(event.getUser(), codeOption.getAsString(), message -> event.getHook().sendMessage(message).queue());
    }

    @SlashHandler(value = "unlink", ephemeral = true)
    public void onUnlinkSlash(SlashCommandInteractionEvent event) {
        links().unlinkDiscord(event.getUser().getId()).ifPresentOrElse(uuid -> {
            plugin.roleSync().clear(event.getUser().getId());
            plugin.audit("UNLINK discord=" + event.getUser().getId() + " player=" + uuid);
            event.getHook().sendMessage("Your Minecraft account has been unlinked.").queue();
        }, () -> event.getHook().sendMessage("Your account is not linked.").queue());
    }

    @SlashHandler(value = "whois", ephemeral = true)
    public void onWhoisSlash(SlashCommandInteractionEvent event) {
        if (!plugin.isDiscordModerator(event.getMember(), event.getUser().getId())) {
            event.getHook().sendMessage("You are not authorized to use this command.").queue();
            return;
        }
        OptionMapping user = event.getOption("user");
        OptionMapping player = event.getOption("player");
        if (user != null) {
            User target = user.getAsUser();
            String text = links().minecraftId(target.getId()).map(uuid -> {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                return target.getAsMention() + " is linked to **" + (offline.getName() == null ? uuid : offline.getName()) + "** (`" + uuid + "`).";
            }).orElse(target.getAsMention() + " has no linked Minecraft account.");
            event.getHook().sendMessage(text).queue();
        } else if (player != null) {
            String name = player.getAsString();
            Bukkit.getScheduler().runTask(plugin, () -> {
                OfflinePlayer target = plugin.findPlayer(name);
                String text = target == null ? "`" + name + "` has never joined."
                        : links().discordId(target.getUniqueId()).map(id -> "**" + target.getName() + "** is linked to <@" + id + "> (`" + id + "`).")
                        .orElse("**" + target.getName() + "** has no linked Discord account.");
                event.getHook().sendMessage(text).queue();
            });
        } else {
            event.getHook().sendMessage("Give a `user` or a `player`.").queue();
        }
    }

    /** Lets users simply DM the bot their code. */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!enabled() || event.isFromGuild() || event.getAuthor().isBot()) return;
        String content = event.getMessage().getContentRaw().trim();
        String prefix = plugin.getConfig().getString("server-info.prefix", "!");
        if (prefix != null && !prefix.isEmpty() && content.startsWith(prefix)) return; // handled as a prefix command
        if (!content.matches("(?i)[" + LinkManager.ALPHABET + "]{" + LinkManager.CODE_LENGTH + "}")) {
            event.getChannel().sendMessage("Send me the " + LinkManager.CODE_LENGTH + "-character code from `/link` in Minecraft to link your account.").queue();
            return;
        }
        redeemFromDiscord(event.getAuthor(), content, message -> event.getChannel().sendMessage(message).queue());
    }

    private void redeemFromDiscord(User user, String code, java.util.function.Consumer<String> reply) {
        LinkManager.Result result = links().redeemFromDiscord(user.getId(), code);
        switch (result) {
            case LINKED, ALREADY_LINKED -> {
                UUID uuid = links().minecraftId(user.getId()).orElse(null);
                if (uuid == null) { reply.accept("Something went wrong; please try again."); return; }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                    String name = player.getName() == null ? uuid.toString() : player.getName();
                    reply.accept("✅ Linked to **" + name + "**!");
                    if (player.getPlayer() != null) player.getPlayer().sendMessage("§aYour account is now linked to Discord as §f" + user.getName() + "§a.");
                    linked(uuid, user.getId(), name);
                });
            }
            case RATE_LIMITED -> reply.accept("Too many wrong codes. Please wait a few minutes.");
            case INVALID_CODE -> reply.accept("That code is invalid or expired. Run `/link` in Minecraft for a new one.");
        }
    }

    /** Runs on the main thread after a successful link. */
    private void linked(UUID uuid, String discordId, String name) {
        plugin.audit("LINK player=" + name + " uuid=" + uuid + " discord=" + discordId);
        plugin.roleSync().sync(uuid);
        Player online = Bukkit.getPlayer(uuid);
        if (plugin.automations() != null) plugin.automations().dispatch(Trigger.ACCOUNT_LINKED, online, plugin.jda());
        plugin.postToChannel("linking.log-channel-id", new EmbedBuilder()
                .setDescription("🔗 **" + net.dv8tion.jda.api.utils.MarkdownSanitizer.escape(name) + "** linked to <@" + discordId + ">")
                .setColor(new Color(0x5865F2)).build());
    }

    // ---- gates -----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled() || !plugin.getConfig().getBoolean("linking.required-to-join", false)) return;
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!plugin.isDiscordReady()) return; // never lock everyone out because the bot is down
        if (links().isLinked(event.getUniqueId())) return;
        if (plugin.getConfig().getStringList("linking.bypass-players").stream().anyMatch(event.getName()::equalsIgnoreCase)) return;
        String code = links().createCodeForMinecraft(event.getUniqueId());
        String invite = plugin.getConfig().getString("discord.invite-url", "");
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Component.text()
                .append(Component.text("Link your Discord account to play on ExeSMP\n\n", NamedTextColor.RED))
                .append(Component.text("DM the bot or use /link with this code:\n", NamedTextColor.GRAY))
                .append(Component.text(code, NamedTextColor.AQUA))
                .append(Component.text(invite.isBlank() ? "" : "\n\n" + invite, NamedTextColor.BLUE)).build());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Small delay so permission plugins have finished loading the player's groups.
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.roleSync().sync(uuid), 40L);
    }
}

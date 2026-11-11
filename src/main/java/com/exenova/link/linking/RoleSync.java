package com.exenova.link.linking;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps linked players' Discord roles (and optionally nicknames) in step with
 * their Minecraft groups. Groups come from LuckPerms when installed, otherwise
 * from the {@code group.<name>} permission most permission plugins grant.
 */
public final class RoleSync {
    private final ExeNovaLink plugin;

    public RoleSync(ExeNovaLink plugin) { this.plugin = plugin; }

    private Guild guild() {
        JDA jda = plugin.jda();
        String id = plugin.getConfig().getString("discord.guild-id", "");
        return jda == null || !id.matches("\\d{17,20}") ? null : jda.getGuildById(id);
    }

    public void syncOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) sync(player.getUniqueId());
    }

    /** Safe to call from any thread. */
    public void sync(UUID uuid) {
        Guild guild = guild();
        String discordId = plugin.links().discordId(uuid).orElse(null);
        if (guild == null || discordId == null) return;
        boolean rolesEnabled = plugin.getConfig().getBoolean("role-sync.enabled", false);
        String linkedRole = plugin.getConfig().getString("linking.linked-role-id", "");
        boolean nicknames = plugin.getConfig().getBoolean("linking.sync-nicknames", false);
        if (!rolesEnabled && !linkedRole.matches("\\d{17,20}") && !nicknames) return;

        groups(uuid).thenAccept(groups -> guild.retrieveMemberById(discordId).queue(member -> {
            Set<Role> add = new HashSet<>();
            Set<Role> remove = new HashSet<>();
            Role linked = linkedRole.matches("\\d{17,20}") ? guild.getRoleById(linkedRole) : null;
            if (linked != null && !member.getRoles().contains(linked)) add.add(linked);
            if (rolesEnabled) {
                ConfigurationSection mapping = plugin.getConfig().getConfigurationSection("role-sync.groups");
                boolean removeOthers = plugin.getConfig().getBoolean("role-sync.remove-unmatched", true);
                if (mapping != null) for (Map.Entry<String, Object> entry : mapping.getValues(false).entrySet()) {
                    Role role = guild.getRoleById(String.valueOf(entry.getValue()));
                    if (role == null) continue;
                    boolean should = groups.contains(entry.getKey().toLowerCase(Locale.ROOT));
                    boolean has = member.getRoles().contains(role);
                    if (should && !has) add.add(role);
                    else if (!should && has && removeOthers) remove.add(role);
                }
            }
            Member self = guild.getSelfMember();
            add.removeIf(role -> !self.canInteract(role));
            remove.removeIf(role -> !self.canInteract(role));
            if (!add.isEmpty() || !remove.isEmpty()) {
                guild.modifyMemberRoles(member, add, remove).queue(null,
                        failure -> plugin.getLogger().warning("Role sync failed for " + member.getUser().getName() + ": " + failure.getMessage()));
            }
            if (nicknames) syncNickname(guild, member, uuid);
        }, failure -> { /* not in the guild */ }));
    }

    /** Removes synced roles after an unlink. */
    public void clear(String discordId) {
        Guild guild = guild();
        if (guild == null) return;
        Set<String> ids = new HashSet<>();
        String linked = plugin.getConfig().getString("linking.linked-role-id", "");
        if (linked.matches("\\d{17,20}")) ids.add(linked);
        ConfigurationSection mapping = plugin.getConfig().getConfigurationSection("role-sync.groups");
        if (mapping != null) mapping.getValues(false).values().forEach(value -> ids.add(String.valueOf(value)));
        guild.retrieveMemberById(discordId).queue(member -> {
            Set<Role> remove = new HashSet<>();
            for (Role role : member.getRoles()) if (ids.contains(role.getId()) && guild.getSelfMember().canInteract(role)) remove.add(role);
            if (!remove.isEmpty()) guild.modifyMemberRoles(member, Set.of(), remove).queue(null, ignored -> { });
        }, ignored -> { });
    }

    private void syncNickname(Guild guild, Member member, UUID uuid) {
        if (!guild.getSelfMember().hasPermission(Permission.NICKNAME_MANAGE) || !guild.getSelfMember().canInteract(member)) return;
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        if (player.getName() == null) return;
        String nick = plugin.getConfig().getString("linking.nickname-format", "{player}").replace("{player}", player.getName());
        if (nick.length() > 32) nick = nick.substring(0, 32);
        if (!nick.equals(member.getNickname())) member.modifyNickname(nick).queue(null, ignored -> { });
    }

    private CompletableFuture<Set<String>> groups(UUID uuid) {
        var luckPerms = plugin.integrations().luckPerms();
        if (luckPerms.isPresent()) return luckPerms.get().groups(uuid).exceptionally(error -> Set.of());
        Player online = Bukkit.getPlayer(uuid);
        Set<String> groups = new HashSet<>();
        ConfigurationSection mapping = plugin.getConfig().getConfigurationSection("role-sync.groups");
        if (online != null && mapping != null) {
            for (String group : mapping.getKeys(false)) if (online.hasPermission("group." + group)) groups.add(group.toLowerCase(Locale.ROOT));
        }
        return CompletableFuture.completedFuture(groups);
    }
}

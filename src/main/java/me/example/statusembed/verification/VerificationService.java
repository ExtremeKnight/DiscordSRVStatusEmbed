package me.example.statusembed.verification;

import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Guild;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role;
import me.example.statusembed.StatusEmbed;

/** Discord role verification boundary used by the verification panel and future automations. */
public final class VerificationService {
    private final StatusEmbed plugin;

    public VerificationService(StatusEmbed plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("verification.enabled", true); }

    public boolean assignRole(JDA jda, String guildId, String userId) {
        if (!enabled() || jda == null || !validId(guildId) || !validId(userId)) return false;
        Guild guild = jda.getGuildById(guildId);
        String roleId = plugin.getConfig().getString("verification.role-id", "");
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (guild == null || role == null) return false;
        guild.addRoleToMember(userId, role).queue();
        return true;
    }

    private boolean validId(String value) { return value != null && value.matches("\\d{17,20}"); }
}

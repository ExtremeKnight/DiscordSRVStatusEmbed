package com.exenova.link.verification;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import com.exenova.link.ExeNovaLink;

import java.util.function.Consumer;

/** Discord role verification boundary used by the verification panel and future automations. */
public final class VerificationService {
    private final ExeNovaLink plugin;

    public VerificationService(ExeNovaLink plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("verification.enabled", true); }

    /**
     * Queues the verification role for {@code userId}. Returns false if the
     * request could not even be sent (bad config/IDs); otherwise exactly one
     * of the callbacks runs once Discord answers.
     */
    public boolean assignRole(JDA jda, String guildId, String userId, Runnable onSuccess, Consumer<Throwable> onFailure) {
        if (!enabled() || jda == null || !validId(guildId) || !validId(userId)) return false;
        Guild guild = jda.getGuildById(guildId);
        String roleId = plugin.getConfig().getString("verification.role-id", "");
        Role role = guild == null || !validId(roleId) ? null : guild.getRoleById(roleId);
        if (guild == null || role == null) return false;
        try {
            guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(userId), role).queue(ignored -> onSuccess.run(), onFailure);
        } catch (RuntimeException exception) {
            // JDA throws synchronously (e.g. HierarchyException,
            // InsufficientPermissionException) before anything is queued.
            onFailure.accept(exception);
        }
        return true;
    }

    private boolean validId(String value) { return value != null && value.matches("\\d{17,20}"); }
}

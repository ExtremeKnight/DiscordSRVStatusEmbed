package com.exenova.link.automation;

import net.dv8tion.jda.api.JDA;
import com.exenova.link.ExeNovaLink;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/** Runtime data exposed to conditions and actions. */
public final class AutomationContext {
    private final ExeNovaLink plugin;
    private final Object event;
    private final JDA jda;
    private final Map<String, String> variables = new HashMap<>();

    public AutomationContext(ExeNovaLink plugin, Object event, JDA jda) {
        this.plugin = plugin;
        this.event = event;
        this.jda = jda;
        Player player = player();
        if (player != null) {
            variables.put("player", player.getName());
            variables.put("uuid", player.getUniqueId().toString());
        } else if (event instanceof net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent interaction) {
            // Discord-originated triggers (buttons, verification, suggestions)
            // expose who clicked so actions like ASSIGN_DISCORD_ROLE can use
            // user: "{discord_user}".
            variables.put("discord_user", interaction.getUser().getId());
            variables.put("discord_name", interaction.getUser().getName());
            if (interaction.getGuild() != null) variables.put("discord_guild", interaction.getGuild().getId());
            if (interaction instanceof net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent component) {
                variables.put("component_id", component.getComponentId());
            }
        }
        // Deliberately no {message}/{command} variables: substituting raw
        // player-typed text into CONSOLE_COMMAND actions would be a command
        // injection vector.
    }

    public ExeNovaLink plugin() { return plugin; }
    public Object event() { return event; }
    public JDA jda() { return jda; }
    public Map<String, String> variables() { return variables; }
    public Player player() {
        if (event instanceof org.bukkit.event.player.PlayerEvent e) return e.getPlayer();
        if (event instanceof org.bukkit.event.entity.PlayerDeathEvent death) return death.getEntity();
        return event instanceof Player player ? player : null;
    }

    public String replace(String value) {
        if (value == null) return "";
        String result = value;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}

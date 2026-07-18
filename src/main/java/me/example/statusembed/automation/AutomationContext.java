package me.example.statusembed.automation;

import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import me.example.statusembed.StatusEmbed;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/** Runtime data exposed to conditions and actions. */
public final class AutomationContext {
    private final StatusEmbed plugin;
    private final Object event;
    private final JDA jda;
    private final Map<String, String> variables = new HashMap<>();

    public AutomationContext(StatusEmbed plugin, Object event, JDA jda) {
        this.plugin = plugin;
        this.event = event;
        this.jda = jda;
        if (event instanceof org.bukkit.event.player.PlayerEvent playerEvent) {
            variables.put("player", playerEvent.getPlayer().getName());
            variables.put("uuid", playerEvent.getPlayer().getUniqueId().toString());
        } else if (event instanceof Player player) {
            variables.put("player", player.getName());
            variables.put("uuid", player.getUniqueId().toString());
        }
    }

    public StatusEmbed plugin() { return plugin; }
    public Object event() { return event; }
    public JDA jda() { return jda; }
    public Map<String, String> variables() { return variables; }
    public Player player() {
        if (event instanceof org.bukkit.event.player.PlayerEvent e) return e.getPlayer();
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

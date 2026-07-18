package me.example.statusembed.automation;

import java.util.List;
import java.util.Map;

/** Immutable, validated automation definition loaded from config.yml. */
public record Automation(String id, boolean enabled, Trigger trigger,
                         Map<String, Object> conditions, List<ActionDefinition> actions,
                         long cooldownSeconds, long intervalSeconds) {
    public Automation {
        conditions = conditions == null ? Map.of() : Map.copyOf(conditions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (cooldownSeconds < 0 || intervalSeconds < 0) {
            throw new IllegalArgumentException("Automation timing values cannot be negative");
        }
    }

    public record ActionDefinition(String type, Map<String, Object> values) {
        public ActionDefinition {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("Action type is required");
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }
}

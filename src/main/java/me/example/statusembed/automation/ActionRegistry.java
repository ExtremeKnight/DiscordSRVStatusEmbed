package me.example.statusembed.automation;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry for independently replaceable automation actions. */
public final class ActionRegistry {
    private final Map<String, Action> actions = new ConcurrentHashMap<>();

    public void register(String type, Action action) {
        if (type == null || type.isBlank() || action == null) throw new IllegalArgumentException("Action registration is invalid");
        actions.put(type.toUpperCase(Locale.ROOT), action);
    }

    public Action find(String type) {
        return type == null ? null : actions.get(type.toUpperCase(Locale.ROOT));
    }

    public boolean contains(String type) { return find(type) != null; }
}

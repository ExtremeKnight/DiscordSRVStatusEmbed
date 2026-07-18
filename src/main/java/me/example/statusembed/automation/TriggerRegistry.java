package me.example.statusembed.automation;

import java.util.EnumSet;
import java.util.Set;

/** Registry of supported trigger types; extensions can add validation here later. */
public final class TriggerRegistry {
    private final Set<Trigger> triggers = EnumSet.allOf(Trigger.class);

    public boolean supports(Trigger trigger) { return trigger != null && triggers.contains(trigger); }
    public void register(Trigger trigger) { if (trigger != null) triggers.add(trigger); }
}

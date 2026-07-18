package me.example.statusembed.automation;

import org.bukkit.Bukkit;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Executes configured actions defensively and keeps one bad action from stopping the plugin. */
public final class AutomationExecutor {
    private final Logger logger;
    private final ActionRegistry actions;

    public AutomationExecutor(Logger logger, ActionRegistry actions) {
        this.logger = logger;
        this.actions = actions;
    }

    public void execute(Automation automation, AutomationContext context) {
        for (Automation.ActionDefinition definition : automation.actions()) {
            Action action = actions.find(definition.type());
            if (action == null) {
                logger.warning("Automation '" + automation.id() + "' references unknown action '" + definition.type() + "'.");
                continue;
            }
            try {
                action.execute(context, definition);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Automation '" + automation.id() + "' action '" + definition.type() + "' failed.", exception);
            }
        }
    }
}

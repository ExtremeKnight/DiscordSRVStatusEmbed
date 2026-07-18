package me.example.statusembed.automation;

/** Independently registerable automation action. */
@FunctionalInterface
public interface Action {
    void execute(AutomationContext context, Automation.ActionDefinition definition);
}

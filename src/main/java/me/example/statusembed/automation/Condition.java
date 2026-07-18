package me.example.statusembed.automation;

/** Evaluates whether an automation may run for a dispatch context. */
@FunctionalInterface
public interface Condition {
    boolean test(AutomationContext context);
}

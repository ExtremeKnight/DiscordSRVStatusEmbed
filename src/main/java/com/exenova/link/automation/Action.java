package com.exenova.link.automation;

/** Independently registerable automation action. */
@FunctionalInterface
public interface Action {
    void execute(AutomationContext context, Automation.ActionDefinition definition);
}

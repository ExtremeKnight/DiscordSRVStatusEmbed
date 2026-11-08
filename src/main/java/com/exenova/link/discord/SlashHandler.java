package com.exenova.link.discord;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method {@code void m(SlashCommandInteractionEvent)} as the handler
 * for a slash command. {@link SlashCommandRouter} discovers these at startup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SlashHandler {
    /** Slash command name, e.g. "java". */
    String value();
    /** Acknowledge the interaction before calling the handler (needed when it may take over 3 seconds). */
    boolean defer() default true;
    /** Make the deferred reply visible only to the user. */
    boolean ephemeral() default false;
}

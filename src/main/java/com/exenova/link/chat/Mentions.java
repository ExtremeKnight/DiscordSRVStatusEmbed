package com.exenova.link.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code @name} mentions of online players in a chat message. Pure and
 * unit-tested; the plugin-side listener handles the actual notify/highlight.
 */
public final class Mentions {
    private static final Pattern TOKEN = Pattern.compile("@([A-Za-z0-9_]{1,16})");

    private Mentions() { }

    /**
     * Returns the canonical names (from {@code onlineNames}) mentioned in
     * {@code message}, case-insensitively, each at most once, in order of first
     * appearance. Requires the {@code @} prefix.
     */
    public static List<String> find(String message, Collection<String> onlineNames) {
        if (message == null || message.indexOf('@') < 0 || onlineNames == null || onlineNames.isEmpty()) return List.of();
        java.util.Map<String, String> canonical = new java.util.HashMap<>();
        for (String name : onlineNames) if (name != null) canonical.put(name.toLowerCase(Locale.ROOT), name);
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(message);
        while (matcher.find()) {
            String hit = canonical.get(matcher.group(1).toLowerCase(Locale.ROOT));
            if (hit != null) found.add(hit);
        }
        return new ArrayList<>(found);
    }

    /** True if {@code message} mentions {@code name} (with or without the {@code @}, case-insensitively). */
    public static boolean mentions(String message, String name) {
        return !find(message, List.of(name)).isEmpty();
    }
}

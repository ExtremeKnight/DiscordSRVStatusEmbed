package com.exenova.link.chat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared, side-effect-free text filtering for in-game chat and the Discord
 * bridge: censor a configurable word list, and detect or strip Discord invites
 * and other URLs. All methods are static and take their inputs explicitly so
 * they can be unit-tested without a server.
 */
public final class TextFilter {
    private static final Pattern INVITE = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?(?:discord(?:app)?\\.com/invite|discord\\.gg|discord\\.me|dsc\\.gg)/\\S+");
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?");

    private TextFilter() { }

    /** Replaces each listed word (case-insensitive, anywhere) with asterisks of the same length. */
    public static String censorWords(String text, List<String> words) {
        if (text == null || words == null || words.isEmpty()) return text;
        String result = text;
        for (String word : words) {
            if (word == null || word.isBlank()) continue;
            result = Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE)
                    .matcher(result).replaceAll("*".repeat(word.length()));
        }
        return result;
    }

    public static boolean containsInvite(String text) {
        return text != null && INVITE.matcher(text).find();
    }

    public static boolean containsUrl(String text) {
        return text != null && URL.matcher(text).find();
    }

    /** Replaces Discord invites with {@code replacement}. */
    public static String stripInvites(String text, String replacement) {
        return text == null ? null : INVITE.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /** Replaces any URL (invites included) with {@code replacement}. */
    public static String stripUrls(String text, String replacement) {
        if (text == null) return null;
        String noInvites = stripInvites(text, replacement);
        return URL.matcher(noInvites).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }
}

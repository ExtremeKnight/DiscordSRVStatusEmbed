package com.exenova.link.chat;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code :shortcode:} tokens with configured symbols/emoji, e.g.
 * {@code :smile:} → {@code ☺}. Side-effect-free and unit-tested. Only tokens
 * present in the supplied map are replaced; anything else (including stray
 * colons or URLs) is left untouched.
 */
public final class Emoji {
    private static final Pattern TOKEN = Pattern.compile(":([a-z0-9_+-]{1,32}):", Pattern.CASE_INSENSITIVE);

    private Emoji() { }

    public static String apply(String text, Map<String, String> emoji) {
        if (text == null || emoji == null || emoji.isEmpty() || text.indexOf(':') < 0) return text;
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String replacement = emoji.get(key);
            matcher.appendReplacement(out, replacement == null
                    ? Matcher.quoteReplacement(matcher.group()) : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

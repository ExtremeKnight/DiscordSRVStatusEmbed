package com.exenova.link.integration;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** PlaceholderAPI bridge. Only loaded when PlaceholderAPI is installed. */
final class PlaceholderSupport {
    String apply(OfflinePlayer player, String text) {
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (RuntimeException exception) {
            return text; // a broken third-party expansion must not break our messages
        }
    }
}

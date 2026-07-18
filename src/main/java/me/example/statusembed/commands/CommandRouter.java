package me.example.statusembed.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small command-routing boundary for future extraction of individual command handlers. */
public final class CommandRouter {
    @FunctionalInterface public interface Handler { boolean handle(CommandSender sender, Command command, String label, String[] args); }
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public void register(String command, Handler handler) { handlers.put(command.toLowerCase(Locale.ROOT), handler); }
    public boolean route(CommandSender sender, Command command, String label, String[] args) {
        Handler handler = handlers.get(command.getName().toLowerCase(Locale.ROOT));
        return handler != null && handler.handle(sender, command, label, args);
    }
    public boolean contains(String command) { return command != null && handlers.containsKey(command.toLowerCase(Locale.ROOT)); }
}

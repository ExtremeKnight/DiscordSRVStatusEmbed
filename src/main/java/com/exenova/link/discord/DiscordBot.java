package com.exenova.link.discord;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Owns ExeNovaLink's own Discord bot connection. The plugin no longer
 * borrows DiscordSRV's bot: it logs in with {@code discord.bot-token} (or the
 * {@code EXENOVA_DISCORD_TOKEN} environment variable) using JDA 5.
 */
public final class DiscordBot {
    private final ExeNovaLink plugin;
    private final List<Object> listeners = new CopyOnWriteArrayList<>();
    private volatile JDA jda;
    private volatile boolean ready;

    public DiscordBot(ExeNovaLink plugin) { this.plugin = plugin; }

    public JDA jda() { return jda; }
    public boolean isReady() { return ready && jda != null && jda.getStatus() == JDA.Status.CONNECTED; }

    /** Adds a JDA listener; it is attached now if connected and on every future login. */
    public void addListener(Object listener) {
        listeners.add(listener);
        JDA current = jda;
        if (current != null) current.addEventListener(listener);
    }

    public String token() {
        String env = System.getenv("EXENOVA_DISCORD_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        return plugin.getConfig().getString("discord.bot-token", "").trim();
    }

    /**
     * Logs in on a background thread so a slow Discord gateway never stalls
     * server startup. {@code onReady} runs on the main server thread.
     */
    public void start(Supplier<Collection<CommandData>> commands, Runnable onReady) {
        String token = token();
        if (token.isEmpty() || token.equals("PUT_YOUR_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("No Discord bot token set. Create a bot at https://discord.com/developers/applications, "
                    + "enable the MESSAGE CONTENT and SERVER MEMBERS intents, then put its token in discord.bot-token "
                    + "(or the EXENOVA_DISCORD_TOKEN environment variable) and run /exenova reload.");
            return;
        }
        // Relayed chat, console output and player-supplied text must never be
        // able to ping @everyone/@here or roles; only user/channel mentions render.
        net.dv8tion.jda.api.utils.messages.MessageRequest.setDefaultMentions(EnumSet.of(
                net.dv8tion.jda.api.entities.Message.MentionType.USER,
                net.dv8tion.jda.api.entities.Message.MentionType.CHANNEL,
                net.dv8tion.jda.api.entities.Message.MentionType.EMOJI,
                net.dv8tion.jda.api.entities.Message.MentionType.SLASH_COMMAND));
        boolean members = plugin.getConfig().getBoolean("discord.intents.server-members", true);
        EnumSet<GatewayIntent> intents = EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_WEBHOOKS);
        if (members) intents.add(GatewayIntent.GUILD_MEMBERS);

        Thread loginThread = new Thread(() -> {
            try {
                JDABuilder builder = JDABuilder.createLight(token, intents)
                        .setStatus(OnlineStatus.ONLINE)
                        .enableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.ROLE_TAGS)
                        .setMemberCachePolicy(members ? MemberCachePolicy.ALL : MemberCachePolicy.DEFAULT)
                        .setChunkingFilter(members ? ChunkingFilter.ALL : ChunkingFilter.NONE)
                        .addEventListeners(new ListenerAdapter() {
                            @Override public void onReady(ReadyEvent event) {
                                ready = true;
                                registerCommands(event.getJDA(), commands.get());
                                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, onReady);
                            }
                        });
                for (Object listener : listeners) builder.addEventListeners(listener);
                jda = builder.build();
                plugin.getLogger().info("Connecting to Discord...");
            } catch (IllegalArgumentException | net.dv8tion.jda.api.exceptions.InvalidTokenException exception) {
                plugin.getLogger().severe("Discord rejected the bot token: " + exception.getMessage());
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not start the Discord bot", exception);
            }
        }, "ExeNovaLink-Discord-Login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    private void registerCommands(JDA jda, Collection<CommandData> commands) {
        List<CommandData> list = new ArrayList<>(commands);
        String guildId = plugin.getConfig().getString("discord.guild-id", "");
        Guild guild = guildId.matches("\\d{17,20}") ? jda.getGuildById(guildId) : null;
        if (guild != null) {
            // Guild commands update instantly; global ones can take up to an hour.
            guild.updateCommands().addCommands(list).queue(
                    done -> plugin.getLogger().info("Registered " + done.size() + " slash command(s) in " + guild.getName() + "."),
                    failure -> plugin.getLogger().warning("Could not register slash commands: " + failure.getMessage()));
        } else {
            jda.updateCommands().addCommands(list).queue(
                    done -> plugin.getLogger().info("Registered " + done.size() + " global slash command(s). Set discord.guild-id for instant updates."),
                    failure -> plugin.getLogger().warning("Could not register slash commands: " + failure.getMessage()));
        }
    }

    /** Re-sends the slash command list (used after a reload changes which commands are enabled). */
    public void refreshCommands(Collection<CommandData> commands) {
        JDA current = jda;
        if (current != null && ready) registerCommands(current, commands);
    }

    /** Disconnects cleanly, waiting briefly so pending messages (e.g. the "server offline" embed) are flushed. */
    public void shutdown() {
        JDA current = jda;
        jda = null;
        ready = false;
        if (current == null) return;
        current.shutdown();
        try {
            if (!current.awaitShutdown(Duration.ofSeconds(5))) current.shutdownNow();
        } catch (InterruptedException exception) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

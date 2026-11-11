package com.exenova.link.linking;

import com.exenova.link.linking.store.LinkStore;
import com.exenova.link.linking.store.YamlLinkStore;

import java.io.File;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Minecraft ↔ Discord account links. The links are held in memory and persisted
 * through a {@link LinkStore} (YAML, SQLite or MySQL); reads are always served
 * from memory, so the backend never blocks a lookup. Linking is a rare,
 * user-initiated action, so each change is written through synchronously.
 *
 * <p>Linking works in either direction with a short one-time code: run
 * {@code /link} in Minecraft and give the code to the bot (slash command or
 * DM), or run {@code /link} in Discord and type {@code /link <code>} in game.
 * Codes expire, and failed attempts are rate limited per requester, so codes
 * cannot be brute-forced to hijack someone else's link.</p>
 */
public final class LinkManager {
    /** No 0/O/1/I/L to avoid look-alike characters. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int CODE_LENGTH = 6;
    static final long CODE_TTL_MS = 10 * 60_000L;
    static final int MAX_FAILED_ATTEMPTS = 5;

    public enum Result { LINKED, INVALID_CODE, RATE_LIMITED, ALREADY_LINKED }

    private record Pending(UUID minecraft, String discord, long expires) { }
    private record Attempts(int failures, long windowStart) { }

    private final Map<UUID, String> byMinecraft = new ConcurrentHashMap<>();
    private final Map<String, UUID> byDiscord = new ConcurrentHashMap<>();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LinkStore store;
    private final Logger logger;
    private final LongSupplier clock;
    private Function<UUID, Optional<String>> fallbackDiscord = uuid -> Optional.empty();
    private Function<String, Optional<UUID>> fallbackMinecraft = id -> Optional.empty();

    public LinkManager(LinkStore store, Logger logger) { this(store, logger, System::currentTimeMillis); }

    /** Backwards-compatible constructor: persists to {@code file} as YAML. */
    public LinkManager(File file, Logger logger) { this(new YamlLinkStore(file, logger), logger, System::currentTimeMillis); }

    LinkManager(File file, Logger logger, LongSupplier clock) { this(new YamlLinkStore(file, logger), logger, clock); }

    LinkManager(LinkStore store, Logger logger, LongSupplier clock) {
        this.store = store;
        this.logger = logger;
        this.clock = clock;
    }

    /** Lets an existing link source (DiscordSRV) answer for players who have no ExeNovaLink link yet. */
    public void setFallback(Function<UUID, Optional<String>> discord, Function<String, Optional<UUID>> minecraft) {
        fallbackDiscord = discord;
        fallbackMinecraft = minecraft;
    }

    public void load() {
        byMinecraft.clear();
        byDiscord.clear();
        try {
            store.loadAll().forEach(this::putInMemory);
            logger.info("Loaded " + byMinecraft.size() + " linked account(s) from " + store.describe() + ".");
        } catch (Exception exception) {
            logger.warning("Could not load links from " + store.describe() + ": " + exception.getMessage());
        }
    }

    /** Writes are per-change now; retained so the existing shutdown call is a harmless flush. */
    public void save() { /* no-op: link()/unlink() write through to the store */ }

    public void close() {
        try { store.close(); } catch (Exception exception) { logger.warning("Error closing link store: " + exception.getMessage()); }
    }

    public Optional<String> discordId(UUID minecraft) {
        String local = byMinecraft.get(minecraft);
        return local != null ? Optional.of(local) : fallbackDiscord.apply(minecraft);
    }

    public Optional<UUID> minecraftId(String discordId) {
        UUID local = byDiscord.get(discordId);
        return local != null ? Optional.of(local) : fallbackMinecraft.apply(discordId);
    }

    public boolean isLinked(UUID minecraft) { return discordId(minecraft).isPresent(); }
    public int size() { return byMinecraft.size(); }

    /** Code for a Minecraft player to give to the Discord bot. */
    public String createCodeForMinecraft(UUID minecraft) {
        codes.values().removeIf(pending -> minecraft.equals(pending.minecraft()));
        return issue(new Pending(minecraft, null, clock.getAsLong() + CODE_TTL_MS));
    }

    /** Code for a Discord user to type in game. */
    public String createCodeForDiscord(String discordId) {
        codes.values().removeIf(pending -> discordId.equals(pending.discord()));
        return issue(new Pending(null, discordId, clock.getAsLong() + CODE_TTL_MS));
    }

    private String issue(Pending pending) {
        purgeExpired();
        String code;
        do {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = builder.toString();
        } while (codes.putIfAbsent(code, pending) != null);
        return code;
    }

    /** Called from Discord with a code that a Minecraft player generated. */
    public Result redeemFromDiscord(String discordId, String code) {
        return redeem("discord:" + discordId, code, pending -> pending.minecraft() != null, pending -> link(pending.minecraft(), discordId));
    }

    /** Called in game with a code that a Discord user generated. */
    public Result redeemFromMinecraft(UUID minecraft, String code) {
        return redeem("minecraft:" + minecraft, code, pending -> pending.discord() != null, pending -> link(minecraft, pending.discord()));
    }

    private Result redeem(String requester, String rawCode, java.util.function.Predicate<Pending> rightDirection, Function<Pending, Result> onSuccess) {
        long now = clock.getAsLong();
        Attempts current = attempts.get(requester);
        if (current != null && now - current.windowStart() < CODE_TTL_MS && current.failures() >= MAX_FAILED_ATTEMPTS) return Result.RATE_LIMITED;
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        Pending pending = codes.get(code);
        if (pending == null || pending.expires() < now || !rightDirection.test(pending) || !codes.remove(code, pending)) {
            attempts.merge(requester, new Attempts(1, now), (old, ignored) ->
                    now - old.windowStart() >= CODE_TTL_MS ? new Attempts(1, now) : new Attempts(old.failures() + 1, old.windowStart()));
            return Result.INVALID_CODE;
        }
        attempts.remove(requester);
        return onSuccess.apply(pending);
    }

    /** Links (replacing any previous link of either account) and persists. */
    public Result link(UUID minecraft, String discordId) {
        if (discordId.equals(byMinecraft.get(minecraft))) return Result.ALREADY_LINKED;
        removeFromMemory(minecraft);
        UUID previous = byDiscord.get(discordId);
        if (previous != null) removeFromMemory(previous);
        putInMemory(minecraft, discordId);
        store.put(minecraft, discordId, clock.getAsLong(), "user");
        return Result.LINKED;
    }

    /** Removes a link; returns the Discord ID that was linked, if any. */
    public Optional<String> unlinkMinecraft(UUID minecraft) {
        String discordId = removeFromMemory(minecraft);
        if (discordId != null) store.remove(minecraft);
        return Optional.ofNullable(discordId);
    }

    public Optional<UUID> unlinkDiscord(String discordId) {
        UUID minecraft = byDiscord.get(discordId);
        if (minecraft != null) unlinkMinecraft(minecraft);
        return Optional.ofNullable(minecraft);
    }

    public Map<UUID, String> snapshot() { return Map.copyOf(byMinecraft); }

    private void putInMemory(UUID minecraft, String discordId) {
        byMinecraft.put(minecraft, discordId);
        byDiscord.put(discordId, minecraft);
    }

    private String removeFromMemory(UUID minecraft) {
        String discordId = byMinecraft.remove(minecraft);
        if (discordId != null) byDiscord.remove(discordId, minecraft);
        return discordId;
    }

    private void purgeExpired() {
        long now = clock.getAsLong();
        codes.values().removeIf(pending -> pending.expires() < now);
        attempts.values().removeIf(entry -> now - entry.windowStart() >= CODE_TTL_MS);
    }
}

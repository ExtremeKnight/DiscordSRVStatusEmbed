package com.exenova.link.linking.store;

import java.util.Map;
import java.util.UUID;

/**
 * Persistence backend for account links. {@link com.exenova.link.linking.LinkManager}
 * keeps the links in memory and treats a store as the durable copy: it reads
 * everything once with {@link #loadAll()} and then writes each change through
 * {@link #put} / {@link #remove}.
 *
 * <p>Implementations decide their own threading. Writes may be asynchronous;
 * the in-memory cache is always updated first, so a slow or failing store never
 * blocks a read.</p>
 */
public interface LinkStore extends AutoCloseable {
    /** Every link, Minecraft UUID → Discord ID. Called once at startup. */
    Map<UUID, String> loadAll() throws Exception;

    /** Inserts or replaces the link for {@code minecraft}, clearing any previous owner of {@code discordId}. */
    void put(UUID minecraft, String discordId, long linkedAt, String source);

    /** Removes the link for {@code minecraft}, if any. */
    void remove(UUID minecraft);

    /** Human-readable backend name for diagnostics, e.g. "SQLite" or "links.yml". */
    String describe();

    @Override
    default void close() { }
}

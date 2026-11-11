package com.exenova.link.linking.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite or MySQL link store backed by a HikariCP pool. One table maps a
 * Minecraft UUID to a Discord ID; {@code discord_id} is unique so a single
 * Discord account owns one Minecraft account. Writes are a single upsert, which
 * also clears any previous owner of that Discord ID (mirrors the relink rule).
 */
public final class SqlLinkStore implements LinkStore {

    public enum Dialect { SQLITE, MYSQL }

    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final String table;

    private SqlLinkStore(HikariDataSource dataSource, Dialect dialect, String table) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.table = table;
    }

    /** Opens a SQLite store at {@code dbFile} and creates the schema if needed. */
    public static SqlLinkStore sqlite(java.io.File dbFile, String tablePrefix) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(1); // SQLite is single-writer
        config.setPoolName("ExeNovaLink-SQLite");
        return create(config, Dialect.SQLITE, tablePrefix);
    }

    /** Opens a MySQL store with the given connection settings and creates the schema if needed. */
    public static SqlLinkStore mysql(String host, int port, String database, String user, String password,
                                     int poolSize, String tablePrefix) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true&characterEncoding=utf8");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(1, poolSize));
        config.setPoolName("ExeNovaLink-MySQL");
        return create(config, Dialect.MYSQL, tablePrefix);
    }

    private static SqlLinkStore create(HikariConfig config, Dialect dialect, String tablePrefix) throws SQLException {
        String table = (tablePrefix == null || tablePrefix.isBlank() ? "exenova_" : tablePrefix) + "links";
        if (!table.matches("[A-Za-z0-9_]+")) throw new SQLException("Invalid table prefix: " + tablePrefix);
        HikariDataSource dataSource = new HikariDataSource(config);
        SqlLinkStore store = new SqlLinkStore(dataSource, dialect, table);
        store.createSchema();
        return store;
    }

    private void createSchema() throws SQLException {
        // VARCHAR length differs only cosmetically; both engines accept this.
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "minecraft_uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                + "discord_id VARCHAR(20) NOT NULL UNIQUE, "
                + "linked_at BIGINT NOT NULL, "
                + "source VARCHAR(16) NOT NULL)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.executeUpdate();
        }
    }

    @Override
    public Map<UUID, String> loadAll() throws SQLException {
        Map<UUID, String> links = new LinkedHashMap<>();
        String sql = "SELECT minecraft_uuid, discord_id FROM " + table;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    links.put(UUID.fromString(rows.getString(1)), rows.getString(2));
                } catch (IllegalArgumentException ignored) { /* skip a malformed row */ }
            }
        }
        return links;
    }

    @Override
    public void put(UUID minecraft, String discordId, long linkedAt, String source) {
        // Clear any other account that currently owns this Discord ID, then upsert.
        String clear = "DELETE FROM " + table + " WHERE discord_id = ? AND minecraft_uuid <> ?";
        String upsert = dialect == Dialect.SQLITE
                ? "INSERT INTO " + table + " (minecraft_uuid, discord_id, linked_at, source) VALUES (?,?,?,?) "
                  + "ON CONFLICT(minecraft_uuid) DO UPDATE SET discord_id=excluded.discord_id, linked_at=excluded.linked_at, source=excluded.source"
                : "INSERT INTO " + table + " (minecraft_uuid, discord_id, linked_at, source) VALUES (?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE discord_id=VALUES(discord_id), linked_at=VALUES(linked_at), source=VALUES(source)";
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement clearStatement = connection.prepareStatement(clear);
                 PreparedStatement upsertStatement = connection.prepareStatement(upsert)) {
                clearStatement.setString(1, discordId);
                clearStatement.setString(2, minecraft.toString());
                clearStatement.executeUpdate();
                upsertStatement.setString(1, minecraft.toString());
                upsertStatement.setString(2, discordId);
                upsertStatement.setLong(3, linkedAt);
                upsertStatement.setString(4, source == null ? "user" : source);
                upsertStatement.executeUpdate();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save link for " + minecraft + ": " + exception.getMessage(), exception);
        }
    }

    @Override
    public void remove(UUID minecraft) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE minecraft_uuid = ?")) {
            statement.setString(1, minecraft.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not remove link for " + minecraft + ": " + exception.getMessage(), exception);
        }
    }

    @Override
    public String describe() { return dialect == Dialect.SQLITE ? "SQLite" : "MySQL"; }

    @Override
    public void close() {
        if (!dataSource.isClosed()) dataSource.close();
    }
}

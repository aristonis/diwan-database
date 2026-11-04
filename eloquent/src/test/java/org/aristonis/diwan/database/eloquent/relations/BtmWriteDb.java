package org.aristonis.diwan.database.eloquent.relations;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/**
 * The belongsToMany database with timestamps on users and roles, the keyed membership table and
 * optional unique indexes. Wraps {@link BtmDb}, so the base tables and seed rows come from it.
 */
final class BtmWriteDb implements AutoCloseable {

    private final BtmDb base;
    private final QueryLog log = new QueryLog();

    /** The logged factory the write models are bound to. */
    final QueryFactory db;

    /** The unlogged connection, for tests that add their own indexes. */
    final Connection real;

    private BtmWriteDb(BtmDb base) {
        this.base = base;
        this.real = base.real;
        this.db = SQLiteQuery.of(log.wrap(real));
        Schema schema = SQLiteSchema.of(real);
        schema.table("users", t -> {
            t.nullableTimestamps();
        });
        schema.table("roles", t -> {
            t.nullableTimestamps();
        });
        schema.create("role_user_keyed", t -> {
            t.id();
            t.integer("user_id");
            t.integer("role_id");
            t.string("status");
            t.integer("weight");
            t.nullableTimestamps();
        });
        BtmWriteModels.register();
    }

    /**
     * Opens a new in-memory database with the write-side tables and the base seed data.
     *
     * @return a new database instance, never {@code null}
     * @throws SQLException if the database cannot be opened
     */
    static BtmWriteDb open() throws SQLException {
        return new BtmWriteDb(BtmDb.open());
    }

    /**
     * Adds a unique index on the link table so a duplicate user and role pair is refused.
     */
    void createLinkUniqueIndex() {
        SQLiteSchema.of(real).table("role_user", t -> t.unique("user_id", "role_id"));
    }

    /**
     * Adds a unique index on the role names.
     */
    void createRoleNameUniqueIndex() {
        SQLiteSchema.of(real).table("roles", t -> t.unique("name"));
    }

    /**
     * Fills {@code role_user_keyed} with the memberships the base fixture holds in
     * {@code role_user}: user 1 has roles 1 (active, 5) and 2 (pending, 1), user 2 has role 1
     * (active, 3), user 3 has none. The base database already holds the {@code role_user} rows.
     */
    void seed() {
        keyedRow(1, 1, 1, "active", 5);
        keyedRow(2, 1, 2, "pending", 1);
        keyedRow(3, 2, 1, "active", 3);
    }

    private void keyedRow(int id, int userId, int roleId, String status, int weight) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("user_id", userId);
        row.put("role_id", roleId);
        row.put("status", status);
        row.put("weight", weight);
        row.put("created_at", "2024-01-01 10:00:00");
        row.put("updated_at", "2024-01-01 10:00:00");
        base.seed("role_user_keyed", row);
    }

    /**
     * Returns the query log of the write database's factory, which records its selects.
     *
     * @return the log, never {@code null}
     */
    QueryLog queryLog() {
        return log;
    }

    /**
     * Closes the underlying database.
     *
     * @throws SQLException if the connection cannot be closed
     */
    @Override
    public void close() throws SQLException {
        base.close();
    }
}

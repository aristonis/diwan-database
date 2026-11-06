package org.aristonis.diwan.database.sqlite;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Processor;

/**
 * Processes SQLite results; returns the key of an inserted row.
 *
 * <p>Laravel asks PDO for the last insert id. Here SQLite 3.35 and later return the key from the
 * insert itself with {@code returning}, which works for any key type. An older SQLite runs the
 * insert and then {@code select last_insert_rowid()} on the same physical connection, which
 * returns the row's {@code rowid}: the key only when the key column is an {@code integer primary
 * key}, such as Diwan's {@code id()}. On that path a table keyed by text or a {@code without
 * rowid} table gets a wrong or stale number back, so such a table needs SQLite 3.35.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public class SQLiteProcessor extends Processor {

    private static final int RETURNING_MAJOR = 3;
    private static final int RETURNING_MINOR = 35;

    private final Function<Connection, String> version;

    /** Creates a processor that reads the SQLite version from the connection. */
    public SQLiteProcessor() {
        this(connection -> connection.getDriverInfo().databaseVersion());
    }

    /** Creates a processor that takes the SQLite version from {@code version}; a test seam. */
    SQLiteProcessor(Function<Connection, String> version) {
        this.version = version;
    }

    /**
     * Runs an insert of one row and returns the new key.
     *
     * <p>With {@code returning}, the key column's value as SQLite returned it. Without, the
     * {@code rowid}, read on the session that ran the insert; see the class description for the
     * tables that path supports.
     *
     * @param query the query, never {@code null}
     * @param sql the compiled insert, never {@code null}
     * @param values the prepared bindings, never {@code null}
     * @param sequence the key column, never {@code null}
     * @return the new key: a {@code Long} when it is a whole number, else as SQLite returned it;
     *     {@code null} when nothing ran, as in pretend mode
     * @throws NullPointerException if an argument is {@code null}
     * @throws org.aristonis.diwan.database.QueryException if SQLite refuses the insert
     */
    @Override
    public Object processInsertGetId(Builder query, String sql, List<Object> values, String sequence) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(sequence, "sequence");
        Connection connection = query.getConnection();
        if (supportsReturning(version.apply(connection))) {
            String returning = sql + " returning " + query.getGrammar().wrap(sequence);
            return connection.selectOne(returning, values)
                    .map(row -> toId(row.values().iterator().next()))
                    .orElse(null);
        }
        // last_insert_rowid() belongs to the physical connection, so the insert and the read must
        // share one: a pooled connection could otherwise read another session's rowid.
        return connection.session(session -> {
            session.insert(sql, values);
            return session.scalar("select last_insert_rowid()").map(this::toId).orElse(null);
        });
    }

    /**
     * Returns whether a SQLite version has {@code returning} (3.35.0 or later), comparing each
     * part as a whole number; a version that cannot be read counts as older.
     *
     * @param version the version the database reports, may be {@code null}
     * @return {@code true} if {@code returning} is available; see {@link SqliteVersion}
     */
    static boolean supportsReturning(String version) {
        return SqliteVersion.atLeast(version, RETURNING_MAJOR, RETURNING_MINOR, 0);
    }
}

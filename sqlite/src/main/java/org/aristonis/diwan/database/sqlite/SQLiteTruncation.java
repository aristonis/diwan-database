package org.aristonis.diwan.database.sqlite;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.driver.DriverFailureMessages;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;

/**
 * Empties tables with foreign key enforcement switched off, so a key declared
 * {@code on delete restrict} does not stop the delete. Used only when such a key links listed
 * tables, since no referential action runs while enforcement is off. SQLite checks such a key row by row, even
 * when checks are deferred, so a self-referencing table cannot be emptied in one statement
 * otherwise.
 *
 * <p>SQLite ignores {@code PRAGMA foreign_keys} inside a transaction, so the switch happens on one
 * held connection before the transaction starts. Before the commit, {@code pragma foreign_key_check}
 * is compared with its result from before the deletes: a row outside the emptied tables that now
 * points at a deleted one fails the truncate and undoes it, as an enforced key would have. Orphans
 * that were there before are left alone.
 */
final class SQLiteTruncation {

    private static final String KEYS_OFF = "pragma foreign_keys = off";
    private static final String KEYS_ON = "pragma foreign_keys = on";
    private static final String CHECK = "pragma foreign_key_check";
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;

    private SQLiteTruncation() {
    }

    /**
     * Runs the deletes with enforcement off when it is on; otherwise runs them as before.
     *
     * @param connection the connection, outside a transaction
     * @param deletes runs the statements on the transaction it is given
     * @param asBefore runs the statements the usual way, for a session with enforcement already off
     */
    static void run(Connection connection, Consumer<Connection> deletes, Consumer<Connection> asBefore) {
        connection.session(session -> {
            if (!isOn(session.scalar("pragma foreign_keys").orElse(null))) {
                asBefore.accept(session);
                return null;
            }
            session.statement(KEYS_OFF);
            try {
                session.transaction(tx -> {
                    Map<String, Integer> before = counts(violations(tx));
                    deletes.accept(tx);
                    refuseNewViolations(tx, before);
                    return null;
                });
            } catch (RuntimeException | Error failure) {
                try {
                    session.statement(KEYS_ON);
                } catch (RuntimeException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
                throw failure;
            }
            try {
                session.statement(KEYS_ON);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("The tables were emptied, but foreign key enforcement could not be"
                        + " switched back on; the database connection was discarded", failure);
            }
            return null;
        });
    }

    private static boolean isOn(Object value) {
        return value instanceof Number && ((Number) value).longValue() == 1;
    }

    /**
     * Every violation in every attached database, each with a key that compares equal later. A
     * {@code without rowid} table reports no rowid, so one key can stand for several rows; the
     * comparison counts them.
     */
    private static List<Map.Entry<String, Map<String, Object>>> violations(Connection connection) {
        List<Map.Entry<String, Map<String, Object>>> found = new ArrayList<>();
        for (Map<String, Object> database : connection.select("pragma database_list")) {
            String schema = String.valueOf(database.get("name"));
            String check = "pragma \"" + schema.replace("\"", "\"\"") + "\".foreign_key_check";
            for (Map<String, Object> row : connection.select(check)) {
                found.add(new AbstractMap.SimpleImmutableEntry<>(schema + "\u0000" + row.get("table") + "\u0000"
                        + row.get("rowid") + "\u0000" + row.get("parent") + "\u0000" + row.get("fkid"), row));
            }
        }
        return found;
    }

    private static Map<String, Integer> counts(List<Map.Entry<String, Map<String, Object>>> violations) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> violation : violations) {
            counts.merge(violation.getKey(), 1, Integer::sum);
        }
        return counts;
    }

    private static void refuseNewViolations(Connection connection, Map<String, Integer> before) {
        Map<String, Integer> left = new HashMap<>(before);
        for (Map.Entry<String, Map<String, Object>> violation : violations(connection)) {
            int known = left.getOrDefault(violation.getKey(), 0);
            if (known == 0) {
                throw failure(connection, violation.getValue());
            }
            left.put(violation.getKey(), known - 1);
        }
    }

    private static ForeignKeyViolationException failure(Connection connection, Map<String, Object> row) {
        Set<DriverFailureKind> kinds = EnumSet.of(DriverFailureKind.FOREIGN_KEY_VIOLATION);
        String databaseMessage = "FOREIGN KEY constraint failed (table " + row.get("table") + ", parent "
                + row.get("parent") + ")";
        DriverException cause = new DriverException(
                DriverFailureMessages.message(kinds, null, SQLITE_CONSTRAINT_FOREIGNKEY, "SQLITE_CONSTRAINT_FOREIGNKEY"),
                null, SQLITE_CONSTRAINT_FOREIGNKEY, databaseMessage, null, kinds);
        return new ForeignKeyViolationException(connection.getName(), CHECK, Collections.emptyList(), cause, false);
    }
}

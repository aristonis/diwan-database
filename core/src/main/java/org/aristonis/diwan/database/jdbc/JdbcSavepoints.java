package org.aristonis.diwan.database.jdbc;

import java.sql.Savepoint;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The named savepoints of one JDBC session, kept apart from the session so the name rules and the
 * bookkeeping of which savepoints survive a rollback or release sit in one small place.
 *
 * <p>Not thread-safe; it shares the single-thread use of its session.
 */
final class JdbcSavepoints {

    // The name goes to the database verbatim, so it must not be able to carry SQL.
    private static final Pattern SAVEPOINT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,29}");

    /** Savepoints by name, in creation order. */
    private final Map<String, Savepoint> savepoints = new LinkedHashMap<>();

    /** Creates an empty set of savepoints. */
    JdbcSavepoints() {
    }

    /**
     * Checks that a name may be used for a new savepoint.
     *
     * @param name the name to check, not {@code null}
     * @throws IllegalArgumentException if the name is not 1 to 30 ASCII letters, digits or underscores
     *         not starting with a digit, or a savepoint of that name already exists
     */
    void requireNewName(String name) {
        if (!SAVEPOINT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "savepoint name must be 1 to 30 ASCII letters, digits or underscores, not starting with a digit");
        }
        if (savepoints.containsKey(name)) {
            throw new IllegalArgumentException("savepoint already exists: " + name);
        }
    }

    /**
     * Records a savepoint under its name.
     *
     * @param name the savepoint's name
     * @param savepoint the savepoint the connection returned
     */
    void put(String name, Savepoint savepoint) {
        savepoints.put(name, savepoint);
    }

    /**
     * Looks up a savepoint that must exist.
     *
     * @param name the savepoint's name, not {@code null}
     * @return the savepoint
     * @throws IllegalArgumentException if no savepoint has that name
     */
    Savepoint require(String name) {
        Objects.requireNonNull(name, "name");
        Savepoint savepoint = savepoints.get(name);
        if (savepoint == null) {
            throw new IllegalArgumentException("unknown savepoint: " + name);
        }
        return savepoint;
    }

    /**
     * Forgets the savepoints created after the named one.
     *
     * @param name the savepoint that marks the cut
     * @param isInclusive whether the named savepoint is forgotten too
     */
    void dropAfter(String name, boolean isInclusive) {
        boolean isFound = false;
        Iterator<String> names = savepoints.keySet().iterator();
        while (names.hasNext()) {
            String current = names.next();
            if (isFound) {
                names.remove();
            } else if (current.equals(name)) {
                isFound = true;
                if (isInclusive) {
                    names.remove();
                }
            }
        }
    }

    /** Forgets every savepoint, as when the transaction ends. */
    void clear() {
        savepoints.clear();
    }
}

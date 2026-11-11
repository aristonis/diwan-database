package org.aristonis.diwan.database.testing;

import java.util.Optional;
import org.aristonis.diwan.database.Connection;

/**
 * The advisory locks template mode takes on its run connection: one that says a run is alive and
 * one that serialises building a template.
 *
 * <p>Both use the two-int form {@code pg_advisory_lock(int, int)}, with a namespace in the first
 * int and the key in the second. The migration lock in the PostgreSQL module uses the one-bigint
 * form, whose key is a 64-bit FNV hash of a name ({@code PostgresMigrationLock.keyOf}). PostgreSQL
 * keeps the two forms apart when it grants locks, so a run lock can never block a migration lock.
 * That module's holder lookup reads {@code pg_locks} without {@code objsubid} and could in theory
 * name a two-int holder whose halves equal a bigint key; that needs the high 32 bits of a name's
 * hash to equal a namespace below, about one chance in 2^31 per lock name, and the only effect
 * would be a wrong process id in a lock-wait message. This is accepted.
 */
final class PostgresAdvisoryKeys {

    /** Namespace of the run lock; the key is the run id as an unsigned 32-bit number. The text "DWR1". */
    static final int RUN_NS = 0x44575231;

    /** Namespace of the template build lock; the key is the hash as an unsigned 32-bit number. The text "DWB1". */
    static final int BUILD_NS = 0x44574231;

    private PostgresAdvisoryKeys() {
    }

    /**
     * Takes a lock on the connection's session, waiting until it is free.
     *
     * @param connection the run connection, never {@code null}
     * @param namespace the namespace, {@link #RUN_NS} or {@link #BUILD_NS}
     * @param key the key within the namespace
     * @throws org.aristonis.diwan.database.exception.QueryException if the statement fails
     */
    static void lock(Connection connection, int namespace, int key) {
        connection.statement("select pg_advisory_lock(?::int, ?::int)", namespace, key);
    }

    /**
     * Takes a lock if it is free, without waiting.
     *
     * @param connection the run connection, never {@code null}
     * @param namespace the namespace, {@link #RUN_NS} or {@link #BUILD_NS}
     * @param key the key within the namespace
     * @return {@code true} if the session now holds the lock
     * @throws org.aristonis.diwan.database.exception.QueryException if the statement fails
     */
    static boolean tryLock(Connection connection, int namespace, int key) {
        return isTrue(connection.scalar("select pg_try_advisory_lock(?::int, ?::int)", namespace, key));
    }

    /**
     * Gives back one hold of a lock the session took.
     *
     * @param connection the run connection, never {@code null}
     * @param namespace the namespace, {@link #RUN_NS} or {@link #BUILD_NS}
     * @param key the key within the namespace
     * @return {@code true} if the session held the lock
     * @throws org.aristonis.diwan.database.exception.QueryException if the statement fails
     */
    static boolean unlock(Connection connection, int namespace, int key) {
        return isTrue(connection.scalar("select pg_advisory_unlock(?::int, ?::int)", namespace, key));
    }

    private static boolean isTrue(Optional<Object> answer) {
        return answer.map(Boolean.TRUE::equals).orElse(false);
    }
}

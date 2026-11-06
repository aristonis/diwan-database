package org.aristonis.diwan.database.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Row locks over the base grammar and over a grammar that writes and needs a transaction for them. */
class LockSqlTest {

    /** A grammar like PostgreSQL's for locks: writes {@code for update} and needs a transaction. */
    static final class LockingGrammar extends QueryGrammar {
        LockingGrammar() {
            super("");
        }

        @Override
        protected String compileLock(Builder query, RowLock lock) {
            checkLockable(query);
            return "for " + lock.strength() + " " + lock.lockWait();
        }

        @Override
        protected boolean locksNeedTransaction() {
            return true;
        }
    }

    private final RecordingConnection recording = new RecordingConnection();
    private final QueryFactory plain = recording.db();
    private final QueryFactory locking = recording.db(new LockingGrammar());

    @Test
    void theBaseGrammarWritesNothingForThePlainLocks() {
        assertEquals("select * from \"users\"", plain.table("users").lockForUpdate().toSql());
        assertEquals("select * from \"users\" limit 1", plain.table("users").limit(1).sharedLock().toSql());
        assertEquals("select * from \"users\"",
                plain.table("users").lock(LockStrength.UPDATE, LockWait.WAIT).toSql());
    }

    @Test
    void theBaseGrammarRefusesWhatOnlyPostgresqlHas() {
        for (LockStrength strength : List.of(LockStrength.NO_KEY_UPDATE, LockStrength.KEY_SHARE)) {
            assertThrows(UnsupportedOperationException.class, () -> plain.table("users").lock(strength).toSql());
        }
        for (LockWait wait : List.of(LockWait.NOWAIT, LockWait.SKIP_LOCKED)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> plain.table("users").lock(LockStrength.UPDATE, wait).toSql());
        }
    }

    @Test
    void aPlainLockOutsideATransactionRunsOnTheBaseGrammar() {
        plain.table("users").lockForUpdate().get();

        assertEquals("select * from \"users\"", recording.last().sql());
    }

    @Test
    void theLockIsWrittenLastAfterTheOffset() {
        String sql = locking.table("users").where("id", "=", 1).orderBy("id").limit(5).offset(10).lockForUpdate().toSql();

        assertEquals("select * from \"users\" where \"id\" = ? order by \"id\" asc limit 5 offset 10"
                + " for UPDATE WAIT", sql);
    }

    @Test
    void theLastLockWinsAndACloneKeepsIt() {
        Builder query = locking.table("users").lockForUpdate().lock(LockStrength.KEY_SHARE, LockWait.SKIP_LOCKED);

        assertEquals("select * from \"users\" for KEY_SHARE SKIP_LOCKED", query.clone().toSql());
        assertTrue(query.getLock().isPresent());
        assertFalse(locking.table("users").getLock().isPresent());
    }

    @Test
    void aLockedSelectOutsideATransactionIsRefusedWhereTheLockEndsWithTheStatement() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> locking.table("users").lockForUpdate().get());

        assertEquals("A row lock outside a transaction is released at once; run the query inside transaction(...)",
                refused.getMessage());
        assertTrue(recording.calls.isEmpty());
    }

    @Test
    void aLockedCursorOutsideATransactionIsRefusedToo() {
        assertThrows(IllegalStateException.class, () -> locking.table("users").orderBy("id").lockForUpdate().cursor());
    }

    @Test
    void aLockedSelectInsideATransactionRuns() {
        recording.connection.transaction(connection -> locking.table("users").lockForUpdate().get());

        assertTrue(recording.calls.stream().anyMatch(call -> call.sql().endsWith("for UPDATE WAIT")));
    }

    // The last call is the transaction's end marker, so the statement is the one before it.
    private RecordingConnection.Call lastStatement() {
        return recording.calls.get(recording.calls.size() - 2);
    }

    @Test
    void aggregatesLeaveTheLockOut() {
        recording.connection.transaction(connection -> locking.table("users").lockForUpdate().count());

        assertEquals("select count(*) as \"aggregate\" from \"users\"", lastStatement().sql());
    }

    @Test
    void existsLeavesTheLockOut() {
        recording.connection.transaction(connection -> locking.table("users").sharedLock().exists());

        assertEquals("select exists(select * from \"users\") as \"exists\"", lastStatement().sql());
    }

    @Test
    void theCountOfAPaginationLeavesTheLockOutAndThePageKeepsIt() {
        recording.returns("select count(*) as \"aggregate\" from \"users\"",
                List.<Map<String, Object>>of(RecordingConnection.row("aggregate", 3)));

        recording.connection.transaction(connection -> locking.table("users").lockForUpdate().paginate(2, 1));

        assertEquals("select count(*) as \"aggregate\" from \"users\"", recording.calls.get(1).sql());
        assertEquals("select * from \"users\" limit 2 offset 0 for UPDATE WAIT", lastStatement().sql());
    }

    @Test
    void aGroupedCountOfAPaginationLeavesTheLockOutOfTheSubquery() {
        recording.connection.transaction(connection ->
                locking.table("users").groupBy("team").lockForUpdate().getCountForPagination());

        assertEquals("select count(*) as \"aggregate\" from (select * from \"users\" group by \"team\")"
                + " as \"aggregate_table\"", lastStatement().sql());
    }

    @Test
    void aUnionWithALockOnAnyPartIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> plain.table("a").lockForUpdate().union(plain.table("b")).toSql());
        assertThrows(IllegalArgumentException.class,
                () -> plain.table("a").union(plain.table("b").lockForUpdate()).toSql());
    }

    @Test
    void aLockNeedsAStrengthAndAWait() {
        assertThrows(NullPointerException.class, () -> plain.table("users").lock(null));
        assertThrows(NullPointerException.class, () -> plain.table("users").lock(LockStrength.SHARE, null));
    }
}

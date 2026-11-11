package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionScope;
import org.aristonis.diwan.database.exception.QueryException;
import org.junit.jupiter.api.Test;

/** A failed statement aborts a PostgreSQL transaction; Diwan says so instead of passing 25P02 on. */
class PostgresAbortedTransactionTest {

    private static final String ABORTED = "aborted by a failed statement";
    private static final String DUPLICATE = "insert into aborted_rows values (1)";

    private static Connection database() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table aborted_rows (id int primary key)");
        db.insert(DUPLICATE);
        return db;
    }

    private static int count(Connection db) {
        return ((Number) db.scalar("select count(*) from aborted_rows").get()).intValue();
    }

    @Test
    void aCaughtFailureMakesTheNextQueryFailWithTheAbortedMessageAndTheFirstCause() {
        Connection db = database();

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            assertThrows(QueryException.class, () -> tx.insert(DUPLICATE));
            return tx.select("select * from aborted_rows");
        }));

        assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        assertTrue(refused.getCause() instanceof QueryException);
        assertEquals(0, db.transactionLevel());
        assertEquals(1, count(db));
        db.insert("insert into aborted_rows values (5)");
        assertEquals(2, count(db));
    }

    @Test
    void rollingBackToTheSavepointMakesTheNestedTransactionUsableAgain() {
        Connection db = database();

        db.transaction(tx -> {
            tx.insert("insert into aborted_rows values (2)");
            assertThrows(QueryException.class, () -> tx.transaction(inner -> inner.insert(DUPLICATE)));
            assertEquals(2, ((Number) tx.scalar("select count(*) from aborted_rows").get()).intValue());
            return tx.insert("insert into aborted_rows values (3)");
        });

        assertEquals(3, count(db));
    }

    @Test
    void aCommitOnAnAbortedTransactionIsRefusedAndTheSessionIsRolledBack() {
        Connection db = database();

        try (TransactionScope scope = db.beginTransaction()) {
            scope.insert("insert into aborted_rows values (2)");
            assertThrows(QueryException.class, () -> scope.insert(DUPLICATE));

            IllegalStateException refused = assertThrows(IllegalStateException.class, scope::commit);

            assertTrue(refused.getMessage().contains(ABORTED), refused.getMessage());
        }

        assertEquals(0, db.transactionLevel());
        assertEquals(1, count(db));
        db.insert("insert into aborted_rows values (4)");
        assertEquals(2, count(db));
    }
}

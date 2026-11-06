package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.event.QueryExecuted;
import org.aristonis.diwan.database.event.QueryFailed;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.event.TransactionEvent;
import org.junit.jupiter.api.Test;

/** Query events, the query log and commit callbacks against a real PostgreSQL; skipped when it is not installed. */
class PostgresEventsTest {

    private final List<QueryExecuted> executed = new ArrayList<>();
    private final List<QueryFailed> failed = new ArrayList<>();
    private final List<String> phases = new ArrayList<>();

    private Connection database() {
        Connection db = PostgresServer.newDatabase();
        db.statement("create table items (id serial primary key, name text unique)");
        db.events().addListener(new ConnectionListener() {
            @Override
            public void queryExecuted(QueryExecuted event) {
                executed.add(event);
            }

            @Override
            public void queryFailed(QueryFailed event) {
                failed.add(event);
            }

            @Override
            public void transactionBeginning(TransactionEvent event) {
                phases.add("beginning:" + event.level());
            }

            @Override
            public void transactionCommitted(TransactionEvent event) {
                phases.add("committed:" + event.level());
            }

            @Override
            public void transactionRolledBack(TransactionEvent event) {
                phases.add("rolledBack:" + event.level());
            }
        });
        return db;
    }

    @Test
    void reportsEachStatementWithItsKindAndBindings() {
        Connection db = database();

        db.insert("insert into items (name) values (?)", "a");
        db.select("select * from items where name = ?", "a");
        db.delete("delete from items where name = ?", "a");

        assertEquals(Arrays.asList(QueryKind.INSERT, QueryKind.SELECT, QueryKind.DELETE),
                Arrays.asList(executed.get(0).kind(), executed.get(1).kind(), executed.get(2).kind()));
        assertEquals(Arrays.<Object>asList("a"), executed.get(1).bindings());
    }

    @Test
    void reportsAFailedStatementWithTheExceptionTheCallerGets() {
        Connection db = database();
        db.insert("insert into items (name) values (?)", "dup");

        QueryException thrown = assertThrows(QueryException.class,
                () -> db.insert("insert into items (name) values (?)", "dup"));

        assertEquals(1, failed.size());
        assertSame(thrown, failed.get(0).exception());
    }

    @Test
    void reportsTransactionPhasesIncludingARolledBackSavepoint() {
        Connection db = database();

        db.transaction(tx -> {
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                inner.insert("insert into items (name) values (?)", "gone");
                throw new IllegalStateException("rule");
            }));
            tx.insert("insert into items (name) values (?)", "kept");
            return null;
        });

        assertEquals(Arrays.asList("beginning:1", "beginning:2", "rolledBack:2", "committed:1"), phases);
        assertEquals(1, db.select("select * from items").size());
    }

    @Test
    void runsAfterCommitOnlyOnceTheRowIsVisible() {
        Connection db = database();
        List<Integer> visible = new ArrayList<>();

        db.transaction(tx -> {
            tx.insert("insert into items (name) values (?)", "x");
            tx.afterCommit(() -> visible.add(db.select("select * from items").size()));
            return null;
        });

        assertEquals(Arrays.asList(1), visible);
    }

    @Test
    void runsAfterRollbackAndDropsAfterCommitWhenTheTransactionFails() {
        Connection db = database();
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.insert("insert into items (name) values (?)", "x");
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("rollback"), ran);
    }

    @Test
    void keepsAQueryLogWithCompactBlobsAndLongText() {
        Connection db = Connection.builder().name("pgsql")
                .dataSource(PostgresServer.dataSource(PostgresServer.newDatabaseName()))
                .showBindingsInErrors(true).build();
        db.statement("create table blobs (data bytea, note text)");
        db.events().enableQueryLog();

        db.insert("insert into blobs values (?, ?)", new byte[] {1, 2, 3}, "n".repeat(3000));

        LoggedQuery entry = db.events().getQueryLog().get(0);
        assertEquals("<byte[3]>", entry.bindings().get(0));
        assertTrue(((String) entry.bindings().get(1)).endsWith("...(3000 chars)"));
    }
}

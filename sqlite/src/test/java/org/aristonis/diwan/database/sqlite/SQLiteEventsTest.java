package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.ConnectionListener;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.QueryException;
import org.aristonis.diwan.database.QueryExecuted;
import org.aristonis.diwan.database.QueryFailed;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Query events, the query log and commit callbacks against a real SQLite file. */
class SQLiteEventsTest {

    @TempDir
    Path folder;

    private final List<QueryExecuted> executed = new ArrayList<>();
    private final List<QueryFailed> failed = new ArrayList<>();
    private final List<String> phases = new ArrayList<>();
    private Connection db;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("events.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db.statement("create table items (id integer primary key autoincrement, name text unique)");
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
    }

    @Test
    void reportsEachStatementWithItsKindAndBindings() {
        db.insert("insert into items (name) values (?)", "a");
        db.select("select * from items where name = ?", "a");
        db.update("update items set name = ? where name = ?", "b", "a");
        db.delete("delete from items where name = ?", "b");

        assertEquals(Arrays.asList(QueryKind.INSERT, QueryKind.SELECT, QueryKind.UPDATE, QueryKind.DELETE),
                Arrays.asList(executed.get(0).kind(), executed.get(1).kind(), executed.get(2).kind(),
                        executed.get(3).kind()));
        assertEquals(Arrays.<Object>asList("b", "a"), executed.get(2).bindings());
        assertEquals("sqlite", executed.get(0).connectionName());
    }

    @Test
    void reportsAFailedStatementWithTheExceptionTheCallerGets() {
        db.insert("insert into items (name) values (?)", "dup");

        QueryException thrown = assertThrows(QueryException.class,
                () -> db.insert("insert into items (name) values (?)", "dup"));

        assertEquals(1, failed.size());
        assertSame(thrown, failed.get(0).exception());
        assertEquals(1, executed.size());
    }

    @Test
    void reportsTransactionPhasesAndLevelsOnTheStatementsInside() {
        db.transaction(tx -> tx.insert("insert into items (name) values (?)", "x"));

        assertEquals(Arrays.asList("beginning:1", "committed:1"), phases);
        assertEquals(1, executed.get(0).transactionLevel());
    }

    @Test
    void reportsARolledBackSavepointInsideACommittedTransaction() {
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
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.insert("insert into items (name) values (?)", "x");
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("rollback"), ran);
        assertTrue(db.select("select * from items").isEmpty());
    }

    @Test
    void keepsAQueryLogWithCompactBlobs() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + folder.resolve("blobs.db"));
        Connection db = Connection.builder().name("sqlite").dataSource(source).showBindingsInErrors(true).build();
        db.events().enableQueryLog();

        db.statement("create table blobs (data blob)");
        db.insert("insert into blobs values (?)", (Object) new byte[] {1, 2, 3, 4});

        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(2, log.size());
        assertEquals("<byte[4]>", log.get(1).bindings().get(0));
        db.events().flushQueryLog();
        assertFalse(db.events().getQueryLog().iterator().hasNext());
    }
}

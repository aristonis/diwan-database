package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.ConnectionListener;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.QueryExecuted;
import org.aristonis.diwan.database.QueryKind;
import org.aristonis.diwan.database.TransactionEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Query events, the query log and commit callbacks over Android's SQLiteDatabase, under Robolectric. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidQueryLogTest {

    private final List<QueryExecuted> executed = new ArrayList<>();
    private final List<String> phases = new ArrayList<>();
    private SQLiteDatabase database;
    private Connection db;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table items (id integer primary key autoincrement, name text)");
        db.events().addListener(new ConnectionListener() {
            @Override
            public void queryExecuted(QueryExecuted event) {
                executed.add(event);
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

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void reportsEachStatementWithItsKindAndBindings() {
        db.insert("insert into items (name) values (?)", "a");
        db.select("select * from items where name = ?", "a");

        assertEquals(QueryKind.INSERT, executed.get(0).kind());
        assertEquals(QueryKind.SELECT, executed.get(1).kind());
        assertEquals(Arrays.<Object>asList("a"), executed.get(1).bindings());
    }

    @Test
    public void keepsABoundedLogWithCompactBlobs() {
        Connection db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database))
                .showBindingsInErrors(true).build();
        db.statement("create table blobs (data blob)");
        db.events().enableQueryLog();

        db.insert("insert into blobs values (?)", (Object) new byte[] {1, 2, 3});

        List<LoggedQuery> log = db.events().getQueryLog();
        assertEquals(1, log.size());
        assertEquals("<byte[3]>", log.get(0).bindings().get(0));
        db.events().flushQueryLog();
        assertTrue(db.events().getQueryLog().isEmpty());
    }

    @Test
    public void reportsTransactionPhasesAndRunsAfterCommitOnceTheRowIsVisible() {
        List<Integer> visible = new ArrayList<>();

        db.transaction(tx -> {
            tx.insert("insert into items (name) values (?)", "x");
            tx.afterCommit(() -> visible.add(db.select("select * from items").size()));
            return null;
        });

        assertEquals(Arrays.asList("beginning:1", "committed:1"), phases);
        assertEquals(Arrays.asList(1), visible);
    }

    @Test
    public void runsAfterRollbackAndDropsAfterCommitWhenTheTransactionFails() {
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.afterCommit(() -> ran.add("commit"));
            tx.afterRollback(() -> ran.add("rollback"));
            throw new IllegalStateException("rule");
        }));

        assertEquals(Arrays.asList("rollback"), ran);
        assertEquals(Arrays.asList("beginning:1", "rolledBack:1"), phases);
    }
}

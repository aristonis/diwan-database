package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A deferred foreign key violation fails the commit and rolls the transaction back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidDeferredCommitTest {

    private static final String DEFER = "PRAGMA defer_foreign_keys = ON";
    private static final String ORPHAN = "insert into children (parent_id) values (99)";

    private SQLiteDatabase database;
    private Connection db;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        database.setForeignKeyConstraintsEnabled(true);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        db.statement("create table parents (id integer primary key)");
        db.statement("create table children (id integer primary key autoincrement,"
                + " parent_id integer not null references parents (id))");
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    private long count(String table) {
        return ((Number) db.scalar("select count(*) from " + table).orElseThrow()).longValue();
    }

    private DriverException failedCommit() {
        ForeignKeyViolationException failure = assertThrows(ForeignKeyViolationException.class,
                () -> db.transaction(tx -> {
                    tx.statement(DEFER);
                    tx.insert(ORPHAN);
                    return null;
                }));
        return (DriverException) failure.getCause();
    }

    @Test
    public void theCheckSeesAnUncommittedOrphanBeforeTheCommit() {
        db.transaction(tx -> {
            tx.statement(DEFER);
            tx.insert(ORPHAN);
            assertEquals(1, tx.select("pragma foreign_key_check").size());
            tx.statement("delete from children");
            return null;
        });
    }

    @Test
    public void commitWithAnOrphanFailsNamesTheChildTableAndRollsBack() {
        DriverException failure = failedCommit();

        assertTrue(failure.kinds().contains(DriverFailureKind.FOREIGN_KEY_VIOLATION));
        assertEquals("children", failure.error().table().orElseThrow());
        assertFalse(database.inTransaction());
        assertEquals(0L, count("children"));
    }

    @Test
    public void aNewTransactionWorksAfterTheFailure() {
        failedCommit();

        db.transaction(tx -> {
            tx.insert("insert into parents (id) values (1)");
            return null;
        });

        assertEquals(1L, count("parents"));
    }

    @Test
    public void deferredTransactionWithoutViolationCommits() {
        db.transaction(tx -> {
            tx.statement(DEFER);
            tx.insert("insert into parents (id) values (1)");
            tx.insert("insert into children (parent_id) values (1)");
            return null;
        });

        assertEquals(1L, count("children"));
    }

    @Test
    public void commitWithoutDeferralWorks() {
        db.transaction(tx -> {
            tx.insert("insert into parents (id) values (1)");
            tx.insert("insert into children (parent_id) values (1)");
            return null;
        });

        assertEquals(1L, count("children"));
    }

    @Test
    public void aRawDeferPragmaIsCheckedToo() {
        DriverException failure = failedCommit();

        assertTrue(failure.kinds().contains(DriverFailureKind.FOREIGN_KEY_VIOLATION));
    }

    @Test
    public void dropAllTablesWithAForeignKeyCycleCommits() {
        db.statement("create table a (id integer primary key, b_id integer references b (id))");
        db.statement("create table b (id integer primary key, a_id integer references a (id))");

        db.transaction(tx -> {
            SQLiteSchema.of(tx).dropAllTables();
            return null;
        });

        assertTrue(SQLiteSchema.of(db).getTables().isEmpty());
    }

    @Test
    public void anOrphanFixedBeforeTheCommitCommits() {
        db.transaction(tx -> {
            tx.statement(DEFER);
            tx.insert(ORPHAN);
            tx.insert("insert into parents (id) values (99)");
            return null;
        });

        assertEquals(1L, count("children"));
    }

    @Test
    public void aRolledBackSavepointRemovesTheOrphan() {
        db.transaction(tx -> {
            tx.statement(DEFER);
            assertThrows(IllegalStateException.class, () -> tx.transaction(inner -> {
                inner.insert(ORPHAN);
                throw new IllegalStateException("undo");
            }));
            return null;
        });

        assertEquals(0L, count("children"));
    }

    @Test
    public void aFailureWithOpenSavepointsStillAllowsANewTransactionAndSavepoint() {
        assertThrows(ForeignKeyViolationException.class, () -> db.transaction(tx -> {
            tx.statement(DEFER);
            tx.transaction(inner -> {
                inner.insert(ORPHAN);
                return null;
            });
            return null;
        }));
        assertFalse(database.inTransaction());

        db.transaction(tx -> {
            tx.transaction(inner -> {
                inner.insert("insert into parents (id) values (1)");
                return null;
            });
            return null;
        });

        assertEquals(1L, count("parents"));
    }

    @Test
    public void withForeignKeysOffADeferredOrphanCommits() {
        database.setForeignKeyConstraintsEnabled(false);

        db.transaction(tx -> {
            tx.statement(DEFER);
            tx.insert(ORPHAN);
            return null;
        });

        assertEquals(1L, count("children"));
    }

    @Test
    public void anOrphanFromBeforeTheTransactionFailsADeferredCommit() {
        database.setForeignKeyConstraintsEnabled(false);
        db.insert(ORPHAN);
        database.setForeignKeyConstraintsEnabled(true);

        assertThrows(ForeignKeyViolationException.class, () -> db.transaction(tx -> {
            tx.statement(DEFER);
            tx.insert("insert into parents (id) values (1)");
            return null;
        }));

        assertEquals(0L, count("parents"));
    }
}

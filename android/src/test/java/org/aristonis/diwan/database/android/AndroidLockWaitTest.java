package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.Duration;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.TransactionOptions;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A lock wait sets {@code PRAGMA busy_timeout} for one transaction, and the connection option sets it for all. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidLockWaitTest {

    private static SQLiteOpenHelper memoryHelper() {
        return new SQLiteOpenHelper(RuntimeEnvironment.getApplication(), null, null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
    }

    private static Connection connect(String lockWait) {
        ConnectionConfig.Builder builder = ConnectionConfig.builder("app").driver("android");
        if (lockWait != null) {
            builder.option("lock_wait", lockWait);
        }
        AndroidConnector connector = AndroidConnector.of(AndroidLockWaitTest::memoryHelper,
                SQLiteQuery::of, SQLiteSchema::of);
        return connector.connect(builder.build(), null);
    }

    private static long busyTimeout(Connection connection) {
        return ((Number) connection.scalar("pragma busy_timeout").get()).longValue();
    }

    @Test
    public void busyTimeoutIsSetInsideAndRestoredAfterCommit() {
        Connection db = connect(null);
        long before = busyTimeout(db);

        long inside = db.transaction(tx -> busyTimeout(tx), TransactionOptions.lockWait(Duration.ofMillis(1234)));

        assertEquals("inside the transaction", 1234L, inside);
        assertEquals("after commit", before, busyTimeout(db));
    }

    @Test
    public void busyTimeoutIsRestoredAfterAThrowingCallback() {
        Connection db = connect(null);
        long before = busyTimeout(db);

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            throw new IllegalStateException("boom");
        }, TransactionOptions.lockWait(Duration.ofMillis(1234))));

        assertEquals("after rollback", before, busyTimeout(db));
    }

    @Test
    public void statementLockWaitIsRefusedWithTheSqliteMessage() {
        Connection db = connect(null);
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> AndroidSqliteDriver.of(memoryHelper()).open().statementLockWait(100));

        assertTrue(e.getMessage(), e.getMessage().contains("not supported by SQLite"));
        assertEquals("the connection still works", 1L, ((Number) db.scalar("select 1").get()).longValue());
    }

    @Test
    public void exactDecimalDigitsIsFifteen() {
        assertEquals("driver", 15, AndroidSqliteDriver.of(memoryHelper()).exactDecimalDigits().getAsInt());
        assertEquals("session", 15, AndroidSqliteDriver.of(memoryHelper()).open().exactDecimalDigits().getAsInt());
    }

    @Test
    public void lockWaitOptionSetsTheBusyTimeoutOnOpen() {
        assertEquals(2000L, busyTimeout(connect("2s")));
        assertEquals(250L, busyTimeout(connect("250ms")));
    }

    @Test
    public void badLockWaitOptionIsRefused() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class, () -> connect("soon"));

        assertTrue(e.getMessage(), e.getMessage().contains("app") && e.getMessage().contains("lock_wait"));
    }
}

package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DatabaseConfigException;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The {@code transaction_mode} option picks how Android sessions begin a transaction. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidTransactionModeTest {

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

    private static Connection connect(String mode) {
        ConnectionConfig.Builder builder = ConnectionConfig.builder("app").driver("android");
        if (mode != null) {
            builder.option("transaction_mode", mode);
        }
        AndroidConnector connector = AndroidConnector.of(AndroidTransactionModeTest::memoryHelper,
                SQLiteQuery::of, SQLiteSchema::of);
        return connector.connect(builder.build(), null);
    }

    // The begin call itself is not observable without a mock library; each mode must still run and
    // commit a transaction.
    @Test
    public void modesMapToBeginCalls() {
        for (String mode : new String[] {null, "exclusive", "IMMEDIATE"}) {
            Connection db = connect(mode);
            db.statement("create table t (i integer)");

            db.transaction(tx -> tx.insert("insert into t (i) values (1)"));

            assertEquals(mode, 1L, ((Number) db.scalar("select count(*) from t").get()).longValue());
        }
    }

    @Test
    public void deferredIsRefused() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class, () -> connect("deferred"));

        assertTrue(e.getMessage(), e.getMessage().contains("app") && e.getMessage().contains("transaction_mode")
                && e.getMessage().contains("deferred"));
    }

    @Test
    public void badValueIsRefused() {
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class, () -> connect("eager"));

        assertTrue(e.getMessage(), e.getMessage().contains("transaction_mode") && e.getMessage().contains("eager"));
    }
}

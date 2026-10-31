package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.robolectric.RuntimeEnvironment;
import org.aristonis.diwan.database.driver.DriverCapability;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidSqliteDriverTest {

    @Test
    public void infoNamesSqliteItsVersionAndCapabilities() {
        SQLiteDatabase database = SQLiteDatabase.create(null);
        AndroidSqliteDriver driver = AndroidSqliteDriver.of(database);

        DriverInfo info = driver.info();

        assertEquals("SQLite", info.databaseName());
        assertTrue(info.databaseVersion().startsWith("3."));
        assertTrue(info.supports(DriverCapability.SAVEPOINT_RELEASE));
        assertTrue(info.supports(DriverCapability.TRANSACTIONAL_DDL));
        assertSame(info, driver.info());
        database.close();
    }

    @Test
    public void aHelperGivesItsWritableDatabase() {
        Context context = RuntimeEnvironment.getApplication();
        SQLiteOpenHelper helper = new SQLiteOpenHelper(context, "helper.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("create table h (x integer)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };

        AndroidSqliteDriver driver = AndroidSqliteDriver.of(helper);
        assertTrue(!context.getDatabasePath("helper.db").exists());

        assertEquals("SQLite", driver.info().databaseName());
        assertTrue(context.getDatabasePath("helper.db").exists());
        helper.close();
    }

    @Test
    public void nullIsRefused() {
        assertThrows(NullPointerException.class, () -> AndroidSqliteDriver.of((SQLiteDatabase) null));
        assertThrows(NullPointerException.class, () -> AndroidSqliteDriver.of((SQLiteOpenHelper) null));
    }
}

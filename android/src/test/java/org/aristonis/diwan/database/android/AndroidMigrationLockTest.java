package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.migrations.FileMigrationLock;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationLock;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

/** The Android connector's migration lock: a lock file beside a file database, none for an in-memory one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidMigrationLockTest {

    private static final String DATABASE_NAME = "lock.db";

    private static SQLiteOpenHelper helper(String name) {
        return new SQLiteOpenHelper(RuntimeEnvironment.getApplication(), name, null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
    }

    private static ConnectionConfig config(String lockWait) {
        ConnectionConfig.Builder builder = ConnectionConfig.builder("app").driver("android");
        if (lockWait != null) {
            builder.option("lock_wait", lockWait);
        }
        return builder.build();
    }

    private static AndroidConnector connector(SQLiteOpenHelper helper) {
        return AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of);
    }

    private static Migration probe(Runnable duringUp) {
        return new Migration() {
            @Override
            public String name() {
                return "2026_01_probe";
            }

            @Override
            public void up(Schema schema) {
                duringUp.run();
            }

            @Override
            public void down(Schema schema) {
            }
        };
    }

    @Test
    public void fileDatabaseHasALockFileHeldDuringARun() {
        SQLiteOpenHelper helper = helper(DATABASE_NAME);
        AndroidConnector connector = connector(helper);
        Connection db = connector.connect(config(null), null);
        MigrationLock lock = connector.lock(config(null), null).get();
        File database = RuntimeEnvironment.getApplication().getDatabasePath(DATABASE_NAME);
        File lockFile = new File(database.getPath() + ".migrate.lock");
        AtomicBoolean isLockFilePresent = new AtomicBoolean();
        AtomicReference<RuntimeException> second = new AtomicReference<>();
        Migration migration = probe(() -> {
            isLockFilePresent.set(lockFile.exists());
            try {
                new FileMigrationLock(() -> database, 0, TimeUnit.MILLISECONDS).acquire();
            } catch (IllegalStateException e) {
                second.set(e);
            }
        });

        Migrator.builder(SQLiteSchema.of(db)).add(migration).lock(lock).build().run();

        assertTrue("the lock file exists during the run", isLockFilePresent.get());
        assertTrue("a second holder is refused", second.get() != null);
        assertTrue(second.get().getMessage(), second.get().getMessage().contains(".migrate.lock"));
        assertTrue("the lock file stays", lockFile.exists());
        helper.close();
    }

    @Test
    public void lockIsFreeAfterTheRun() {
        SQLiteOpenHelper helper = helper(DATABASE_NAME);
        AndroidConnector connector = connector(helper);
        Connection db = connector.connect(config("1s"), null);
        MigrationLock lock = connector.lock(config("1s"), null).get();
        Migrator.builder(SQLiteSchema.of(db)).add(probe(() -> { })).lock(lock).build().run();
        File database = RuntimeEnvironment.getApplication().getDatabasePath(DATABASE_NAME);

        FileMigrationLock again = new FileMigrationLock(() -> database, 0, TimeUnit.MILLISECONDS);
        again.acquire();
        again.release();

        helper.close();
    }

    @Test
    public void helperWithoutADatabaseNameHasNoLock() {
        AndroidConnector connector = connector(helper(null));

        assertFalse(connector.lock(config(null), null).isPresent());
        assertEquals("no lock for an in-memory helper, with a lock_wait too", false,
                connector.lock(config("2s"), null).isPresent());
    }

    @Test
    public void badLockWaitIsRefusedWhenTheLockIsBuilt() {
        AndroidConnector connector = connector(helper(DATABASE_NAME));

        assertThrows(org.aristonis.diwan.database.exception.DatabaseConfigException.class,
                () -> connector.lock(config("soon"), null));
    }
}

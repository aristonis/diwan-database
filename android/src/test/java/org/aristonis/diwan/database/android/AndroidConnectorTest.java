package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.Seeder;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The connector builds named connections over the application's helper. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidConnectorTest {

    private static SQLiteOpenHelper memoryHelper() {
        // A null name gives a private in-memory database.
        return new SQLiteOpenHelper(RuntimeEnvironment.getApplication(), null, null, 1) {
            @Override
            public void onCreate(SQLiteDatabase db) {
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            }
        };
    }

    private static DatabaseManager manager(AndroidConnector connector) {
        ConnectionConfig config = ConnectionConfig.builder("app").driver("android").build();
        DatabaseConfig all = DatabaseConfig.builder().defaultConnection("app").connection(config).build();
        return DatabaseManager.builder().config(all).connector(connector).build();
    }

    private static Migration users() {
        return new Migration() {
            @Override
            public String name() {
                return "2026_01_users";
            }

            @Override
            public void up(Schema schema) {
                schema.create("users", t -> {
                    t.id();
                    t.string("name");
                });
            }

            @Override
            public void down(Schema schema) {
                schema.drop("users");
            }
        };
    }

    private static Seeder seeder() {
        return new Seeder() {
            @Override
            protected void run(SeedContext context) {
                context.query().table("users").insert(Map.of("name", "Ann"));
            }
        };
    }

    @Test
    public void explicitFactoriesMigrateAndSeedThroughTheManager() {
        SQLiteOpenHelper helper = memoryHelper();
        try (DatabaseManager manager = manager(AndroidConnector.of(() -> helper, SQLiteQuery::of, SQLiteSchema::of))) {
            manager.migrator("app").add(users()).build().run();
            manager.seed(AndroidConnectorTest::seeder);

            assertEquals(1, manager.query().table("users").count());
        }
    }

    @Test
    public void theHelperIsAskedOnlyWhenTheConnectionIsFirstUsed() {
        AtomicInteger asked = new AtomicInteger();
        SQLiteOpenHelper helper = memoryHelper();
        Supplier<SQLiteOpenHelper> supplier = () -> {
            asked.incrementAndGet();
            return helper;
        };
        try (DatabaseManager manager = manager(AndroidConnector.of(supplier, SQLiteQuery::of, SQLiteSchema::of))) {
            assertEquals(0, asked.get());

            manager.query();
            manager.query();

            assertEquals(1, asked.get());
        }
    }

    @Test
    public void theDriverNameIsAndroid() {
        assertEquals("android", AndroidConnector.of(AndroidConnectorTest::memoryHelper, SQLiteQuery::of, SQLiteSchema::of)
                .driver());
    }

    @Test(expected = NullPointerException.class)
    public void aNullHelperSupplierIsRefused() {
        AndroidConnector.of(null, SQLiteQuery::of, SQLiteSchema::of);
    }
}

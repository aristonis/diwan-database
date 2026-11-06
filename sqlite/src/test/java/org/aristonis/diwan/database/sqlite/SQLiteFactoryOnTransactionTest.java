package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverInfo;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** A factory and a schema builder carried into a transaction with on(tx), without naming SQLite again. */
class SQLiteFactoryOnTransactionTest {

    @TempDir
    Path folder;

    private Connection connection;
    private QueryFactory db;
    private Schema schema;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("on.db"));
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        schema = SQLiteSchema.of(connection);
        schema.create("accounts", t -> {
            t.id();
            t.integer("balance");
        });
        db.table("accounts").insert(Map.of("balance", 10));
    }

    @Test
    void aFactoryBoundToTheTransactionCommitsWithIt() {
        connection.transaction(tx -> {
            db.on(tx).table("accounts").where("id", 1).update(Map.of("balance", 20));
            return null;
        });

        assertEquals(20, ((Number) db.table("accounts").value("balance").get()).intValue());
    }

    @Test
    void aFactoryBoundToTheTransactionRollsBackWithIt() {
        assertThrows(IllegalStateException.class, () -> connection.transaction(tx -> {
            db.on(tx).table("accounts").where("id", 1).update(Map.of("balance", 99));
            throw new IllegalStateException("undo");
        }));

        assertEquals(10, ((Number) db.table("accounts").value("balance").get()).intValue());
    }

    @Test
    void aSchemaBuilderBoundToTheTransactionCreatesInsideIt() {
        assertThrows(IllegalStateException.class, () -> connection.transaction(tx -> {
            schema.on(tx).create("audit", t -> t.id());
            throw new IllegalStateException("undo");
        }));

        assertTrue(!schema.hasTable("audit"));
    }

    @Test
    void aConnectionToAnotherKindOfDatabaseIsRefused() {
        Driver other = new Driver() {
            @Override
            public org.aristonis.diwan.database.driver.DriverSession open() {
                throw new UnsupportedOperationException("never opened");
            }

            @Override
            public DriverInfo info() {
                return new DriverInfo("Other", "1", Set.of());
            }
        };
        Connection foreign = Connection.builder().name("other").driver(other).build();

        assertThrows(IllegalArgumentException.class, () -> db.on(foreign));
        assertThrows(IllegalArgumentException.class, () -> schema.on(foreign));
        assertThrows(NullPointerException.class, () -> db.on(null));
    }
}

package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The close of a test's database: manager first, then the copy, and no failure escapes. */
class TestDatabaseTest {

    @TempDir
    Path folder;

    @Test
    void closeClosesTheManagerThenDropsTheCopy() {
        DatabaseManager manager = FakeCopies.manager(folder.resolve("one.db").toString());
        List<String> dropped = new ArrayList<>();
        TestDatabase database = new TestDatabase("one", manager, name -> {
            // The manager is closed already when the copy is dropped.
            assertThrows(IllegalStateException.class, manager::query);
            dropped.add(name);
        });

        database.close();

        assertEquals(List.of("one"), dropped);
    }

    @Test
    void aFailingDropIsLoggedWithTheCopyNameAndNotThrown() {
        DatabaseManager manager = FakeCopies.manager(folder.resolve("two.db").toString());
        TestDatabase database = new TestDatabase("diwan_t_x", manager, name -> {
            throw new IllegalStateException("denied");
        });

        List<LogRecord> records = capture(database::close);

        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.get(0).getLevel());
        assertTrue(records.get(0).getMessage().contains("diwan_t_x"), records.get(0).getMessage());
        assertEquals("denied", records.get(0).getThrown().getMessage());
    }

    @Test
    void aCopyWithoutAManagerIsStillDropped() {
        List<String> dropped = new ArrayList<>();
        TestDatabase database = new TestDatabase("lonely", null, dropped::add);

        database.close();

        assertEquals(List.of("lonely"), dropped);
    }

    @Test
    void parametersOfACopyWithoutAManagerAreRefused() {
        TestDatabase database = new TestDatabase("lonely", null, name -> { });

        IllegalStateException error = assertThrows(IllegalStateException.class, database::query);

        assertTrue(error.getMessage().contains("lonely"), error.getMessage());
    }

    @Test
    void theQueryFactoryAndConnectionComeFromTheManager() {
        DatabaseManager manager = FakeCopies.manager(folder.resolve("three.db").toString());
        TestDatabase database = new TestDatabase("three", manager, name -> { });
        try {
            assertEquals(manager.connection(), database.connection());
            assertEquals(manager.query().connection(), database.query().connection());
        } finally {
            database.close();
        }
    }

    private static List<LogRecord> capture(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(TestDatabase.class.getName());
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }
}

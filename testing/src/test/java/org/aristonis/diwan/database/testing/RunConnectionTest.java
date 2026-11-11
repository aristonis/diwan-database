package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The one root connection a template run holds. */
class RunConnectionTest {

    @TempDir
    Path folder;

    @Test
    void manyStatementsTakeOneConnectionFromTheRoot() {
        CountingSource root = new CountingSource(folder.resolve("one.db"));
        RunConnection run = RunConnection.open(root);
        try {
            for (int i = 0; i < 5; i++) {
                assertEquals(1, ((Number) run.connection().scalar("select 1").orElseThrow()).intValue());
            }
            assertEquals(1, root.opened.get());
        } finally {
            run.close();
        }
    }

    @Test
    void closeEndsTheHeldConnection() throws SQLException {
        CountingSource root = new CountingSource(folder.resolve("two.db"));
        RunConnection run = RunConnection.open(root);

        run.close();

        assertTrue(root.last.isClosed());
    }

    @Test
    void anOpenFailureIsReportedAsAnIllegalState() {
        SQLiteDataSource root = new SQLiteDataSource() {
            @Override
            public java.sql.Connection getConnection() throws SQLException {
                throw new SQLException("no server");
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> RunConnection.open(root));

        assertTrue(error.getMessage().contains("no server"), error.getMessage());
    }

    @Test
    void aNullRootIsRefused() {
        assertThrows(NullPointerException.class, () -> RunConnection.open(null));
    }

    /** Counts the connections taken from it and remembers the last one. */
    private static final class CountingSource extends SQLiteDataSource {
        private final AtomicInteger opened = new AtomicInteger();
        private volatile java.sql.Connection last;

        CountingSource(Path file) {
            setUrl("jdbc:sqlite:" + file);
        }

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            opened.incrementAndGet();
            last = super.getConnection();
            return last;
        }
    }
}

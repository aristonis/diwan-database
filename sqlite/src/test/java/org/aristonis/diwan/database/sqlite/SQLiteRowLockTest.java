package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.query.LockStrength;
import org.aristonis.diwan.database.query.LockWait;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Row locks on SQLite, which has none: the strengths write nothing, the waits that change rows are refused. */
class SQLiteRowLockTest {

    @TempDir
    Path folder;

    private QueryFactory db;

    @BeforeEach
    void createTable() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("locks.db"));
        Connection connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("items", t -> {
            t.id();
            t.string("name");
        });
        db.table("items").insert(Map.of("name", "a"));
        db.table("items").insert(Map.of("name", "b"));
    }

    @Test
    void thePlainLocksRunWithoutATransactionAndWriteNothing() {
        assertEquals("select * from \"items\" limit 1", db.table("items").limit(1).lockForUpdate().toSql());
        assertEquals(2, db.table("items").lockForUpdate().get().size());
        assertEquals(2, db.table("items").sharedLock().get().size());
        assertEquals(2, db.table("items").lock(LockStrength.UPDATE, LockWait.WAIT).get().size());
    }

    @Test
    void aLockedQueryStillCountsAndPaginates() {
        assertEquals(2L, db.table("items").lockForUpdate().count());
        assertEquals(2L, db.table("items").orderBy("id").lockForUpdate().paginate(1, 1).total());
    }

    @Test
    void keyLevelStrengthsWriteNothing() {
        for (LockStrength strength : List.of(LockStrength.NO_KEY_UPDATE, LockStrength.KEY_SHARE)) {
            List<Map<String, Object>> rows = db.connection().transaction(tx -> SQLiteQuery.of(tx).table("items")
                    .lock(strength).get());

            assertEquals(2, rows.size());
            assertFalse(db.table("items").lock(strength).toSql().toLowerCase().contains("for"));
        }
    }

    @Test
    void lockWaitsAreStillRefused() {
        for (LockWait wait : List.of(LockWait.NOWAIT, LockWait.SKIP_LOCKED)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> db.table("items").lock(LockStrength.UPDATE, wait).get());
        }
    }
}

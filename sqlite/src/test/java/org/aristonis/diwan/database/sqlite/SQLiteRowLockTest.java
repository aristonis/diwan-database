package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Row locks on SQLite, which has none: the plain ones are ignored, the PostgreSQL-only ones refused. */
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
    void whatOnlyPostgresqlHasIsRefused() {
        for (LockStrength strength : List.of(LockStrength.NO_KEY_UPDATE, LockStrength.KEY_SHARE)) {
            assertThrows(UnsupportedOperationException.class, () -> db.table("items").lock(strength).get());
        }
        for (LockWait wait : List.of(LockWait.NOWAIT, LockWait.SKIP_LOCKED)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> db.table("items").lock(LockStrength.UPDATE, wait).get());
        }
    }
}

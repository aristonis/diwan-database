package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.exception.MigrationFailedException;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.Test;

class PostgresNonTransactionalMigrationTest {

    private static Migration concurrentIndex(boolean isTransactional) {
        return new Migration() {
            @Override
            public String name() {
                return "2026_02_index";
            }

            @Override
            public void up(Schema schema) {
                schema.statement("create index concurrently t_n_index on t (n)");
            }

            @Override
            public boolean withinTransaction() {
                return isTransactional;
            }
        };
    }

    private static Migration table() {
        return new Migration() {
            @Override
            public String name() {
                return "2026_01_table";
            }

            @Override
            public void up(Schema schema) {
                schema.create("t", t -> t.integer("n"));
            }
        };
    }

    @Test
    void aStatementThatCannotRunInATransactionGetsAHint() {
        Connection db = PostgresServer.newDatabase();
        List<Migration> list = Arrays.asList(table(), concurrentIndex(true));

        MigrationFailedException error = assertThrows(MigrationFailedException.class,
                () -> Migrator.builder(PostgresSchema.of(db)).migrations(list).build().run());

        Throwable cause = error.getCause();
        assertTrue(cause instanceof QueryException);
        assertTrue(cause.getMessage().contains("withinTransaction()"), cause.getMessage());
    }

    @Test
    void theSameStatementRunsOutsideATransaction() {
        Connection db = PostgresServer.newDatabase();
        List<Migration> list = Arrays.asList(table(), concurrentIndex(false));

        assertEquals(2, Migrator.builder(PostgresSchema.of(db)).migrations(list).build().run().size());
        assertEquals(1L, ((Number) db.scalar("select count(*) from pg_indexes where indexname = 't_n_index'").get())
                .longValue());
    }
}

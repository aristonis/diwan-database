package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Test;

class PostgresServerTest {

    @Test
    void aFreshDatabaseAnswers() {
        Connection db = PostgresServer.newDatabase();

        assertEquals(1, ((Number) db.scalar("select 1").get()).intValue());
        assertEquals("pgsql", PostgresSchema.of(db).driverName());
    }
}

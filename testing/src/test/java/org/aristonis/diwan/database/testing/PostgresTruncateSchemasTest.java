package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Truncate mode on PostgreSQL with two schemas on the search path: a table of the second schema is
 * emptied, and PostGIS's {@code spatial_ref_sys} is not.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class PostgresTruncateSchemasTest {

    private static Connection connection;

    private static synchronized Connection connection() {
        if (connection == null) {
            connection = Fixtures.postgresWithExtraSchema("pgsql-schemas");
        }
        return connection;
    }

    @RegisterExtension
    final RefreshDatabase db = RefreshDatabase.truncate(() -> PostgresQuery.of(connection()),
            PostgresSchema::of, Fixtures.withExtraSchema());

    private static long count(Connection connection, String table) {
        return ((Number) connection.scalar("select count(*) from " + table).orElseThrow()).longValue();
    }

    @Test
    void a1WritesToEverySchema(Connection connection) {
        connection.insert("insert into users (name) values ('ann')");
        connection.insert("insert into extra.audit (note) values ('x')");
        connection.insert("insert into spatial_ref_sys (id) values (4326)");

        assertEquals(1L, count(connection, "extra.audit"));
    }

    @Test
    void a2TheSecondSchemaIsEmptyAndSpatialRefSysIsKept(Connection connection) {
        assertEquals(0L, count(connection, "users"));
        assertEquals(0L, count(connection, "extra.audit"));
        assertEquals(1L, count(connection, "spatial_ref_sys"));
        assertTrue(count(connection, "migrations") > 0);
    }
}

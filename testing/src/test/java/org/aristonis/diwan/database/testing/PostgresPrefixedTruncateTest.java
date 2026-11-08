package org.aristonis.diwan.database.testing;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;

/** Truncate mode against PostgreSQL with a table prefix; skipped when the server binaries are missing. */
class PostgresPrefixedTruncateTest extends TruncateContract {

    private static Connection connection;

    private static synchronized Connection connection() {
        if (connection == null) {
            connection = Fixtures.postgres("pgsql-prefixed", "app_");
        }
        return connection;
    }

    @Override
    RefreshDatabase create() {
        return RefreshDatabase.truncate(() -> PostgresQuery.of(connection()), PostgresSchema::of, Fixtures.base());
    }
}

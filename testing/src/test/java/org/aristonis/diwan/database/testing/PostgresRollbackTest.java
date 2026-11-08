package org.aristonis.diwan.database.testing;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.pgsql.PostgresSchema;

/** Rollback mode against a throwaway PostgreSQL; skipped when the server binaries are missing. */
class PostgresRollbackTest extends RollbackContract {

    private static Connection connection;

    private static synchronized Connection connection() {
        if (connection == null) {
            connection = Fixtures.postgres("pgsql-rollback");
        }
        return connection;
    }

    @Override
    RefreshDatabase create() {
        return RefreshDatabase.rollback(() -> PostgresQuery.of(connection()), PostgresSchema::of, Fixtures.base());
    }
}

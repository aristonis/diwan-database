package org.aristonis.diwan.database.testing;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/** Rollback mode against a SQLite file. */
class SQLiteRollbackTest extends RollbackContract {

    private static final Connection CONNECTION = Fixtures.sqlite("sqlite-rollback");

    @Override
    RefreshDatabase create() {
        return RefreshDatabase.rollback(() -> SQLiteQuery.of(CONNECTION), SQLiteSchema::of, Fixtures.base());
    }
}

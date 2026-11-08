package org.aristonis.diwan.database.testing;

import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;

/** Truncate mode against a SQLite file. */
class SQLiteTruncateTest extends TruncateContract {

    private static final Connection CONNECTION = Fixtures.sqlite("sqlite-truncate");

    @Override
    RefreshDatabase create() {
        return RefreshDatabase.truncate(() -> SQLiteQuery.of(CONNECTION), SQLiteSchema::of, Fixtures.base());
    }
}

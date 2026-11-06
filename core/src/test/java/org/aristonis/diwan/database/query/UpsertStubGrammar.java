package org.aristonis.diwan.database.query;

import java.util.List;
import java.util.Map;

/** The base grammar plus the upsert forms PostgreSQL and SQLite share, to test the core in isolation. */
class UpsertStubGrammar extends QueryGrammar {

    UpsertStubGrammar() {
        super("");
    }

    @Override
    protected String compileInsertOrIgnore(Builder query, List<? extends Map<String, ?>> rows) {
        return compileInsert(query, rows).get() + " on conflict do nothing";
    }

    @Override
    protected String compileUpsert(Builder query, List<? extends Map<String, ?>> rows, List<String> uniqueBy,
            List<String> excluded, Map<String, ?> values) {
        return compileInsert(query, rows).get() + " " + compileOnConflict(uniqueBy, excluded, values);
    }
}

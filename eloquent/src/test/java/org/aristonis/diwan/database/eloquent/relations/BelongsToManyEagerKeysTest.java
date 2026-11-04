package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Eager key chunking for belongsToMany on SQLite: string keys are bound in chunks of the bound
 * size less the constraint's bindings, integer keys are written inline in one query.
 */
class BelongsToManyEagerKeysTest {

    /** The bound keys per eager query on SQLite, before a constraint's bindings are taken off. */
    private static final int SQLITE_BOUND_KEYS = 900;

    private static final int STRING_KEY_COUNT = 2000;

    /** The countries {@link BtmDb} already seeds, which count toward {@link #STRING_KEY_COUNT}. */
    private static final int SEEDED_COUNTRY_COUNT = 2;

    private static final int INTEGER_KEY_COUNT = 2000;

    private static final int CONSTRAINT_BINDINGS = 400;

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void stringKeysAreChunkedAtTheBoundSizeOnSqlite() {
        seedCountries(STRING_KEY_COUNT - SEEDED_COUNTRY_COUNT);
        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db).all();
        db.clearLog();

        countries.load("languages");

        assertEquals(3, db.queries().size());
        assertEquals(Arrays.asList(SQLITE_BOUND_KEYS, SQLITE_BOUND_KEYS, 200), bindingCounts());
    }

    @Test
    void constraintBindingsShrinkTheStringKeyChunk() {
        seedCountries(STRING_KEY_COUNT - SEEDED_COUNTRY_COUNT);
        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db).all();
        List<String> names = namesOf(CONSTRAINT_BINDINGS);
        db.clearLog();

        countries.load("languages", q -> q.whereIn("languages.name", names));

        int chunk = SQLITE_BOUND_KEYS - CONSTRAINT_BINDINGS;
        assertEquals(4, db.queries().size());
        for (int i = 0; i < db.queries().size(); i++) {
            assertEquals(chunk + CONSTRAINT_BINDINGS, db.queries().get(i).bindings().size());
        }
    }

    @Test
    void integerKeysAreInlinedInOneQuery() {
        ModelCollection<BtmModels.User> users = usersWithIds(INTEGER_KEY_COUNT);
        db.clearLog();

        users.load("roles");

        assertEquals(1, db.queries().size());
        assertTrue(db.queries().get(0).bindings().isEmpty());
        assertTrue(db.sql().get(0).startsWith("select \"roles\".*, "));
        assertTrue(db.sql().get(0).contains(" where \"role_user\".\"user_id\" in (1, 2, 3, 4, 5, "));
        assertTrue(db.sql().get(0).endsWith(", " + INTEGER_KEY_COUNT + ")"));
    }

    @Test
    void constraintBindingsDoNotShrinkInlinedIntegerChunk() {
        ModelCollection<BtmModels.User> users = usersWithIds(INTEGER_KEY_COUNT);
        db.clearLog();

        users.load("roles", q -> q.whereIn("roles.name", namesOf(CONSTRAINT_BINDINGS)));

        assertEquals(1, db.queries().size());
        assertEquals(CONSTRAINT_BINDINGS, db.queries().get(0).bindings().size());
    }

    private List<Integer> bindingCounts() {
        List<Integer> counts = new ArrayList<>();
        for (BtmDb.Query query : db.queries()) {
            counts.add(query.bindings().size());
        }
        return counts;
    }

    private void seedCountries(int count) {
        for (int i = 0; i < count; i++) {
            db.seed("countries", Map.of("code", String.format("C%04d", i), "name", "Country " + i));
        }
    }

    private ModelCollection<BtmModels.User> usersWithIds(int count) {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(Collections.singletonMap("id", i));
        }
        return BtmModels.User.QUERY.on(db.db).hydrate(rows);
    }

    private static List<String> namesOf(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("Language " + i);
        }
        return names;
    }
}

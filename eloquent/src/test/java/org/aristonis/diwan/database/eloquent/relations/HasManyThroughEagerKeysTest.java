package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Eager key handling of a has-many-through relation on SQLite: string keys are bound in chunks of
 * the bound size, integer keys are written inline, and keys are compared in normalised form.
 */
class HasManyThroughEagerKeysTest {

    /** The bound keys per eager query on SQLite, with no constraint bindings. */
    private static final int SQLITE_BOUND_KEYS = 900;

    /** The inlined keys per eager query on SQLite. */
    private static final int SQLITE_INLINE_KEYS = 20000;

    private static final int FAR_PARENT_COUNT = 1200;

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void longKeyListIsChunked() {
        ModelCollection<TextCountry> countries = textCountries(FAR_PARENT_COUNT);
        db.log.clear();

        countries.load("posts");

        assertEquals(Arrays.asList(SQLITE_BOUND_KEYS, FAR_PARENT_COUNT - SQLITE_BOUND_KEYS), db.log.bindingCounts());
    }

    @Test
    void exactlyTheBoundSizeRunsOneQuery() {
        ModelCollection<TextCountry> countries = textCountries(SQLITE_BOUND_KEYS);
        db.log.clear();

        countries.load("posts");

        assertEquals(Collections.singletonList(SQLITE_BOUND_KEYS), db.log.bindingCounts());
    }

    @Test
    void oneKeyOverTheBoundSizeRunsASecondQuery() {
        ModelCollection<TextCountry> countries = textCountries(SQLITE_BOUND_KEYS + 1);
        db.log.clear();

        countries.load("posts");

        assertEquals(Arrays.asList(SQLITE_BOUND_KEYS, 1), db.log.bindingCounts());
    }

    @Test
    void chunkedLoadMatchesParentsFromEveryChunk() {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 0; i < SQLITE_BOUND_KEYS; i++) {
            rows.add(row("id", "x" + i));
        }
        rows.add(row("id", "1"));
        ModelCollection<TextCountry> countries = TextCountry.QUERY.on(db.db).hydrate(rows);

        countries.load("posts");

        TextCountry last = countries.get(SQLITE_BOUND_KEYS);
        assertEquals(3, ((ModelCollection<?>) last.getRelation("posts")).size());
        assertTrue(((ModelCollection<?>) countries.get(0).getRelation("posts")).isEmpty());
    }

    @Test
    void integerKeysAreInlined() {
        ModelCollection<ThroughModels.Country> countries = integerCountries(FAR_PARENT_COUNT);
        db.log.clear();

        countries.load("posts");

        assertEquals(1, db.log.sql().size());
        assertEquals(Collections.singletonList(0), db.log.bindingCounts());
        String sql = db.log.sql().get(0);
        assertTrue(sql.contains(" in (1, 2, 3, 4, 5, "), sql);
        assertTrue(sql.endsWith(", " + FAR_PARENT_COUNT + ")"), sql);
    }

    @Test
    void inlinedKeysChunkAtTheInlineSize() {
        ModelCollection<ThroughModels.Country> countries = integerCountries(SQLITE_INLINE_KEYS + 1);
        db.log.clear();

        countries.load("posts");

        assertEquals(2, db.log.sql().size());
        assertTrue(db.log.sql().get(0).endsWith(", " + SQLITE_INLINE_KEYS + ")"));
        assertTrue(db.log.sql().get(1).endsWith(" in (" + (SQLITE_INLINE_KEYS + 1) + ")"));
    }

    @Test
    void keysAreNormalisedOnBothSides() {
        List<Map<String, ?>> rows = Arrays.asList(row("id", "1", "name", "text"), row("id", 1, "name", "number"),
                row("id", 2, "name", "other"));
        ModelCollection<TextCountry> countries = TextCountry.QUERY.on(db.db).hydrate(rows);
        db.log.clear();

        countries.load("posts");

        assertEquals(Collections.singletonList(2), db.log.bindingCounts());
        assertEquals(3, ((ModelCollection<?>) countries.get(0).getRelation("posts")).size());
        assertEquals(3, ((ModelCollection<?>) countries.get(1).getRelation("posts")).size());
        assertEquals(1, ((ModelCollection<?>) countries.get(2).getRelation("posts")).size());
    }

    @Test
    void parentWithNullKeyIsSkipped() {
        List<Map<String, ?>> rows = Arrays.asList(row("id", null, "name", "unsaved"), row("id", 1, "name", "kept"));
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db).hydrate(rows);
        db.log.clear();

        countries.load("posts");

        assertEquals(1, db.log.sql().size());
        assertFalse(db.log.sql().get(0).contains("null"));
        assertTrue(((ModelCollection<?>) countries.get(0).getRelation("posts")).isEmpty());
        assertEquals(3, ((ModelCollection<?>) countries.get(1).getRelation("posts")).size());
    }

    @Test
    void onlyNullKeysRunNoQuery() {
        ModelCollection<ThroughModels.Country> countries = ThroughModels.Country.QUERY.on(db.db)
                .hydrate(Collections.singletonList(row("id", null)));
        db.log.clear();

        countries.load("posts");

        assertTrue(db.log.sql().isEmpty());
        assertTrue(((ModelCollection<?>) countries.get(0).getRelation("posts")).isEmpty());
    }

    private ModelCollection<TextCountry> textCountries(int count) {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row("id", "key-" + i));
        }
        return TextCountry.QUERY.on(db.db).hydrate(rows);
    }

    private ModelCollection<ThroughModels.Country> integerCountries(int count) {
        List<Map<String, ?>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row("id", i));
        }
        return ThroughModels.Country.QUERY.on(db.db).hydrate(rows);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /**
     * A country with a text key, so its eager keys are bound instead of inlined.
     */
    static final class TextCountry extends Model {
        static final ModelQuery<TextCountry> QUERY = ModelQuery.of(TextCountry.class, TextCountry::new);

        @Override
        protected String table() { return "countries"; }

        @Override
        protected String keyType() { return "string"; }

        @Override
        protected boolean timestamps() { return false; }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TextCountry.class).add("posts", TextCountry::posts);
        }

        /**
         * Returns the posts reached through users.
         *
         * @return the through relation, never {@code null}
         */
        public HasManyThrough<ThroughModels.Post> posts() {
            return hasManyThrough(ThroughModels.Post.class, ThroughModels.User.class, "country_id", "user_id",
                    "id", "id");
        }
    }
}

package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code load*} aggregate methods on a belongsToMany relation, on a collection and on a
 * model: one key-only query, values set as clean attributes, missing rows skipped, and a parent
 * key that is not the primary key.
 */
class BelongsToManyLoadAggregateTest {

    private static final String ROLE_COUNT = "(select count(*) from \"roles\" inner join \"role_user\""
            + " on \"roles\".\"id\" = \"role_user\".\"role_id\""
            + " where \"users\".\"id\" = \"role_user\".\"user_id\") as \"roles_count\"";
    private static final String KEYS_1_TO_3 = " from \"users\" where \"users\".\"id\" in (1, 2, 3)";
    private static final int CHUNK_PARENTS = 2000;
    private static final int SQLITE_CHUNK_KEYS = 900;

    /** A country keyed by a name, whose parent key for the pivot is the country code, not the key. */
    static final class Nation extends Model {
        static final ModelQuery<Nation> QUERY = ModelQuery.of(Nation.class, Nation::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected String primaryKey() {
            return "name";
        }

        @Override
        protected String keyType() {
            return "string";
        }

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Nation.class).add("languages", Nation::languages);
        }

        BelongsToMany<BtmModels.Language> languages() {
            return belongsToMany(BtmModels.Language.class, "country_language", "country_code", "language_iso",
                    "code", "iso");
        }
    }

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private ModelCollection<BtmModels.User> firstThreeUsers() {
        ModelCollection<BtmModels.User> users = BtmModels.User.QUERY.on(db.db).query()
                .where("id", "<=", 3).orderBy("id").get();
        db.clearLog();
        return users;
    }

    private void deleteUser(int id) throws SQLException {
        db.real.statement("delete from \"users\" where \"id\" = ?", id);
    }

    private static long number(Model model, String key) {
        Object value = model.getAttribute(key);
        assertNotNull(value, key);
        return ((Number) value).longValue();
    }

    @Test
    void oneQuery() {
        ModelCollection<BtmModels.User> users = firstThreeUsers();

        ModelCollection<BtmModels.User> returned = users.loadCount("roles");

        assertSame(users, returned);
        assertEquals(Collections.singletonList("select \"id\", " + ROLE_COUNT + KEYS_1_TO_3), db.sql());
        assertEquals(2L, number(users.get(0), "roles_count"));
        assertEquals(1L, number(users.get(1), "roles_count"));
        assertEquals(0L, number(users.get(2), "roles_count"));
    }

    @Test
    void modelLoadCount() {
        BtmModels.User ann = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        ann.loadCount("roles");

        assertEquals(1, db.sql().size());
        assertEquals(2L, number(ann, "roles_count"));
    }

    @Test
    void sumNotDirty() {
        BtmModels.User ann = BtmModels.User.QUERY.on(db.db).findOrFail(1);
        ann.setAttribute("name", "Anna");

        ann.loadSum("roles", "role_user.weight");

        assertEquals(6L, number(ann, "roles_sum_role_userweight"));
        assertFalse(ann.isDirty("roles_sum_role_userweight"));
        assertTrue(ann.isDirty("name"));
        assertEquals("Anna", ann.getAttribute("name"));
    }

    @Test
    void existsOverlay() {
        ModelCollection<BtmModels.User> users = firstThreeUsers();

        users.loadExists("roles");

        assertEquals(Boolean.TRUE, users.get(0).getAttribute("roles_exists"));
        assertEquals(Boolean.TRUE, users.get(1).getAttribute("roles_exists"));
        assertEquals(Boolean.FALSE, users.get(2).getAttribute("roles_exists"));
        assertFalse(users.get(0).isDirty());
    }

    @Test
    void missingRowsSkipped() throws SQLException {
        List<BtmModels.User> models = new ArrayList<>(firstThreeUsers());
        BtmModels.User unsaved = BtmModels.User.QUERY.on(db.db).newModel();
        models.add(unsaved);
        ModelCollection<BtmModels.User> users = Access.eloquent()
                .newCollection(BtmModels.User.QUERY.on(db.db).query(), models);
        deleteUser(2);

        users.loadCount("roles");

        assertEquals(1, db.sql().size());
        assertEquals(2L, number(users.get(0), "roles_count"));
        assertNull(users.get(1).getAttribute("roles_count"));
        assertEquals(0L, number(users.get(2), "roles_count"));
        assertNull(unsaved.getAttribute("roles_count"));
    }

    @Test
    void emptyCollectionNoQuery() {
        ModelCollection<BtmModels.User> none = BtmModels.User.QUERY.on(db.db).query().where("id", 99).get();
        db.clearLog();

        assertSame(none, none.loadCount("roles"));
        assertSame(none, none.loadExists("roles"));
        assertTrue(db.sql().isEmpty());
    }

    @Test
    void customParentKey() {
        db.seed("country_language", Map.of("country_code", "US", "language_iso", "fr"));
        ModelCollection<Nation> nations = Nation.QUERY.on(db.db).query().orderBy("name").get();
        db.clearLog();

        nations.loadCount("languages");

        assertEquals(1, db.sql().size());
        assertTrue(db.sql().get(0).contains("\"countries\".\"code\" = \"country_language\".\"country_code\""),
                db.sql().get(0));
        assertTrue(db.sql().get(0).contains("where \"countries\".\"name\" in ("), db.sql().get(0));
        assertEquals(1L, number(nations.get(0), "languages_count"));
        assertEquals(2L, number(nations.get(1), "languages_count"));
    }

    @Test
    void chunking() {
        for (int i = 0; i < CHUNK_PARENTS; i++) {
            db.seed("countries", Map.of("code", String.format("X%04d", i), "name", "Extra " + i));
        }
        ModelCollection<BtmModels.Country> countries = BtmModels.Country.QUERY.on(db.db).query()
                .where("code", "like", "X%").get();
        assertEquals(CHUNK_PARENTS, countries.size());
        db.clearLog();

        countries.loadCount("languages");

        assertEquals(Arrays.asList(SQLITE_CHUNK_KEYS, SQLITE_CHUNK_KEYS, CHUNK_PARENTS - 2 * SQLITE_CHUNK_KEYS),
                keyCounts());
        assertEquals(3, db.sql().size());
        assertEquals(0L, number(countries.get(0), "languages_count"));
        assertEquals(0L, number(countries.get(CHUNK_PARENTS - 1), "languages_count"));
    }

    private List<Integer> keyCounts() {
        List<Integer> counts = new ArrayList<>();
        for (BtmDb.Query query : db.queries()) {
            counts.add(query.bindings().size());
        }
        return counts;
    }
}

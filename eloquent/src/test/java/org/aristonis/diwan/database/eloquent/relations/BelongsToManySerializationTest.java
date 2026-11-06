package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a related model read through a belongsToMany relation serializes with its pivot: under the
 * accessor name, hidden, with the pivot's own hidden, visible and cast settings, with its
 * timestamps, and ending a chaperone cycle.
 */
class BelongsToManySerializationTest {

    /** The moment the seeded pivot rows carry, as the model timestamp rule writes it. */
    private static final String SEEDED_MOMENT = "2024-01-01T10:00:00.000000Z";

    /** The seeded pivot creation text, as the database stores it. */
    private static final String SEEDED_TEXT = "2024-01-01 10:00:00";

    /**
     * A user-shaped parent over the users table, with the relations the serialization tests read.
     */
    static final class Holder extends Model {
        /** The registry entry that lets relations name this class. */
        static final ModelQuery<Holder> QUERY = ModelQuery.of(Holder.class, Holder::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Holder.class)
                    .add("stampedRoles", Holder::stampedRoles)
                    .add("chaperonedRoles", Holder::chaperonedRoles)
                    .add("updatedAtOnlyRoles", Holder::updatedAtOnlyRoles);
        }

        /**
         * Belongs to many roles through the stamped pivot class.
         *
         * @return the relation, never {@code null}
         */
        BelongsToMany<BtmModels.Role> stampedRoles() {
            return belongsToMany(BtmModels.Role.class, StampedMembership.class, "user_id", "role_id")
                    .withPivot("status", "weight")
                    .withTimestamps();
        }

        /**
         * Belongs to many roles through the membership pivot with both sides chaperoned.
         *
         * @return the relation, never {@code null}
         */
        BelongsToMany<BtmModels.Role> chaperonedRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .using(BtmModels.Membership.class)
                    .chaperone();
        }

        /**
         * Belongs to many roles with only the updated timestamp read from the pivot.
         *
         * @return the relation, never {@code null}
         */
        BelongsToMany<BtmModels.Role> updatedAtOnlyRoles() {
            return belongsToMany(BtmModels.Role.class, "role_user", "user_id", "role_id")
                    .withPivot("updated_at");
        }
    }

    /**
     * A pivot that hides its weight and reads its creation timestamp through a date cast.
     */
    public static final class StampedMembership extends Pivot {
        /** The registry entry that lets relations name this pivot class. */
        static final ModelQuery<StampedMembership> QUERY =
                ModelQuery.of(StampedMembership.class, StampedMembership::new);

        /** Creates an empty stamped membership, as the registry builds it. */
        public StampedMembership() {
        }

        @Override
        protected String table() {
            return "role_user";
        }

        @Override
        protected List<String> hidden() {
            return Collections.singletonList("weight");
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Collections.<String, Cast<?>>singletonMap("created_at", Casts.date());
        }
    }

    private BtmDb db;

    /**
     * Opens a fresh in-memory database with the belongsToMany fixture for one test.
     *
     * @throws SQLException if the database cannot be opened
     */
    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    /**
     * Closes the database opened for the test.
     *
     * @throws SQLException if the database cannot be closed
     */
    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    /**
     * Loads a seeded user through the logged factory.
     *
     * @param id the user id
     * @return the user, never {@code null}
     */
    private BtmModels.User user(int id) {
        return BtmModels.User.QUERY.on(db.db).findOrFail(id);
    }

    /**
     * Loads a seeded holder through the logged factory.
     *
     * @param id the user id
     * @return the holder, never {@code null}
     */
    private Holder holder(int id) {
        return Holder.QUERY.on(db.db).findOrFail(id);
    }

    /**
     * Returns the role with the given id from a read, so the tests do not depend on row order.
     *
     * @param roles the roles read, never {@code null}
     * @param id the role id to find
     * @return the role, never {@code null}
     */
    private static BtmModels.Role roleWithId(Iterable<BtmModels.Role> roles, int id) {
        for (BtmModels.Role role : roles) {
            if (intOf(role.getAttribute("id")) == id) {
                return role;
            }
        }
        throw new AssertionError("no role with id " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        if (!(value instanceof Map)) {
            throw new AssertionError("expected a map but was " + value);
        }
        return (Map<String, Object>) value;
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private static void assertKeys(Map<String, Object> map, String... keys) {
        assertEquals(new TreeSet<>(Arrays.asList(keys)), new TreeSet<>(map.keySet()));
    }

    private static int count(String text, String needle) {
        int found = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            found++;
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    /** A role read through the relation serializes with its pivot under the accessor name. */
    @Test
    void pivotInMap() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);

        Map<String, Object> role = admin.toMap();

        assertKeys(role, "id", "name", "pivot");
        Map<String, Object> pivot = mapOf(role.get("pivot"));
        assertKeys(pivot, "user_id", "role_id", "status", "weight", "created_at", "updated_at");
        assertEquals("active", pivot.get("status"));
    }

    /** An alias set with as() replaces the default accessor name as the map key. */
    @Test
    void renamedKey() {
        BtmModels.Role admin = roleWithId(user(1).rolesAs().get(), 1);

        Map<String, Object> role = admin.toMap();

        assertKeys(role, "id", "name", "membership");
        assertEquals(1, intOf(mapOf(role.get("membership")).get("user_id")));
    }

    /** A related model whose hidden list names the accessor writes no pivot key. */
    @Test
    void hiddenAccessor() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);
        admin.makeHidden("pivot");

        assertTrue(admin.getHidden().contains("pivot"));
        assertKeys(admin.toMap(), "id", "name");
    }

    /** The pivot's own hidden list drops its column and its date cast writes the creation value. */
    @Test
    void pivotHiddenAndCasts() {
        BtmModels.Role admin = roleWithId(holder(1).stampedRoles().get(), 1);

        Map<String, Object> pivot = mapOf(admin.toMap().get("pivot"));

        assertFalse(pivot.containsKey("weight"));
        assertTrue(pivot.containsKey("status"));
        assertEquals("2024-01-01", pivot.get("created_at"));
    }

    /** The pivot creation and update timestamps follow the model timestamp rule. */
    @Test
    void pivotTimestamps() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);

        Map<String, Object> pivot = mapOf(admin.toMap().get("pivot"));

        assertEquals(SEEDED_MOMENT, pivot.get("created_at"));
        assertEquals(SEEDED_MOMENT, pivot.get("updated_at"));
    }

    /** A pivot read with only the update timestamp has timestamps off and keeps the raw text. */
    @Test
    void pivotWithoutCreatedAtStaysRaw() {
        BtmModels.Role admin = roleWithId(holder(1).updatedAtOnlyRoles().get(), 1);

        Map<String, Object> pivot = mapOf(admin.toMap().get("pivot"));

        assertFalse(pivot.containsKey("created_at"));
        assertEquals(SEEDED_TEXT, pivot.get("updated_at"));
    }

    /** The pivot's own visible list limits the pivot keys it writes. */
    @Test
    void pivotVisibleListApplies() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);
        Pivot.of(admin).setVisible(Arrays.asList("status"));

        assertKeys(mapOf(admin.toMap().get("pivot")), "status");
    }

    /** The pivot's columns appear without their raw pivot_ prefix in either map. */
    @Test
    void pivotHasNoRawPivotKeys() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);

        Map<String, Object> role = admin.toMap();

        for (String key : role.keySet()) {
            assertFalse(key.startsWith("pivot_"), key);
        }
        for (String key : mapOf(role.get("pivot")).keySet()) {
            assertFalse(key.startsWith("pivot_"), key);
        }
    }

    /** Each role of a collection serializes with its own pivot in the JSON text. */
    @Test
    void collection() {
        BtmModels.User ann = user(1);
        ann.setRelation("roles", ann.roles().get());

        String json = ann.toJson();

        assertTrue(json.contains("\"roles\":["), json);
        assertEquals(2, count(json, "\"pivot\":{"), json);
        assertTrue(json.contains("\"status\":\"active\""), json);
        assertTrue(json.contains("\"status\":\"pending\""), json);
    }

    /** A chaperoned graph from role to pivot and back to the role terminates with attributes only. */
    @Test
    void chaperoneEnds() {
        BtmModels.Role admin = roleWithId(holder(1).chaperonedRoles().get(), 1);

        Map<String, Object> pivot = mapOf(admin.toMap().get("pivot"));

        assertEquals(1, intOf(mapOf(pivot.get("user")).get("id")));
        assertKeys(mapOf(pivot.get("role")), "id", "name");
        String json = admin.toJson();
        assertTrue(json.contains("\"role\":{"), json);
    }

    /** A role read without its relation writes no pivot key. */
    @Test
    void noPivotNoKey() {
        BtmModels.Role admin = BtmModels.Role.QUERY.on(db.db).findOrFail(1);

        assertNull(Pivot.of(admin));
        assertFalse(admin.toMap().containsKey("pivot"));
    }

    /** A failed serialization does not leave the role marked as being written. */
    @Test
    void guardReleased() {
        BtmModels.Role admin = roleWithId(user(1).roles().get(), 1);
        Pivot pivot = Pivot.of(admin);
        pivot.setAttribute("created_at", "not a moment");
        assertThrows(InvalidCastException.class, admin::toMap);

        // The failed call must leave the role off the path, or the retry below would drop its pivot.
        pivot.setAttribute("created_at", SEEDED_TEXT);
        Map<String, Object> map = admin.toMap();

        assertTrue(map.containsKey("pivot"));
        assertEquals(SEEDED_MOMENT, mapOf(map.get("pivot")).get("created_at"));
    }

    /** Two reads of the same role give separate instances whose maps share no state. */
    @Test
    void readTwiceIndependentMaps() {
        BtmModels.User ann = user(1);
        BtmModels.Role first = roleWithId(ann.roles().get(), 1);
        BtmModels.Role second = roleWithId(ann.roles().get(), 1);
        assertNotSame(first, second);

        Map<String, Object> firstMap = first.toMap();
        firstMap.put("extra", "x");
        mapOf(firstMap.get("pivot")).put("extra", "x");

        Map<String, Object> secondMap = second.toMap();
        assertFalse(secondMap.containsKey("extra"));
        assertFalse(mapOf(secondMap.get("pivot")).containsKey("extra"));
        assertFalse(first.toMap().containsKey("extra"));
    }
}

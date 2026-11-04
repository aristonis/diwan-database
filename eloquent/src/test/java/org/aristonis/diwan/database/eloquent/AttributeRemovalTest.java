package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.junit.jupiter.api.Test;

class AttributeRemovalTest {

    private static final String PIVOT_PREFIX = "pivot_";

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<Member> members = Member.QUERY.on(fake.db);
    private final ModelQuery<Profile> profiles = Profile.QUERY.on(fake.db);

    @Test
    void removesMatchingPrefixOnly() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "pivotal", "keep", "pivot_role_id", 2L,
                "name", "John"));

        Map<String, Object> taken = model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertEquals(Arrays.asList("user_id", "role_id"), new ArrayList<>(taken.keySet()));
        assertEquals(Arrays.asList("id", "pivotal", "name"), new ArrayList<>(model.getAttributes().keySet()));
    }

    @Test
    void strippedNamesAndOrderKept() {
        Member model = hydrated(row("pivot_b", 2L, "name", "John", "pivot_a", 1L, "email", "john@test.com"));

        Map<String, Object> taken = model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertEquals(Arrays.asList("b", "a"), new ArrayList<>(taken.keySet()));
        assertEquals(2L, taken.get("b"));
        assertEquals(1L, taken.get("a"));
        assertEquals(Arrays.asList("name", "email"), new ArrayList<>(model.getAttributes().keySet()));
    }

    @Test
    void modelNotDirtyAfterRemoval() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));

        model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertFalse(model.isDirty());
        assertTrue(model.getDirty().isEmpty());
    }

    @Test
    void cleanSaveSendsNothing() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));
        model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertTrue(model.save());

        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void changedNameSendsOnlyName() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));
        model.takeAttributesWithPrefix(PIVOT_PREFIX);
        model.setAttribute("name", "Jane");

        assertTrue(model.save());

        assertEquals(1, fake.calls.size());
        assertEquals("update \"members\" set \"name\" = ? where \"id\" = ?", fake.last().sql());
        assertEquals(Arrays.asList("Jane", 1L), fake.last().bindings());
    }

    @Test
    void toMapLacksRemoved() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));

        model.takeAttributesWithPrefix(PIVOT_PREFIX);
        Map<String, Object> map = model.toMap();

        assertEquals("John", map.get("name"));
        assertFalse(map.containsKey("pivot_user_id"));
        assertFalse(map.containsKey("user_id"));
    }

    @Test
    void getOriginalKeepsRemoved() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));

        model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertEquals(1L, model.getOriginal("pivot_user_id"));
        assertTrue(model.getOriginal().containsKey("pivot_user_id"));
    }

    @Test
    void discardChangesRestoresThem() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));
        model.takeAttributesWithPrefix(PIVOT_PREFIX);

        model.discardChanges();

        assertEquals(1L, model.getAttribute("pivot_user_id"));
        assertEquals(1L, model.toMap().get("pivot_user_id"));
    }

    @Test
    void replicateOmitsRemovedAttributes() {
        Member model = hydrated(row("id", 1L, "pivot_user_id", 1L, "name", "John"));
        model.takeAttributesWithPrefix(PIVOT_PREFIX);
        Member copy = model.replicate();
        fake.nextId(9);

        assertTrue(copy.save());

        assertEquals("insert into \"members\" (\"name\") values (?)", fake.last().sql());
        assertEquals(Collections.singletonList("John"), fake.last().bindings());
    }

    @Test
    void noMatchReturnsEmptyMap() {
        Member model = hydrated(row("id", 1L, "name", "John"));

        Map<String, Object> taken = model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertTrue(taken.isEmpty());
        assertEquals(Arrays.asList("id", "name"), new ArrayList<>(model.getAttributes().keySet()));
    }

    @Test
    void cachedCastObjectEvicted() {
        Profile model = profiles.query().hydrate(Collections.singletonList(
                row("id", 1L, "pivot_meta", "{\"a\":1}"))).get(0);
        assertNotNull(model.getAttribute("pivot_meta"));

        model.takeAttributesWithPrefix(PIVOT_PREFIX);

        assertNull(model.getAttribute("pivot_meta"));
        assertFalse(model.getAttributes().containsKey("pivot_meta"));
        assertFalse(model.toMap().containsKey("pivot_meta"));
    }

    private Member hydrated(Map<String, Object> values) {
        return members.query().hydrate(Collections.singletonList(values)).get(0);
    }

    /** A model on the members table with no timestamps, so updates carry only changed columns. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String table() {
            return "members";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A model with a JSON cast on a prefixed key, so reading it caches a decoded object. */
    static final class Profile extends Model {
        static final ModelQuery<Profile> QUERY = ModelQuery.of(Profile.class, Profile::new);

        @Override
        protected String table() {
            return "profiles";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("pivot_meta", Casts.json());
        }
    }
}

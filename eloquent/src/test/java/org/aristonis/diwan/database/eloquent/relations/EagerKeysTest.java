package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.Test;

/** Eager key handling: normalise, collect, inline check and chunking, then the queries they give. */
class EagerKeysTest {

    private static final Function<Object, Object> SELF = key -> key;

    private static List<Object> keys(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    // ---- normalise ----

    @Test
    void longAndIntegerMatch() {
        assertEquals(EagerKeys.normalise(1L), EagerKeys.normalise(1));
        assertEquals(1L, EagerKeys.normalise((byte) 1));
        assertEquals(1L, EagerKeys.normalise((short) 1));
        assertEquals(1L, EagerKeys.normalise(BigInteger.ONE));
        assertEquals(1L, EagerKeys.normalise(new BigDecimal("1.000")));
    }

    @Test
    void canonicalIntegerTextMatches() {
        assertEquals(42L, EagerKeys.normalise("42"));
        assertEquals(-7L, EagerKeys.normalise("-7"));
        assertEquals(0L, EagerKeys.normalise("0"));
        assertEquals(Long.MAX_VALUE, EagerKeys.normalise(String.valueOf(Long.MAX_VALUE)));
    }

    @Test
    void nonCanonicalTextIsNotNormalised() {
        for (String text : Arrays.asList("01", " 1", "1 ", "1.0", "+1", "9223372036854775808", "1e3", "")) {
            assertEquals(text, EagerKeys.normalise(text), text);
        }
    }

    @Test
    void minusZeroTextNotNormalised() {
        assertEquals("-0", EagerKeys.normalise("-0"));
        assertNotEquals(EagerKeys.normalise(0), EagerKeys.normalise("-0"));
    }

    @Test
    void wholeDoubleMatchesLong() {
        assertEquals(3L, EagerKeys.normalise(3.0d));
        assertEquals(3L, EagerKeys.normalise(3.0f));
        assertEquals(0L, EagerKeys.normalise(-0.0d));
        assertEquals(EagerKeys.normalise(3), EagerKeys.normalise(3.0d));
    }

    @Test
    void fractionalAndHugeNumbersBecomeText() {
        assertEquals("1.5", EagerKeys.normalise(1.5d));
        assertEquals("1.0E19", EagerKeys.normalise(1e19d));
        assertEquals("NaN", EagerKeys.normalise(Double.NaN));
        assertEquals("Infinity", EagerKeys.normalise(Double.POSITIVE_INFINITY));
        assertEquals("1.5", EagerKeys.normalise(new BigDecimal("1.5")));
        BigInteger big = BigInteger.ONE.shiftLeft(64);
        assertEquals(big.toString(), EagerKeys.normalise(big));
    }

    @Test
    void uuidTextNotNormalised() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        String upper = id.toString().toUpperCase(java.util.Locale.ROOT);

        assertEquals(id.toString(), EagerKeys.normalise(id));
        assertEquals(upper, EagerKeys.normalise(upper));
        assertNotEquals(EagerKeys.normalise(id), EagerKeys.normalise(upper));
        assertEquals(EagerKeys.normalise(id), EagerKeys.normalise(id.toString()));
    }

    @Test
    void nullStaysNull() {
        assertNull(EagerKeys.normalise(null));
    }

    // ---- collect ----

    @Test
    void duplicateKeysQueriedOnce() {
        List<Object> collected = EagerKeys.collect(keys(3, 1L, "3", 1, 2), SELF);

        assertEquals(Arrays.asList(1L, 2, 3), collected);
    }

    @Test
    void zeroIsAValidKey() {
        assertEquals(Arrays.asList(0, 5), EagerKeys.collect(keys(5, 0, "0"), SELF));
    }

    @Test
    void nullKeysAreDropped() {
        assertEquals(Arrays.asList(1, 2), EagerKeys.collect(keys(null, 2, null, 1), SELF));
        assertEquals(Collections.singletonList("a"), EagerKeys.collect(keys(null, "a", null), SELF));
        assertTrue(EagerKeys.collect(keys(null, null), SELF).isEmpty());
        assertTrue(EagerKeys.collect(keys(), SELF).isEmpty());
    }

    @Test
    void integerKeysAreSorted() {
        assertEquals(Arrays.asList(-4, "2", 10, 300), EagerKeys.collect(keys(300, 10, -4, "2"), SELF));
    }

    @Test
    void otherKeysKeepFirstSeenOrder() {
        assertEquals(Arrays.asList("b", "a", 1), EagerKeys.collect(keys("b", "a", 1, "b"), SELF));
    }

    @Test
    void collectKeepsTheFirstSeenOriginalValueForBinding() {
        List<Object> collected = EagerKeys.collect(keys("10001", "123", 123L, 10001), SELF);

        assertEquals(Arrays.asList("123", "10001"), collected);
        assertTrue(collected.get(0) instanceof String);
        assertTrue(collected.get(1) instanceof String);
    }

    @Test
    void collectReadsKeysThroughTheFunction() {
        List<String> parents = Arrays.asList("x:2", "y:1", "z:");
        Function<String, Object> keyOf = parent -> {
            String key = parent.substring(2);
            return key.isEmpty() ? null : key;
        };

        assertEquals(Arrays.asList("1", "2"), EagerKeys.collect(parents, keyOf));
    }

    @Test
    void collectedListIsANewList() {
        List<Object> collected = EagerKeys.collect(keys(1), SELF);

        collected.add(2L);

        assertEquals(Collections.singletonList(1), EagerKeys.collect(keys(1), SELF));
    }

    // ---- exactLong ----

    @Test
    void exactLongAcceptsEveryIntegerForm() {
        assertEquals(7L, EagerKeys.exactLong(7, Object.class, "r"));
        assertEquals(7L, EagerKeys.exactLong("7", Object.class, "r"));
        assertEquals(7L, EagerKeys.exactLong(7.0d, Object.class, "r"));
        assertEquals(Long.MAX_VALUE, EagerKeys.exactLong(Long.MAX_VALUE, Object.class, "r"));
    }

    @Test
    void nonIntegralKeyRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EagerKeys.exactLong(1.5d, String.class, "comments"));

        assertEquals("Key of [java.lang.String] for relation [comments] is not an integer: java.lang.Double",
                e.getMessage());
    }

    @Test
    void messageHasTypeNotValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EagerKeys.exactLong("secret-key-text", String.class, "comments"));

        assertTrue(e.getMessage().endsWith("java.lang.String"));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void nullKeyIsRejectedForInlining() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EagerKeys.exactLong(null, String.class, "comments"));

        assertTrue(e.getMessage().endsWith("is not an integer: null"));
    }

    // ---- chunks ----

    @Test
    void keysSplitIntoChunksInOrder() {
        List<List<Object>> chunks = EagerKeys.chunks(keys(1, 2, 3, 4, 5), 2);

        assertEquals(Arrays.asList(keys(1, 2), keys(3, 4), keys(5)), chunks);
    }

    @Test
    void exactMultipleHasNoEmptyTail() {
        assertEquals(2, EagerKeys.chunks(keys(1, 2, 3, 4), 2).size());
        assertEquals(1, EagerKeys.chunks(keys(1, 2), 900).size());
    }

    @Test
    void noKeysGiveNoChunks() {
        assertTrue(EagerKeys.chunks(keys(), 900).isEmpty());
    }

    @Test
    void chunksDoNotShareTheSourceList() {
        List<Object> source = keys(1, 2, 3);
        List<List<Object>> chunks = EagerKeys.chunks(source, 2);

        source.set(0, 99);

        assertEquals(keys(1, 2), chunks.get(0));
    }

    @Test
    void chunkSizeBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EagerKeys.chunks(keys(1), 0));
    }

    @Test
    void fortyFiveThousandKeysMakeThreeSQLiteInlineChunks() {
        List<Object> many = new ArrayList<>();
        for (long i = 1; i <= 45_000; i++) {
            many.add(i);
        }

        List<List<Object>> chunks = EagerKeys.chunks(many, 20_000);

        assertEquals(3, chunks.size());
        assertEquals(5_000, chunks.get(2).size());
    }

    // ---- the queries the keys give ----

    private static final Consumer<EloquentBuilder<?>> NONE = q -> {
    };

    private static void load(List<? extends Model> models, String name) {
        new RelationAccessImpl().load(models, Collections.singletonMap(name, NONE));
    }

    private static List<User> usersWithIds(RelationDb db, long first, int count) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = User.QUERY.on(db.db).newModel();
            user.setAttribute("id", first + i);
            users.add(user);
        }
        return users;
    }

    @Test
    void nullForeignKeysAreDropped() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            db.seed("users", Map.of("id", 1, "name", "Ann"));
            db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
            db.seed("posts", Map.of("id", 2, "title", "orphan"));
            List<Post> posts = Post.QUERY.on(db.db).all();
            db.clearLog();

            load(posts, "author");

            assertEquals(Collections.singletonList("select * from \"users\" where \"users\".\"id\" in (1)"), db.sql());
            assertEquals("Ann", posts.get(0).<User>related("author").getAttribute("name"));
            assertNull(posts.get(1).related("author"));
        }
    }

    @Test
    void nullParentKeyNeverBecomesZero() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            db.seed("users", Map.of("id", 1, "name", "Ann"));
            db.seed("posts", Map.of("id", 1, "user_id", 0, "title", "zero"));
            List<User> users = new ArrayList<>(User.QUERY.on(db.db).all());
            users.add(User.QUERY.on(db.db).newModel());
            db.clearLog();

            load(users, "posts");

            assertEquals(1, db.sql().size());
            assertTrue(db.sql().get(0).endsWith("in (1)"), db.sql().get(0));
            assertTrue(users.get(1).relatedMany("posts").isEmpty());
        }
    }

    @Test
    void allNullKeysRunNoQuery() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            List<User> users = Arrays.asList(User.QUERY.on(db.db).newModel(), User.QUERY.on(db.db).newModel());
            db.clearLog();

            load(users, "posts");

            assertEquals(Collections.emptyList(), db.sql());
            assertTrue(users.get(0).relatedMany("posts").isEmpty());
            assertTrue(users.get(1).relationLoaded("posts"));
        }
    }

    @Test
    void integerKeysInlined() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            List<User> users = usersWithIds(db, 1, 2_000);
            db.clearLog();

            load(users, "posts");

            assertEquals(1, db.queries().size());
            assertEquals(Collections.emptyList(), db.queries().get(0).bindings());
        }
    }

    @Test
    void hugeInlinedListChunked() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            List<User> users = usersWithIds(db, 1, 45_000);
            db.clearLog();

            load(users, "posts");

            assertEquals(3, db.sql().size());
        }
    }

    @Test
    void longMaxValueInlined() throws SQLException {
        try (RelationDb db = new RelationDb()) {
            List<User> users = usersWithIds(db, Long.MAX_VALUE, 1);
            db.clearLog();

            load(users, "posts");

            assertTrue(db.sql().get(0).endsWith("in (" + Long.MAX_VALUE + ")"), db.sql().get(0));
        }
    }
}

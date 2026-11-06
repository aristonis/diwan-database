package org.aristonis.diwan.database.eloquent;

import org.aristonis.diwan.database.eloquent.scope.Scope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Post;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The class-level registry of global scopes: keys, replacement, refusals, sharing between entries,
 * removal and concurrent registration. Uses the {@link Tag} model, which has no scopes of its own.
 */
class GlobalScopeRegistryTest {

    private static final String TENANT = ScopeFixtures.TENANT;
    private static final int REGISTER_COUNT = 40;
    private static final int READER_THREADS = 2;
    private static final int READER_ROUNDS = 300;
    private static final long TIMEOUT_SECONDS = 30L;

    private ScopeFixtures fixtures;

    /** A plain model that soft deletes, the base of {@link Admin}. */
    static class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }
    }

    /** A subclass with its own registry that inherits the soft-delete setting of {@link Member}. */
    static final class Admin extends Member {
        static final ModelQuery<Admin> QUERY = ModelQuery.of(Admin.class, Admin::new);
    }

    /** A scope with no explicit name, keyed by its class name. */
    static final class NamedScope implements Scope {
        @Override
        public void apply(EloquentBuilder<?> builder, Model model) {
            // Adds nothing: only its key matters here.
        }
    }

    @BeforeEach
    void open() throws SQLException {
        ScopeFixtures.clearScopes();
        fixtures = ScopeFixtures.open();
    }

    @AfterEach
    void close() throws SQLException {
        ScopeFixtures.clearScopes();
        Member.QUERY.clearGlobalScopes();
        Admin.QUERY.clearGlobalScopes();
        fixtures.close();
    }

    private static Scope noop() {
        return (builder, model) -> {
            // Adds nothing.
        };
    }

    private static Consumer<EloquentBuilder<?>> noopConsumer() {
        return builder -> {
            // Adds nothing.
        };
    }

    private static List<String> keysOf(ModelQuery<?> query) {
        return new ArrayList<>(query.getGlobalScopes().keySet());
    }

    private static List<String> names(String prefix, int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(prefix + i);
        }
        return names;
    }

    @Test
    void sameNameReplacesAndKeepsPosition() {
        Scope replacement = noop();
        Tag.QUERY.addGlobalScope("first", noop())
                .addGlobalScope("second", noop())
                .addGlobalScope("first", replacement);

        assertEquals(Arrays.asList("first", "second"), keysOf(Tag.QUERY));
        assertSame(replacement, Tag.QUERY.getGlobalScope("first"));
    }

    @Test
    void blankNameIsRefusedAndRegistryUnchanged() {
        Tag.QUERY.addGlobalScope("kept", noop());

        assertThrows(IllegalArgumentException.class, () -> Tag.QUERY.addGlobalScope("   ", noopConsumer()));
        assertThrows(IllegalArgumentException.class, () -> Tag.QUERY.addGlobalScope("", noop()));

        assertEquals(Collections.singletonList("kept"), keysOf(Tag.QUERY));
    }

    @Test
    void nullNameIsRefusedNamingTheArgument() {
        NullPointerException refused = assertThrows(NullPointerException.class,
                () -> Tag.QUERY.addGlobalScope((String) null, noop()));

        assertEquals("name", refused.getMessage());
        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
    }

    @Test
    void nullScopeOrConsumerIsRefusedNamingTheArgument() {
        NullPointerException unnamed = assertThrows(NullPointerException.class,
                () -> Tag.QUERY.addGlobalScope((Scope) null));
        NullPointerException named = assertThrows(NullPointerException.class,
                () -> Tag.QUERY.addGlobalScope("x", (Scope) null));
        NullPointerException consumer = assertThrows(NullPointerException.class,
                () -> Tag.QUERY.addGlobalScope("x", (Consumer<EloquentBuilder<?>>) null));

        assertEquals("scope", unnamed.getMessage());
        assertEquals("scope", named.getMessage());
        assertEquals("scope", consumer.getMessage());
        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
    }

    @Test
    void classNameIsTheDefaultKey() {
        NamedScope scope = new NamedScope();

        Tag.QUERY.addGlobalScope(scope);

        assertTrue(Tag.QUERY.hasGlobalScope(NamedScope.class));
        assertTrue(Tag.QUERY.hasGlobalScope(NamedScope.class.getName()));
        assertSame(scope, Tag.QUERY.getGlobalScope(NamedScope.class.getName()));
    }

    @Test
    void lateRegistrationOnlyReachesNewBuilders() {
        EloquentBuilder<Tag> before = fixtures.tags().query();

        Tag.QUERY.addGlobalScope("late", noopConsumer());
        EloquentBuilder<Tag> after = fixtures.tags().query();

        assertEquals(Collections.emptyList(), before.withoutGlobalScopes().removedScopes());
        assertEquals(Collections.singletonList("late"), after.withoutGlobalScopes().removedScopes());
    }

    @Test
    void listIsUnmodifiable() {
        Tag.QUERY.addGlobalScope("kept", noop());
        Map<String, Scope> snapshot = Tag.QUERY.getGlobalScopes();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("other", noop()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove("kept"));

        assertEquals(Collections.singletonList("kept"), keysOf(Tag.QUERY));
    }

    @Test
    void subclassHasItsOwnScopes() {
        Member.QUERY.addGlobalScope("memberOnly", (EloquentBuilder<?> builder) -> builder.where("country_id", 1));

        String memberSql = Member.QUERY.on(fixtures.db).query().toSql();
        String adminSql = Admin.QUERY.on(fixtures.db).query().toSql();

        assertTrue(memberSql.contains("\"country_id\" = ?"));
        assertFalse(adminSql.contains("country_id"));
        assertFalse(Admin.QUERY.hasGlobalScope("memberOnly"));
        assertTrue(adminSql.contains("\"deleted_at\" is null"), "the soft-delete setting is inherited");
    }

    @Test
    void derivedEntriesShareScopes() {
        Tag.QUERY.on(fixtures.db).addGlobalScope("shared", noopConsumer());

        assertTrue(Tag.QUERY.hasGlobalScope("shared"));
        assertTrue(fixtures.tags().hasGlobalScope("shared"));
        assertTrue(Tag.QUERY.withClock(ModelClock.fixed(ScopeFixtures.NOW)).hasGlobalScope("shared"));

        fixtures.tags().removeGlobalScope("shared");
        assertFalse(Tag.QUERY.hasGlobalScope("shared"));
    }

    @Test
    void concurrentRegistrationKeepsListsConsistent() throws Exception {
        List<String> expected = names("k", REGISTER_COUNT);
        ExecutorService pool = Executors.newFixedThreadPool(READER_THREADS + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<List<String>>>> readers = new ArrayList<>();
        try {
            Future<?> writer = pool.submit(() -> {
                start.await();
                for (String name : expected) {
                    Tag.QUERY.addGlobalScope(name, noopConsumer());
                }
                return null;
            });
            for (int i = 0; i < READER_THREADS; i++) {
                readers.add(pool.submit(() -> readSnapshots(start)));
            }
            start.countDown();
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            for (Future<List<List<String>>> reader : readers) {
                assertConsistent(expected, reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(expected, keysOf(Tag.QUERY));
    }

    private List<List<String>> readSnapshots(CountDownLatch start) throws InterruptedException {
        start.await();
        List<List<String>> snapshots = new ArrayList<>();
        for (int round = 0; round < READER_ROUNDS; round++) {
            snapshots.add(keysOf(Tag.QUERY));
            snapshots.add(fixtures.tags().query().withoutGlobalScopes().removedScopes());
        }
        return snapshots;
    }

    private static void assertConsistent(List<String> expected, List<List<String>> snapshots) {
        int previous = 0;
        for (List<String> seen : snapshots) {
            assertTrue(seen.size() >= previous, "a later read must not see fewer scopes");
            assertEquals(expected.subList(0, seen.size()), seen);
            previous = seen.size();
        }
    }

    @Test
    void removeGlobalScopeByNameLeavesOthersInPlace() {
        Tag.QUERY.addGlobalScope("a", noop()).addGlobalScope("b", noop()).addGlobalScope("c", noop());

        Tag.QUERY.removeGlobalScope("b");

        assertEquals(Arrays.asList("a", "c"), keysOf(Tag.QUERY));
    }

    @Test
    void removeGlobalScopeByClassUsesTheClassKey() {
        Tag.QUERY.addGlobalScope(new NamedScope());

        Tag.QUERY.removeGlobalScope(NamedScope.class);

        assertFalse(Tag.QUERY.hasGlobalScope(NamedScope.class));
        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
    }

    @Test
    void removingAnUnknownScopeIsIgnored() {
        Tag.QUERY.addGlobalScope("kept", noop());

        Tag.QUERY.removeGlobalScope("unknown").removeGlobalScope(NamedScope.class);

        assertEquals(Collections.singletonList("kept"), keysOf(Tag.QUERY));
    }

    @Test
    void clearGlobalScopesLeavesOtherModelsAlone() {
        Tag.QUERY.addGlobalScope("tagOnly", noop());
        assertTrue(ScopeFixtures.Post.QUERY.hasGlobalScope(TENANT));

        Tag.QUERY.clearGlobalScopes();

        assertTrue(Tag.QUERY.getGlobalScopes().isEmpty());
        assertTrue(Post.QUERY.hasGlobalScope(TENANT));
    }

    @Test
    void secondOfCallStillRegistersIntoTheSharedRegistry() {
        ModelQuery<Tag> second = ModelQuery.of(Tag.class, Tag::new);

        second.on(fixtures.db).addGlobalScope("second", noopConsumer());

        assertTrue(Tag.QUERY.hasGlobalScope("second"));
        assertTrue(fixtures.tags().hasGlobalScope("second"));
    }
}

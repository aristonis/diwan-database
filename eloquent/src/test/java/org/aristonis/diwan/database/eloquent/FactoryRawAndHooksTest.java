package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The raw form returns maps only, and the make hooks run in order, once per model, and can be removed. */
class FactoryRawAndHooksTest {

    /** A post with two fillable columns that counts the instances built. */
    static final class Post extends Model {
        static final AtomicInteger BUILT = new AtomicInteger();
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);

        Post() {
            BUILT.incrementAndGet();
        }

        @Override
        protected List<String> fillable() {
            return List.of("title", "body");
        }
    }

    private FakeDb fake;
    private ModelQuery<Post> posts;

    @BeforeEach
    void open() {
        fake = new FakeDb();
        posts = Post.QUERY.on(fake.db);
    }

    private Factory<Post> factory(Object... pairs) {
        return Factory.of(posts, () -> row(pairs));
    }

    @Test
    void rawReturnsTheResolvedMapOnly() {
        Map<String, Object> raw = factory("title", "a").state(row("body", "b")).set("title", "c").raw();

        assertEquals(row("title", "c", "body", "b"), raw);
    }

    @Test
    void rawManyReturnsMaps() {
        AtomicInteger counter = new AtomicInteger();
        Supplier<Object> next = counter::incrementAndGet;

        List<Map<String, Object>> raws = factory("title", next).rawMany(3);

        assertEquals(List.of(row("title", 1), row("title", 2), row("title", 3)), raws);
    }

    @Test
    void rawBuildsNoModelAndRunsNoHook() {
        AtomicInteger hooked = new AtomicInteger();
        Factory<Post> base = factory("title", "a").afterMaking(m -> hooked.incrementAndGet());
        int builtBefore = Post.BUILT.get();

        base.raw();
        base.rawMany(2);

        assertEquals(builtBefore, Post.BUILT.get());
        assertEquals(0, hooked.get());
    }

    @Test
    void rawTouchesNoDatabase() {
        factory("title", "a").rawMany(2);

        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void rawCountBelowOneGivesAnEmptyList() {
        Factory<Post> base = factory("title", "a");

        assertTrue(base.count(0).rawMany().isEmpty());
        assertTrue(base.count(-1).rawMany().isEmpty());
        assertTrue(base.rawMany(0).isEmpty());
    }

    @Test
    void afterMakingHooksRunInTheOrderAdded() {
        List<String> log = new ArrayList<>();

        factory("title", "a").afterMaking(m -> log.add("first")).afterMaking(m -> log.add("second")).make();

        assertEquals(List.of("first", "second"), log);
    }

    @Test
    void afterMakingRunsOncePerModel() {
        AtomicInteger hooked = new AtomicInteger();

        factory("title", "a").afterMaking(m -> hooked.incrementAndGet()).makeMany(3);

        assertEquals(3, hooked.get());
    }

    @Test
    void makeRunsAfterMakingAndNotAfterCreating() {
        List<String> log = new ArrayList<>();

        factory("title", "a").afterMaking(m -> log.add("making")).afterCreating(m -> log.add("creating")).make();

        assertEquals(List.of("making"), log);
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void withoutAfterMakingRemovesTheHooks() {
        AtomicInteger hooked = new AtomicInteger();
        Factory<Post> hooks = factory("title", "a").afterMaking(m -> hooked.incrementAndGet());

        hooks.withoutAfterMaking().makeMany(2);

        assertEquals(0, hooked.get());
        hooks.make();
        assertEquals(1, hooked.get());
    }

    @Test
    void withoutAfterCreatingReturnsAFactoryWithNoHooks() {
        AtomicInteger making = new AtomicInteger();
        Factory<Post> hooks = factory("title", "a").afterMaking(m -> making.incrementAndGet())
                .afterCreating(m -> { });

        Factory<Post> cleared = hooks.withoutAfterCreating();

        assertNotSame(hooks, cleared);
        cleared.make();
        assertEquals(1, making.get());
    }

    @Test
    void hooksAreCopiedByFluentCalls() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        Factory<Post> base = factory("title", "a").afterMaking(m -> first.incrementAndGet());

        Factory<Post> extended = base.count(2).afterMaking(m -> second.incrementAndGet());
        base.make();

        assertEquals(1, first.get());
        assertEquals(0, second.get());
        extended.makeMany();
        assertEquals(3, first.get());
        assertEquals(2, second.get());
    }

    @Test
    void afterMakingSeesTheFilledModel() {
        List<Object> seen = new ArrayList<>();

        factory("title", "a", "secret", "guarded").afterMaking(m -> {
            seen.add(m.getAttribute("title"));
            seen.add(m.getAttribute("secret"));
            seen.add(m.exists());
        }).make();

        assertEquals(List.of("a", "guarded", false), seen);
        assertTrue(fake.calls.isEmpty());
    }
}

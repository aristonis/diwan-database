package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.relations.HasMany;
import org.aristonis.diwan.database.eloquent.relations.RelationMap;
import org.junit.jupiter.api.Test;

class RowHookTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final String ALL_USERS = "select * from \"users\"";
    private static final String ORDERED_USERS = "select * from \"users\" order by \"users\".\"id\" asc";
    private static final String KEY_PAGE_FIRST = "select * from \"users\" where \"id\" is not null order by \"id\" asc limit 2";
    private static final String KEY_PAGE_NEXT = "select * from \"users\" where \"id\" > ? order by \"id\" asc limit 2";

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW));
    private final ModelQuery<Member> members = Member.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW));

    @Test
    void hookRunsOnGet() {
        List<User> called = new ArrayList<>();
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        recordingQuery(called).get();

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void hookRunsOnFirst() {
        List<User> called = new ArrayList<>();
        fake.returns("select * from \"users\" limit 1", row("id", 1L, "name", "Ann"));

        recordingQuery(called).first();

        assertEquals(Arrays.asList("Ann"), namesOf(called));
    }

    @Test
    void hookRunsOnFind() {
        List<User> called = new ArrayList<>();
        fake.returns("select * from \"users\" where \"users\".\"id\" = ? limit 1", row("id", 1L, "name", "Ann"));

        recordingQuery(called).find(1);

        assertEquals(Arrays.asList("Ann"), namesOf(called));
    }

    @Test
    void hookRunsOnSole() {
        List<User> called = new ArrayList<>();
        fake.returns("select * from \"users\" limit 2", row("id", 1L, "name", "Ann"));

        recordingQuery(called).sole();

        assertEquals(Arrays.asList("Ann"), namesOf(called));
    }

    @Test
    void hookRunsOnChunk() {
        List<User> called = new ArrayList<>();
        fake.returns(ORDERED_USERS + " limit 2 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(ORDERED_USERS + " limit 2 offset 2", row("id", 3L, "name", "Carol"));

        recordingQuery(called).chunk(2, chunk -> true);

        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnChunkById() {
        List<User> called = new ArrayList<>();
        fake.returns(KEY_PAGE_FIRST, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(KEY_PAGE_NEXT, row("id", 3L, "name", "Carol"));

        recordingQuery(called).chunkById(2, chunk -> true);

        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnEach() {
        List<User> called = new ArrayList<>();
        fake.returns(ORDERED_USERS + " limit 2 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(ORDERED_USERS + " limit 2 offset 2", row("id", 3L, "name", "Carol"));

        recordingQuery(called).each(user -> true, 2);

        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnLazy() {
        List<User> called = new ArrayList<>();
        fake.returns(ORDERED_USERS + " limit 2 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(ORDERED_USERS + " limit 2 offset 2", row("id", 3L, "name", "Carol"));

        int seen = 0;
        for (User user : recordingQuery(called).lazy(2)) {
            seen++;
        }

        assertEquals(3, seen);
        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnLazyById() {
        List<User> called = new ArrayList<>();
        fake.returns(KEY_PAGE_FIRST, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(KEY_PAGE_NEXT, row("id", 3L, "name", "Carol"));

        int seen = 0;
        for (User user : recordingQuery(called).lazyById(2)) {
            seen++;
        }

        assertEquals(3, seen);
        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnLazyWithEagerLoadsBuffer() {
        List<Member> called = new ArrayList<>();
        fake.returns(ORDERED_USERS + " limit 2 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        fake.returns(ORDERED_USERS + " limit 2 offset 2", row("id", 3L, "name", "Carol"));

        EloquentBuilder<Member> query = members.query().orderBy("users.id").with("posts");
        query.useRowHook(called::add);
        int seen = 0;
        for (Member member : query.lazy(2)) {
            seen++;
        }

        assertEquals(3, seen);
        assertEquals(Arrays.asList("Ann", "Bob", "Carol"), namesOf(called));
    }

    @Test
    void hookRunsOnCursor() {
        List<User> called = new ArrayList<>();
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        try (ModelCursor<User> cursor = recordingQuery(called).cursor()) {
            int seen = 0;
            for (User user : cursor) {
                seen++;
            }
            assertEquals(2, seen);
        }

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void hookRunsOnPaginate() {
        List<User> called = new ArrayList<>();
        fake.returns("select count(*) as \"aggregate\" from \"users\"", row("aggregate", 3L));
        fake.returns("select * from \"users\" limit 2 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        recordingQuery(called).paginate(2);

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void hookRunsOnSimplePaginate() {
        List<User> called = new ArrayList<>();
        fake.returns("select * from \"users\" limit 3 offset 0", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        recordingQuery(called).simplePaginate(2);

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void hookRunsOnCursorPaginate() {
        List<User> called = new ArrayList<>();
        fake.returns(ORDERED_USERS + " limit 3", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        EloquentBuilder<User> query = recordingQuery(called);
        query.orderBy("users.id");

        query.cursorPaginate(2);

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void copyKeepsHook() {
        List<User> called = new ArrayList<>();
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        EloquentBuilder<User> copied = recordingQuery(called).copy();
        copied.get();

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void withoutEagerLoadsKeepsHook() {
        List<Member> called = new ArrayList<>();
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        EloquentBuilder<Member> query = members.query().with("posts");
        query.useRowHook(called::add);
        Access.eloquent().withoutEagerLoads(query).get();

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(called));
    }

    @Test
    void hookSeesModelAfterRetrieved() {
        List<String> order = new ArrayList<>();

        class Watched extends User {
            @Override
            protected String table() {
                return "users";
            }

            @Override
            protected void retrieved() {
                order.add("retrieved-" + getAttribute("name"));
            }
        }

        ModelQuery<Watched> watched = ModelQuery.of(Watched.class, Watched::new).on(fake.db);
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        EloquentBuilder<Watched> query = watched.query();
        query.useRowHook(model -> order.add("hook-" + model.getAttribute("name")));

        query.get();

        assertEquals(Arrays.asList("retrieved-Ann", "hook-Ann", "retrieved-Bob", "hook-Bob"), order);
    }

    @Test
    void nullClearsHook() {
        List<User> called = new ArrayList<>();
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"));
        EloquentBuilder<User> query = recordingQuery(called);

        query.useRowHook(null);
        query.get();

        assertEquals(0, called.size());
        assertNull(query.rowHook());
    }

    @Test
    void plainBuilderUnchanged() {
        fake.returns(ALL_USERS, row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));
        EloquentBuilder<User> query = users.query();

        ModelCollection<User> models = query.get();

        assertEquals(Arrays.asList("Ann", "Bob"), namesOf(models));
        assertNull(query.rowHook());
    }

    private EloquentBuilder<User> recordingQuery(List<User> called) {
        EloquentBuilder<User> query = users.query();
        query.useRowHook(called::add);
        return query;
    }

    private static List<Object> namesOf(List<? extends Model> models) {
        List<Object> names = new ArrayList<>();
        for (Model model : models) {
            names.add(model.getAttribute("name"));
        }
        return names;
    }

    /** A model on the users table that can load its posts by name. */
    static final class Member extends Model {
        static final ModelQuery<Member> QUERY = ModelQuery.of(Member.class, Member::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Member.class).add("posts", Member::posts);
        }

        HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    /** A post, the related model of {@link Member#posts()}. */
    static final class Post extends Model {
        static final ModelQuery<Post> QUERY = ModelQuery.of(Post.class, Post::new);
    }
}

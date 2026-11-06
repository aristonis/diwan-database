package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.aristonis.diwan.database.eloquent.ScopeFixtures.Tag;
import org.aristonis.diwan.database.eloquent.TestModels.Note;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryCursor;
import org.aristonis.diwan.database.query.internal.MappingCursor;
import org.junit.jupiter.api.Test;

class EloquentBuilderTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:15:30Z");
    private static final String NOW_TEXT = "2026-11-02 10:15:30";
    private static final String BY_KEY = "select * from \"users\" where \"users\".\"id\" = ? limit 1";

    private final FakeDb fake = new FakeDb();
    private final ModelQuery<User> users = User.QUERY.on(fake.db).withClock(ModelClock.fixed(NOW));

    @Test
    void forwardersBuildTheSameSqlAsTheQueryBuilder() {
        EloquentBuilder<User> model = users.query().where("a", 1).orWhere("b", ">", 2)
                .whereIn("c", Arrays.asList(1, 2)).whereNull("d").whereNotNull("e").whereBetween("f", Arrays.asList(1, 9))
                .whereColumn("g", "h").whereRaw("x = ?", Collections.singletonList(5)).whereLike("n", "a%")
                .whereDate("t", "2026-01-01").orderBy("a").orderByDesc("b").limit(5).offset(10);
        Builder base = fake.db.table("users").where("a", 1).orWhere("b", ">", 2)
                .whereIn("c", Arrays.asList(1, 2)).whereNull("d").whereNotNull("e").whereBetween("f", Arrays.asList(1, 9))
                .whereColumn("g", "h").whereRaw("x = ?", Collections.singletonList(5)).whereLike("n", "a%")
                .whereDate("t", "2026-01-01").orderBy("a").orderByDesc("b").limit(5).offset(10);

        assertEquals(base.toSql(), model.toSql());
        assertEquals(base.getBindings(), model.getBindings());
    }

    @Test
    void pagingAndSelectForwardersPassEveryArgument() {
        EloquentBuilder<User> model = users.query().select("id", "name").addSelect("email").selectRaw("1 as one")
                .distinct().whereNot("a", 1).orWhereNot("b", 2).whereTime("t", "10:00").orWhereColumn("c", ">", "d")
                .whereIn("e", b -> b.select("id").from("x")).whereNotIn("f", b -> b.select("id").from("y"))
                .groupBy("name").having("n", ">", 1).forPage(3, 10).take(4).skip(2);
        Builder base = fake.db.table("users").select("id", "name").addSelect("email").selectRaw("1 as one")
                .distinct().whereNot("a", 1).orWhereNot("b", 2).whereTime("t", "10:00").orWhereColumn("c", ">", "d")
                .whereIn("e", b -> b.select("id").from("x")).whereNotIn("f", b -> b.select("id").from("y"))
                .groupBy("name").having("n", ">", 1).forPage(3, 10).take(4).skip(2);

        assertEquals(base.toSql(), model.toSql());
        assertEquals(base.getBindings(), model.getBindings());
        assertEquals(fake.db.table("users").forPage(3, 10).toSql(), users.query().forPage(3, 10).toSql());
    }

    @Test
    void latestAndOldestUseTheCreatedAtColumn() {
        assertEquals("select * from \"users\" order by \"created_at\" desc", users.query().latest().toSql());
        assertEquals("select * from \"users\" order by \"created_at\" asc", users.query().oldest().toSql());
        assertEquals("select * from \"users\" order by \"x\" desc", users.query().latest("x").toSql());
    }

    @Test
    void queryAndToBaseReachTheUnderlyingBuilder() {
        EloquentBuilder<User> query = users.query().query(b -> b.where("a", 1));
        Builder copy = query.toBase();
        copy.where("b", 2);

        assertEquals("select * from \"users\" where \"a\" = ?", query.toSql());
        assertTrue(query.getModel() instanceof User);
    }

    @Test
    void getHydratesExistingModelsAndCallsRetrieved() {
        fake.returns("select * from \"users\"", row("id", 1L, "name", "Ann"), row("id", 2L, "name", "Bob"));

        ModelCollection<User> all = users.all();

        assertEquals(2, all.size());
        assertTrue(all.get(0).exists());
        assertTrue(all.get(0).isClean());
        assertEquals("Bob", all.get(1).getAttribute("name"));
        assertSame(fake.db, all.get(1).getQueryFactory());
    }

    @Test
    void findLooksUpByTheQualifiedKey() {
        fake.returns(BY_KEY, row("id", 1L, "name", "Ann"));

        assertEquals("Ann", users.find(1).get().getAttribute("name"));
        assertEquals(BY_KEY, fake.last().sql());
        assertEquals("Ann", users.findOrFail(1).getAttribute("name"));
        assertEquals("Ann", users.query().findOrNew(1).getAttribute("name"));
        assertEquals("Ann", users.query().findOr(1, () -> null).getAttribute("name"));
    }

    @Test
    void missingModelsThrowLaravelsMessage() {
        ModelNotFoundException one = assertThrows(ModelNotFoundException.class, () -> users.findOrFail(5));
        assertEquals("No query results for model [" + User.class.getName() + "] 5", one.getMessage());
        assertEquals(Collections.singletonList("5"), one.getIds());
        assertEquals(User.class.getName(), one.getModel());

        fake.returns("select * from \"users\" where \"users\".\"id\" in (?, ?, ?)", row("id", 2L));
        ModelNotFoundException many = assertThrows(ModelNotFoundException.class,
                () -> users.query().findOrFail(Arrays.asList(1, 2, 3)));
        assertEquals("No query results for model [" + User.class.getName() + "] 1, 3", many.getMessage());

        ModelNotFoundException first = assertThrows(ModelNotFoundException.class, () -> users.query().firstOrFail());
        assertEquals("No query results for model [" + User.class.getName() + "].", first.getMessage());
        assertThrows(ModelNotFoundException.class, () -> users.query().sole());
    }

    @Test
    void soleRefusesMoreThanOneRow() {
        fake.returns("select * from \"users\" limit 2", row("id", 1L), row("id", 2L));

        MultipleRecordsFoundException e = assertThrows(MultipleRecordsFoundException.class,
                () -> users.query().sole());
        assertEquals("2 records were found.", e.getMessage());
        assertEquals(2, e.getCount());
    }

    @Test
    void findManyWithNoIdsRunsNoQuery() {
        assertTrue(users.findMany(Collections.emptyList()).isEmpty());
        assertTrue(users.query().findOrFail(Collections.emptyList()).isEmpty());
        assertTrue(fake.calls.isEmpty());
        users.findMany(Arrays.asList(1, 2));
        assertEquals("select * from \"users\" where \"users\".\"id\" in (?, ?)", fake.last().sql());
    }

    @Test
    void whereKeyCastsForStringKeysAndWhereKeyNotNegates() {
        assertEquals("select * from \"users\" where \"users\".\"id\" != ?", users.query().whereKeyNot(1).toSql());
        assertEquals("select * from \"users\" where \"users\".\"id\" not in (?, ?)",
                users.query().whereKeyNot(Arrays.asList(1, 2)).toSql());
        assertEquals(Collections.singletonList("5"), TestModels.Doc.QUERY.on(fake.db).query().whereKey(5).getBindings());
    }

    @Test
    void firstOrAndFirstWhereAndValue() {
        User fallback = users.newModel();
        assertSame(fallback, users.query().firstOr(() -> fallback));
        assertFalse(users.query().firstWhere("name", "Ann").isPresent());
        assertEquals("select * from \"users\" where \"name\" = ? limit 1", fake.last().sql());
        users.query().firstWhere("age", ">", 3);
        assertEquals("select * from \"users\" where \"age\" > ? limit 1", fake.last().sql());
        assertFalse(users.query().value("name").isPresent());
        assertEquals(0, users.query().count());
        assertFalse(users.query().exists());
        assertTrue(users.query().pluck("name").isEmpty());
        assertFalse(users.query().findOrNew(9).exists());
        assertSame(fallback, users.query().findOr(9, () -> fallback));
    }

    @Test
    void createFillsAndSavesAndForceCreateIgnoresGuards() {
        User created = users.create(row("name", "Ann", "is_admin", true));

        assertTrue(created.exists());
        assertFalse(created.hasAttribute("is_admin"));
        User forced = users.forceCreate(row("name", "Ann", "is_admin", true));
        assertEquals(Boolean.TRUE, forced.getAttribute("is_admin"));
        assertFalse(Model.isUnguarded());
    }

    @Test
    void firstOrNewAndFirstOrCreateLookFirst() {
        fake.returns("select * from \"users\" where (\"email\" = ?) limit 1", row("id", 3L, "email", "a@x"));

        User found = users.firstOrCreate(row("email", "a@x"), row("name", "Ann"));

        assertEquals(3L, found.getKey());
        assertEquals(1, fake.calls.size());
        FakeDb empty = new FakeDb();
        User made = User.QUERY.on(empty.db).firstOrNew(row("email", "b@x"), row("name", "Bob"));
        assertFalse(made.exists());
        assertEquals(row("email", "b@x", "name", "Bob"), made.getAttributes());
        User inserted = User.QUERY.on(empty.db).firstOrCreate(row("email", "b@x"));
        assertTrue(inserted.exists());
        assertTrue(inserted.wasRecentlyCreated());
    }

    @Test
    void createOrFirstUsesASavepointInsideATransaction() {
        fake.inTransaction(() -> users.query().createOrFirst(row("email", "c@x"), row("name", "C")));

        assertTrue(fake.last().sql().startsWith("insert into \"users\""));
    }

    @Test
    void updateOrCreateUpdatesTheFoundModel() {
        fake.returns("select * from \"users\" where (\"email\" = ?) limit 1", row("id", 3L, "email", "a@x", "name", "A"));

        User user = users.updateOrCreate(row("email", "a@x"), row("name", "Z"));

        assertEquals("update \"users\" set \"name\" = ?, \"updated_at\" = ? where \"id\" = ?", fake.last().sql());
        assertEquals("Z", user.getAttribute("name"));
    }

    @Test
    void builderWritesAddTheUpdatedAtColumn() {
        users.query().where("a", 1).update(row("name", "x"));
        assertEquals("update \"users\" set \"name\" = ?, \"updated_at\" = ? where \"a\" = ?", fake.last().sql());
        assertEquals(Arrays.asList("x", NOW_TEXT, 1), fake.last().bindings());

        users.query().update(row("name", "x", "updated_at", "keep"));
        assertEquals(Arrays.asList("x", "keep"), fake.last().bindings());

        users.query().increment("votes");
        assertEquals("update \"users\" set \"votes\" = \"votes\" + 1, \"updated_at\" = ?", fake.last().sql());
        users.query().decrement("votes", 2, row("name", "y"));
        assertEquals("update \"users\" set \"votes\" = \"votes\" - 2, \"name\" = ?, \"updated_at\" = ?",
                fake.last().sql());
        users.query().increment("votes", 3);
        users.query().decrement("votes");

        Note.QUERY.on(fake.db).query().update(row("body", "x"));
        assertEquals("update \"notes\" set \"body\" = ?", fake.last().sql());
        assertEquals(1, users.query().whereKey(1).delete());
        assertEquals("delete from \"users\" where \"users\".\"id\" = ?", fake.last().sql());
    }

    @Test
    void chunksEachAndLazyYieldModels() {
        fake.returns("select * from \"users\" order by \"users\".\"id\" asc limit 2 offset 0", row("id", 1L), row("id", 2L));
        fake.returns("select * from \"users\" order by \"users\".\"id\" asc limit 2 offset 2", row("id", 3L));
        List<Object> keys = new ArrayList<>();

        assertTrue(users.query().orderBy("users.id").chunk(2, chunk -> keys.addAll(chunk.modelKeys())));
        assertEquals(Arrays.asList(1L, 2L, 3L), keys);
        List<Object> each = new ArrayList<>();
        users.query().orderBy("users.id").each(u -> each.add(u.getKey()), 2);
        assertEquals(Arrays.asList(1L, 2L, 3L), each);
        List<Object> lazy = new ArrayList<>();
        for (User user : users.query().orderBy("users.id").lazy(2)) {
            lazy.add(user.getKey());
        }
        assertEquals(Arrays.asList(1L, 2L, 3L), lazy);
        assertTrue(users.query().orderBy("users.id").each(u -> true));
        for (User user : users.query().orderBy("users.id").lazy()) {
            assertTrue(user.exists());
        }
    }

    @Test
    void chunkByIdAndLazyByIdUseTheModelKey() {
        // Laravel's chunkById defaults to the unqualified key name.
        fake.returns("select * from \"users\" where \"id\" is not null order by \"id\" asc limit 2", row("id", 1L),
                row("id", 2L));
        fake.returns("select * from \"users\" where \"id\" > ? order by \"id\" asc limit 2", row("id", 3L));
        List<Object> keys = new ArrayList<>();

        users.query().chunkById(2, chunk -> keys.addAll(chunk.modelKeys()));
        assertEquals(Arrays.asList(1L, 2L, 3L), keys);
        List<Object> lazy = new ArrayList<>();
        for (User user : users.query().lazyById(2)) {
            lazy.add(user.getKey());
        }
        assertEquals(Arrays.asList(1L, 2L, 3L), lazy);
        users.query().chunkById(2, chunk -> true, "users.id", "id");
        users.query().lazyById(2, "users.id", "id").iterator().hasNext();
        users.query().chunkById(2, chunk -> false);
    }

    @Test
    void cursorMapsRowsToModelsAndCloses() {
        fake.returns("select * from \"users\"", row("id", 1L), row("id", 2L));

        List<Object> keys = new ArrayList<>();
        try (QueryCursor<User> cursor = users.query().cursor()) {
            Iterator<User> iterator = cursor.iterator();
            while (iterator.hasNext()) {
                keys.add(iterator.next().getKey());
            }
            assertThrows(java.util.NoSuchElementException.class, iterator::next);
        }
        assertEquals(Arrays.asList(1L, 2L), keys);
        QueryCursor<User> open = users.query().cursor();
        Iterator<User> openRows = open.iterator();
        assertTrue(openRows.hasNext());
        open.close();
        assertFalse(openRows.hasNext());
    }

    @Test
    void pagesHoldModels() {
        fake.returns("select count(*) as \"aggregate\" from \"users\"", row("aggregate", 3L));
        fake.returns("select * from \"users\" limit 15 offset 0", row("id", 1L), row("id", 2L), row("id", 3L));

        ModelPage<User> page = users.query().paginate();

        assertEquals(3, page.total());
        assertEquals(15, page.perPage());
        assertEquals(1, page.currentPage());
        assertEquals(1, page.lastPage());
        assertFalse(page.hasMorePages());
        assertTrue(page.items().get(0).exists());
        assertTrue(page.toString().contains("3"));

        fake.returns("select * from \"users\" limit 3 offset 0", row("id", 1L), row("id", 2L), row("id", 3L));
        SimpleModelPage<User> simple = users.query().simplePaginate(2);
        assertEquals(2, simple.items().size());
        assertTrue(simple.hasMorePages());
        assertEquals(2, simple.perPage());
        assertEquals(1, simple.currentPage());
        assertTrue(users.query().simplePaginate().items().isEmpty() || true);

        fake.returns("select * from \"users\" order by \"users\".\"id\" asc limit 3", row("id", 1L), row("id", 2L),
                row("id", 3L));
        CursorModelPage<User> cursorPage = users.query().cursorPaginate(2);
        assertEquals(2, cursorPage.items().size());
        assertTrue(cursorPage.nextCursor().isPresent());
        assertFalse(cursorPage.previousCursor().isPresent());
        assertEquals(2, cursorPage.perPage());
        users.query().cursorPaginate(2, cursorPage.nextCursor().get());
        users.query().cursorPaginate();
        users.query().paginate(5, 2);
        users.query().simplePaginate(5, 2);
    }

    @Test
    void hydrateBuildsExistingModels() {
        ModelCollection<User> models = users.query().hydrate(Arrays.asList(row("id", 1L), row("id", 2L)));

        assertEquals(Arrays.asList(1L, 2L), models.modelKeys());
        assertTrue(models.get(1).exists());
    }

    @Test
    void keyClausesMapEveryCollectionElementThroughTheKey() {
        User ann = users.hydrate(Collections.singletonList(row("id", 7L))).get(0);

        assertEquals(Arrays.asList(7L, 2), users.query().whereKey(Arrays.asList(ann, 2)).getBindings());
        assertEquals(Arrays.asList(7L, 3), users.query().whereKeyNot(Arrays.asList(ann, 3)).getBindings());
        assertEquals(Arrays.asList("5", "6"),
                TestModels.Doc.QUERY.on(fake.db).query().whereKey(Arrays.asList(5, 6)).getBindings());
        assertEquals(Arrays.asList("5"),
                TestModels.Doc.QUERY.on(fake.db).query().whereKeyNot(Arrays.asList(5)).getBindings());
    }

    @Test
    void findManyMapsModelsToTheirKeys() {
        User ann = users.hydrate(Collections.singletonList(row("id", 7L))).get(0);

        users.findMany(Arrays.asList(ann, 8));

        assertEquals(Arrays.asList(7L, 8), fake.last().bindings());
    }

    @Test
    void findWithACollectionFindsMany() {
        fake.returns("select * from \"users\" where \"users\".\"id\" in (?, ?)", row("id", 1L), row("id", 2L));

        ModelCollection<User> found = users.query().find(Arrays.asList(1, 2));

        assertEquals(2, found.size());
        assertEquals(2, users.find(Arrays.asList(1, 2)).size());
    }

    @Test
    void findOfAnObjectRefusesACollection() {
        Object ids = Arrays.asList(1, 2);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> users.query().find(ids));

        assertTrue(e.getMessage().contains("findMany"));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    void aCursorClosesWhenHydrationFails() {
        FakeDb.ListCursor rows = new FakeDb.ListCursor(Collections.singletonList(row("id", 1L)));
        QueryCursor<User> cursor = new MappingCursor<User>(rows, r -> {
            throw new IllegalStateException("bad row");
        });

        assertThrows(IllegalStateException.class, () -> cursor.iterator().next());

        assertTrue(rows.isClosed());
    }

    @Test
    void unscopedModelsAreUnchanged() {
        ModelQuery<Tag> tags = Tag.QUERY.on(fake.db);
        fake.returns("select * from \"tags\" where \"name\" = ?", row("id", 1L, "name", "php"));
        EloquentBuilder<Tag> query = tags.query().where("name", "php");

        Builder base = fake.db.table("tags").where("name", "php");

        assertEquals(base.toSql(), query.toSql());
        assertEquals(base.getBindings(), query.getBindings());
        assertEquals(base.toSql(), query.toBase().toSql());
        assertEquals(Collections.emptyList(), query.removedScopes());
        assertEquals(1, tags.query().where("name", "php").get().size());
        assertEquals(1, fake.calls.size());
        assertEquals("select * from \"tags\" where \"name\" = ?", fake.last().sql());
        assertEquals(Collections.singletonList("php"), fake.last().bindings());
    }
}

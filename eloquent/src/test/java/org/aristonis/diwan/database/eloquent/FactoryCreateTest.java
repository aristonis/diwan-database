package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.factory.Factory;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Factory create, createMany, createQuietly, hooks and connection choice against SQLite. */
class FactoryCreateTest {

    private static final String SECRET_TITLE = "a-title-that-must-not-leak";

    /** An article with an array cast, a hidden attribute, a unique slug and timestamps. */
    static final class Article extends Model {
        static final ModelQuery<Article> QUERY = ModelQuery.of(Article.class, Article::new);

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected List<String> hidden() {
            return List.of("secret");
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            return Map.of("tags", Casts.of("array"));
        }
    }

    /** A UUID-keyed model. */
    static final class Ticket extends Model {
        static final ModelQuery<Ticket> QUERY = ModelQuery.of(Ticket.class, Ticket::new);

        @Override
        protected UniqueIdKind uniqueIdKind() {
            return UniqueIdKind.UUID;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model with a key that is not generated and no timestamps, so an empty save stores nothing. */
    static final class Marker extends Model {
        static final ModelQuery<Marker> QUERY = ModelQuery.of(Marker.class, Marker::new);

        @Override
        protected boolean incrementing() {
            return false;
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    private SqliteModelDb database;

    private static void createTables(SqliteModelDb target) {
        target.sql("create table articles (id integer primary key autoincrement, title text, tags text, secret text,"
                + " slug text unique, created_at text, updated_at text)");
        target.sql("create table tickets (id text primary key, code text, created_at text, updated_at text)");
        target.sql("create table markers (id text primary key)");
    }

    @BeforeEach
    void open() throws SQLException {
        database = SqliteModelDb.open();
        createTables(database);
    }

    @AfterEach
    void close() throws SQLException {
        Article.QUERY.events().forget();
        database.close();
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private Factory<Article> articles() {
        return Factory.of(Article.QUERY.on(database.db), () -> map("title", SECRET_TITLE));
    }

    private static long count(SqliteModelDb target, String table) {
        return ((Number) target.scalar("select count(*) from " + table)).longValue();
    }

    @Test
    void createStoresOneRowAndSetsTheKey() {
        Article article = articles().create();

        assertTrue(article.exists());
        assertEquals(1L, ((Number) article.getKey()).longValue());
        assertEquals(1L, count(database, "articles"));
        assertEquals(SECRET_TITLE, database.scalar("select title from articles"));
    }

    @Test
    void createSetsTheKeyOfAUuidModel() {
        Ticket ticket = Factory.of(Ticket.QUERY.on(database.db), () -> map("code", "T-1")).create();

        assertNotNull(ticket.getKey());
        assertEquals(36, String.valueOf(ticket.getKey()).length());
        assertEquals(ticket.getKey(), database.scalar("select id from tickets"));
    }

    @Test
    void anArrayCastValueReadsBackEqualAfterReload() {
        Article created = Factory.of(Article.QUERY.on(database.db),
                () -> map("title", "t", "tags", List.of("a", "b"))).create();

        Article reloaded = Article.QUERY.on(database.db).findOrFail(created.getKey());

        assertEquals(List.of("a", "b"), reloaded.getAttribute("tags"));
    }

    @Test
    void createManyStoresThreeRows() {
        List<Article> created = articles().createMany(3);

        assertEquals(3, created.size());
        assertEquals(3L, count(database, "articles"));
        assertTrue(created.stream().allMatch(Model::exists));
    }

    @Test
    void countThenCreateManyStoresThree() {
        List<Article> created = articles().count(3).createMany();

        assertEquals(3, created.size());
        assertEquals(3L, count(database, "articles"));
    }

    @Test
    void aVetoingSavingHookMakesCreateThrow() {
        Article.QUERY.events().saving(m -> false);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> articles().create());

        assertTrue(failure.getMessage().contains("Article"), failure.getMessage());
        assertFalse(failure.getMessage().contains(SECRET_TITLE), failure.getMessage());
        assertEquals(0L, count(database, "articles"));
    }

    @Test
    void aNonIncrementingModelWithNoAttributesMakesCreateThrow() {
        Factory<Marker> markers = Factory.of(Marker.QUERY.on(database.db), LinkedHashMap::new);

        IllegalStateException failure = assertThrows(IllegalStateException.class, markers::create);

        assertTrue(failure.getMessage().contains("Marker"), failure.getMessage());
        assertEquals(0L, count(database, "markers"));
    }

    @Test
    void modelQueryCreateStillReturnsTheUnsavedModelOnAVeto() {
        Article.QUERY.events().saving(m -> false);

        Article article = Article.QUERY.on(database.db).create(map("title", "t"));

        assertFalse(article.exists());
        assertEquals(0L, count(database, "articles"));
    }

    @Test
    void aFailureFromSaveIsNotWrapped() {
        Factory<Article> sameSlug = Factory.of(Article.QUERY.on(database.db),
                () -> map("title", "t", "slug", "dup"));
        sameSlug.create();

        assertThrows(UniqueConstraintViolationException.class, sameSlug::create);
        assertEquals(1L, count(database, "articles"));
    }

    @Test
    void afterCreatingDoesNotRunWhenCreateThrows() {
        AtomicInteger runs = new AtomicInteger();
        Article.QUERY.events().saving(m -> false);

        assertThrows(IllegalStateException.class, () -> articles().afterCreating(m -> runs.incrementAndGet()).create());

        assertEquals(0, runs.get());
    }

    @Test
    void createQuietlyStoresTheRowWithoutFiringHooks() {
        AtomicInteger fired = new AtomicInteger();
        Article.QUERY.events().saving(m -> {
            fired.incrementAndGet();
            return true;
        }).created(m -> fired.incrementAndGet());

        Article article = articles().createQuietly();

        assertTrue(article.exists());
        assertEquals(1L, count(database, "articles"));
        assertEquals(0, fired.get());
    }

    @Test
    void createManyQuietlyFiresNoHookForAnyModel() {
        AtomicInteger fired = new AtomicInteger();
        Article.QUERY.events().saved(m -> fired.incrementAndGet());

        List<Article> created = articles().createManyQuietly(3);

        assertEquals(3, created.size());
        assertEquals(3L, count(database, "articles"));
        assertEquals(0, fired.get());
    }

    @Test
    void theSameHooksRunForCreate() {
        AtomicInteger fired = new AtomicInteger();
        Article.QUERY.events().saving(m -> {
            fired.incrementAndGet();
            return true;
        }).created(m -> fired.incrementAndGet());

        articles().create();

        assertEquals(2, fired.get());
    }

    @Test
    void afterMakingRunsBeforeTheSaveForCreate() {
        List<Boolean> storedWhenRun = new ArrayList<>();

        articles().afterMaking(m -> storedWhenRun.add(m.exists())).create();

        assertEquals(List.of(false), storedWhenRun);
    }

    @Test
    void afterCreatingRunsAfterTheSaveWithTheKeySet() {
        List<Object> keys = new ArrayList<>();

        Article article = articles().afterCreating(m -> keys.add(m.getKey())).create();

        assertEquals(1, keys.size());
        assertNotNull(keys.get(0));
        assertEquals(article.getKey(), keys.get(0));
    }

    @Test
    void afterCreatingHooksRunInOrderAndOncePerModel() {
        List<String> calls = new ArrayList<>();

        articles().afterMaking(m -> calls.add("making-1")).afterMaking(m -> calls.add("making-2"))
                .afterCreating(m -> calls.add("creating-1")).afterCreating(m -> calls.add("creating-2"))
                .createMany(2);

        assertEquals(List.of("making-1", "making-2", "creating-1", "creating-2", "making-1", "making-2",
                "creating-1", "creating-2"), calls);
    }

    @Test
    void connectionSelectsTheSecondDatabase() throws SQLException {
        try (SqliteModelDb second = SqliteModelDb.open()) {
            createTables(second);

            articles().connection(second.connection).create();

            assertEquals(1L, count(second, "articles"));
            assertEquals(0L, count(database, "articles"));
        }
    }

    @Test
    void withoutConnectionTheDefaultIsUsed() throws SQLException {
        try (SqliteModelDb second = SqliteModelDb.open()) {
            createTables(second);

            articles().create();

            assertEquals(1L, count(database, "articles"));
            assertEquals(0L, count(second, "articles"));
        }
    }

    @Test
    void connectionAppliesToCreateManyAndInsert() throws SQLException {
        try (SqliteModelDb second = SqliteModelDb.open()) {
            createTables(second);

            Factory<Article> onSecond = articles().connection(second.connection);
            onSecond.createMany(2);
            onSecond.insert(2);

            assertEquals(4L, count(second, "articles"));
            assertEquals(0L, count(database, "articles"));
        }
    }
}

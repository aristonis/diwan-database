package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.UniqueConstraintViolationException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Country;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The find-or-create family on a has-many-through relation: lookups name the related table, created
 * rows are not linked to the far parent, and a unique violation resolves to the row already in the
 * relation. Each test opens its own seeded in-memory database.
 */
class ThroughCreatorsTest {

    private static final String TITLE = "title";
    private static final String COUNT_POSTS = "select count(*) from posts";
    private static final String UNIQUE_TITLES = "create unique index posts_title_unique on posts (title)";

    private ThroughDb db;

    /**
     * Opens a seeded database for one test.
     *
     * @throws SQLException if the database cannot be opened
     */
    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    /**
     * Closes the database of one test.
     *
     * @throws SQLException if the connection cannot be closed
     */
    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void firstOrNewFindsByQualifiedColumn() {
        // Arrange
        Country country = country(1);
        db.log.clear();

        // Act
        Post found = Model.unguarded(() -> country.posts().firstOrNew(attrs(TITLE, "Post 2")));

        // Assert
        assertTrue(found.exists());
        assertEquals(2L, ((Number) found.getKey()).longValue());
        assertEquals(1, db.log.sql().size());
        assertTrue(db.log.sql().get(0).contains("\"posts\".\"title\" = ?"), db.log.sql().get(0));
    }

    @Test
    void firstOrNewBuildsAnUnsavedModel() {
        // Arrange
        Country country = country(1);

        // Act
        Post made = Model.unguarded(() -> country.posts().firstOrNew(attrs(TITLE, "Fresh"), attrs("person_id", 9)));

        // Assert
        assertFalse(made.exists());
        assertNull(made.getKey());
        assertEquals("Fresh", made.getAttribute(TITLE));
        assertEquals(9, ((Number) made.getAttribute("person_id")).intValue());
        assertEquals(6, count(COUNT_POSTS));
    }

    @Test
    void firstOrCreateSavesAnUnlinkedRow() {
        // Arrange
        Country country = country(1);

        // Act
        Post created = Model.unguarded(() -> country.posts().firstOrCreate(attrs(TITLE, "Fresh")));

        // Assert
        assertTrue(created.exists());
        assertTrue(created.wasRecentlyCreated());
        assertEquals(7, count(COUNT_POSTS));
        assertEquals(1, count("select count(*) from posts where title = 'Fresh' and user_id is null"));
        assertEquals(3, country.posts().get().size());
    }

    @Test
    void repeatedFirstOrCreateWithoutKeyInsertsAgain() {
        // Arrange
        Country country = country(1);

        // Act
        Model.unguarded(() -> country.posts().firstOrCreate(attrs(TITLE, "Twice")));
        Model.unguarded(() -> country.posts().firstOrCreate(attrs(TITLE, "Twice")));

        // Assert
        assertEquals(2, count("select count(*) from posts where title = 'Twice'"));
    }

    @Test
    void firstOrCreateWithThroughKeyIsFoundAgain() {
        // Arrange
        Country country = country(1);
        Map<String, Object> attributes = attrs(TITLE, "Linked", "user_id", 1);

        // Act
        Post first = Model.unguarded(() -> country.posts().firstOrCreate(attributes));
        Post second = Model.unguarded(() -> country.posts().firstOrCreate(attributes));

        // Assert
        assertTrue(first.wasRecentlyCreated());
        assertFalse(second.wasRecentlyCreated());
        assertEquals(((Number) first.getKey()).longValue(), ((Number) second.getKey()).longValue());
        assertEquals(1, count("select count(*) from posts where title = 'Linked'"));
        assertEquals(4, country.posts().get().size());
    }

    @Test
    void createOrFirstReturnsTheRowOnUniqueViolation() {
        // Arrange
        db.real.statement(UNIQUE_TITLES);
        Country country = country(1);

        // Act
        Post existing = Model.unguarded(() -> country.posts().createOrFirst(attrs(TITLE, "Post 1")));

        // Assert
        assertTrue(existing.exists());
        assertFalse(existing.wasRecentlyCreated());
        assertEquals(1L, ((Number) existing.getKey()).longValue());
        assertEquals(6, count(COUNT_POSTS));
    }

    @Test
    void createOrFirstRethrowsWhenNotInRelation() {
        // Arrange
        db.real.statement(UNIQUE_TITLES);
        Country country = country(1);

        // Act and assert: "Post 4" belongs to a user of another country
        assertThrows(UniqueConstraintViolationException.class,
                () -> Model.unguarded(() -> country.posts().createOrFirst(attrs(TITLE, "Post 4"))));
        assertEquals(6, count(COUNT_POSTS));
    }

    @Test
    void createOrFirstUsesASavepointInsideATransaction() {
        // Arrange
        AtomicInteger outside = new AtomicInteger();
        AtomicInteger inside = new AtomicInteger();

        // Act
        Country outer = country(1, counting(db.real, outside));
        Model.unguarded(() -> outer.posts().createOrFirst(attrs(TITLE, "Outer")));
        db.real.transaction(tx -> {
            Country country = country(1, counting(tx, inside));
            return Model.unguarded(() -> country.posts().createOrFirst(attrs(TITLE, "Inner")));
        });

        // Assert
        assertEquals(0, outside.get());
        assertEquals(1, inside.get());
        assertEquals(1, count("select count(*) from posts where title = 'Outer'"));
        assertEquals(1, count("select count(*) from posts where title = 'Inner'"));
    }

    @Test
    void updateOrCreateSavesOnlyExistingRows() {
        // Arrange
        Country country = country(1);

        // Act
        Post updated = Model.unguarded(() -> country.posts().updateOrCreate(attrs(TITLE, "Post 1"), attrs("person_id", 8)));
        List<String> afterUpdate = db.log.writes();
        db.log.clear();
        Post created = Model.unguarded(() -> country.posts().updateOrCreate(attrs(TITLE, "Brand new"), attrs("person_id", 7)));

        // Assert
        assertFalse(updated.wasRecentlyCreated());
        assertEquals(1, afterUpdate.size());
        assertTrue(afterUpdate.get(0).startsWith("update \"posts\""), afterUpdate.get(0));
        assertEquals(1, count("select count(*) from posts where id = 1 and person_id = 8"));
        assertTrue(created.wasRecentlyCreated());
        for (String write : db.log.writes()) {
            assertFalse(write.startsWith("update"), write);
        }
        assertEquals(1, count("select count(*) from posts where title = 'Brand new' and person_id = 7"));
    }

    @Test
    void findOrNewReturnsNewModelOnMiss() {
        // Arrange
        Country country = country(1);

        // Act
        Post missing = country.posts().findOrNew(999);
        Post present = country.posts().findOrNew(1);
        Post outside = country.posts().findOrNew(4);

        // Assert
        assertFalse(missing.exists());
        assertNull(missing.getKey());
        assertTrue(present.exists());
        assertFalse(outside.exists());
        assertEquals(6, count(COUNT_POSTS));
    }

    private Country country(int id) {
        return Country.QUERY.on(db.db).findOrFail(id);
    }

    private static Country country(int id, Connection connection) {
        return Country.QUERY.on(SQLiteQuery.of(connection)).findOrFail(id);
    }

    private int count(String sql) {
        List<Map<String, Object>> rows = db.real.select(sql);
        return ((Number) rows.get(0).values().iterator().next()).intValue();
    }

    private static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    // A connection that forwards everything and counts the transaction calls, one per savepoint.
    private static Connection counting(Connection target, AtomicInteger transactions) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("transaction".equals(method.getName())) {
                        transactions.incrementAndGet();
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}

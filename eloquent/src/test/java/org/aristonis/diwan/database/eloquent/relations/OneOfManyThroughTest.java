package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Country;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Order;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One-of-many over a has-one-through relation on SQLite: countries reach the latest order of their
 * users, with ties, soft-deleted users, eager loading and existence queries.
 */
class OneOfManyThroughTest {

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
        db.country(1, "France");
        db.country(2, "Japan");
        db.country(3, "Peru");
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static String day(int day) {
        return OneOfManyModels.day(day);
    }

    private static Long keyOf(org.aristonis.diwan.database.eloquent.Model model) {
        return OneOfManyModels.key(model);
    }

    private void seedOrders() {
        db.user(1L, 1L, "Ann");
        db.user(2L, 1L, "Ben");
        db.user(3L, 2L, "Cleo");
        db.order(1, 1, day(1), 500, "paid");
        db.order(2, 2, day(3), 10, "paid");
        db.order(3, 1, day(2), 20, "paid");
        db.order(4, 3, day(1), 300, "paid");
    }

    @Test
    void throughPicksLatest() {
        // Arrange
        seedOrders();
        Country france = Country.QUERY.on(db.db).findOrFail(1);
        Country japan = Country.QUERY.on(db.db).findOrFail(2);
        Country peru = Country.QUERY.on(db.db).findOrFail(3);

        // Act
        Order frenchOrder = france.latestOrder().getResults();
        Order japaneseOrder = japan.latestOrder().getResults();
        Order none = peru.latestOrder().getResults();

        // Assert
        assertEquals(3L, keyOf(frenchOrder));
        assertEquals(4L, keyOf(japaneseOrder));
        assertNull(none);
    }

    @Test
    void throughOldestPicksTheEarliestOfAnyUser() {
        // Arrange
        seedOrders();
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        // Act
        Order oldest = france.oldestOrder().getResults();

        // Assert
        assertEquals(1L, keyOf(oldest));
    }

    @Test
    void throughTieAcrossUsersTakesTheHigherKeyForTheLatest() {
        // Arrange
        db.user(1L, 1L, "Ann");
        db.user(2L, 1L, "Ben");
        db.order(5, 1, day(4), 10, "paid");
        db.order(6, 2, day(4), 10, "paid");
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        // Act
        Order latest = france.latestOrder().getResults();
        Order oldest = france.oldestOrder().getResults();

        // Assert
        assertEquals(6L, keyOf(latest));
        assertEquals(5L, keyOf(oldest));
    }

    @Test
    void throughEagerPicksPerCountry() {
        // Arrange
        seedOrders();

        // Act
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();

        // Assert
        assertEquals(1, db.readsOn("orders").size(), db.queries().toString());
        assertEquals(3L, keyOf(countries.get(0).<Order>related("latestOrder")));
        assertEquals(4L, keyOf(countries.get(1).<Order>related("latestOrder")));
        assertNull(countries.get(2).<Order>related("latestOrder"));
    }

    @Test
    void softDeletedThroughParentIsSkippedLazily() {
        // Arrange: Ben's order is the newest of France, but Ben is trashed
        db.user(1L, 1L, "Ann");
        db.trashedUser(2L, 1L, "Ben");
        db.order(1, 1, day(1), 500, "paid");
        db.order(2, 2, day(3), 10, "paid");
        db.order(3, 1, day(2), 20, "paid");
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        // Act
        Order latest = france.latestOrder().getResults();
        Order oldest = france.oldestOrder().getResults();

        // Assert
        assertEquals(3L, keyOf(latest));
        assertEquals(1L, keyOf(oldest));
    }

    @Test
    void softDeletedThroughParentIsSkippedEagerly() {
        // Arrange
        db.user(1L, 1L, "Ann");
        db.trashedUser(2L, 1L, "Ben");
        db.order(1, 1, day(1), 500, "paid");
        db.order(2, 2, day(3), 10, "paid");
        db.order(3, 1, day(2), 20, "paid");

        // Act
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();

        // Assert
        assertEquals(3L, keyOf(countries.get(0).<Order>related("latestOrder")));
    }

    @Test
    void countryWhoseOnlyUserIsTrashedHasNoOrder() {
        // Arrange
        db.trashedUser(1L, 1L, "Ann");
        db.order(1, 1, day(1), 500, "paid");
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        // Act
        Order latest = france.latestOrder().getResults();

        // Assert
        assertNull(latest);
    }

    @Test
    void whereHasOnAThroughRelationJudgesThePickedRow() {
        // Arrange: France's latest order (highest key, 3) is cheap although it also has an expensive one
        seedOrders();

        // Act
        ModelCollection<Country> expensive = Country.QUERY.on(db.db).query()
                .whereHas("latestOrder", q -> q.where("orders.price", ">", 100)).orderBy("id").get();
        ModelCollection<Country> has = Country.QUERY.on(db.db).query().has("latestOrder").orderBy("id").get();
        ModelCollection<Country> hasNot = Country.QUERY.on(db.db).query().doesntHave("latestOrder").get();

        // Assert
        assertEquals(Collections.singletonList(2L), OneOfManyModels.keys(expensive));
        assertEquals(java.util.Arrays.asList(1L, 2L), OneOfManyModels.keys(has));
        assertEquals(Collections.singletonList(3L), OneOfManyModels.keys(hasNot));
    }

    @Test
    void whereHasOnAThroughRelationSkipsTrashedThroughParents() {
        // Arrange
        db.trashedUser(1L, 1L, "Ann");
        db.order(1, 1, day(1), 500, "paid");

        // Act
        ModelCollection<Country> has = Country.QUERY.on(db.db).query().has("latestOrder").get();

        // Assert
        assertEquals(Collections.emptyList(), OneOfManyModels.keys(has));
    }

    @Test
    void withCountOnAThroughRelationIsZeroOrOne() {
        // Arrange
        seedOrders();

        // Act
        ModelCollection<Country> countries = Country.QUERY.on(db.db).query().withCount("latestOrder")
                .orderBy("id").get();

        // Assert
        assertEquals(1, ((Number) countries.get(0).getAttribute("latest_order_count")).intValue());
        assertEquals(1, ((Number) countries.get(1).getAttribute("latest_order_count")).intValue());
        assertEquals(0, ((Number) countries.get(2).getAttribute("latest_order_count")).intValue());
    }

    @Test
    void whereHasWhoseOuterTableIsTheThroughTableJudgesThePickedRow() {
        // Arrange: each user reaches its own orders through the users table; user 4 has none
        seedOrders();
        db.user(4L, 3L, "Dan");

        // Act
        ModelCollection<User> expensive = User.QUERY.on(db.db).query()
                .whereHas("latestOrderThroughSelf", q -> q.where("orders.price", ">", 100)).orderBy("id").get();
        ModelCollection<User> has = User.QUERY.on(db.db).query().has("latestOrderThroughSelf").orderBy("id").get();
        ModelCollection<User> hasNot = User.QUERY.on(db.db).query().doesntHave("latestOrderThroughSelf").get();

        // Assert: user 1's latest order (3) is cheap although order 1 is dear
        assertEquals(Collections.singletonList(3L), OneOfManyModels.keys(expensive));
        assertEquals(java.util.Arrays.asList(1L, 2L, 3L), OneOfManyModels.keys(has));
        assertEquals(Collections.singletonList(4L), OneOfManyModels.keys(hasNot));
    }
}

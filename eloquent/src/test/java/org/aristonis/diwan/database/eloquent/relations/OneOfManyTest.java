package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Country;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Order;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.PlainOrder;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lazy read of a one-of-many relation on SQLite: which row is picked on ties, with several
 * columns, constraints and scopes, the SQL of the read, the aliases and the entry from a many relation.
 */
class OneOfManyTest {

    private static final String LAZY_SQL = "select \"orders\".* from \"orders\" inner join ("
            + "select max(\"orders\".\"id\") as \"id_aggregate\", \"orders\".\"user_id\" from \"orders\""
            + " where \"orders\".\"user_id\" = ? group by \"orders\".\"user_id\") as \"latest_order\""
            + " on \"latest_order\".\"id_aggregate\" = \"orders\".\"id\""
            + " and \"latest_order\".\"user_id\" = \"orders\".\"user_id\""
            + " where \"orders\".\"user_id\" = ? and \"orders\".\"user_id\" is not null limit 1";

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(long id) {
        User user = User.QUERY.on(db.db).findOrFail(id);
        db.clearLog();
        return user;
    }

    private static Long keyOf(org.aristonis.diwan.database.eloquent.Model model) {
        return OneOfManyModels.key(model);
    }

    private static String day(int day) {
        return OneOfManyModels.day(day);
    }

    private static List<String> texts(List<Object> bindings) {
        List<String> texts = new ArrayList<>();
        for (Object binding : bindings) {
            texts.add(String.valueOf(binding));
        }
        return texts;
    }

    @Test
    void tieBrokenByKey() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(5), 10, "paid");
        db.order(2, 1, day(5), 10, "paid");
        db.order(3, 1, day(2), 10, "paid");
        User ann = user(1);

        // Act
        Order first = ann.latestByCreated().getResults();
        Order second = ann.latestByCreated().getResults();

        // Assert
        assertEquals(2L, keyOf(first));
        assertEquals(2L, keyOf(second));
    }

    @Test
    void oldestTieTakesLowerKey() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(5, 1, day(1), 10, "paid");
        db.order(6, 1, day(1), 10, "paid");
        db.order(7, 1, day(3), 10, "paid");
        User ann = user(1);

        // Act
        Order oldest = ann.oldestByCreated().getResults();

        // Assert
        assertEquals(5L, keyOf(oldest));
    }

    @Test
    void noRowsGivesNullOrDefault() {
        // Arrange
        db.user(4L, null, "Dan");
        User dan = user(4);

        // Act
        Order none = dan.latestOrder().getResults();
        Order fallback = dan.latestOrDefault().getResults();

        // Assert
        assertNull(none);
        assertFalse(fallback.exists());
        assertEquals("none", fallback.getAttribute("status"));
        assertEquals(4L, ((Number) fallback.getAttribute("user_id")).longValue());
    }

    @Test
    void lazySqlShape() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 20, "paid");
        User ann = user(1);

        // Act
        PlainOrder found = ann.namedLatest().getResults();

        // Assert
        assertEquals(2L, keyOf(found));
        assertEquals(1, db.queries().size(), db.queries().toString());
        assertEquals(LAZY_SQL, db.queries().get(0).sql());
        assertEquals(Arrays.asList("1", "1"), texts(db.queries().get(0).bindings()));
    }

    @Test
    void multiColumnChain() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(9), 50, "paid");
        db.order(2, 1, day(1), 90, "paid");
        db.order(3, 1, day(1), 90, "paid");
        db.order(4, 1, day(5), 70, "paid");
        User ann = user(1);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("price", "max");
        columns.put("id", "max");

        // Act
        Order picked = ann.ofManyOrder(columns).getResults();

        // Assert
        assertEquals(3L, keyOf(picked));
    }

    @Test
    void latestOfManyWithSeveralColumnsOrdersByTheFirstThenTheNext() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 90, "paid");
        db.order(2, 1, day(2), 90, "paid");
        db.order(3, 1, day(9), 40, "paid");
        User ann = user(1);

        // Act
        Order picked = ann.latestByPriceThenDay().getResults();

        // Assert
        assertEquals(2L, keyOf(picked));
    }

    @Test
    void mixedMapPicksTheLowestPriceThenTheLatestDay() {
        // Arrange: min price first, so the key is the lowest among the full ties
        db.user(1L, null, "Ann");
        db.order(1, 1, day(4), 10, "paid");
        db.order(2, 1, day(4), 10, "paid");
        db.order(3, 1, day(4), 10, "paid");
        db.order(4, 1, day(7), 10, "paid");
        db.order(5, 1, day(9), 30, "paid");
        User ann = user(1);

        // Act
        Order picked = ann.mixedMapOrder().getResults();

        // Assert: price 10 is min, day 7 is the latest of those, only order 4 is left
        assertEquals(4L, keyOf(picked));
    }

    @Test
    void mixedMapFullTieTakesTheLowestKeyBecauseTheFirstEntryIsMin() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(8, 1, day(7), 10, "paid");
        db.order(9, 1, day(7), 10, "paid");
        db.order(3, 1, day(1), 99, "paid");
        User ann = user(1);

        // Act
        Order picked = ann.mixedMapOrder().getResults();

        // Assert
        assertEquals(8L, keyOf(picked));
    }

    @Test
    void invalidAggregateRefused() {
        // Arrange
        db.user(1L, null, "Ann");
        User ann = user(1);

        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ann.ofManyOrder("price", "avg").getResults());

        // Assert
        assertTrue(error.getMessage().contains("avg"), error.getMessage());
        assertTrue(error.getMessage().contains("MIN, MAX"), error.getMessage());
    }

    @Test
    void invalidAggregateInAMapRefused() {
        // Arrange
        db.user(1L, null, "Ann");
        User ann = user(1);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("price", "max");
        columns.put("created_at", "sum");

        // Act
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ann.ofManyOrder(columns).getResults());

        // Assert
        assertTrue(error.getMessage().contains("sum"), error.getMessage());
    }

    @Test
    void aggregateNameIsCaseInsensitive() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(1), 80, "paid");
        User ann = user(1);

        // Act
        Order picked = ann.ofManyOrder("price", "MAX").getResults();

        // Assert
        assertEquals(2L, keyOf(picked));
    }

    @Test
    void constraintAppliesInSubquery() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "paid");
        db.order(3, 1, day(9), 10, "open");
        User ann = user(1);

        // Act
        Order picked = ann.latestPaidOrder().getResults();

        // Assert
        assertEquals(2L, keyOf(picked));
    }

    @Test
    void scopesApplyInsideSubquery() {
        // Arrange: the newest order is trashed
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "paid");
        db.trashedOrder(3, 1, day(3), 10, "paid");
        User ann = user(1);

        // Act
        Order picked = ann.latestOrder().getResults();

        // Assert
        assertEquals(2L, keyOf(picked));
    }

    @Test
    void relationWhereFiltersOnlyThePickedRow() {
        // Arrange: the latest order is open, an older one is paid
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "open");
        User ann = user(1);

        // Act
        Order paid = ann.latestOrder().where("status", "=", "paid").getResults();
        Order open = ann.latestOrder().where("status", "=", "open").getResults();

        // Assert
        assertNull(paid);
        assertEquals(2L, keyOf(open));
    }

    @Test
    void withTrashedAfterOfManyLetsTheTrashedRowWin() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.trashedOrder(2, 1, day(2), 10, "paid");
        User ann = user(1);

        // Act
        Order live = ann.latestOrder().getResults();
        Order lifted = ann.latestOrder().withTrashed().getResults();

        // Assert
        assertEquals(1L, keyOf(live));
        assertEquals(2L, keyOf(lifted));
    }

    @Test
    void withoutGlobalScopeAfterOfManyLetsTheTrashedRowWin() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        db.trashedOrder(2, 1, day(2), 10, "paid");
        User ann = user(1);

        // Act
        Order lifted = ann.latestOrder().withoutGlobalScope(SoftDeletingScope.NAME).getResults();

        // Assert
        assertEquals(2L, keyOf(lifted));
    }

    @Test
    void oneFromManyRelations() {
        // Arrange
        db.country(1, "France");
        db.user(1L, 1L, "Ann");
        db.user(2L, 1L, "Ben");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "paid");
        db.order(3, 2, day(3), 10, "paid");
        User ann = user(1);
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        // Act
        Order viaMany = ann.latestOrderViaMany().getResults();
        Order direct = ann.latestOrder().getResults();
        Order throughMany = france.latestOrderViaMany().getResults();

        // Assert
        assertEquals(2L, keyOf(viaMany));
        assertEquals(keyOf(direct), keyOf(viaMany));
        assertEquals(3L, keyOf(throughMany));
    }

    @Test
    void oneFromManyKeepsAnEagerLoadWorking() {
        // Arrange
        db.user(1L, null, "Ann");
        db.user(2L, null, "Ben");
        db.order(1, 1, day(1), 10, "paid");
        db.order(2, 1, day(2), 10, "paid");
        db.order(3, 2, day(1), 10, "paid");

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestOrderViaMany").orderBy("id").get();

        // Assert
        assertEquals(2L, keyOf(users.get(0).<Order>related("latestOrderViaMany")));
        assertEquals(3L, keyOf(users.get(1).<Order>related("latestOrderViaMany")));
    }

    @Test
    void aliasRules() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");
        User ann = user(1);

        // Act
        ann.latestOrder().getResults();
        String unnamed = db.queries().get(0).sql();
        db.clearLog();
        ann.namedLatest().getResults();
        String explicit = db.queries().get(0).sql();
        db.clearLog();
        ann.tableNamedLatest().getResults();
        String clashRelated = db.queries().get(0).sql();
        db.clearLog();
        ann.parentNamedLatest().getResults();
        String clashParent = db.queries().get(0).sql();

        // Assert
        assertTrue(unnamed.contains("as \"orders_of_many\""), unnamed);
        assertTrue(explicit.contains("as \"latest_order\""), explicit);
        assertTrue(clashRelated.contains("as \"orders_of_many\""), clashRelated);
        assertTrue(clashParent.contains("as \"users_of_many\""), clashParent);
    }

    @Test
    void relationNameIsTheAliasWhenTheRelationIsLoadedByName() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 10, "paid");

        // Act
        User.QUERY.on(db.db).query().with("latestOrder", "priciestOrder").get();

        // Assert
        List<String> orderReads = new ArrayList<>();
        for (OneOfManyModels.Db.Query read : db.readsOn("orders")) {
            orderReads.add(read.sql());
        }
        assertEquals(2, orderReads.size(), orderReads.toString());
        assertTrue(orderReads.get(0).contains("as \"latestOrder\""), orderReads.get(0));
        assertTrue(orderReads.get(1).contains("as \"priciestOrder\""), orderReads.get(1));
    }

    @Test
    void isOneOfManyReportsTheMode() {
        // Arrange
        db.user(1L, null, "Ann");
        User ann = user(1);

        // Act
        boolean picked = ann.latestOrder().isOneOfMany();
        boolean plain = ann.plainOrders().one().isOneOfMany();

        // Assert
        assertTrue(picked);
        assertFalse(plain);
    }

    @Test
    void priciestAndCheapestPickOppositeRows() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 30, "paid");
        db.order(2, 1, day(2), 90, "paid");
        db.order(3, 1, day(3), 10, "paid");
        User ann = user(1);

        // Act
        Order priciest = ann.priciestOrder().getResults();
        Order cheapest = ann.cheapestOrder().getResults();

        // Assert
        assertEquals(2L, keyOf(priciest));
        assertEquals(3L, keyOf(cheapest));
    }

    @Test
    void nullParentKeyRunsNoQuery() {
        // Arrange
        User unsaved = User.QUERY.on(db.db).newModel();
        db.clearLog();

        // Act
        Order found = unsaved.latestOrder().getResults();

        // Assert
        assertNull(found);
        assertEquals(Collections.emptyList(), db.queries());
    }
}

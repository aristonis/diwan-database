package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Country;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nested existence queries on SQLite: a dotted path that ends in a one-of-many relation, and a
 * one-of-many existence inside another one-of-many existence.
 */
class OneOfManyNestedTest {

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static String day(int day) {
        return OneOfManyModels.day(day);
    }

    private void seedCountries() {
        db.country(1, "France");
        db.country(2, "Japan");
        db.country(3, "Peru");
        db.user(1L, 1L, "Ann");
        db.user(2L, 1L, "Ben");
        db.user(3L, 2L, "Cleo");
        db.order(1, 1, day(1), 500, "paid");
        db.order(2, 2, day(3), 10, "paid");
        db.order(3, 1, day(2), 20, "paid");
        db.order(4, 3, day(1), 300, "paid");
    }

    @Test
    void whereHasThroughARelationChainJudgesThePickedRow() {
        // Arrange: France's users pick the cheap orders 3 and 2; Japan's user picks the dear order 4
        seedCountries();

        // Act
        ModelCollection<Country> expensive = Country.QUERY.on(db.db).query()
                .whereHas("users.latestOrder", q -> q.where("orders.price", ">", 100)).orderBy("id").get();
        ModelCollection<Country> any = Country.QUERY.on(db.db).query().has("users.latestOrder").orderBy("id").get();

        // Assert
        assertEquals(Collections.singletonList(2L), OneOfManyModels.keys(expensive));
        assertEquals(Arrays.asList(1L, 2L), OneOfManyModels.keys(any));
    }

    @Test
    void whereHasThroughARelationChainSkipsUsersWithoutOrders() {
        // Arrange
        seedCountries();
        db.user(4L, 3L, "Dan");

        // Act
        ModelCollection<Country> hasNot = Country.QUERY.on(db.db).query().doesntHave("users.latestOrder")
                .get();

        // Assert
        assertEquals(Collections.singletonList(3L), OneOfManyModels.keys(hasNot));
    }

    @Test
    void oneOfManyExistenceInsideAnotherOneOfManyExistenceJudgesBothPickedRows() {
        // Arrange: 1 has the children 2 and 3 (3 is newer); 3 has the children 5 and 6 (5 is newer)
        db.node(1, null, day(1));
        db.node(2, 1L, day(2));
        db.node(3, 1L, day(3));
        db.node(4, 2L, day(4));
        db.node(5, 3L, day(5));
        db.node(6, 3L, day(1));

        // Act
        ModelCollection<Node> picked = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.whereHas("latestChild", r -> r.where("id", "=", 5))).get();
        ModelCollection<Node> notPicked = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.whereHas("latestChild", r -> r.where("id", "=", 6))).get();
        ModelCollection<Node> any = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.has("latestChild")).orderBy("id").get();

        // Assert: the older child 6 of node 3 is never judged
        assertEquals(Collections.singletonList(1L), OneOfManyModels.keys(picked));
        assertEquals(Collections.emptyList(), OneOfManyModels.keys(notPicked));
        assertEquals(Arrays.asList(1L), OneOfManyModels.keys(any));
    }
}

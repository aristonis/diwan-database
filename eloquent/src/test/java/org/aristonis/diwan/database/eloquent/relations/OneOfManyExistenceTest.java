package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Node;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existence queries over one-of-many relations on SQLite: has, whereHas and doesntHave judge only
 * the picked row, counts are zero or one, and a self relation joins on its aliased table.
 */
class OneOfManyExistenceTest {

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

    private static List<Long> keys(Iterable<? extends org.aristonis.diwan.database.eloquent.Model> models) {
        return OneOfManyModels.keys(models);
    }

    private void seedOrders() {
        db.user(1L, null, "Ann");
        db.user(2L, null, "Ben");
        db.user(3L, null, "Cleo");
        db.order(1, 1, day(1), 500, "paid");
        db.order(2, 1, day(2), 400, "paid");
        db.order(3, 1, day(3), 50, "paid");
        db.order(4, 2, day(1), 200, "paid");
    }

    @Test
    void whereHasUsesPickedRow() {
        // Arrange: Ann's latest order (highest key) is cheap although older ones are expensive
        seedOrders();

        // Act
        ModelCollection<User> expensive = User.QUERY.on(db.db).query()
                .whereHas("latestOrder", q -> q.where("orders.price", ">", 100)).orderBy("id").get();

        // Assert
        assertEquals(Collections.singletonList(2L), keys(expensive));
    }

    @Test
    void whereHasWithoutConstraintKeepsParentsWithAnyOrder() {
        // Arrange
        seedOrders();

        // Act
        ModelCollection<User> has = User.QUERY.on(db.db).query().has("latestOrder").orderBy("id").get();
        ModelCollection<User> hasNot = User.QUERY.on(db.db).query().doesntHave("latestOrder").get();

        // Assert
        assertEquals(Arrays.asList(1L, 2L), keys(has));
        assertEquals(Collections.singletonList(3L), keys(hasNot));
    }

    @Test
    void whereHasSkipsATrashedNewestRowAndCanLiftIt() {
        // Arrange
        db.user(1L, null, "Ann");
        db.order(1, 1, day(1), 500, "paid");
        db.trashedOrder(2, 1, day(2), 5, "paid");

        // Act
        ModelCollection<User> live = User.QUERY.on(db.db).query()
                .whereHas("latestOrder", q -> q.where("orders.price", ">", 100)).get();
        ModelCollection<User> lifted = User.QUERY.on(db.db).query()
                .whereHas("latestOrder", q -> q.withTrashed().where("orders.price", ">", 100)).get();

        // Assert
        assertEquals(Collections.singletonList(1L), keys(live));
        assertEquals(Collections.emptyList(), keys(lifted));
    }

    @Test
    void withCountIsZeroOrOne() {
        // Arrange
        seedOrders();

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().withCount("latestOrder").orderBy("id").get();

        // Assert: Ann has three orders and still counts one
        assertEquals(1, ((Number) users.get(0).getAttribute("latest_order_count")).intValue());
        assertEquals(1, ((Number) users.get(1).getAttribute("latest_order_count")).intValue());
        assertEquals(0, ((Number) users.get(2).getAttribute("latest_order_count")).intValue());
    }

    @Test
    void withCountConstraintJudgesThePickedRow() {
        // Arrange
        seedOrders();
        Map<String, java.util.function.Consumer<org.aristonis.diwan.database.eloquent.EloquentBuilder<?>>> cheap =
                Collections.singletonMap("latestOrder", q -> q.where("orders.price", "<", 100));

        // Act
        ModelCollection<User> users = User.QUERY.on(db.db).query().withCount(cheap).orderBy("id").get();

        // Assert
        assertEquals(1, ((Number) users.get(0).getAttribute("latest_order_count")).intValue());
        assertEquals(0, ((Number) users.get(1).getAttribute("latest_order_count")).intValue());
    }

    @Test
    void whereHasOnASelfRelationJoinsOnTheAliasedTable() {
        // Arrange: 1 is the root; 2 and 3 are its children (3 is newer); 4 is a child of 2
        db.node(1, null, day(1));
        db.node(2, 1L, day(2));
        db.node(3, 1L, day(3));
        db.node(4, 2L, day(4));

        // Act
        ModelCollection<Node> latestIsFour = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.where("id", "=", 4)).get();
        ModelCollection<Node> latestIsThree = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.where("id", "=", 3)).get();
        ModelCollection<Node> any = Node.QUERY.on(db.db).query().has("latestChild").orderBy("id").get();
        ModelCollection<Node> leaves = Node.QUERY.on(db.db).query().doesntHave("latestChild").orderBy("id").get();

        // Assert
        assertEquals(Collections.singletonList(2L), keys(latestIsFour));
        assertEquals(Collections.singletonList(1L), keys(latestIsThree));
        assertEquals(Arrays.asList(1L, 2L), keys(any));
        assertEquals(Arrays.asList(3L, 4L), keys(leaves));
    }

    @Test
    void withCountOnASelfRelationCountsThePickedChild() {
        // Arrange
        db.node(1, null, day(1));
        db.node(2, 1L, day(2));
        db.node(3, 1L, day(3));

        // Act
        ModelCollection<Node> nodes = Node.QUERY.on(db.db).query().withCount("latestChild").orderBy("id").get();

        // Assert
        assertEquals(1, ((Number) nodes.get(0).getAttribute("latest_child_count")).intValue());
        assertEquals(0, ((Number) nodes.get(1).getAttribute("latest_child_count")).intValue());
    }
}

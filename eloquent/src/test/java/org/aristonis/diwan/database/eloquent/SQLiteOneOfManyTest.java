package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Country;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Image;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Node;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Order;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One-of-many relations on SQLite: lazy, eager, existence, count, self, through and morph reads.
 */
class SQLiteOneOfManyTest {

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
        OneOfManyModels.mapAliases();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static String day(int day) {
        return OneOfManyModels.day(day);
    }

    private void seed() {
        db.user(1L, 1L, "Ann");
        db.user(2L, 1L, "Ben");
        db.user(3L, 2L, "Cleo");
        db.country(1, "France");
        db.country(2, "Japan");
        db.order(1, 1, day(5), 500, "paid");
        db.order(2, 1, day(5), 50, "paid");
        db.order(3, 1, day(2), 400, "paid");
        db.trashedOrder(4, 1, day(9), 10, "paid");
        db.order(5, 2, day(1), 200, "paid");
    }

    @Test
    void lazyPicksTheLatestRowAndBreaksTiesByKey() {
        seed();
        User ann = User.QUERY.on(db.db).findOrFail(1);

        Order latest = ann.latestByCreated().getResults();
        Order oldest = ann.oldestByCreated().getResults();
        Order cheapest = ann.cheapestOrder().getResults();

        assertEquals(2L, OneOfManyModels.key(latest));
        assertEquals(3L, OneOfManyModels.key(oldest));
        assertEquals(2L, OneOfManyModels.key(cheapest));
        assertNull(User.QUERY.on(db.db).findOrFail(3).latestOrder().getResults());
    }

    @Test
    void eagerPicksOneRowPerParentInOneStatement() {
        seed();

        ModelCollection<User> users = User.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();

        assertEquals(1, db.readsOn("orders").size());
        assertEquals(3L, OneOfManyModels.key(users.get(0).<Order>related("latestOrder")));
        assertEquals(5L, OneOfManyModels.key(users.get(1).<Order>related("latestOrder")));
        assertNull(users.get(2).<Order>related("latestOrder"));
    }

    @Test
    void trashedNewestRowCanBeLiftedLazilyAndEagerly() {
        seed();
        User ann = User.QUERY.on(db.db).findOrFail(1);

        Order lazy = ann.latestOrder().withTrashed().getResults();
        ModelCollection<User> eager = User.QUERY.on(db.db).query().with("latestOrder", q -> q.withTrashed())
                .where("id", 1).get();

        assertEquals(4L, OneOfManyModels.key(lazy));
        assertEquals(4L, OneOfManyModels.key(eager.get(0).<Order>related("latestOrder")));
    }

    @Test
    void whereHasAndWithCountJudgeThePickedRow() {
        seed();

        ModelCollection<User> expensive = User.QUERY.on(db.db).query()
                .whereHas("latestOrder", q -> q.where("orders.price", ">", 300)).orderBy("id").get();
        ModelCollection<User> counted = User.QUERY.on(db.db).query().withCount("latestOrder").orderBy("id").get();

        assertEquals(Collections.singletonList(1L), OneOfManyModels.keys(expensive));
        assertEquals(1, ((Number) counted.get(1).getAttribute("latest_order_count")).intValue());
        assertEquals(0, ((Number) counted.get(2).getAttribute("latest_order_count")).intValue());
    }

    @Test
    void selfRelationWhereHasUsesTheAliasedTable() {
        db.node(1, null, day(1));
        db.node(2, 1L, day(2));
        db.node(3, 1L, day(3));
        db.node(4, 2L, day(4));

        ModelCollection<Node> found = Node.QUERY.on(db.db).query()
                .whereHas("latestChild", q -> q.where("id", "=", 4)).get();

        assertEquals(Collections.singletonList(2L), OneOfManyModels.keys(found));
    }

    @Test
    void throughRelationPicksTheLatestOrderOfAnyUserLazyEagerAndInExistence() {
        seed();
        Country france = Country.QUERY.on(db.db).findOrFail(1);

        Order lazy = france.latestOrder().getResults();
        ModelCollection<Country> eager = Country.QUERY.on(db.db).query().with("latestOrder").orderBy("id").get();
        ModelCollection<Country> has = Country.QUERY.on(db.db).query().has("latestOrder").orderBy("id").get();

        assertEquals(5L, OneOfManyModels.key(lazy));
        assertEquals(5L, OneOfManyModels.key(eager.get(0).<Order>related("latestOrder")));
        assertEquals(Collections.singletonList(1L), OneOfManyModels.keys(has));
    }

    @Test
    void morphRelationStaysInTypeLazyAndEager() {
        db.post(1);
        db.video(1);
        db.image(1, "post", 1, day(1));
        db.image(2, "post", 1, day(2));
        db.image(3, "video", 1, day(9));
        Post post = Post.QUERY.on(db.db).findOrFail(1);

        Image lazy = post.latestImage().getResults();
        ModelCollection<Post> eager = Post.QUERY.on(db.db).query().with("latestImage").get();

        assertEquals(2L, OneOfManyModels.key(lazy));
        assertEquals(2L, OneOfManyModels.key(eager.get(0).<Image>related("latestImage")));
    }
}

package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Forwarders chained inside a relation factory: lazy, eager and existence paths. */
class ChainedFactoryTest {

    private static final String TITLED_LAZY = "select * from \"posts\" where \"posts\".\"user_id\" = ?"
            + " and \"posts\".\"user_id\" is not null and \"title\" = ? order by \"id\" desc";

    /** A user whose map chains a where and an order onto its relation. */
    static final class Blogger extends Model {
        static final ModelQuery<Blogger> QUERY = ModelQuery.of(Blogger.class, Blogger::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Blogger.class).add("titled", Blogger::titled);
        }

        public HasMany<Post> titled() {
            return hasMany(Post.class, "user_id").where("title", "b").orderByDesc("id");
        }
    }

    /** A post whose inverse relation is chained with a where. */
    static final class Entry extends Model {
        static final ModelQuery<Entry> QUERY = ModelQuery.of(Entry.class, Entry::new);

        @Override
        protected String table() {
            return "posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Entry.class).add("ann", m -> m.belongsTo(User.class, "user_id").where("name", "Ann"));
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        RelationModels.register();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
        db.seed("posts", Map.of("id", 4, "user_id", 1, "title", "b"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void lazyPathAddsTheKeyBeforeTheChainedClauses() {
        Blogger ann = Blogger.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        ModelCollection<Post> posts = ann.relatedMany("titled");

        assertEquals(Arrays.asList(4, 2), posts.modelKeys());
        assertEquals(Collections.singletonList(TITLED_LAZY), db.sql());
        assertEquals(Arrays.asList(1, "b"), db.queries().get(0).bindings());
    }

    @Test
    void eagerPathKeepsTheChainedClausesWithoutLazyConstraints() {
        db.clearLog();

        ModelCollection<Blogger> bloggers = Blogger.QUERY.on(db.db).with("titled").get();

        assertEquals(Arrays.asList(4, 2), bloggers.get(0).<Post>relatedMany("titled").modelKeys());
        assertEquals(Collections.emptyList(), bloggers.get(1).<Post>relatedMany("titled").modelKeys());
        assertEquals("select * from \"posts\" where \"title\" = ? and \"posts\".\"user_id\" in (1, 2)"
                + " order by \"id\" desc", db.sql().get(1));
    }

    @Test
    void existencePathKeepsTheChainedWheresOnly() {
        String sql = Blogger.QUERY.on(db.db).query().has("titled").toSql();
        ModelCollection<Blogger> having = Blogger.QUERY.on(db.db).query().has("titled").get();
        ModelCollection<Blogger> whereHas = Blogger.QUERY.on(db.db).query()
                .whereHas("titled", q -> q.where("posts.id", 2)).get();
        ModelCollection<Blogger> without = Blogger.QUERY.on(db.db).query().doesntHave("titled").get();

        assertEquals("select * from \"users\" where exists (select * from \"posts\" where \"users\".\"id\""
                + " = \"posts\".\"user_id\" and \"title\" = ?)", sql);
        assertEquals(Collections.singletonList(1), having.modelKeys());
        assertEquals(Collections.singletonList(1), whereHas.modelKeys());
        assertEquals(Collections.singletonList(2), without.modelKeys());
    }

    @Test
    void inverseChainedFactoryWorksEagerAndInExistence() {
        ModelCollection<Entry> entries = Entry.QUERY.on(db.db).with("ann").orderBy("id").get();
        ModelCollection<Entry> having = Entry.QUERY.on(db.db).query().has("ann").orderBy("id").get();

        assertEquals(1, ((User) entries.get(0).related("ann")).getKey());
        assertNull(entries.get(2).related("ann"));
        assertEquals(Arrays.asList(1, 2, 4), having.modelKeys());
    }
}

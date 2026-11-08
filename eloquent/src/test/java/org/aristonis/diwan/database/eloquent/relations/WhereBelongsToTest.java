package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** whereBelongsTo and orWhereBelongsTo: the foreign key against the owners' keys. */
class WhereBelongsToTest {

    /** A row of {@code posts} with two belongs-to relations to a user and one on another owner key. */
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
            return RelationMap.of(Entry.class).add("user", Entry::user).add("editor", Entry::editor)
                    .add("owner", Entry::owner).add("comments", Entry::comments);
        }

        public BelongsTo<User> user() {
            return belongsTo(User.class, "user_id");
        }

        public BelongsTo<User> editor() {
            return belongsTo(User.class, "editor_id");
        }

        public BelongsTo<User> owner() {
            return belongsTo(User.class, "title", "name");
        }

        public HasMany<RelationModels.Comment> comments() {
            return hasMany(RelationModels.Comment.class, "post_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("users", Map.of("id", 2, "name", "Bob"));
        db.seed("users", Map.of("id", 3, "name", "Cid"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "a"));
        db.seed("posts", Map.of("id", 2, "user_id", 1, "title", "b"));
        db.seed("posts", Map.of("id", 3, "user_id", 2, "title", "c"));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private User user(int id) {
        return User.QUERY.on(db.db).query().find(id).get();
    }

    private EloquentBuilder<Post> posts() {
        return Post.QUERY.on(db.db).query();
    }

    private EloquentBuilder<Entry> entries() {
        return Entry.QUERY.on(db.db).query();
    }

    @Test
    void filtersByTheRelationKey() {
        EloquentBuilder<Post> query = posts().whereBelongsTo(user(1), "author");

        assertEquals("select * from \"posts\" where \"posts\".\"user_id\" in (?)", query.toSql());
        assertEquals(Arrays.asList(1, 2), query.get().modelKeys());
    }

    @Test
    void defaultNameComesFromTheClass() {
        EloquentBuilder<Entry> query = entries().whereBelongsTo(user(2));

        assertEquals("select * from \"posts\" where \"posts\".\"user_id\" in (?)", query.toSql());
        assertEquals(Collections.singletonList(3), query.get().modelKeys());
    }

    @Test
    void collectionUsesIn() {
        EloquentBuilder<Post> query = posts().whereBelongsTo(Arrays.asList(user(1), user(2)), "author");

        assertEquals("select * from \"posts\" where \"posts\".\"user_id\" in (?, ?)", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.orderBy("id").get().modelKeys());
    }

    @Test
    void explicitRelationName() {
        EloquentBuilder<Entry> query = entries().whereBelongsTo(user(1), "editor");

        assertEquals("select * from \"posts\" where \"posts\".\"editor_id\" in (?)", query.toSql());
    }

    @Test
    void emptyCollectionIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> posts().whereBelongsTo(Collections.<User>emptyList(), "author"));

        assertEquals("Collection given to whereBelongsTo may not be empty", refused.getMessage());
    }

    @Test
    void unknownOrWrongRelationIsRefused() {
        User ann = user(1);

        assertThrows(RelationNotFoundException.class, () -> posts().whereBelongsTo(ann, "nope"));
        assertThrows(RelationNotFoundException.class, () -> entries().whereBelongsTo(Entry.QUERY.on(db.db).query()
                .find(1).get(), "comments"));
        assertThrows(RelationNotFoundException.class, () -> posts().whereBelongsTo(ann));
    }

    @Test
    void orForm() {
        EloquentBuilder<Post> query = posts().where("title", "c").orWhereBelongsTo(user(1), "author");

        assertEquals("select * from \"posts\" where \"title\" = ? or \"posts\".\"user_id\" in (?)", query.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.orderBy("id").get().modelKeys());
    }

    @Test
    void customOwnerKey() {
        EloquentBuilder<Entry> query = entries().whereBelongsTo(user(2), "owner");

        assertEquals("select * from \"posts\" where \"posts\".\"title\" in (?)", query.toSql());
        assertEquals(List.of("Bob"), query.toBase().getBindings());
    }

    @Test
    void wrongParentTypeIsRefused() {
        Post post = Post.QUERY.on(db.db).query().find(1).get();

        assertThrows(IllegalArgumentException.class, () -> posts().whereBelongsTo(post, "author"));
    }

    @Test
    void nullOwnerKeyIsRefused() {
        User unsaved = User.QUERY.on(db.db).make(Map.of());

        assertThrows(IllegalArgumentException.class, () -> posts().whereBelongsTo(unsaved, "author"));
    }

    @Test
    void orFormWithACollection() {
        EloquentBuilder<Post> query = posts().where("title", "c")
                .orWhereBelongsTo(Arrays.asList(user(1), user(2)), "author");

        assertEquals("select * from \"posts\" where \"title\" = ? or \"posts\".\"user_id\" in (?, ?)",
                query.toSql());
        assertEquals(Arrays.asList(1, 2, 3), query.orderBy("id").get().modelKeys());
    }

    @Test
    void mixedTypesInACollectionAreRefused() {
        List<Model> mixed = Arrays.<Model>asList(user(1), Post.QUERY.on(db.db).query().find(1).get());

        assertThrows(IllegalArgumentException.class, () -> posts().whereBelongsTo(mixed, "author"));
    }
}

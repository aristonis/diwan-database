package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.aristonis.diwan.database.eloquent.ConnectionResolver;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InverseGuessTest {

    /** The parent of most cases: key {@code id}, foreign key {@code writer_id}. */
    static final class Writer extends Model {
        static final ModelQuery<Writer> QUERY = ModelQuery.of(Writer.class, Writer::new);

        HasMany<Note> notes() {
            return hasMany(Note.class, "user_id");
        }

        HasMany<Memo> memos() {
            return hasMany(Memo.class, "author_id");
        }

        HasMany<Scrap> scraps() {
            return hasMany(Scrap.class, "x_id");
        }

        HasMany<Draft> drafts() {
            return hasMany(Draft.class, "creator_id");
        }

        HasMany<Kid> kids() {
            return hasMany(Kid.class, "up_id");
        }

        HasMany<Tag> tags() {
            return hasMany(Tag.class, "user_id");
        }

        HasMany<Note> byKey() {
            return hasMany(Note.class, "id", "id");
        }
    }

    /** Knows {@code user} and {@code owner}. */
    static final class Note extends Model {
        static final ModelQuery<Note> QUERY = ModelQuery.of(Note.class, Note::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Note.class).add("owner", m -> m.belongsTo(Writer.class, "owner_id"))
                    .add("user", m -> m.belongsTo(Writer.class, "user_id"));
        }
    }

    /** Knows only {@code owner}. */
    static final class Memo extends Model {
        static final ModelQuery<Memo> QUERY = ModelQuery.of(Memo.class, Memo::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Memo.class).add("owner", m -> m.belongsTo(Writer.class, "author_id"));
        }
    }

    /** Knows no candidate. */
    static final class Scrap extends Model {
        static final ModelQuery<Scrap> QUERY = ModelQuery.of(Scrap.class, Scrap::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Scrap.class).add("other", m -> m.belongsTo(Writer.class, "x_id"));
        }
    }

    /** Knows {@code writer}, the parent's class and foreign key name. */
    static final class Draft extends Model {
        static final ModelQuery<Draft> QUERY = ModelQuery.of(Draft.class, Draft::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Draft.class).add("writer", m -> m.belongsTo(Writer.class, "creator_id"));
        }
    }

    /** Knows {@code parent}, but is not the parent's class. */
    static final class Kid extends Model {
        static final ModelQuery<Kid> QUERY = ModelQuery.of(Kid.class, Kid::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Kid.class).add("parent", m -> m.belongsTo(Writer.class, "up_id"));
        }
    }

    /** Knows {@code user} as a has-many, not a belongs-to. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Tag.class).add("user", m -> m.hasMany(Writer.class, "tag_id"));
        }
    }

    /** A self relation with {@code parent} and {@code category}. */
    static final class Category extends Model {
        static final ModelQuery<Category> QUERY = ModelQuery.of(Category.class, Category::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Category.class).add("category", m -> m.belongsTo(Category.class, "category_id"))
                    .add("parent", m -> m.belongsTo(Category.class, "parent_id"));
        }

        HasMany<Category> children() {
            return hasMany(Category.class, "parent_id");
        }
    }

    /** A self relation whose foreign key does not name {@code parent}. */
    static final class Node extends Model {
        static final ModelQuery<Node> QUERY = ModelQuery.of(Node.class, Node::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Node.class).add("parent", m -> m.belongsTo(Node.class, "up_id"));
        }

        HasMany<Node> children() {
            return hasMany(Node.class, "up_id");
        }
    }

    /** A parent whose foreign key differs from its class name. */
    static final class BlogPost extends Model {
        static final ModelQuery<BlogPost> QUERY = ModelQuery.of(BlogPost.class, BlogPost::new);

        @Override
        public String getForeignKey() {
            return "article_id";
        }

        HasMany<Note> notes() {
            return hasMany(Note.class, "post_id");
        }
    }

    /** A self relation that chaperones inside its own relations() map. */
    static final class Folder extends Model {
        static final ModelQuery<Folder> QUERY = ModelQuery.of(Folder.class, Folder::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Folder.class).add("parent", m -> m.belongsTo(Folder.class, "parent_id"))
                    .add("children", m -> m.hasMany(Folder.class, "parent_id").chaperone());
        }
    }

    /** Chaperones toward {@link Pong}, which chaperones back. */
    static final class Ping extends Model {
        static final ModelQuery<Ping> QUERY = ModelQuery.of(Ping.class, Ping::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Ping.class).add("pong", m -> m.belongsTo(Pong.class, "pong_id"))
                    .add("pongs", m -> m.hasMany(Pong.class, "ping_id").chaperone());
        }
    }

    /** Chaperones toward {@link Ping}. */
    static final class Pong extends Model {
        static final ModelQuery<Pong> QUERY = ModelQuery.of(Pong.class, Pong::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Pong.class).add("ping", m -> m.belongsTo(Ping.class, "ping_id"))
                    .add("pings", m -> m.hasMany(Ping.class, "pong_id").chaperone());
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        // A class literal does not run the static initialiser that registers the entry.
        for (ModelQuery<?> query : Arrays.asList(Note.QUERY, Memo.QUERY, Scrap.QUERY, Draft.QUERY, Kid.QUERY,
                Tag.QUERY, Category.QUERY, Node.QUERY, BlogPost.QUERY, Folder.QUERY, Ping.QUERY, Pong.QUERY)) {
            assertTrue(Model.class.isAssignableFrom(query.getModelClass()));
        }
    }

    @AfterEach
    void close() throws SQLException {
        ConnectionResolver.resetDefault();
        db.close();
    }

    private Writer writer() {
        return Writer.QUERY.on(db.db).newModel();
    }

    @Test
    void noneFound() {
        HasMany<Scrap> relation = writer().scraps();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> InverseRelations.guess(relation));

        assertSame(Scrap.class, e.getModelClass());
        assertEquals(Collections.singletonList("other"), e.getKnownRelations());
        assertTrue(e.getRelation().startsWith("inverse of "), e.getRelation());
        assertFalse(e.getRelation().contains("["), e.getRelation());
        assertTrue(e.getRelation().endsWith(
                " (tried: " + String.join(", ", InverseRelations.candidates(relation)) + ")"),
                e.getRelation());
    }

    @Test
    void fromForeignKey() {
        assertEquals("user", InverseRelations.guess(writer().notes()));
    }

    @Test
    void ownerFallback() {
        assertEquals("owner", InverseRelations.guess(writer().memos()));
    }

    @Test
    void orderRespected() {
        // Note declares owner before user; the candidate order, not the map order, decides.
        assertEquals(Arrays.asList("user", "writer", "owner"), InverseRelations.candidates(writer().notes()));
        assertEquals("user", InverseRelations.guess(writer().notes()));
    }

    @Test
    void fromParentForeignKeyAndClassName() {
        assertEquals("writer", InverseRelations.guess(writer().drafts()));
        assertEquals(Arrays.asList("post", "article", "blogPost", "owner"),
                InverseRelations.candidates(BlogPost.QUERY.on(db.db).newModel().notes()));
    }

    @Test
    void candidatesDedupedAndEmptiesDropped() {
        // beforeLast("id", "id") is empty; writer_id and Writer both give "writer".
        assertEquals(Arrays.asList("writer", "owner"), InverseRelations.candidates(writer().byKey()));
    }

    @Test
    void selfRelationParent() {
        Category category = Category.QUERY.on(db.db).newModel();
        assertEquals(Arrays.asList("parent", "category", "owner"), InverseRelations.candidates(category.children()));
        assertEquals("parent", InverseRelations.guess(category.children()));

        Node node = Node.QUERY.on(db.db).newModel();
        assertEquals(Arrays.asList("up", "node", "owner", "parent"), InverseRelations.candidates(node.children()));
        assertEquals("parent", InverseRelations.guess(node.children()));
    }

    @Test
    void parentOnlySameClass() {
        HasMany<Kid> relation = writer().kids();

        assertFalse(InverseRelations.candidates(relation).contains("parent"));
        assertThrows(RelationNotFoundException.class, () -> InverseRelations.guess(relation));
    }

    @Test
    void anyRelationAccepted() {
        assertEquals("user", InverseRelations.guess(writer().tags()));
        assertEquals("user", InverseRelations.check(writer().tags(), "user"));
    }

    @Test
    void checkReturnsKnownName() {
        assertEquals("owner", InverseRelations.check(writer().notes(), "owner"));
    }

    @Test
    void checkRefusesUnknownName() {
        HasMany<Note> relation = writer().notes();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class,
                () -> InverseRelations.check(relation, "author"));

        assertSame(Note.class, e.getModelClass());
        assertEquals("author", e.getRelation());
        assertEquals(Arrays.asList("owner", "user"), e.getKnownRelations());
    }

    @Test
    void checkRefusesNull() {
        HasMany<Note> relation = writer().notes();

        assertThrows(NullPointerException.class, () -> InverseRelations.check(relation, null));
    }

    @Test
    void camelCases() {
        assertEquals("user", InverseRelations.camel("user_"));
        assertEquals("blogPost", InverseRelations.camel("blog_post"));
        assertEquals("blogPost", InverseRelations.camel("BlogPost"));
        assertEquals("fooBarBaz", InverseRelations.camel("foo-bar baz"));
        assertEquals("aB", InverseRelations.camel("  a \t b"));
        assertEquals("fooBar", InverseRelations.camel("foo__bar"));
        assertEquals("", InverseRelations.camel("_"));
        assertEquals("", InverseRelations.camel(""));
        assertEquals("userID", InverseRelations.camel("user_ID"));
    }

    @Test
    void beforeLastCases() {
        assertEquals("user_", InverseRelations.beforeLast("user_id", "id"));
        assertEquals("id_", InverseRelations.beforeLast("id_id", "id"));
        assertEquals("abc", InverseRelations.beforeLast("abc", "z"));
        assertEquals("abc", InverseRelations.beforeLast("abc", ""));
        assertEquals("", InverseRelations.beforeLast("id", "id"));
    }

    @Test
    void chaperoneInOwnMapDoesNotRecurse() {
        Folder folder = Folder.QUERY.on(db.db).newModel();

        HasMany<?> children = (HasMany<?>) folder.relation("children");

        assertEquals(Arrays.asList("parent", "children"), RelationRegistry.of(folder).names());
        assertEquals("parent", children.inverseName());
    }

    @Test
    void mutualChaperoneDoesNotRecurse() {
        Ping ping = Ping.QUERY.on(db.db).newModel();
        Pong pong = Pong.QUERY.on(db.db).newModel();

        HasMany<?> pongs = (HasMany<?>) ping.relation("pongs");
        HasMany<?> pings = (HasMany<?>) pong.relation("pings");

        // Each guess reads the other class's names only, so neither registry builds the other.
        assertEquals("ping", pongs.inverseName());
        assertEquals("pong", pings.inverseName());
        assertEquals(Arrays.asList("pong", "pongs"), RelationRegistry.of(ping).names());
    }
}

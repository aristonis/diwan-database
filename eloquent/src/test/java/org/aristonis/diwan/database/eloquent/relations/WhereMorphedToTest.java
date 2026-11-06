package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.exception.ClassMorphViolationException;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Comment;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Post;
import org.aristonis.diwan.database.eloquent.relations.MorphToModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WhereMorphedToTest {

    private static final String SELECT = "select * from \"morph_comments\" where ";
    private static final String TYPE = "\"morph_comments\".\"commentable_type\"";
    private static final String KEY = "\"morph_comments\".\"commentable_id\"";

    private RelationDb db;
    private Post post5;
    private Post post6;
    private Video video5;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
        db.seed("morph_posts", Map.of("id", 5, "title", "post five", "author_id", 1));
        db.seed("morph_posts", Map.of("id", 6, "title", "post six", "author_id", 1));
        db.seed("morph_videos", Map.of("id", 5, "title", "video five", "author_id", 1));
        comment(1, "post", 5);
        comment(2, "video", 5);
        comment(3, "post", 6);
        comment(4, "video", 99);
        comment(5, null, null);
        comment(6, "post", 5);
        comment(8, "post", null);
        post5 = Post.QUERY.on(db.db).find(5).orElseThrow();
        post6 = Post.QUERY.on(db.db).find(6).orElseThrow();
        video5 = Video.QUERY.on(db.db).find(5).orElseThrow();
        db.clearLog();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private void comment(int id, String type, Integer commentableId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("body", "c" + id);
        row.put("commentable_type", type);
        row.put("commentable_id", commentableId);
        db.seed("morph_comments", row);
    }

    private EloquentBuilder<Comment> comments() {
        return Comment.QUERY.on(db.db).query();
    }

    private static List<Long> ids(List<Comment> rows) {
        List<Long> ids = new ArrayList<>();
        for (Comment row : rows) {
            ids.add(((Number) row.getKey()).longValue());
        }
        Collections.sort(ids);
        return ids;
    }

    private static List<Long> longs(long... values) {
        List<Long> list = new ArrayList<>();
        for (long value : values) {
            list.add(value);
        }
        return list;
    }

    private static List<String> texts(List<Object> bindings) {
        List<String> texts = new ArrayList<>();
        for (Object binding : bindings) {
            texts.add(String.valueOf(binding));
        }
        return texts;
    }

    @Test
    void modelComparesTypeAndKey() {
        EloquentBuilder<Comment> query = comments().whereMorphedTo("commentable", post5);

        assertEquals(SELECT + "(" + TYPE + " = ? and " + KEY + " = ?)", query.toSql());
        assertEquals(Arrays.asList("post", "5"), texts(query.getBindings()));
        assertEquals(longs(1, 6), ids(query.get()));
    }

    @Test
    void classComparesTheTypeOnly() {
        EloquentBuilder<Comment> query = comments().whereMorphedTo("commentable", Post.class);

        assertEquals(SELECT + TYPE + " = ?", query.toSql());
        assertEquals(Collections.singletonList("post"), query.getBindings());
        assertEquals(longs(1, 3, 6, 8), ids(query.get()));
    }

    @Test
    void aliasComparesTheTypeOnly() {
        EloquentBuilder<Comment> query = comments().whereMorphedTo("commentable", "video");

        assertEquals(SELECT + TYPE + " = ?", query.toSql());
        assertEquals(longs(2, 4), ids(query.get()));
    }

    @Test
    void unknownAliasIsRefused() {
        assertThrows(ClassMorphViolationException.class, () -> comments().whereMorphedTo("commentable", "ghost"));
    }

    @Test
    void collectionIsGroupedPerType() {
        EloquentBuilder<Comment> query = comments().whereMorphedTo("commentable",
                Arrays.asList(post5, video5, post6));

        assertEquals(SELECT + "((" + TYPE + " = ? and " + KEY + " in (?, ?)) or (" + TYPE + " = ? and " + KEY
                + " in (?)))", query.toSql());
        assertEquals(Arrays.asList("post", "5", "6", "video", "5"), texts(query.getBindings()));
        assertEquals(longs(1, 2, 3, 6), ids(query.get()));
    }

    @Test
    void nullComparesAMissingType() {
        EloquentBuilder<Comment> query = comments().whereMorphedTo("commentable", null);

        assertEquals(SELECT + TYPE + " is null", query.toSql());
        assertEquals(longs(5), ids(query.get()));
    }

    @Test
    void emptyCollectionIsRefused() {
        EloquentBuilder<Comment> query = comments();

        assertThrows(IllegalArgumentException.class,
                () -> query.whereMorphedTo("commentable", Collections.emptyList()));

        assertEquals("select * from \"morph_comments\"", query.toSql());
    }

    @Test
    void modelWithoutAKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> comments().whereMorphedTo("commentable", new Post()));
    }

    @Test
    void unsupportedTargetIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> comments().whereMorphedTo("commentable", 5));
    }

    @Test
    void notModelIsNullSafe() {
        EloquentBuilder<Comment> query = comments().whereNotMorphedTo("commentable", post5);

        assertEquals(SELECT + "(not (" + TYPE + " = ? and " + KEY + " = ?) or " + TYPE + " is null or " + KEY
                + " is null)", query.toSql());
        assertEquals(longs(2, 3, 4, 5, 8), ids(query.get()));
    }

    @Test
    void notClassIncludesNullTypes() {
        EloquentBuilder<Comment> query = comments().whereNotMorphedTo("commentable", Post.class);

        assertEquals(SELECT + "(not (" + TYPE + " = ?) or " + TYPE + " is null or " + KEY + " is null)",
                query.toSql());
        assertEquals(longs(2, 4, 5, 8), ids(query.get()));
    }

    @Test
    void notCollectionExcludesEveryListedOwner() {
        EloquentBuilder<Comment> query = comments().whereNotMorphedTo("commentable", Arrays.asList(post5, video5));

        assertEquals(longs(3, 4, 5, 8), ids(query.get()));
    }

    @Test
    void notNullMeansATypeIsSet() {
        EloquentBuilder<Comment> query = comments().whereNotMorphedTo("commentable", null);

        assertEquals(SELECT + TYPE + " is not null", query.toSql());
        assertEquals(longs(1, 2, 3, 4, 6, 8), ids(query.get()));
    }

    @Test
    void orFormsJoinWithOr() {
        EloquentBuilder<Comment> model = comments().where("id", 5).orWhereMorphedTo("commentable", post5);
        EloquentBuilder<Comment> type = comments().where("id", 5).orWhereMorphedTo("commentable", Video.class);
        EloquentBuilder<Comment> nothing = comments().where("id", 1).orWhereMorphedTo("commentable", null);
        EloquentBuilder<Comment> not = comments().where("id", 1).orWhereNotMorphedTo("commentable", post5);
        EloquentBuilder<Comment> notNull = comments().where("id", 5).orWhereNotMorphedTo("commentable", null);

        assertEquals(SELECT + "\"id\" = ? or (" + TYPE + " = ? and " + KEY + " = ?)", model.toSql());
        assertEquals(longs(1, 5, 6), ids(model.get()));
        assertEquals(SELECT + "\"id\" = ? or " + TYPE + " = ?", type.toSql());
        assertEquals(longs(2, 4, 5), ids(type.get()));
        assertEquals(longs(1, 5), ids(nothing.get()));
        assertEquals(longs(1, 2, 3, 4, 5, 8), ids(not.get()));
        assertEquals(SELECT + "\"id\" = ? or " + TYPE + " is not null", notNull.toSql());
        assertEquals(longs(1, 2, 3, 4, 5, 6, 8), ids(notNull.get()));
    }

    @Test
    void dottedNameIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> comments().whereMorphedTo("commentable.author", post5));

        assertTrue(thrown.getMessage().contains("commentable.author"), thrown.getMessage());
    }

    @Test
    void nonMorphToRelationIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Post.QUERY.on(db.db).query().whereMorphedTo("author", post5));

        assertTrue(thrown.getMessage().contains("author"), thrown.getMessage());
    }

    @Test
    void modelComparesTheOwnerKeyOfTheRelation() {
        Coded owner = seedCoded();
        String keyColumn = "\"coded_comments\".\"owner_id\"";
        EloquentBuilder<CodedComment> query = CodedComment.QUERY.on(db.db).query().whereMorphedTo("owner", owner);

        assertEquals(Arrays.asList("coded", "abc"), texts(query.getBindings()));
        assertTrue(query.toSql().contains(keyColumn + " = ?"), query.toSql());
        assertEquals(1, query.get().size());
        assertEquals(1, ((Number) query.get().get(0).getKey()).intValue());
    }

    @Test
    void collectionComparesTheOwnerKeyOfTheRelation() {
        Coded first = seedCoded();
        Coded second = Coded.QUERY.on(db.db).find(2).orElseThrow();
        EloquentBuilder<CodedComment> query = CodedComment.QUERY.on(db.db).query()
                .whereMorphedTo("owner", Arrays.asList(first, second));

        assertEquals(Arrays.asList("coded", "abc", "def"), texts(query.getBindings()));
        assertEquals(2, query.get().size());
    }

    private Coded seedCoded() {
        MorphMap.morphMap(Collections.<String, Class<? extends Model>>singletonMap("coded", Coded.class));
        db.real.statement("create table coded (id integer primary key, code varchar)");
        db.real.statement("create table coded_comments (id integer primary key, owner_type varchar,"
                + " owner_id varchar)");
        db.seed("coded", Map.of("id", 1, "code", "abc"));
        db.seed("coded", Map.of("id", 2, "code", "def"));
        codedComment(1, "abc");
        codedComment(2, "def");
        codedComment(3, "1");
        return Coded.QUERY.on(db.db).find(1).orElseThrow();
    }

    private void codedComment(int id, String ownerId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("owner_type", "coded");
        row.put("owner_id", ownerId);
        db.seed("coded_comments", row);
    }

    /** An owner whose public key is the code column, apart from its id. */
    static final class Coded extends Model {
        static final ModelQuery<Coded> QUERY = ModelQuery.of(Coded.class, Coded::new);

        @Override
        protected String table() {
            return "coded";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }
    }

    /** A row that points at a {@link Coded} through the code column. */
    static final class CodedComment extends Model {
        static final ModelQuery<CodedComment> QUERY = ModelQuery.of(CodedComment.class, CodedComment::new);

        @Override
        protected String table() {
            return "coded_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(CodedComment.class).add("owner", CodedComment::owner);
        }

        public MorphTo<Model> owner() {
            return morphTo("owner", "owner_type", "owner_id", "code");
        }
    }
}

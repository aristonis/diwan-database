package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

class HasMorphTest {

    private static final String SELECT = "select * from \"morph_comments\" where ";
    private static final String TYPE = "\"morph_comments\".\"commentable_type\"";
    private static final String FK = "\"morph_comments\".\"commentable_id\"";
    private static final List<Object> POST_AND_VIDEO = Arrays.asList(Post.class, Video.class);
    private static final List<Object> WILDCARD = Collections.singletonList("*");

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        MorphToModels.prepare(db);
        db.seed("morph_authors", Map.of("id", 1, "name", "Ann"));
        db.seed("morph_posts", Map.of("id", 5, "title", "post five", "author_id", 1));
        db.seed("morph_posts", Map.of("id", 6, "title", "post six", "author_id", 1));
        db.seed("morph_videos", Map.of("id", 5, "title", "video five", "author_id", 1));
        comment(1, "post", 5);
        comment(2, "video", 5);
        comment(3, "post", 6);
        comment(4, "video", 99);
        comment(5, null, null);
        comment(6, "post", 5);
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

    private static String owner(String table) {
        return "select * from \"" + table + "\" where " + FK + " = \"" + table + "\".\"id\"";
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

    @Test
    void whereHasMorphGroupsOneBranchPerType() {
        EloquentBuilder<Comment> query = comments().whereHasMorph("commentable", POST_AND_VIDEO,
                (q, type) -> q.where("title", type == Post.class ? "post five" : "video five"));

        assertEquals(SELECT + "((" + TYPE + " = ? and exists (" + owner("morph_posts")
                + " and \"title\" = ?)) or (" + TYPE + " = ? and exists (" + owner("morph_videos")
                + " and \"title\" = ?)))", query.toSql());
        assertEquals(Arrays.asList("post", "post five", "video", "video five"), query.getBindings());
        assertEquals(longs(1, 2, 6), ids(query.get()));
    }

    @Test
    void constraintReceivesTheOwnerClass() {
        List<Class<? extends Model>> seen = new ArrayList<>();

        comments().whereHasMorph("commentable", POST_AND_VIDEO, (q, type) -> seen.add(type));

        assertEquals(Arrays.asList(Post.class, Video.class), seen);
    }

    @Test
    void constraintWithoutTheClassIsOffered() {
        EloquentBuilder<Comment> query = comments().whereHasMorph("commentable", Collections.singletonList(Post.class),
                q -> q.where("title", "post six"));

        assertEquals(longs(3), ids(query.get()));
    }

    @Test
    void aliasesGiveTheSameQueryAsClasses() {
        String byClass = comments().hasMorph("commentable", POST_AND_VIDEO).toSql();
        EloquentBuilder<Comment> byAlias = comments().hasMorph("commentable", Arrays.asList("post", "video"));

        assertEquals(byClass, byAlias.toSql());
        assertEquals(longs(1, 2, 3, 6), ids(byAlias.get()));
    }

    @Test
    void wildcardReadsTheDistinctTypesOnTheOuterConnection() {
        EloquentBuilder<Comment> query = comments().hasMorph("commentable", WILDCARD);

        assertEquals(Collections.singletonList(
                "select distinct " + TYPE + " from \"morph_comments\""), db.sql());
        assertTrue(query.toSql().contains("morph_posts"), query.toSql());
        assertTrue(query.toSql().contains("morph_videos"), query.toSql());
        assertEquals(longs(1, 2, 3, 6), ids(query.get()));
    }

    @Test
    void wildcardOnlyNullTypesNeverMatches() {
        db.real.statement("delete from morph_comments where id <> 5");

        EloquentBuilder<Comment> positive = comments().hasMorph("commentable", WILDCARD);
        EloquentBuilder<Comment> negative = comments().doesntHaveMorph("commentable", WILDCARD);

        assertEquals(SELECT + "0 = 1", positive.toSql());
        assertEquals(Collections.emptyList(), ids(positive.get()));
        assertEquals("select * from \"morph_comments\"", negative.toSql());
        assertEquals(longs(5), ids(negative.get()));
    }

    @Test
    void wildcardOnAnEmptyTableMatchesNothing() {
        db.real.statement("delete from morph_comments");

        assertEquals(Collections.emptyList(), ids(comments().hasMorph("commentable", WILDCARD).get()));
        assertEquals(Collections.emptyList(), ids(comments().doesntHaveMorph("commentable", WILDCARD).get()));
    }

    @Test
    void wildcardRefusesAStoredTypeTheMapDoesNotKnow() {
        MorphMap.requireMorphMap(true);
        comment(7, "ghost", 1);

        assertThrows(ClassMorphViolationException.class, () -> comments().hasMorph("commentable", WILDCARD));
    }

    @Test
    void doesntHaveMorphAlsoMatchesANullType() {
        EloquentBuilder<Comment> query = comments().doesntHaveMorph("commentable", POST_AND_VIDEO);

        assertEquals(SELECT + "((" + TYPE + " = ? and not exists (" + owner("morph_posts") + ")) or (" + TYPE
                + " = ? and not exists (" + owner("morph_videos") + ")) or " + TYPE + " is null)", query.toSql());
        assertEquals(Arrays.asList("post", "video"), query.getBindings());
        assertEquals(longs(4, 5), ids(query.get()));
    }

    @Test
    void doesntHaveMorphWithAnEmptyListAddsNothing() {
        EloquentBuilder<Comment> query = comments().doesntHaveMorph("commentable", Collections.emptyList());

        assertEquals("select * from \"morph_comments\"", query.toSql());
        assertEquals(6, query.get().size());
    }

    @Test
    void hasMorphWithAnEmptyListNeverMatches() {
        EloquentBuilder<Comment> query = comments().hasMorph("commentable", Collections.emptyList());

        assertEquals(SELECT + "0 = 1", query.toSql());
        assertEquals(Collections.emptyList(), ids(query.get()));
    }

    @Test
    void countFormComparesTheCountPerType() {
        EloquentBuilder<Comment> query = comments().hasMorph("commentable", POST_AND_VIDEO, "=", 1);

        assertEquals(SELECT + "((" + TYPE + " = ? and (select count(*) from \"morph_posts\" where " + FK
                + " = \"morph_posts\".\"id\") = ?) or (" + TYPE + " = ? and (select count(*) from \"morph_videos\""
                + " where " + FK + " = \"morph_videos\".\"id\") = ?))", query.toSql());
        assertEquals(Arrays.asList("post", 1L, "video", 1L), query.getBindings());
        assertEquals(longs(1, 2, 3, 6), ids(query.get()));
    }

    @Test
    void countOfTwoOrMoreOwnersNeverMatchesAMorphTo() {
        assertEquals(Collections.emptyList(),
                ids(comments().hasMorph("commentable", POST_AND_VIDEO, ">=", 2).get()));
    }

    @Test
    void countOfZeroMatchesNullTypesAndMissingOwners() {
        EloquentBuilder<Comment> query = comments().hasMorph("commentable", POST_AND_VIDEO, "=", 0);

        assertTrue(query.toSql().endsWith(" or " + TYPE + " is null)"), query.toSql());
        assertEquals(longs(4, 5), ids(query.get()));
    }

    @Test
    void unknownTypeFailsBeforeAnythingIsAdded() {
        EloquentBuilder<Comment> query = comments();

        assertThrows(ClassMorphViolationException.class,
                () -> query.hasMorph("commentable", Arrays.asList("post", "ghost")));

        assertEquals("select * from \"morph_comments\"", query.toSql());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void unknownOperatorIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> comments().hasMorph("commentable", POST_AND_VIDEO, "~", 1));
    }

    @Test
    void sameKeyOfAnotherTypeIsNotMatched() {
        EloquentBuilder<Comment> query = comments().whereHasMorph("commentable", Collections.singletonList(Post.class),
                q -> q.where("title", "video five"));

        assertEquals(Collections.emptyList(), ids(query.get()));
        assertEquals(longs(1, 3, 6), ids(comments().hasMorph("commentable", Collections.singletonList(Post.class))
                .get()));
    }

    @Test
    void whereMorphRelationIsWhereHasMorphWithAWhere() {
        EloquentBuilder<Comment> relation = comments().whereMorphRelation("commentable", POST_AND_VIDEO, "title",
                "post five");
        EloquentBuilder<Comment> has = comments().whereHasMorph("commentable", POST_AND_VIDEO,
                q -> q.where("title", "post five"));

        assertEquals(has.toSql(), relation.toSql());
        assertEquals(longs(1, 6), ids(relation.get()));
    }

    @Test
    void whereMorphRelationTakesAnOperator() {
        EloquentBuilder<Comment> query = comments().whereMorphRelation("commentable", POST_AND_VIDEO, "title",
                "like", "%five%");

        assertEquals(longs(1, 2, 6), ids(query.get()));
    }

    @Test
    void whereMorphDoesntHaveRelationNegates() {
        EloquentBuilder<Comment> query = comments().whereMorphDoesntHaveRelation("commentable", POST_AND_VIDEO,
                "title", "post five");

        assertEquals(longs(2, 3, 4, 5), ids(query.get()));
    }

    @Test
    void orFormsJoinWithOr() {
        EloquentBuilder<Comment> or = comments().where("id", 5).orHasMorph("commentable",
                Collections.singletonList(Post.class));
        EloquentBuilder<Comment> orRelation = comments().where("id", 4).orWhereMorphRelation("commentable",
                POST_AND_VIDEO, "title", "post six");
        EloquentBuilder<Comment> orDoesnt = comments().where("id", 1).orWhereMorphDoesntHaveRelation("commentable",
                POST_AND_VIDEO, "title", "post five");

        assertEquals(SELECT + "\"id\" = ? or ((" + TYPE + " = ? and exists (" + owner("morph_posts") + ")))",
                or.toSql());
        assertEquals(longs(1, 3, 5, 6), ids(or.get()));
        assertEquals(longs(3, 4), ids(orRelation.get()));
        assertEquals(longs(1, 2, 3, 4, 5), ids(orDoesnt.get()));
    }

    @Test
    void orWhereHasAndDoesntHaveFormsJoinWithOr() {
        EloquentBuilder<Comment> orHas = comments().where("id", 4).orWhereHasMorph("commentable",
                Collections.singletonList(Video.class), q -> q.where("title", "video five"));
        EloquentBuilder<Comment> orDoesnt = comments().where("id", 1).orDoesntHaveMorph("commentable",
                POST_AND_VIDEO);
        EloquentBuilder<Comment> orWhereDoesnt = comments().where("id", 1).orWhereDoesntHaveMorph("commentable",
                POST_AND_VIDEO, q -> q.where("title", "post five"));

        assertEquals(longs(2, 4), ids(orHas.get()));
        assertEquals(longs(1, 4, 5), ids(orDoesnt.get()));
        assertEquals(longs(1, 2, 3, 4, 5), ids(orWhereDoesnt.get()));
    }

    @Test
    void selfReferencingMorphIsAliased() {
        Map<String, Class<? extends Model>> aliases = new HashMap<>();
        aliases.put("comment", Comment.class);
        MorphMap.morphMap(aliases);
        comment(7, "comment", 1);

        EloquentBuilder<Comment> query = comments().whereHasMorph("commentable", Collections.singletonList("comment"),
                (q, type) -> q.where("body", "c1"));

        assertEquals(SELECT + "((" + TYPE + " = ? and exists (select * from \"morph_comments\" as"
                + " \"laravel_reserved_0\" where \"laravel_reserved_0\".\"id\" = " + FK
                + " and \"body\" = ?)))", query.toSql());
        assertEquals(longs(7), ids(query.get()));
    }

    @Test
    void dottedNameIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> comments().hasMorph("commentable.author", POST_AND_VIDEO));

        assertTrue(thrown.getMessage().contains("commentable.author"), thrown.getMessage());
    }

    @Test
    void nonMorphToRelationIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Post.QUERY.on(db.db).query().hasMorph("author", Collections.singletonList("post")));

        assertTrue(thrown.getMessage().contains("author"), thrown.getMessage());
        assertEquals(Collections.emptyList(), db.sql());
    }

    @Test
    void unknownRelationIsRefused() {
        assertThrows(RelationNotFoundException.class, () -> comments().hasMorph("nothing", POST_AND_VIDEO));
    }

    @Test
    void wildcardIgnoresGlobalScopesAndKeepsTheOuterScopeOnTheQuery() {
        db.real.statement("create table morph_soft_comments (id integer primary key, commentable_type varchar,"
                + " commentable_id integer, deleted_at varchar)");
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("id", 1);
        live.put("commentable_type", "post");
        live.put("commentable_id", 5);
        live.put("deleted_at", null);
        db.seed("morph_soft_comments", live);
        Map<String, Object> trashed = new LinkedHashMap<>();
        trashed.put("id", 2);
        trashed.put("commentable_type", "video");
        trashed.put("commentable_id", 5);
        trashed.put("deleted_at", "2026-01-01 00:00:00");
        db.seed("morph_soft_comments", trashed);

        EloquentBuilder<SoftComment> query = SoftComment.QUERY.on(db.db).query()
                .hasMorph("commentable", WILDCARD);

        assertEquals(Collections.singletonList(
                "select distinct \"morph_soft_comments\".\"commentable_type\" from \"morph_soft_comments\""),
                db.sql());
        assertTrue(query.toSql().contains("morph_videos"), query.toSql());
        assertTrue(query.toSql().contains("\"deleted_at\" is null"), query.toSql());
        assertEquals(1, query.get().size());
    }

    /** A soft-deleting comment, to show that the wildcard reads trashed rows too. */
    static final class SoftComment extends Model {
        static final ModelQuery<SoftComment> QUERY = ModelQuery.of(SoftComment.class, SoftComment::new);

        @Override
        protected String table() {
            return "morph_soft_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(SoftComment.class).add("commentable", SoftComment::commentable);
        }

        public MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }
}

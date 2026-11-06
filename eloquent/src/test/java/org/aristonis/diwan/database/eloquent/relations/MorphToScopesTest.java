package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.scope.SoftDeletingScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Global scopes, selected columns and constraints on a morph-to owner: lazily, eagerly and through
 * {@code whereHasMorph}. The owner class soft deletes, and one of its two comments points at a
 * trashed row.
 */
class MorphToScopesTest {

    private static final String TRASHED_AT = "2020-01-01 00:00:00";

    /** A soft-deleting post, owner of comments. */
    static final class ScopedPost extends Model {
        static final ModelQuery<ScopedPost> QUERY = ModelQuery.of(ScopedPost.class, ScopedPost::new);

        @Override
        protected String table() {
            return "scoped_posts";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }
    }

    /** A comment on a scoped post. */
    static final class ScopedComment extends Model {
        static final ModelQuery<ScopedComment> QUERY = ModelQuery.of(ScopedComment.class, ScopedComment::new);

        @Override
        protected String table() {
            return "scoped_comments";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(ScopedComment.class).add("commentable", ScopedComment::commentable);
        }

        MorphTo<Model> commentable() {
            return morphTo("commentable");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.real.statement("create table scoped_posts (id integer primary key, title varchar, deleted_at varchar)");
        db.real.statement("create table scoped_comments (id integer primary key,"
                + " commentable_type varchar, commentable_id integer)");
        Map<String, Class<? extends Model>> aliases = new HashMap<>();
        aliases.put("scoped_post", ScopedPost.class);
        MorphMap.morphMap(aliases);
        db.seed("scoped_posts", Map.of("id", 1, "title", "hello"));
        db.seed("scoped_posts", Map.of("id", 2, "title", "hello", "deleted_at", TRASHED_AT));
        db.seed("scoped_posts", Map.of("id", 3, "title", "other"));
        db.seed("scoped_comments", Map.of("id", 1, "commentable_type", "scoped_post", "commentable_id", 1));
        db.seed("scoped_comments", Map.of("id", 2, "commentable_type", "scoped_post", "commentable_id", 2));
        db.seed("scoped_comments", Map.of("id", 3, "commentable_type", "scoped_post", "commentable_id", 3));
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private EloquentBuilder<ScopedComment> comments() {
        return ScopedComment.QUERY.on(db.db).query().orderBy("id");
    }

    private static List<Object> ids(ModelCollection<ScopedComment> rows) {
        List<Object> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ids.add(((Number) rows.get(i).getKey()).intValue());
        }
        return ids;
    }

    @Test
    void eagerLoadHidesAnOwnerTheScopeRemoves() {
        // Act
        ModelCollection<ScopedComment> rows = comments().with("commentable").get();

        // Assert
        assertNotNull(rows.get(0).related("commentable"));
        assertNull(rows.get(1).related("commentable"));
    }

    @Test
    void eagerLoadWithoutTheSoftDeleteScopeFindsTheTrashedOwner() {
        // Act
        ModelCollection<ScopedComment> rows = comments()
                .with("commentable", q -> q.withoutGlobalScope(SoftDeletingScope.class)).get();

        // Assert
        assertNotNull(rows.get(0).related("commentable"));
        assertNotNull(rows.get(1).related("commentable"));
    }

    @Test
    void eagerLoadWithoutAnyGlobalScopeFindsTheTrashedOwner() {
        // Act
        ModelCollection<ScopedComment> rows = comments()
                .with("commentable", q -> q.withoutGlobalScopes()).get();

        // Assert
        assertNotNull(rows.get(0).related("commentable"));
        assertNotNull(rows.get(1).related("commentable"));
    }

    @Test
    void lazyReadHonoursTheScopeUntilItIsLifted() {
        // Arrange
        ScopedComment onTrashed = comments().get().get(1);

        // Act
        Model hidden = onTrashed.commentable().getResults();
        Model lifted = onTrashed.commentable().withoutGlobalScopes().getResults();

        // Assert
        assertNull(hidden);
        assertNotNull(lifted);
    }

    @Test
    void eagerColumnListKeepsTheOwnerMatched() {
        // Act
        ModelCollection<ScopedComment> rows = comments().with("commentable:id").get();

        // Assert
        Model owner = rows.get(0).related("commentable");
        assertEquals(Collections.singleton("id"), owner.getAttributes().keySet());
        assertEquals(1, ((Number) owner.getKey()).intValue());
    }

    @Test
    void eagerSelectConstraintKeepsTheOwnerMatched() {
        // Act
        ModelCollection<ScopedComment> rows = comments().with("commentable", q -> q.select("id")).get();

        // Assert
        Model owner = rows.get(0).related("commentable");
        assertEquals(Collections.singleton("id"), owner.getAttributes().keySet());
    }

    @Test
    void eagerAddSelectConstraintKeepsTheOwnerMatched() {
        // Act
        ModelCollection<ScopedComment> rows = comments().with("commentable", q -> q.addSelect("id")).get();

        // Assert
        Model owner = rows.get(0).related("commentable");
        assertEquals(Collections.singleton("id"), owner.getAttributes().keySet());
    }

    @Test
    void eagerSelectRawConstraintKeepsTheOwnerMatched() {
        // Act
        ModelCollection<ScopedComment> rows = comments().with("commentable", q -> q.selectRaw("title as t")).get();

        // Assert
        Model owner = rows.get(0).related("commentable");
        assertNotNull(owner);
        assertEquals(1, ((Number) owner.getKey()).intValue());
        assertEquals(Set.of("t", "id"), owner.getAttributes().keySet());
    }

    @Test
    void lazyReadWithASelectedColumnReturnsOnlyThatColumn() {
        // Arrange
        ScopedComment first = comments().get().get(0);

        // Act
        Model owner = first.commentable().select("id").getResults();

        // Assert
        assertEquals(Collections.singleton("id"), owner.getAttributes().keySet());
    }

    @Test
    void whereHasMorphHidesTrashedOwnersByDefault() {
        // Act
        ModelCollection<ScopedComment> rows = comments()
                .whereHasMorph("commentable", Collections.singletonList(ScopedPost.class), q -> { }).get();

        // Assert
        assertEquals(List.of(1, 3), ids(rows));
    }

    @Test
    void whereHasMorphWithTrashedConstraintMatchesTrashedOwners() {
        // Act
        ModelCollection<ScopedComment> rows = comments()
                .whereHasMorph("commentable", Collections.singletonList(ScopedPost.class),
                        q -> q.withTrashed()).get();

        // Assert
        assertEquals(List.of(1, 2, 3), ids(rows));
    }

    @Test
    void whereHasMorphConstraintCanApplyALocalScope() {
        // Act
        ModelCollection<ScopedComment> rows = comments()
                .whereHasMorph("commentable", Collections.singletonList(ScopedPost.class),
                        q -> q.scope(b -> b.where("title", "hello"))).get();

        // Assert
        assertEquals(List.of(1), ids(rows));
    }
}

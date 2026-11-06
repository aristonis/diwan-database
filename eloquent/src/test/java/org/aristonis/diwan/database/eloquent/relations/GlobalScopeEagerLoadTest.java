package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A global scope that eager loads a relation is refused, since a scope may add query clauses only. */
class GlobalScopeEagerLoadTest {

    private static final String SCOPE = "withPosts";

    /** A user whose global scope eager loads its posts. */
    static final class ScopedUser extends Model {
        static final ModelQuery<ScopedUser> QUERY = ModelQuery.of(ScopedUser.class, ScopedUser::new);

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
            return RelationMap.of(ScopedUser.class).add("posts", ScopedUser::posts);
        }

        HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        ScopedUser.QUERY.addGlobalScope(SCOPE, builder -> builder.with("posts"));
        db.seed("users", Map.of("id", 1, "name", "Ann"));
        db.seed("posts", Map.of("id", 1, "user_id", 1, "title", "My Post"));
    }

    @AfterEach
    void close() throws SQLException {
        ScopedUser.QUERY.clearGlobalScopes();
        db.close();
    }

    @Test
    void aGlobalScopeThatEagerLoadsIsRefused() {
        // Act
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ScopedUser.QUERY.on(db.db).query().get());

        // Assert
        assertTrue(error.getMessage().contains("may add query clauses only"), error.getMessage());
    }
}

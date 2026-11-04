package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The default pivot table and key names of a belongsToMany relation, built from the two model
 * class names: sorted, snake-cased and joined with an underscore.
 */
class JoiningTableNameTest {

    private BtmDb db;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void userAndRoleJoinToRoleUserWithDefaultKeys() {
        BelongsToMany<BtmModels.Role> roles = boundInstance(BtmModels.User.QUERY).rolesByDefault();

        assertEquals("role_user", roles.getTable());
        assertEquals("user_id", roles.getForeignPivotKeyName());
        assertEquals("role_id", roles.getRelatedPivotKeyName());
    }

    @Test
    void multiWordNamesKeepEachSegmentSnakeCased() {
        BelongsToMany<BtmModels.Tag> tags = boundInstance(BtmModels.BlogPost.QUERY).tags();

        assertEquals("blog_post_tag", tags.getTable());
        assertEquals("blog_post_id", tags.getForeignPivotKeyName());
        assertEquals("tag_id", tags.getRelatedPivotKeyName());
    }

    @Test
    void selfRelationWithoutTableJoinsToUserUserWithUserIdKeys() {
        BelongsToMany<User> peers = boundInstance(User.QUERY).peers();

        assertEquals("user_user", peers.getTable());
        assertEquals("user_id", peers.getForeignPivotKeyName());
        assertEquals("user_id", peers.getRelatedPivotKeyName());
    }

    @Test
    void mixedCaseNamesSortAlphabeticallyBeforeJoining() {
        BelongsToMany<alpha> alphas = boundInstance(Zeta.QUERY).alphas();

        assertEquals("alpha_zeta", alphas.getTable());
    }

    private <T extends Model> T boundInstance(ModelQuery<T> query) {
        return query.on(db.db).query().getModel();
    }

    /** A user whose self relation names no table, so the joining rule supplies it. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        public BelongsToMany<User> peers() {
            return belongsToMany(User.class);
        }
    }

    /** A model whose simple name starts with an upper-case letter and sorts after {@link alpha}. */
    static final class Zeta extends Model {
        static final ModelQuery<Zeta> QUERY = ModelQuery.of(Zeta.class, Zeta::new);

        @Override
        protected boolean timestamps() {
            return false;
        }

        public BelongsToMany<alpha> alphas() {
            return belongsToMany(alpha.class);
        }
    }

    /**
     * A model whose simple name is lower case on purpose: the joining rule must order it
     * alphabetically against {@link Zeta} without depending on the case of its first letter.
     */
    static final class alpha extends Model {
        static final ModelQuery<alpha> QUERY = ModelQuery.of(alpha.class, alpha::new);

        @Override
        protected boolean timestamps() {
            return false;
        }
    }
}

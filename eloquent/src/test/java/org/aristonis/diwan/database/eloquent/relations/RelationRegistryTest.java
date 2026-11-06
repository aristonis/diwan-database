package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.ConnectionResolver;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.Post;
import org.aristonis.diwan.database.eloquent.relations.RelationModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationRegistryTest {

    /** A model with no ModelQuery entry. */
    static final class Orphan extends Model {
    }

    /** A model whose ModelQuery entry the test registers late, after a first failed use. */
    static final class Late extends Model {
    }

    /** Names {@link Late} before its entry exists. */
    static final class PointsAtLate extends Model {
        static final ModelQuery<PointsAtLate> QUERY = ModelQuery.of(PointsAtLate.class, PointsAtLate::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PointsAtLate.class).add("lates", m -> m.hasMany(Late.class, "owner_id"));
        }
    }

    /** A factory that throws with a message holding a value. */
    static final class ThrowingFactory extends Model {
        static final ModelQuery<ThrowingFactory> QUERY = ModelQuery.of(ThrowingFactory.class, ThrowingFactory::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(ThrowingFactory.class).add("boom", m -> {
                throw new IllegalArgumentException("secret-value");
            });
        }
    }

    /** The map misspells a name. */
    static final class TypoPost extends Model {
        static final ModelQuery<TypoPost> QUERY = ModelQuery.of(TypoPost.class, TypoPost::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TypoPost.class).add("coments", TypoPost::comments);
        }

        public HasMany<RelationModels.Comment> comments() {
            return hasMany(RelationModels.Comment.class, "post_id");
        }
    }

    /** A name with a dot. */
    static final class DottedName extends Model {
        static final ModelQuery<DottedName> QUERY = ModelQuery.of(DottedName.class, DottedName::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(DottedName.class).add("a.b", m -> m.hasMany(Post.class, "user_id"));
        }
    }

    /** A factory returning null, counting its runs. */
    static final class NullFactory extends Model {
        static final ModelQuery<NullFactory> QUERY = ModelQuery.of(NullFactory.class, NullFactory::new);
        static final AtomicInteger RUNS = new AtomicInteger();

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(NullFactory.class).add("nothing", m -> {
                RUNS.incrementAndGet();
                return null;
            });
        }
    }

    /** Returns the map of another class. */
    static final class WrongMap extends Model {
        static final ModelQuery<WrongMap> QUERY = ModelQuery.of(WrongMap.class, WrongMap::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(User.class).add("posts", User::posts);
        }
    }

    /** A relation to a class without an entry. */
    static final class PointsAtOrphan extends Model {
        static final ModelQuery<PointsAtOrphan> QUERY = ModelQuery.of(PointsAtOrphan.class, PointsAtOrphan::new);

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(PointsAtOrphan.class).add("orphans", PointsAtOrphan::orphans);
        }

        public HasMany<Orphan> orphans() {
            return hasMany(Orphan.class);
        }
    }

    /** A parent class declaring the map its subclasses inherit. */
    static class Member extends Model {
        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Member.class).add("posts", Member::posts);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id");
        }
    }

    /** Inherits the map of {@link Member}. */
    static final class Admin extends Member {
        static final ModelQuery<Admin> QUERY = ModelQuery.of(Admin.class, Admin::new);
    }

    /** A related model with its own connection name. */
    static final class Lead extends Model {
        static final ModelQuery<Lead> QUERY = ModelQuery.of(Lead.class, Lead::new);

        @Override
        protected String connectionName() {
            return "crm";
        }
    }

    /** Has leads on another connection and posts on its own. */
    static final class Rep extends Model {
        static final ModelQuery<Rep> QUERY = ModelQuery.of(Rep.class, Rep::new);

        public HasMany<Lead> leads() {
            return hasMany(Lead.class);
        }

        public HasMany<Post> posts() {
            return hasMany(Post.class, "user_id", "id");
        }

        public HasOne<RelationModels.Profile> profile() {
            return hasOne(RelationModels.Profile.class);
        }

        public BelongsTo<User> owner() {
            return belongsTo(User.class, "owner_id", "id", "boss");
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        // A class literal does not run the static initialiser that registers the entry.
        assertSame(Lead.class, Lead.QUERY.getModelClass());
    }

    @AfterEach
    void close() throws SQLException {
        ConnectionResolver.resetDefault();
        db.close();
    }

    @Test
    void typoInMapSurfacesAsNotFound() {
        TypoPost post = TypoPost.QUERY.on(db.db).newModel();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class, () -> post.relation("comments"));

        assertTrue(e.getMessage().contains("coments"));
        assertEquals(Collections.singletonList("coments"), e.getKnownRelations());
        assertEquals("comments", e.getRelation());
    }

    @Test
    void unknownNameListsKnownNames() {
        User user = User.QUERY.on(db.db).newModel();

        RelationNotFoundException e = assertThrows(RelationNotFoundException.class, () -> user.relation("likes"));

        assertEquals(Arrays.asList("posts", "profile"), e.getKnownRelations());
        assertSame(User.class, e.getModelClass());
    }

    @Test
    void badMapRejectedOnce() {
        DottedName dotted = DottedName.QUERY.on(db.db).newModel();

        InvalidRelationException first = assertThrows(InvalidRelationException.class, () -> dotted.relation("a.b"));
        InvalidRelationException second = assertThrows(InvalidRelationException.class, () -> dotted.relation("x"));

        assertEquals("a.b", first.getRelation());
        assertEquals(first.getMessage(), second.getMessage());
    }

    @Test
    void nullFactoryRejected() {
        NullFactory model = NullFactory.QUERY.on(db.db).newModel();

        InvalidRelationException e = assertThrows(InvalidRelationException.class, () -> model.relation("nothing"));

        assertEquals("nothing", e.getRelation());
    }

    @Test
    void failedCheckRethrownSameMessage() {
        NullFactory model = NullFactory.QUERY.on(db.db).newModel();
        int before = NullFactory.RUNS.get();

        InvalidRelationException first = assertThrows(InvalidRelationException.class, () -> model.relation("x"));
        InvalidRelationException second = assertThrows(InvalidRelationException.class, () -> model.relation("x"));

        assertNotSame(first, second);
        assertEquals(first.getMessage(), second.getMessage());
        assertSame(first.getCause(), second.getCause());
        assertTrue(NullFactory.RUNS.get() - before <= 1);
    }

    @Test
    void mapClassMustMatch() {
        WrongMap model = WrongMap.QUERY.on(db.db).newModel();

        InvalidRelationException e = assertThrows(InvalidRelationException.class, () -> model.relation("posts"));

        assertSame(WrongMap.class, e.getModelClass());
        assertTrue(e.getMessage().contains(User.class.getName()));
    }

    @Test
    void unregisteredRelatedRejected() {
        PointsAtOrphan model = PointsAtOrphan.QUERY.on(db.db).newModel();

        IllegalStateException typed = assertThrows(IllegalStateException.class, model::orphans);
        InvalidRelationException named = assertThrows(InvalidRelationException.class,
                () -> model.relation("orphans"));

        assertTrue(typed.getMessage().contains(Orphan.class.getName()));
        assertTrue(named.getMessage().contains(Orphan.class.getName()));
        assertEquals("orphans", named.getRelation());
    }

    @Test
    void unregisteredRelatedIsNotCachedSoALaterRegistrationWorks() {
        PointsAtLate model = PointsAtLate.QUERY.on(db.db).newModel();

        InvalidRelationException first = assertThrows(InvalidRelationException.class,
                () -> model.relation("lates"));
        ModelQuery.of(Late.class, Late::new);
        Relation<?, ?> lates = model.relation("lates");

        assertTrue(first.getMessage().contains(Late.class.getName()));
        assertTrue(lates instanceof HasMany);
        assertSame(Late.class, lates.getRelated().getClass());
    }

    @Test
    void buildFailureNamesTheExceptionClassOnly() {
        ThrowingFactory model = ThrowingFactory.QUERY.on(db.db).newModel();

        InvalidRelationException named = assertThrows(InvalidRelationException.class,
                () -> model.relation("boom"));

        assertTrue(named.getMessage().contains(IllegalArgumentException.class.getName()));
        assertTrue(!named.getMessage().contains("secret-value"));
        assertTrue(named.getCause().getCause() instanceof IllegalArgumentException);
    }

    @Test
    void subclassInheritsMap() {
        Admin admin = Admin.QUERY.on(db.db).newModel();

        Relation<?, ?> posts = admin.relation("posts");

        assertTrue(posts instanceof HasMany);
        assertEquals("posts", posts.getRelationName());
    }

    @Test
    void relatedInheritsTheParentConnection() {
        Rep rep = Rep.QUERY.on(db.db).newModel();

        assertSame(db.db, rep.posts().getRelated().getQueryFactory());
    }

    @Test
    void relatedWithItsOwnConnectionNameKeepsIt() throws SQLException {
        try (RelationDb crm = new RelationDb()) {
            ConnectionResolver.setDefault(new ConnectionResolver().register("crm", crm.db));
            Rep rep = Rep.QUERY.on(db.db).newModel();

            assertSame(crm.db, rep.leads().getRelated().getQueryFactory());
        }
    }

    @Test
    void defaultKeysFollowLaravel() {
        Rep rep = Rep.QUERY.on(db.db).newModel();

        HasOne<RelationModels.Profile> profile = rep.profile();
        BelongsTo<User> owner = rep.owner();

        assertEquals("profiles.rep_id", profile.getQualifiedForeignKeyName());
        assertEquals("id", profile.getLocalKeyName());
        assertEquals("boss", owner.getRelationName());
        assertEquals("owner_id", owner.getForeignKeyName());
    }
}

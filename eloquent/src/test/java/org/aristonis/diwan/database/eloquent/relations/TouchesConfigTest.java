package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.InvalidRelationException;
import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.aristonis.diwan.database.eloquent.exception.UnregisteredModelException;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TComment;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TPost;
import org.aristonis.diwan.database.eloquent.relations.TouchDb.TUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TouchesConfigTest {

    private TouchDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new TouchDb();
        List<ModelQuery<?>> entries = Arrays.asList(Ghost.QUERY, Typo.QUERY, Untouchable.QUERY, Counting.QUERY,
                PlainA.QUERY, PlainB.QUERY, WaitsForLate.QUERY);
        if (entries.contains(null)) {
            throw new IllegalStateException("test model entries not initialised");
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static Set<String> names(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }

    @Test
    void unknownNameRejected() {
        InvalidRelationException e = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(Ghost.QUERY.on(db.db).newModel()));

        assertSame(Ghost.class, e.getModelClass());
        assertEquals("nope", e.getRelation());
        assertEquals("not a registered relation", e.getReason());
        // The first use carries the lookup failure; a later use wraps that first exception.
        Throwable cause = e.getCause() instanceof InvalidRelationException ? e.getCause().getCause() : e.getCause();
        assertInstanceOf(RelationNotFoundException.class, cause);
    }

    @Test
    void typoRejected() {
        InvalidRelationException e = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(Typo.QUERY.on(db.db).newModel()));

        assertSame(Typo.class, e.getModelClass());
        assertEquals("pots", e.getRelation());
        assertTrue(e.getMessage().contains(Typo.class.getName()));
    }

    @Test
    void relationWithoutTouchSupportRejected() {
        InvalidRelationException e = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(Untouchable.QUERY.on(db.db).newModel()));

        assertEquals("plain", e.getRelation());
        assertEquals("relation type does not support touches", e.getReason());
    }

    @Test
    void failureCachedAndRethrownAsNewException() {
        InvalidRelationException first = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(Ghost.QUERY.on(db.db).newModel()));

        InvalidRelationException second = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(Ghost.QUERY.on(db.db).newModel()));

        assertNotSame(first, second);
        assertEquals(first.getMessage(), second.getMessage());
        assertInstanceOf(InvalidRelationException.class, second.getCause());
        assertEquals(first.getMessage(), second.getCause().getMessage());
    }

    @Test
    void validatedOncePerClass() {
        int before = Counting.READS.get();

        RelationTouches.Config first = RelationTouches.config(Counting.QUERY.on(db.db).newModel());
        RelationTouches.Config second = RelationTouches.config(Counting.QUERY.on(db.db).newModel());
        RelationTouches.Config third = RelationTouches.config(Counting.QUERY.on(db.db).newModel());

        assertEquals(1, Counting.READS.get() - before);
        assertSame(first, second);
        assertSame(first, third);
        assertEquals(Collections.singletonList("author"), first.names());
    }

    @Test
    void namesKeepTouchesOrder() {
        RelationTouches.Config config = RelationTouches.config(TComment.QUERY.on(db.db).newModel());

        assertEquals(Arrays.asList("post", "topic"), config.names());
        assertThrows(UnsupportedOperationException.class, () -> config.names().add("x"));
    }

    @Test
    void unregisteredRelatedNotCached() {
        WaitsForLate model = WaitsForLate.QUERY.on(db.db).newModel();

        InvalidRelationException e = assertThrows(InvalidRelationException.class,
                () -> RelationTouches.config(model));
        assertInstanceOf(UnregisteredModelException.class, e.getCause());
        assertNull(Access.eloquent().touchesSlot(model).get());

        Late.register();
        RelationTouches.Config config = RelationTouches.config(model);

        assertEquals(Collections.singletonList("late"), config.names());
        assertSame(config, Access.eloquent().touchesSlot(model).get());
    }

    @Test
    void emptyTouchesShareOneConfig() {
        RelationTouches.Config a = RelationTouches.config(PlainA.QUERY.on(db.db).newModel());
        RelationTouches.Config b = RelationTouches.config(PlainB.QUERY.on(db.db).newModel());

        assertSame(a, b);
        assertTrue(a.names().isEmpty());
        assertSame(a, Access.eloquent().touchesSlot(PlainA.QUERY.on(db.db).newModel()).get());
    }

    @Test
    void emptyTouchesRunNoQuery() {
        TUser user = TUser.QUERY.on(db.db).newModel();
        db.clearLog();

        Relation.ensureInitialised();
        Access.relations().touchOwners(user, new VisitPath());

        assertTrue(db.sql().isEmpty());
        assertEquals(0, user.savedCalls);
    }

    @Test
    void badConfigFailsTouchOwnersBeforeAnyQuery() {
        Ghost ghost = Ghost.QUERY.on(db.db).newModel();
        db.clearLog();

        Relation.ensureInitialised();
        assertThrows(InvalidRelationException.class, () -> Access.relations().touchOwners(ghost, new VisitPath()));

        assertTrue(db.sql().isEmpty());
    }

    @Test
    void touchOwnersRefusesNulls() {
        Relation.ensureInitialised();
        TPost post = TPost.QUERY.on(db.db).newModel();

        assertThrows(NullPointerException.class, () -> Access.relations().touchOwners(null, new VisitPath()));
        assertThrows(NullPointerException.class, () -> Access.relations().touchOwners(post, null));
    }

    /** Touches a name its map does not have. */
    static final class Ghost extends Model {
        static final ModelQuery<Ghost> QUERY = ModelQuery.of(Ghost.class, Ghost::new);

        @Override
        protected Set<String> touches() {
            return names("nope");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Ghost.class).add("author", Ghost::author);
        }

        public BelongsTo<TUser> author() {
            return belongsTo(TUser.class, "user_id");
        }
    }

    /** Touches a misspelt name. */
    static final class Typo extends Model {
        static final ModelQuery<Typo> QUERY = ModelQuery.of(Typo.class, Typo::new);

        @Override
        protected Set<String> touches() {
            return names("pots");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Typo.class).add("posts", Typo::posts);
        }

        public HasMany<TPost> posts() {
            return hasMany(TPost.class, "user_id");
        }
    }

    /** Touches a relation whose type does not support touches. */
    static final class Untouchable extends Model {
        static final ModelQuery<Untouchable> QUERY = ModelQuery.of(Untouchable.class, Untouchable::new);

        @Override
        protected Set<String> touches() {
            return names("plain");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Untouchable.class).add("plain", model -> new NoTouchRelation(
                    Access.eloquent().newQueryOn(TUser.QUERY.on(model.getQueryFactory()).newModel()), model));
        }
    }

    /** Counts how often its {@code touches()} is read. */
    static final class Counting extends Model {
        static final ModelQuery<Counting> QUERY = ModelQuery.of(Counting.class, Counting::new);

        static final AtomicInteger READS = new AtomicInteger();

        @Override
        protected Set<String> touches() {
            READS.incrementAndGet();
            return names("author");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(Counting.class).add("author", Counting::author);
        }

        public BelongsTo<TUser> author() {
            return belongsTo(TUser.class, "user_id");
        }
    }

    /** Touches nothing. */
    static final class PlainA extends Model {
        static final ModelQuery<PlainA> QUERY = ModelQuery.of(PlainA.class, PlainA::new);
    }

    /** Touches nothing either. */
    static final class PlainB extends Model {
        static final ModelQuery<PlainB> QUERY = ModelQuery.of(PlainB.class, PlainB::new);
    }

    /** Touches a relation to a class that registers only when a test asks. */
    static final class WaitsForLate extends Model {
        static final ModelQuery<WaitsForLate> QUERY = ModelQuery.of(WaitsForLate.class, WaitsForLate::new);

        @Override
        protected Set<String> touches() {
            return names("late");
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(WaitsForLate.class).add("late", WaitsForLate::late);
        }

        public BelongsTo<Late> late() {
            return belongsTo(Late.class, "late_id");
        }
    }

    /** Registered only by {@link #register()}, so its entry is missing until then. */
    static final class Late extends Model {
        static void register() {
            ModelQuery.of(Late.class, Late::new);
        }
    }

    /** A relation type that keeps the default {@code supportsTouch()} of {@code false}. */
    static final class NoTouchRelation extends Relation<TUser, NoTouchRelation> {

        NoTouchRelation(EloquentBuilder<TUser> query, Model parent) {
            super(query, parent, null);
        }

        @Override
        NoTouchRelation self() {
            return this;
        }

        @Override
        public Object getResults() {
            return null;
        }

        @Override
        void addConstraints() {
            // No constraint: the relation is only built, never queried.
        }

        @Override
        void initRelation(List<? extends Model> parents, String name) {
            // Never eager loaded.
        }

        @Override
        List<Object> eagerKeys(List<? extends Model> parents) {
            return Collections.emptyList();
        }

        @Override
        void prepareEagerQuery(EloquentBuilder<TUser> eagerQuery, List<Object> chunk) {
            // Never eager loaded.
        }

        @Override
        boolean inlinesEagerKeys() {
            return false;
        }

        @Override
        String eagerMatchColumn() {
            return "id";
        }

        @Override
        void match(List<? extends Model> parents, List<TUser> results, String name) {
            // Never eager loaded.
        }

        @Override
        EloquentBuilder<TUser> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
            throw new UnsupportedOperationException("not used by the touch tests");
        }
    }
}

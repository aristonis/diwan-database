package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.internal.AliasCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A package subclass of belongsToMany: its overridden hooks run in place of the defaults, and
 * chaining still returns the relation type.
 */
class BelongsToManySubclassTest {

    private static final String ROLE_LAZY_SQL = "select * from \"roles\" inner join \"role_user\" on "
            + "\"roles\".\"id\" = \"role_user\".\"role_id\" where \"users\".\"id\" = \"role_user\".\"user_id\"";

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
    void overriddenSelectStepAndPivotFactoryAreUsedByLazyRead() {
        RecordingRoles relation = recordingRoles(BtmModels.User.QUERY.on(db.db).findOrFail(1));

        ModelCollection<BtmModels.Role> roles = relation.get();

        assertEquals(2, roles.size());
        assertEquals(1, relation.calls.stream().filter("select"::equals).count());
        assertEquals(2, relation.calls.stream().filter("pivot"::equals).count());
        assertNotNull(Pivot.of(roles.get(0)));
    }

    @Test
    void overriddenExistenceQueryIsUsedBySubqueryBuild() {
        RecordingRoles relation = recordingRoles(BtmModels.User.QUERY.on(db.db).findOrFail(1));
        EloquentBuilder<BtmModels.User> outer = BtmModels.User.QUERY.on(db.db).query();

        String sql = relation.existenceQuery(outer, new AliasCounter()).toSql();

        assertEquals(Collections.singletonList("existence"), relation.calls);
        assertEquals(ROLE_LAZY_SQL, sql);
    }

    @Test
    void overriddenSelectStepRunsOnEagerCompletion() {
        RecordingRoles relation = recordingRoles(BtmModels.User.QUERY.on(db.db).findOrFail(1));
        EloquentBuilder<BtmModels.Role> eager = BtmModels.Role.QUERY.on(db.db).query();

        relation.completeEagerQuery(eager);

        assertTrue(relation.calls.contains("select"));
        assertTrue(eager.toSql().startsWith("select \"roles\".*, \"role_user\".\"user_id\" as \"pivot_user_id\""));
    }

    @Test
    void chainingOnSubclassStillReturnsTheSameRelation() {
        RecordingRoles relation = recordingRoles(BtmModels.User.QUERY.on(db.db).findOrFail(1));

        BelongsToMany<BtmModels.Role> chained = relation.withPivot("status").wherePivot("status", "active");

        assertSame(relation, chained);
    }

    private RecordingRoles recordingRoles(BtmModels.User user) {
        return new RecordingRoles(BtmModels.Role.QUERY.on(db.db).query(), user, "role_user", "user_id", "role_id",
                "id", "id", "roles");
    }

    /** A relation that records each overridden hook it runs, then runs the default. */
    static final class RecordingRoles extends BelongsToMany<BtmModels.Role> {

        final List<String> calls = new ArrayList<>();

        RecordingRoles(EloquentBuilder<BtmModels.Role> query, Model parent, String table, String foreignPivotKey,
                String relatedPivotKey, String parentKey, String relatedKey, String relation) {
            super(query, parent, table, foreignPivotKey, relatedPivotKey, parentKey, relatedKey, relation);
        }

        @Override
        EloquentBuilder<BtmModels.Role> existenceQuery(EloquentBuilder<?> parentQuery, AliasCounter aliases) {
            calls.add("existence");
            return super.existenceQuery(parentQuery, aliases);
        }

        @Override
        EloquentBuilder<BtmModels.Role> selectFor(EloquentBuilder<BtmModels.Role> builder, Select mode,
                String... columns) {
            calls.add("select");
            return super.selectFor(builder, mode, columns);
        }

        @Override
        public Pivot newPivot(Map<String, ?> attributes, boolean exists) {
            calls.add("pivot");
            return super.newPivot(attributes, exists);
        }
    }
}

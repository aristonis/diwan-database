package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Role;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.aristonis.diwan.database.query.Builder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pivot table query and statement of a belongsToMany relation, and the pivots it builds. No
 * statement runs: the tests read the SQL and the bindings of the builders.
 */
class PivotQueryTest {

    private static final String STATUS = "\"role_user\".\"status\"";
    private static final String WEIGHT = "\"role_user\".\"weight\"";
    private static final String KEY = "\"role_user\".\"user_id\" = ?";

    private BtmDb db;
    private User user;

    @BeforeEach
    void open() throws SQLException {
        db = BtmDb.open();
        user = User.QUERY.on(db.db).findOrFail(1);
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void replayKeepsDeclaredOrderOfClauseKinds() {
        BelongsToMany<Role> roles = user.roles()
                .wherePivotNull("status")
                .wherePivotBetween("weight", Arrays.<Object>asList(1, 5))
                .wherePivotIn("status", Arrays.asList("a", "b"))
                .wherePivot("weight", 3);

        Builder query = roles.newPivotQuery();

        assertEquals("select * from \"role_user\" where (" + STATUS + " is null and " + WEIGHT
                + " between ? and ? and " + STATUS + " in (?, ?) and " + WEIGHT + " = ?) and " + KEY,
                query.toSql());
        assertEquals(Arrays.<Object>asList(1, 5, "a", "b", 3, user.getAttribute("id")), query.getBindings());
    }

    @Test
    void orClausesStayInsideTheirGroupAheadOfTheParentKey() {
        BelongsToMany<Role> roles = user.roles().wherePivot("status", "a").orWherePivot("status", "b");

        Builder query = roles.newPivotQuery();

        assertEquals("select * from \"role_user\" where (" + STATUS + " = ? or " + STATUS + " = ?) and " + KEY,
                query.toSql());
    }

    @Test
    void queryWithoutClausesHasOnlyTheParentKey() {
        Builder query = user.roles().newPivotQuery();

        assertEquals("select * from \"role_user\" where " + KEY, query.toSql());
        assertEquals(Collections.singletonList(user.getAttribute("id")), query.getBindings());
    }

    @Test
    void pivotStatementHasNoConstraints() {
        Builder statement = user.roles().wherePivot("status", "a").newPivotStatement();

        assertEquals("select * from \"role_user\"", statement.toSql());
        assertTrue(statement.getBindings().isEmpty());
    }

    @Test
    void newExistingPivotIsMarkedAsExisting() {
        BelongsToMany<Role> roles = user.roles();

        Pivot pivot = roles.newExistingPivot(Collections.singletonMap("status", "active"));

        assertTrue(pivot.exists());
        assertEquals("user_id", pivot.getForeignPivotKeyName());
        assertEquals("role_id", pivot.getRelatedPivotKeyName());
        assertTrue(pivot.getRelatedModel() instanceof Role);
        assertEquals("active", pivot.getAttribute("status"));
    }

    @Test
    void newPivotMergesDeclaredValuesUnderGivenAttributes() {
        BelongsToMany<Role> roles = user.roles().withPivotValue("status", "declared").withPivotValue("weight", 7);
        Map<String, Object> given = new LinkedHashMap<>();
        given.put("status", "given");

        Pivot pivot = roles.newPivot(given, false);

        assertEquals("given", pivot.getAttribute("status"));
        assertEquals(7, pivot.getAttribute("weight"));
        List<String> names = Arrays.asList("status", "weight");
        assertTrue(pivot.getAttributes().keySet().containsAll(names));
    }
}

package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.Role;
import org.aristonis.diwan.database.eloquent.relations.BtmModels.User;
import org.aristonis.diwan.database.eloquent.relations.PivotClause.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The recorded pivot conditions of a belongsToMany relation: their order, their connectors and
 * the copies handed out to callers.
 */
class PivotClauseRecordTest {

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
    void callOrderAndConnectors() {
        BelongsToMany<Role> roles = user.roles()
                .wherePivot("status", "active")
                .orWherePivot("weight", ">", 2)
                .wherePivotNotIn("status", Arrays.asList("x", "y"))
                .orWherePivotNotNull("weight");

        List<PivotClause> clauses = roles.pivotClauses();

        assertEquals(4, clauses.size());
        assertClause(clauses.get(0), Kind.BASIC, "role_user.status", null, false, false);
        assertClause(clauses.get(1), Kind.BASIC, "role_user.weight", ">", true, false);
        assertClause(clauses.get(2), Kind.IN, "role_user.status", null, false, true);
        assertClause(clauses.get(3), Kind.NULL, "role_user.weight", null, true, true);
    }

    @Test
    void twoArgumentFormRecordsNoOperator() {
        BelongsToMany<Role> roles = user.roles().wherePivot("status", "active");

        assertNull(roles.pivotClauses().get(0).operator());
        assertEquals(Collections.singletonList("active"), roles.pivotClauses().get(0).values());
    }

    @Test
    void withPivotValueRecordsBothWhereAndValue() {
        BelongsToMany<Role> roles = user.roles().withPivotValue("status", "active");

        assertEquals(1, roles.pivotClauses().size());
        assertClause(roles.pivotClauses().get(0), Kind.BASIC, "role_user.status", "=", false, false);
        assertEquals(Collections.singletonMap("status", "active"), roles.pivotValues());
    }

    @Test
    void pivotClausesAreACopy() {
        BelongsToMany<Role> roles = user.roles().wherePivot("status", "active");
        List<PivotClause> before = roles.pivotClauses();

        roles.wherePivot("weight", 1);

        assertEquals(1, before.size());
        assertThrows(UnsupportedOperationException.class,
                () -> before.add(PivotClause.basic("a", null, 1, false)));
    }

    @Test
    void pivotValuesAreACopy() {
        BelongsToMany<Role> roles = user.roles().withPivotValue("status", "active");
        Map<String, Object> before = roles.pivotValues();

        roles.withPivotValue("weight", 3);

        assertEquals(1, before.size());
        assertFalse(before.containsKey("weight"));
        assertThrows(UnsupportedOperationException.class, () -> before.put("weight", 1));
        assertTrue(roles.pivotValues().containsKey("weight"));
    }

    private static void assertClause(PivotClause clause, Kind kind, String column, String operator,
            boolean isOr, boolean isNot) {
        assertEquals(kind, clause.kind());
        assertEquals(column, clause.column());
        assertEquals(operator, clause.operator());
        assertEquals(isOr, clause.isOr());
        assertEquals(isNot, clause.isNot());
    }
}

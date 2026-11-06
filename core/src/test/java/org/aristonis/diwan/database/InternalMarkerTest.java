package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverSession;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Having;
import org.aristonis.diwan.database.query.Where;
import org.aristonis.diwan.database.query.WhereExists;
import org.aristonis.diwan.database.query.WhereInSub;
import org.aristonis.diwan.database.query.WhereRowValues;
import org.aristonis.diwan.database.query.WhereSub;
import org.aristonis.diwan.database.query.WhereSubValue;
import org.junit.jupiter.api.Test;

/** The markers that tell callers which public types and getters are not part of the public API. */
class InternalMarkerTest {

    private static final List<String> BUILDER_STATE_GETTERS = List.of(
            "getColumns", "isDistinct", "getFromExpression", "getJoins", "getWheres", "getGroups",
            "getHavings", "getOrders", "getLimit", "getOffset", "getAggregate", "getUnions",
            "getUnionOrders", "getUnionLimit", "getUnionOffset");

    @Test
    void clauseTypesAreMarkedInternal() {
        assertMarked(Where.class, Having.class, WhereExists.class, WhereInSub.class,
                WhereRowValues.class, WhereSub.class, WhereSubValue.class);
    }

    @Test
    void everyNestedWhereAndHavingTypeIsMarkedInternal() {
        assertMarked(Where.class.getDeclaredClasses());
        assertMarked(Having.class.getDeclaredClasses());
    }

    @Test
    void driverSpiTypesAreMarkedInternal() {
        assertMarked(Driver.class, DriverSession.class, RowCursor.class);
    }

    @Test
    void builderStateClauseGettersAreMarkedInternal() throws NoSuchMethodException {
        for (String name : BUILDER_STATE_GETTERS) {
            Method getter = Builder.class.getMethod(name);
            assertTrue(getter.isAnnotationPresent(Internal.class), name + " should be @Internal");
        }
    }

    private static void assertMarked(Class<?>... types) {
        assertTrue(types.length > 0, "no types to check");
        for (Class<?> type : types) {
            assertTrue(type.isAnnotationPresent(Internal.class), type.getName() + " should be @Internal");
        }
    }
}

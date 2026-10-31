package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DriverInfoTest {

    @Test
    void reportsDatabaseNameAndVersion() {
        DriverInfo info = new DriverInfo("SQLite", "3.54.1", EnumSet.noneOf(DriverCapability.class));

        assertEquals("SQLite", info.databaseName());
        assertEquals("3.54.1", info.databaseVersion());
    }

    @Test
    void answersWhetherACapabilityIsSupported() {
        DriverInfo info = new DriverInfo("PostgreSQL", "17", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE));

        assertTrue(info.supports(DriverCapability.SAVEPOINT_RELEASE));
        assertFalse(info.supports(DriverCapability.TRANSACTIONAL_DDL));
    }

    @Test
    void exposesCapabilitiesAsAnUnmodifiableCopy() {
        EnumSet<DriverCapability> given = EnumSet.of(DriverCapability.TRANSACTIONAL_DDL);
        DriverInfo info = new DriverInfo("SQLite", "3", given);

        given.clear();

        assertTrue(info.supports(DriverCapability.TRANSACTIONAL_DDL));
        assertThrows(UnsupportedOperationException.class,
                () -> info.capabilities().add(DriverCapability.SAVEPOINT_RELEASE));
    }

    @Test
    void refusesMissingParts() {
        EnumSet<DriverCapability> none = EnumSet.noneOf(DriverCapability.class);

        assertThrows(NullPointerException.class, () -> new DriverInfo(null, "1", none));
        assertThrows(NullPointerException.class, () -> new DriverInfo("x", null, none));
        assertThrows(NullPointerException.class, () -> new DriverInfo("x", "1", null));
    }

    @Test
    void equalsAnotherInfoWithTheSameParts() {
        DriverInfo first = new DriverInfo("SQLite", "3", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE));
        DriverInfo same = new DriverInfo("SQLite", "3", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE));
        DriverInfo other = new DriverInfo("SQLite", "4", EnumSet.of(DriverCapability.SAVEPOINT_RELEASE));

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(other));
        assertFalse(first.equals(new DriverInfo("SQLite", "3", EnumSet.noneOf(DriverCapability.class))));
        assertFalse(first.equals("SQLite"));
    }

    @Test
    void describesItselfWithNameAndVersion() {
        String text = new DriverInfo("SQLite", "3.54", EnumSet.noneOf(DriverCapability.class)).toString();

        assertEquals("DriverInfo[SQLite 3.54, capabilities=[]]", text);
    }

    @Test
    void refusesANullCapability() {
        Set<DriverCapability> withNull = new HashSet<>();
        withNull.add(null);
        DriverInfo info = new DriverInfo("x", "1", EnumSet.noneOf(DriverCapability.class));

        assertThrows(NullPointerException.class, () -> new DriverInfo("x", "1", withNull));
        assertThrows(NullPointerException.class, () -> info.supports(null));
    }

    @Test
    void listsCapabilitiesInDeclarationOrder() {
        Set<DriverCapability> given = new HashSet<>(
                Arrays.asList(DriverCapability.TRANSACTIONAL_DDL, DriverCapability.SAVEPOINT_RELEASE));

        DriverInfo info = new DriverInfo("x", "1", given);

        assertEquals(Arrays.asList(DriverCapability.SAVEPOINT_RELEASE, DriverCapability.TRANSACTIONAL_DDL),
                new ArrayList<>(info.capabilities()));
    }
}

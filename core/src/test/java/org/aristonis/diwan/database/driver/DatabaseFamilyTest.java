package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DatabaseFamilyTest {

    @Test
    void recognisesProductNamesIgnoringCaseAndSuffixes() {
        assertEquals(DatabaseFamily.SQLITE, DatabaseFamily.of("SQLite"));
        assertEquals(DatabaseFamily.POSTGRESQL, DatabaseFamily.of("PostgreSQL"));
        assertEquals(DatabaseFamily.MYSQL, DatabaseFamily.of("MySQL"));
        assertEquals(DatabaseFamily.MYSQL, DatabaseFamily.of("MariaDB"));
        assertEquals(DatabaseFamily.SQLSERVER, DatabaseFamily.of("Microsoft SQL Server"));
        assertEquals(DatabaseFamily.ORACLE, DatabaseFamily.of("Oracle"));
        assertEquals(DatabaseFamily.SQLITE, DatabaseFamily.of("sqlite 3 (android)"));
    }

    @Test
    void unknownOrMissingNameIsOther() {
        assertEquals(DatabaseFamily.OTHER, DatabaseFamily.of("H2"));
        assertEquals(DatabaseFamily.OTHER, DatabaseFamily.of((String) null));
    }

    @Test
    void readsTheFamilyFromDriverInfo() {
        DriverInfo info = new DriverInfo("PostgreSQL", "16", EnumSet.noneOf(DriverCapability.class));

        assertEquals(DatabaseFamily.POSTGRESQL, DatabaseFamily.of(info));
        assertThrows(NullPointerException.class, () -> DatabaseFamily.of((DriverInfo) null));
    }
}

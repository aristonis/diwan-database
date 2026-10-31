package org.aristonis.photisma.database.migrations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aristonis.photisma.database.driver.DatabaseFamily;
import org.junit.jupiter.api.Test;

class MigrationTableSqlTest {

    @Test
    void picksTheCatalogQueryForEachSupportedFamily() {
        assertTrue(MigrationTableSql.exists(DatabaseFamily.SQLITE).contains("sqlite_master"));
        assertTrue(MigrationTableSql.exists(DatabaseFamily.POSTGRESQL).contains("current_schema()"));
        assertTrue(MigrationTableSql.exists(DatabaseFamily.MYSQL).contains("database()"));
    }

    @Test
    void refusesFamiliesWithoutAKnownQuery() {
        assertThrows(UnsupportedOperationException.class, () -> MigrationTableSql.exists(DatabaseFamily.OTHER));
        assertThrows(UnsupportedOperationException.class, () -> MigrationTableSql.exists(DatabaseFamily.ORACLE));
    }
}

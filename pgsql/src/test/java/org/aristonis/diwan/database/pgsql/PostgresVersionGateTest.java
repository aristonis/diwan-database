package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Checks that the column query selects the identity and generation columns only on servers that have them. */
class PostgresVersionGateTest {

    private static final String IDENTITY_STUB = "'' as attidentity";
    private static final String GENERATED_STUB = "'' as attgenerated";

    @Test
    void version9StubsIdentityAndGeneration() {
        String sql = PostgresSchemaInspection.columnsSql("9.6");

        assertTrue(sql.contains(IDENTITY_STUB));
        assertTrue(sql.contains(GENERATED_STUB));
    }

    @Test
    void version11KeepsIdentityAndStubsGeneration() {
        String sql = PostgresSchemaInspection.columnsSql("11.2");

        assertFalse(sql.contains(IDENTITY_STUB));
        assertTrue(sql.contains(GENERATED_STUB));
    }

    @Test
    void version16WithVendorSuffixSelectsBothCatalogColumns() {
        String sql = PostgresSchemaInspection.columnsSql("16.2 (Debian 16.2-1)");

        assertFalse(sql.contains(IDENTITY_STUB));
        assertFalse(sql.contains(GENERATED_STUB));
    }
}

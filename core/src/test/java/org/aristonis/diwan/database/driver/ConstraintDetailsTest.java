package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConstraintDetailsTest {

    /** Stands in for the PostgreSQL driver's server error message. */
    public static class FakeServerError {
        public String getConstraint() {
            return "users_email_unique";
        }

        public String getColumn() {
            return "email";
        }
    }

    /** Stands in for the PostgreSQL driver's exception. */
    public static class FakeSqlException extends Exception {
        private static final long serialVersionUID = 1L;

        public FakeServerError getServerErrorMessage() {
            return new FakeServerError();
        }
    }

    /** An exception whose server error accessor fails. */
    public static class BrokenSqlException extends Exception {
        private static final long serialVersionUID = 1L;

        public Object getServerErrorMessage() {
            throw new IllegalStateException("no");
        }
    }

    @Test
    void sqliteUniqueGivesTheColumnsAfterTheDot() {
        ConstraintDetails details = ConstraintDetails.sqlite("UNIQUE constraint failed: t.a, t.b");

        assertEquals(Arrays.asList("a", "b"), details.columns());
        assertFalse(details.key().isPresent());
    }

    @Test
    void sqliteNotNullGivesTheColumn() {
        assertEquals(Arrays.asList("a"), ConstraintDetails.sqlite("NOT NULL constraint failed: t.a").columns());
    }

    @Test
    void sqliteColumnsStopBeforeTheAndroidCodeSuffixAndTheClosingBracket() {
        assertEquals(Arrays.asList("email"), ConstraintDetails.sqlite(
                "UNIQUE constraint failed: u.email (code 2067 SQLITE_CONSTRAINT_UNIQUE)").columns());
        assertEquals(Arrays.asList("email"), ConstraintDetails.sqlite(
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: u.email)")
                .columns());
    }

    @Test
    void sqliteCheckGivesTheNameAsKey() {
        assertEquals("positive", ConstraintDetails.sqlite("CHECK constraint failed: positive").key().orElseThrow());
        assertEquals("positive", ConstraintDetails.sqlite(
                "CHECK constraint failed: positive (code 275 SQLITE_CONSTRAINT_CHECK)").key().orElseThrow());
        assertEquals("positive", ConstraintDetails.sqlite(
                "[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed (CHECK constraint failed: positive)")
                .key().orElseThrow());
    }

    @Test
    void sqliteForeignKeyAndOtherTextGiveNothing() {
        for (String text : new String[] {"FOREIGN KEY constraint failed", "no such table: x", "", null}) {
            ConstraintDetails details = ConstraintDetails.sqlite(text);
            assertFalse(details.key().isPresent());
            assertTrue(details.columns().isEmpty());
        }
    }

    @Test
    void namesLongerThan128CharactersAreDropped() {
        String longName = new String(new char[129]).replace('\0', 'x');

        assertTrue(ConstraintDetails.sqlite("NOT NULL constraint failed: t." + longName).columns().isEmpty());
        assertFalse(ConstraintDetails.sqlite("CHECK constraint failed: " + longName).key().isPresent());
    }

    @Test
    void postgresReadsTheServerErrorByReflection() {
        ConstraintDetails details = ConstraintDetails.postgres(new FakeSqlException());

        assertEquals("users_email_unique", details.key().orElseThrow());
        assertEquals(Arrays.asList("email"), details.columns());
    }

    @Test
    void postgresUniqueGivesTheKeyOnly() {
        ConstraintDetails details = ConstraintDetails.postgres(new FakeSqlException(), true);

        assertEquals("users_email_unique", details.key().orElseThrow());
        assertTrue(details.columns().isEmpty());
    }

    @Test
    void postgresReflectionFailureGivesNothing() {
        for (Throwable failure : new Throwable[] {new BrokenSqlException(), new Exception("plain"), null}) {
            ConstraintDetails details = ConstraintDetails.postgres(failure);
            assertFalse(details.key().isPresent());
            assertTrue(details.columns().isEmpty());
        }
    }
}

package org.aristonis.diwan.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.Test;

class QueryExceptionTest {

    private static DriverException failure(String state, DriverFailureKind... kinds) {
        EnumSet<DriverFailureKind> set = EnumSet.noneOf(DriverFailureKind.class);
        set.addAll(Arrays.asList(kinds));
        return new DriverException("boom", state, 7, null, set);
    }

    @Test
    void carriesWhatFailedAsFields() {
        DriverException cause = failure("42P01");

        QueryException error = new QueryException(
                "main", "select * from missing where id = ?", Arrays.<Object>asList(42), cause, false);

        assertEquals("main", error.getConnectionName());
        assertEquals("select * from missing where id = ?", error.getSql());
        assertEquals(Arrays.<Object>asList(42), error.getBindings());
        assertEquals("42P01", error.sqlState().orElseThrow());
        assertEquals(7, error.vendorCode());
        assertSame(cause, error.getCause());
        assertThrows(UnsupportedOperationException.class, () -> error.getBindings().add(1));
    }

    @Test
    void hidesBindingValuesInTheMessageByDefault() {
        QueryException error = new QueryException(
                "main", "select * from users where email = ?", Arrays.<Object>asList("ana@example.com"),
                failure("42P01"), false);

        assertEquals("SQL: select * from users where email = ? (Connection: main, SQL state 42P01, vendor code 7)",
                error.getMessage());
        assertFalse(error.getMessage().contains("ana@example.com"));
    }

    @Test
    void showsBindingValuesWhenAskedTo() {
        QueryException error = new QueryException(
                "main", "select * from users where email = ?", Arrays.<Object>asList("ana@example.com"),
                failure("42P01"), true);

        assertTrue(error.getMessage().endsWith("Bindings: [ana@example.com]"));
    }

    @Test
    void namesAMissingSqlStateAsUnknown() {
        QueryException error = new QueryException("main", "select 1", Arrays.asList(), failure(null), false);

        assertEquals("SQL: select 1 (Connection: main, SQL state unknown, vendor code 7)", error.getMessage());
    }

    @Test
    void reportsAConcurrencyFailureFromItsCause() {
        QueryException error = new QueryException(
                "main", "update t set a = 1", Arrays.asList(), failure("40001", DriverFailureKind.CONCURRENCY), false);

        assertTrue(error.isConcurrencyFailure());
    }

    @Test
    void uniqueViolationIsAQueryException() {
        UniqueConstraintViolationException error = new UniqueConstraintViolationException(
                "main", "insert into t values (?)", Arrays.<Object>asList(1),
                failure("23505", DriverFailureKind.UNIQUE_VIOLATION), false);

        assertTrue(error instanceof QueryException);
        assertFalse(error.isConcurrencyFailure());
    }

    @Test
    void multipleColumnsSelectedIsUnchecked() {
        MultipleColumnsSelectedException error = new MultipleColumnsSelectedException("select a, b from t");

        assertTrue(error instanceof RuntimeException);
        assertTrue(error.getMessage().contains("select a, b from t"));
    }

    private static DriverException failureWithReason(String reason) {
        return new DriverException("boom", "HY000", 1, reason, null, EnumSet.noneOf(DriverFailureKind.class));
    }

    @Test
    void addsTheDatabaseReasonOnlyWhenValuesAreShown() {
        DriverException cause = failureWithReason("no such table: tagz");

        QueryException hidden = new QueryException("main", "select 1", Arrays.asList(), cause, false);
        QueryException shown = new QueryException("main", "select 1", Arrays.asList(), cause, true);

        assertFalse(hidden.getMessage().contains("tagz"));
        assertTrue(shown.getMessage().endsWith("Bindings: [] Reason: no such table: tagz"));
        assertEquals("no such table: tagz", cause.databaseMessage().orElseThrow());
        assertFalse(failure("1").databaseMessage().isPresent());
    }

    @Test
    void escapesAndShortensTheDatabaseReason() {
        StringBuilder longReason = new StringBuilder("line1\nline2 ");
        for (int i = 0; i < 1100; i++) {
            longReason.append('x');
        }

        String message = new QueryException("main", "select 1", Arrays.asList(),
                failureWithReason(longReason.toString()), true).getMessage();

        assertTrue(message.contains("Reason: line1\\nline2 x"));
        assertTrue(message.endsWith("..."));
        assertFalse(message.contains("\n" + "line2"));
    }

    @Test
    void theDatabaseReasonIsNotSerialized() throws Exception {
        DriverException cause = failureWithReason("Duplicate entry 'ana@example.com'");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(cause);
        }
        DriverException copy;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (DriverException) in.readObject();
        }

        assertFalse(copy.databaseMessage().isPresent());
        assertFalse(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("ana@"));
    }
}

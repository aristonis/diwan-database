package org.aristonis.photisma.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DriverExceptionTest {

    @Test
    void carriesSqlStateVendorCodeAndCause() {
        Exception cause = new Exception("socket closed");

        DriverException failure = new DriverException(
                "insert failed", "23505", 1062, cause, EnumSet.noneOf(DriverFailureKind.class));

        assertEquals("insert failed", failure.getMessage());
        assertEquals("23505", failure.sqlState().orElseThrow());
        assertEquals(1062, failure.vendorCode());
        assertSame(cause, failure.getCause());
    }

    @Test
    void reportsNoSqlStateWhenTheDriverGaveNone() {
        DriverException failure = new DriverException(
                "busy", null, 5, null, EnumSet.noneOf(DriverFailureKind.class));

        assertFalse(failure.sqlState().isPresent());
    }

    @Test
    void flagsAUniqueViolation() {
        DriverException failure = new DriverException(
                "duplicate", "23505", 0, null, EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION));

        assertTrue(failure.isUniqueViolation());
        assertFalse(failure.isConcurrencyFailure());
    }

    @Test
    void flagsAConcurrencyFailure() {
        DriverException failure = new DriverException(
                "deadlock", "40001", 0, null, EnumSet.of(DriverFailureKind.CONCURRENCY));

        assertTrue(failure.isConcurrencyFailure());
        assertFalse(failure.isUniqueViolation());
    }

    @Test
    void keepsItsOwnCopyOfTheKinds() {
        EnumSet<DriverFailureKind> kinds = EnumSet.of(DriverFailureKind.CONCURRENCY);
        DriverException failure = new DriverException("deadlock", "40001", 0, null, kinds);

        kinds.clear();

        assertTrue(failure.isConcurrencyFailure());
    }

    @Test
    void refusesNullKinds() {
        assertThrows(NullPointerException.class, () -> new DriverException("x", null, 0, null, null));
    }

    @Test
    void canBeCaughtAsAnUncheckedException() {
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw new DriverException("x", Collections.<DriverFailureKind>emptySet(), null);
        });

        assertTrue(caught instanceof DriverException);
    }

    @Test
    void acceptsAnEmptySetThatIsNotAnEnumSet() {
        DriverException failure = new DriverException("x", null, 0, null, new HashSet<DriverFailureKind>());

        assertTrue(failure.kinds().isEmpty());
    }

    @Test
    void exposesItsKindsAsAnUnmodifiableSet() {
        DriverException failure = new DriverException(
                "deadlock", "40001", 0, null, EnumSet.of(DriverFailureKind.CONCURRENCY));

        assertEquals(EnumSet.of(DriverFailureKind.CONCURRENCY), failure.kinds());
        assertThrows(UnsupportedOperationException.class,
                () -> failure.kinds().add(DriverFailureKind.UNIQUE_VIOLATION));
    }

    @Test
    void offersAShortConstructorForFailuresWithoutCodes() {
        Exception cause = new Exception("io");

        DriverException failure = new DriverException(
                "duplicate column label: id", EnumSet.noneOf(DriverFailureKind.class), cause);

        assertFalse(failure.sqlState().isPresent());
        assertEquals(0, failure.vendorCode());
        assertSame(cause, failure.getCause());
    }

    @Test
    void survivesSerialization() throws Exception {
        DriverException original = new DriverException(
                "dup", "23505", 1062, null, EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        DriverException copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (DriverException) in.readObject();
        }

        assertEquals("23505", copy.sqlState().orElseThrow());
        assertEquals(1062, copy.vendorCode());
        assertTrue(copy.isUniqueViolation());
        assertEquals(original.kinds(), copy.kinds());
    }

    @Test
    void refusesANullKind() {
        Set<DriverFailureKind> withNull = new HashSet<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> new DriverException("x", null, 0, null, withNull));
    }
}

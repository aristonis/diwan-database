package org.aristonis.diwan.database.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.aristonis.diwan.database.exception.DiwanError;
import org.aristonis.diwan.database.exception.DriverException;
import org.junit.jupiter.api.Test;

class DiwanErrorMappingTest {

    private static DiwanError codeFor(DriverFailureKind... kinds) {
        Set<DriverFailureKind> set = EnumSet.noneOf(DriverFailureKind.class);
        set.addAll(Arrays.asList(kinds));
        return DiwanError.of(set, null, Collections.<String>emptyList());
    }

    @Test
    void everyKindHasItsStableCode() {
        assertEquals("DWN-CST-0001", codeFor(DriverFailureKind.UNIQUE_VIOLATION).code());
        assertEquals("DWN-CST-0002", codeFor(DriverFailureKind.FOREIGN_KEY_VIOLATION).code());
        assertEquals("DWN-CST-0003", codeFor(DriverFailureKind.CHECK_VIOLATION).code());
        assertEquals("DWN-CST-0004", codeFor(DriverFailureKind.NOT_NULL_VIOLATION).code());
        assertEquals("DWN-TXN-0001", codeFor(DriverFailureKind.CONCURRENCY).code());
        assertEquals("DWN-TXN-0003", codeFor(DriverFailureKind.COMMIT_OUTCOME_UNKNOWN).code());
        assertEquals("DWN-CNX-0001", codeFor(DriverFailureKind.CONNECTION_LOST).code());
        assertEquals("DWN-QRY-0001", codeFor(DriverFailureKind.CANCELLED).code());
        assertEquals("DWN-UNK-0000", codeFor().code());
    }

    @Test
    void unknown() {
        DiwanError error = DiwanError.of(EnumSet.noneOf(DriverFailureKind.class), null, Collections.emptyList());

        assertEquals("DWN-UNK-0000", error.code());
        assertFalse(error.key().isPresent());
        assertTrue(error.columns().isEmpty());
    }

    @Test
    void theLockWaitCodeIsReservedAndMapsToNoKind() {
        assertEquals("DWN-TXN-0002", DiwanErrorCodes.LOCK_WAIT_RESERVED);
        assertFalse(DiwanErrorCodes.kindOf(DiwanErrorCodes.LOCK_WAIT_RESERVED).isPresent());
    }

    @Test
    void theFirstKindInTheTableWins() {
        assertEquals("DWN-CST-0001",
                codeFor(DriverFailureKind.CONNECTION_LOST, DriverFailureKind.UNIQUE_VIOLATION).code());
        assertEquals("DWN-CST-0003",
                codeFor(DriverFailureKind.NOT_NULL_VIOLATION, DriverFailureKind.CHECK_VIOLATION).code());
        assertEquals("DWN-TXN-0001", codeFor(DriverFailureKind.CANCELLED, DriverFailureKind.CONCURRENCY).code());
    }

    @Test
    void keyAndColumnsAreExposedAndColumnsAreUnmodifiable() {
        DiwanError error = DiwanError.of(EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION), "k", Arrays.asList("a"));

        assertEquals("k", error.key().orElseThrow());
        assertEquals(Arrays.asList("a"), error.columns());
        assertThrows(UnsupportedOperationException.class, () -> error.columns().add("b"));
        assertFalse(codeFor().key().isPresent());
    }

    @Test
    void equalErrorsAreEqual() {
        DiwanError one = DiwanError.of(EnumSet.of(DriverFailureKind.CHECK_VIOLATION), "k", Arrays.asList("a"));
        DiwanError two = DiwanError.of(EnumSet.of(DriverFailureKind.CHECK_VIOLATION), "k", Arrays.asList("a"));

        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
        assertTrue(one.toString().contains("DWN-CST-0003"));
    }

    @Test
    void tableIsExposedSurvivesSerializationAndTakesPartInEquality() throws Exception {
        Set<DriverFailureKind> kinds = EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION);
        DiwanError withTable = DiwanError.of(kinds, "k", "users", Arrays.asList("a"));
        DiwanError otherTable = DiwanError.of(kinds, "k", "posts", Arrays.asList("a"));
        DiwanError withoutTable = DiwanError.of(kinds, "k", Arrays.asList("a"));

        assertEquals("users", withTable.table().orElseThrow());
        assertFalse(withoutTable.table().isPresent());
        assertFalse(withTable.equals(otherTable));
        assertFalse(withTable.hashCode() == otherTable.hashCode());
        assertFalse(withTable.equals(withoutTable));
        assertEquals(withTable, roundTrip(new DriverException("m", null, 0, null, null, kinds, "k", "users",
                Arrays.asList("a"))).error());
        DiwanError copy = roundTrip(new DriverException("m", null, 0, null, null, kinds, "k",
                Arrays.asList("a"))).error();
        assertFalse(copy.table().isPresent());
        assertEquals(withoutTable, copy);
    }

    @Test
    void aDriverExceptionCarriesTheErrorAndSurvivesSerialization() throws Exception {
        DriverException failure = new DriverException("m", "23505", 0, null, null,
                EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION), "users_email_unique", Arrays.asList("email"));

        DriverException copy = roundTrip(failure);

        assertEquals(failure.error(), copy.error());
    }

    @Test
    void oldConstructorsGiveAnErrorWithoutKeyAndColumns() {
        DriverException failure = new DriverException("m", "23505", 0, null,
                EnumSet.of(DriverFailureKind.UNIQUE_VIOLATION));

        assertEquals("DWN-CST-0001", failure.error().code());
        assertFalse(failure.error().key().isPresent());
        assertTrue(failure.error().columns().isEmpty());
    }

    @Test
    void anErrorFieldMissingFromAnOldStreamIsDerivedFromTheKinds() throws Exception {
        DriverException failure = new DriverException("m", "40001", 0, null,
                EnumSet.of(DriverFailureKind.CONCURRENCY));
        Field field = DriverException.class.getDeclaredField("error");
        field.setAccessible(true);
        field.set(failure, null);

        assertEquals("DWN-TXN-0001", failure.error().code());
        assertFalse(failure.error().key().isPresent());
    }

    private static DriverException roundTrip(DriverException failure) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(failure);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (DriverException) in.readObject();
        }
    }
}

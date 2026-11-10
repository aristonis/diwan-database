package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidSqliteSessionTest {

    private SQLiteDatabase database;
    private AndroidSqliteDriver driver;
    private Connection db;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        driver = AndroidSqliteDriver.of(database);
        db = Connection.builder().name("android").driver(driver).build();
        db.statement("create table v (i integer, r real, t text, b blob)");
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void everyBindingTypeRoundTrips() {
        db.insert("insert into v (i, r, t, b) values (?, ?, ?, ?)", 42, 1.5, "hi", new byte[] {1, 2});
        db.insert("insert into v (i, r, t) values (?, ?, ?)", true, 2.5f, new BigDecimal("12.30"));
        db.insert("insert into v (i, t) values (?, ?)", BigInteger.TEN, LocalDate.of(2026, 1, 2));
        db.insert("insert into v (i, t) values (?, ?)", (short) 3, LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        db.insert("insert into v (i, t) values (?, ?)", (byte) 4, Instant.parse("2026-01-02T03:04:05Z"));
        db.insert("insert into v (i, t) values (?, ?)", 5L, null);

        List<Map<String, Object>> rows = db.select("select i, r, t, b from v order by rowid");
        assertEquals(42L, rows.get(0).get("i"));
        assertEquals(1.5, rows.get(0).get("r"));
        assertEquals("hi", rows.get(0).get("t"));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) rows.get(0).get("b"));
        assertEquals(1L, rows.get(1).get("i"));
        assertEquals("12.30", rows.get(1).get("t"));
        assertEquals("2026-01-02", rows.get(2).get("t"));
        assertEquals("2026-01-02 03:04:05", rows.get(3).get("t"));
        assertEquals("2026-01-02 03:04:05+00:00", rows.get(4).get("t"));
        assertNull(rows.get(5).get("t"));
        assertEquals(Arrays.asList("i", "r", "t", "b"), Arrays.asList(rows.get(0).keySet().toArray()));
        assertEquals(1L, db.select("select count(*) as c from v where i = ?", 42).get(0).get("c"));
    }

    @Test
    public void aDecimalWithAnExponentIsStoredInPlainForm() {
        db.insert("insert into v (t) values (?)", new BigDecimal("1E+3"));

        assertEquals("1000", db.select("select t from v").get(0).get("t"));
    }

    @Test
    public void aNonFiniteDoubleIsRefusedBeforeAnySqlRuns() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> db.insert("insert into v (r) values (?)", Double.NaN));

        assertFalse(error.getMessage().contains("NaN"));
        assertTrue(db.select("select r from v").isEmpty());
    }

    @Test
    public void anUnsupportedBindingTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> db.insert("insert into v (t) values (?)", new Object()));
    }

    @Test
    public void aRepeatedLabelIsRefused() {
        DriverSession session = driver.open();
        try {
            DriverException error = assertThrows(DriverException.class,
                    () -> session.query("select 1 as a, 2 as a", Collections.emptyList()));
            assertTrue(error.getMessage().contains("a"));
        } finally {
            session.close();
        }
    }

    @Test
    public void nestedTransactionsUseSavepoints() {
        db.transaction(tx -> {
            tx.insert("insert into v (i) values (1)");
            try {
                tx.transaction(inner -> {
                    inner.insert("insert into v (i) values (2)");
                    throw new IllegalStateException("undo the inner work");
                });
            } catch (IllegalStateException expected) {
                // The inner savepoint was rolled back.
            }
            tx.transaction(inner -> inner.insert("insert into v (i) values (3)"));
            return null;
        });

        assertEquals(Arrays.asList(1L, 3L), column("select i from v order by i"));
    }

    @Test
    public void aFailedTransactionLeavesNothing() {
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.insert("insert into v (i) values (9)");
            throw new IllegalStateException("fail");
        }));

        assertTrue(column("select i from v").isEmpty());
    }

    @Test
    public void savepointsWorkDirectlyOnTheSession() {
        DriverSession session = driver.open();
        session.begin();
        session.execute("insert into v (i) values (1)", Collections.emptyList());
        session.savepoint("a");
        session.execute("insert into v (i) values (2)", Collections.emptyList());
        session.savepoint("b");
        session.rollbackToSavepoint("a");
        assertThrows(IllegalArgumentException.class, () -> session.releaseSavepoint("b"));
        session.releaseSavepoint("a");
        assertThrows(IllegalArgumentException.class, () -> session.savepoint("1bad"));
        session.commit();
        session.close();

        assertEquals(Collections.singletonList(1L), column("select i from v"));
    }

    @Test
    public void discardRollsBackAndKeepsTheDatabaseOpen() {
        DriverSession session = driver.open();
        session.begin();
        assertFalse(session.inExternalTransaction());
        session.update("insert into v (i) values (7)", Collections.emptyList());
        session.discard();
        session.discard();

        assertTrue(database.isOpen());
        assertTrue(column("select i from v").isEmpty());
        assertThrows(IllegalStateException.class, () -> session.query("select 1", Collections.emptyList()));
    }

    @Test
    public void aTransactionTheSessionDidNotBeginIsExternal() {
        database.beginTransaction();
        DriverSession session = driver.open();
        try {
            assertTrue(session.inExternalTransaction());
            assertThrows(IllegalStateException.class, session::begin);
        } finally {
            session.close();
            database.endTransaction();
        }
    }

    @Test
    public void commitOrRollbackWithoutATransactionIsRefused() {
        DriverSession session = driver.open();
        assertThrows(IllegalStateException.class, session::commit);
        assertThrows(IllegalStateException.class, session::rollback);
        session.begin();
        assertThrows(IllegalStateException.class, session::begin);
        session.rollback();
        session.close();
    }

    @Test
    public void endingATransactionFromAnotherThreadFailsAndLeavesItToItsOwner() throws Exception {
        DriverSession session = driver.open();
        session.begin();
        session.update("insert into v (i) values (5)", Collections.emptyList());
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                session.commit();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        other.start();
        other.join();

        assertTrue(error.get() instanceof IllegalStateException);
        session.commit();
        session.close();
        assertEquals(Collections.singletonList(5L), column("select i from v"));
    }

    @Test
    public void savepointNamesIgnoreCaseAsSqliteDoes() {
        DriverSession session = driver.open();
        session.begin();
        session.savepoint("SP1");
        assertThrows(IllegalArgumentException.class, () -> session.savepoint("sp1"));
        session.releaseSavepoint("sp1");
        session.rollback();
        session.close();
    }

    @Test
    public void aBigIntegerPastALongIsStoredAsText() {
        db.insert("insert into v (t) values (?)", new BigInteger("18446744073709551616"));

        assertEquals("18446744073709551616", db.select("select t from v").get(0).get("t"));
    }

    @Test
    public void aWholeScriptRuns() {
        db.unprepared("create table s (n integer); insert into s values (1); insert into s values (2);");

        assertEquals(2L, db.select("select count(*) as c from s").get(0).get("c"));
    }

    @Test
    public void rawStatementsRun() {
        db.unprepared("insert into v (i) values (11)");

        assertEquals(Collections.singletonList(11L), column("select i from v"));
    }

    private List<Object> column(String sql) {
        List<Object> values = new java.util.ArrayList<>();
        for (Map<String, Object> row : db.select(sql)) {
            values.add(row.values().iterator().next());
        }
        return values;
    }
}

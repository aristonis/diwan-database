package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The exact text SQLite stores for dates and decimals, as Laravel stores it. */
class JdbcSqliteStorageTest {

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void connect() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("store.db"));
        db = Connection.builder().name("sqlite").dataSource(dataSource).build();
        db.statement("create table t (id integer primary key, v)");
    }

    private Map<String, Object> stored(Object value) {
        db.delete("delete from t");
        db.insert("insert into t (id, v) values (1, ?)", value);
        return db.select("select typeof(v) as kind, v from t").get(0);
    }

    @Test
    void dateTimesAreStoredInLaravelsFormat() {
        assertEquals("2026-01-02 03:04:00", stored(LocalDateTime.of(2026, 1, 2, 3, 4)).get("v"));
        assertEquals("2026-01-02 03:04:05.5", stored(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 500_000_000)).get("v"));
        assertEquals("2026-01-02", stored(LocalDate.of(2026, 1, 2)).get("v"));
        assertEquals("03:04:00", stored(LocalTime.of(3, 4)).get("v"));
        assertEquals("text", stored(LocalDate.of(2026, 1, 2)).get("kind"));
    }

    @Test
    void zonedValuesAreStoredAsUtc() {
        assertEquals("2026-01-02 01:04:00+00:00",
                stored(OffsetDateTime.of(2026, 1, 2, 3, 4, 0, 0, ZoneOffset.ofHours(2))).get("v"));
        assertEquals("2026-01-02 03:04:00+00:00", stored(Instant.parse("2026-01-02T03:04:00Z")).get("v"));
        assertEquals("06:30:00+00:00", stored(OffsetTime.of(9, 30, 0, 0, ZoneOffset.ofHours(3))).get("v"));
        assertThrows(IllegalArgumentException.class, () -> stored(LocalDate.MAX));
        assertThrows(IllegalArgumentException.class, () -> stored(LocalDateTime.of(10000, 1, 1, 0, 0)));
    }

    @Test
    void storedTextSortsInTimeOrderAcrossOffsets() {
        db.insert("insert into t (id, v) values (1, ?)", OffsetDateTime.of(2026, 1, 2, 10, 0, 0, 0, ZoneOffset.ofHours(5)));
        db.insert("insert into t (id, v) values (2, ?)", OffsetDateTime.of(2026, 1, 2, 6, 0, 0, 0, ZoneOffset.UTC));
        db.insert("insert into t (id, v) values (3, ?)", OffsetDateTime.of(2026, 1, 2, 3, 0, 0, 0, ZoneOffset.ofHours(-4)));

        List<Object> order = new ArrayList<>();
        for (Map<String, Object> row : db.select("select id from t order by v")) {
            order.add(((Number) row.get("id")).intValue());
        }
        assertEquals(java.util.Arrays.asList(1, 2, 3), order);
    }

    @Test
    void decimalsAreStoredAsPlainText() {
        assertEquals("1000", stored(new BigDecimal("1E+3")).get("v"));
        assertEquals("0.000001", stored(new BigDecimal("1E-6")).get("v"));
        assertEquals("text", stored(new BigDecimal("1E+3")).get("kind"));
        assertEquals("18446744073709551616", stored(new BigInteger("18446744073709551616")).get("v"));
    }

    @Test
    void nanAndInfinityAreRefusedWithoutTheValue() {
        IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                () -> db.insert("insert into t (id, v) values (1, ?)", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> db.insert("insert into t (id, v) values (1, ?)", Float.POSITIVE_INFINITY));
        assertTrue(nan.getMessage().contains("1"));
        assertFalse(nan.getMessage().contains("NaN"));
    }
}

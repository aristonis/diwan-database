package org.aristonis.photisma.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;

class JdbcValuesTest {

    @Test
    void readsLargeObjectsAndOldDateTypesAsPlainJavaValues() throws Exception {
        assertEquals("long text", JdbcValues.read(new SerialClob("long text".toCharArray())));
        assertArrayEquals(new byte[] {4, 5}, (byte[]) JdbcValues.read(new SerialBlob(new byte[] {4, 5})));
        assertEquals(LocalDate.of(2026, 10, 30), JdbcValues.read(Date.valueOf("2026-10-30")));
        assertEquals(LocalTime.of(12, 30), JdbcValues.read(Time.valueOf("12:30:00")));
        assertEquals(LocalDateTime.of(2026, 10, 30, 12, 0),
                JdbcValues.read(Timestamp.valueOf("2026-10-30 12:00:00")));
        assertEquals("plain", JdbcValues.read("plain"));
        assertEquals(null, JdbcValues.read(null));
    }

    @Test
    void readsXmlAsTextAndArraysAsListsAndFreesThem() throws Exception {
        java.util.concurrent.atomic.AtomicInteger freed = new java.util.concurrent.atomic.AtomicInteger();
        java.sql.SQLXML xml = (java.sql.SQLXML) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.SQLXML.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) {
                        return "<a/>";
                    }
                    freed.incrementAndGet();
                    return null;
                });
        java.sql.Array array = (java.sql.Array) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Array.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getArray")) {
                        return new Object[] {1, null, new SerialClob("x".toCharArray())};
                    }
                    if (method.getName().equals("free")) {
                        freed.incrementAndGet();
                        throw new java.sql.SQLException("free not supported");
                    }
                    return null;
                });

        assertEquals("<a/>", JdbcValues.read(xml));
        java.util.List<?> list = (java.util.List<?>) JdbcValues.read(array);

        assertEquals(java.util.Arrays.asList(1, null, "x"), list);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> list.clear());
        assertEquals(2, freed.get());
    }
}

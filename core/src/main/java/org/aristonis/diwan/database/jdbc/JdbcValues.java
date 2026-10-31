package org.aristonis.diwan.database.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts values read from JDBC into the types the driver value contract allows.
 *
 * <p>Dates, times and timestamps are read through their JDBC escape text, so a
 * {@code java.sql.Timestamp} is interpreted in the JVM's default time zone and any offset the
 * database stored is not preserved. An offset-aware read needs a database-specific driver.
 */
final class JdbcValues {

    private JdbcValues() {
    }

    /**
     * Converts one value read from a result set.
     *
     * @param value the value from {@code ResultSet.getObject}, may be {@code null}
     * @return the converted value; large objects as {@code String} or {@code byte[]}, arrays as an
     *     unmodifiable list, old date types as {@code java.time} types, anything else unchanged
     * @throws SQLException if reading a large object or array fails, a large object is too big to
     *     hold in memory, or a date, time or timestamp has no {@code java.time} equivalent (for
     *     example a date before the common era)
     */
    static Object read(Object value) throws SQLException {
        // Clob covers NClob, which extends it.
        if (value instanceof Clob) {
            return readClob((Clob) value);
        }
        if (value instanceof Blob) {
            return readBlob((Blob) value);
        }
        if (value instanceof SQLXML) {
            return readXml((SQLXML) value);
        }
        if (value instanceof Array) {
            return readArray((Array) value);
        }
        try {
            return readTemporal(value);
        } catch (DateTimeParseException e) {
            throw new SQLException("date or time value cannot be represented", e);
        }
    }

    // Android lacks the java.time getters of java.sql types, so convert through their JDBC escape text.
    private static Object readTemporal(Object value) {
        if (value instanceof Timestamp) {
            return LocalDateTime.parse(value.toString().replace(' ', 'T'));
        }
        if (value instanceof Time) {
            return LocalTime.parse(value.toString());
        }
        if (value instanceof java.sql.Date) {
            return LocalDate.parse(value.toString());
        }
        return value;
    }

    private static int checkedLength(long length) throws SQLException {
        if (length > Integer.MAX_VALUE) {
            throw new SQLException("large object too big to read into memory");
        }
        return (int) length;
    }

    private static String readClob(Clob clob) throws SQLException {
        try {
            long length = clob.length();
            return length == 0 ? "" : clob.getSubString(1, checkedLength(length));
        } finally {
            freeQuietly(clob);
        }
    }

    private static byte[] readBlob(Blob blob) throws SQLException {
        try {
            long length = blob.length();
            return length == 0 ? new byte[0] : blob.getBytes(1, checkedLength(length));
        } finally {
            freeQuietly(blob);
        }
    }

    private static String readXml(SQLXML xml) throws SQLException {
        try {
            return xml.getString();
        } finally {
            try {
                xml.free();
            } catch (SQLException | UnsupportedOperationException ignored) {
                // Freeing is only a hint to release resources early; the value is already read.
            }
        }
    }

    private static List<Object> readArray(Array array) throws SQLException {
        try {
            Object raw = array.getArray();
            if (raw == null) {
                return null;
            }
            int length = java.lang.reflect.Array.getLength(raw);
            List<Object> elements = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                elements.add(read(java.lang.reflect.Array.get(raw, i)));
            }
            return Collections.unmodifiableList(elements);
        } finally {
            try {
                array.free();
            } catch (SQLException | UnsupportedOperationException ignored) {
                // Freeing is only a hint to release resources early; the value is already read.
            }
        }
    }

    private static void freeQuietly(Clob clob) {
        try {
            clob.free();
        } catch (SQLException | UnsupportedOperationException ignored) {
            // Freeing is only a hint to release resources early; the value is already read.
        }
    }

    private static void freeQuietly(Blob blob) {
        try {
            blob.free();
        } catch (SQLException | UnsupportedOperationException ignored) {
            // Freeing is only a hint to release resources early; the value is already read.
        }
    }
}

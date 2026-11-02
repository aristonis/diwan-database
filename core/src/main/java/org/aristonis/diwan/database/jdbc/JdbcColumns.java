package org.aristonis.diwan.database.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;

/**
 * Reads the rows of one result: the column labels, checked for duplicates, and the java.time type
 * each date or time column is read as, both worked out once per result and not once per cell.
 * Immutable.
 */
final class JdbcColumns {

    // ResultSet.getObject(int, Class) is JDBC 4.1, missing from Android 28's java.sql; looked up once.
    private static final Method TYPED_GET_OBJECT = typedGetObject();

    private final String[] labels;
    private final Class<?>[] wanted;

    private JdbcColumns(String[] labels, Class<?>[] wanted) {
        this.labels = labels;
        this.wanted = wanted;
    }

    /**
     * Describes the columns of a result.
     *
     * @throws DriverException if two columns share a label
     */
    static JdbcColumns of(ResultSetMetaData metaData, String productName) throws SQLException {
        int columnCount = metaData.getColumnCount();
        String[] labels = new String[columnCount];
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < columnCount; i++) {
            String label = metaData.getColumnLabel(i + 1);
            if (seen.put(label, Boolean.TRUE) != null) {
                throw duplicateLabel(label);
            }
            labels[i] = label;
        }
        Class<?>[] wanted = new Class<?>[columnCount];
        if (DatabaseFamily.of(productName) != DatabaseFamily.SQLITE) {
            for (int i = 0; i < columnCount; i++) {
                wanted[i] = temporalType(metaData.getColumnType(i + 1), metaData.getColumnTypeName(i + 1));
            }
        }
        return new JdbcColumns(labels, wanted);
    }

    /** Reads the current row into a new mutable map, columns in select order. */
    Map<String, Object> read(ResultSet resultSet) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) {
            row.put(labels[i], JdbcValues.read(readValue(resultSet, i + 1, wanted[i])));
        }
        return row;
    }

    // java.time getters avoid Timestamp.toString(), which shifts dates before 1582 and times in a
    // daylight-saving gap and drops a timestamptz's offset. SQLite keeps dates as text, read as is.
    private static Object readValue(ResultSet resultSet, int column, Class<?> wanted) throws SQLException {
        if (wanted == null) {
            return resultSet.getObject(column);
        }
        if (TYPED_GET_OBJECT == null) {
            return resultSet.getObject(column);
        }
        try {
            return TYPED_GET_OBJECT.invoke(resultSet, column, wanted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLFeatureNotSupportedException || cause instanceof AbstractMethodError) {
                return resultSet.getObject(column);
            }
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            }
            throw new SQLException("Reading a date or time failed", cause);
        } catch (IllegalAccessException e) {
            return resultSet.getObject(column);
        }
    }

    private static Method typedGetObject() {
        try {
            return ResultSet.class.getMethod("getObject", int.class, Class.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Class<?> temporalType(int type, String typeName) {
        String name = typeName == null ? "" : typeName.toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return OffsetDateTime.class;
            case Types.TIMESTAMP:
                return name.equals("timestamptz") ? OffsetDateTime.class : java.time.LocalDateTime.class;
            case Types.DATE:
                return java.time.LocalDate.class;
            case Types.TIME_WITH_TIMEZONE:
                return java.time.OffsetTime.class;
            case Types.TIME:
                return name.equals("timetz") ? java.time.OffsetTime.class : java.time.LocalTime.class;
            default:
                return null;
        }
    }

    private static DriverException duplicateLabel(String label) {
        // A label is part of the statement text, not a bound value, so naming it is safe.
        return new DriverException("Result has two columns labelled '" + label + "'; give them distinct aliases",
                EnumSet.noneOf(DriverFailureKind.class), null);
    }
}

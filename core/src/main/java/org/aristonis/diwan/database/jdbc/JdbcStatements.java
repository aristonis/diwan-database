package org.aristonis.diwan.database.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.driver.DatabaseFamily;
import org.aristonis.diwan.database.driver.sqlite.SQLiteText;

/**
 * Statement preparation, value binding and row reading for {@link JdbcSession}, kept apart so the
 * session holds only connection, transaction and cursor state.
 *
 * <p>Every method is static; what the session used to supply from its fields (the connection and the
 * product name) comes in as arguments.
 */
final class JdbcStatements {

    private JdbcStatements() {
    }

    /**
     * Prepares a forward-only statement and binds its values.
     *
     * @param connection the connection to prepare on
     * @param productName the product name that selects the binding rules, or {@code null} if unknown
     * @param sql the SQL text, not {@code null}
     * @param bindings the values for the placeholders in order, not {@code null}
     * @return the prepared statement, which the caller closes
     * @throws SQLException if the statement cannot be prepared or bound
     */
    static PreparedStatement prepare(Connection connection, String productName, String sql,
            List<Object> bindings) throws SQLException {
        return prepare(connection, productName, sql, bindings, false);
    }

    /**
     * Prepares a statement and binds its values; a cursor statement is forward-only and read-only.
     *
     * @param connection the connection to prepare on
     * @param productName the product name that selects the binding rules, or {@code null} if unknown
     * @param sql the SQL text, not {@code null}
     * @param bindings the values for the placeholders in order, not {@code null}
     * @param isCursor whether the statement backs a cursor
     * @return the prepared statement, which the caller closes
     * @throws SQLException if the statement cannot be prepared or bound
     */
    static PreparedStatement prepare(Connection connection, String productName, String sql,
            List<Object> bindings, boolean isCursor) throws SQLException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(bindings, "bindings");
        PreparedStatement statement = isCursor
                ? connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
                : connection.prepareStatement(sql);
        try {
            int index = 1;
            for (Object value : bindings) {
                bind(statement, index++, value, DatabaseFamily.of(productName));
            }
            return statement;
        } catch (IndexOutOfBoundsException e) {
            // A driver that does not check the index (sqlite-jdbc) throws it unchecked; report it as
            // the ordinary statement failure.
            closeAfterFailure(statement, e);
            throw new SQLException("The statement has fewer placeholders than values.", e);
        } catch (SQLException | RuntimeException e) {
            closeAfterFailure(statement, e);
            throw e;
        }
    }

    private static void closeAfterFailure(PreparedStatement statement, Exception failure) {
        try {
            statement.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value, DatabaseFamily family)
            throws SQLException {
        SQLiteText.refuseNonFinite(value, index);
        if (family == DatabaseFamily.SQLITE) {
            // One text for dates and decimals on every SQLite driver, so values sort and compare.
            value = SQLiteText.toStorage(value);
        }
        if (family == DatabaseFamily.POSTGRESQL) {
            if (value == null) {
                // Untyped, so a null fits any column type, uuid and jsonb included.
                statement.setNull(index, Types.OTHER);
                return;
            }
            if (value instanceof String) {
                // Sent untyped, as pgjdbc's stringtype=unspecified does, so text binds into uuid, jsonb and inet.
                statement.setObject(index, value, Types.OTHER);
                return;
            }
        }
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof byte[]) {
            statement.setBytes(index, (byte[]) value);
        } else if (value instanceof BigInteger) {
            statement.setBigDecimal(index, new BigDecimal((BigInteger) value));
        } else if (value instanceof Instant) {
            // JDBC 4.2 maps no SQL type to Instant; OffsetDateTime is the portable equivalent.
            statement.setObject(index, OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
        } else if (!bindTyped(statement, index, value)) {
            statement.setObject(index, value);
        }
    }

    // Typed setters, because some drivers (sqlite-jdbc) store a Boolean given to setObject as text.
    private static boolean bindTyped(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Boolean) {
            statement.setBoolean(index, (Boolean) value);
        } else if (value instanceof Byte) {
            statement.setByte(index, (Byte) value);
        } else if (value instanceof Short) {
            statement.setShort(index, (Short) value);
        } else if (value instanceof Integer) {
            statement.setInt(index, (Integer) value);
        } else if (value instanceof Long) {
            statement.setLong(index, (Long) value);
        } else if (value instanceof Float) {
            statement.setFloat(index, (Float) value);
        } else if (value instanceof Double) {
            statement.setDouble(index, (Double) value);
        } else if (value instanceof BigDecimal) {
            statement.setBigDecimal(index, (BigDecimal) value);
        } else if (value instanceof String) {
            statement.setString(index, (String) value);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Reads every remaining row of a result set into immutable maps.
     *
     * @param resultSet the result set, positioned before its first row
     * @param productName the product name used to read column values, or {@code null} if unknown
     * @return the rows as an unmodifiable list of unmodifiable maps
     * @throws SQLException if reading fails
     */
    static List<Map<String, Object>> readRows(ResultSet resultSet, String productName) throws SQLException {
        JdbcColumns columns = JdbcColumns.of(resultSet.getMetaData(), productName);
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            rows.add(Collections.unmodifiableMap(columns.read(resultSet)));
        }
        return Collections.unmodifiableList(rows);
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.SQLiteModelTest.SingleConnection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The strict decimal cast on a connection that keeps 15 digits exactly, as SQLite does. */
class StrictDecimalGuardTest {

    private static final int EXACT_DIGITS = 15;

    /** A model with a strict decimal(18,2) column. */
    static final class Price extends Model {
        static final ModelQuery<Price> QUERY = ModelQuery.of(Price.class, Price::new);

        @Override
        protected String table() {
            return "prices";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }

        @Override
        protected Map<String, Cast<?>> casts() {
            Map<String, Cast<?>> casts = new LinkedHashMap<>();
            casts.put("amount", Casts.decimal(18, 2));
            casts.put("rate", Casts.decimalText(20, 15));
            return casts;
        }
    }

    private java.sql.Connection jdbc;
    private Connection connection;
    private QueryFactory db;

    // Wraps the real connection so only exactDecimalDigits is overridden.
    private static Connection reporting(Connection real, int digits) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("exactDecimalDigits")) {
                return OptionalInt.of(digits);
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, handler);
    }

    @BeforeEach
    void open() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection real = Connection.builder().name("sqlite").connectionProvider(new SingleConnection(jdbc)).build();
        connection = reporting(real, EXACT_DIGITS);
        db = SQLiteQuery.of(connection);
        connection.statement("create table prices (id integer primary key, amount numeric, rate text)");
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    @Test
    void sqliteRefusesSixteenDigits() {
        InvalidCastException e = assertThrows(InvalidCastException.class,
                () -> Price.QUERY.on(db).create(row("amount", new BigDecimal("12345678901234.56"))));

        assertTrue(e.getMessage().contains("amount"));
        assertTrue(e.getMessage().contains("16"));
        assertTrue(e.getMessage().contains("15"));
        assertFalse(e.getMessage().contains("12345678901234"));
    }

    @Test
    void sqliteKeepsFifteen() {
        Price created = Price.QUERY.on(db).create(row("amount", new BigDecimal("1234567890123.45")));

        Price found = Price.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(new BigDecimal("1234567890123.45"), found.getAttribute("amount"));
    }

    @Test
    void readRestoresScale() {
        connection.statement("insert into prices (amount) values (10.1)");

        Price found = Price.QUERY.on(db).findOrFail(1);

        assertEquals(new BigDecimal("10.10"), found.getAttribute("amount"));
    }

    @Test
    void textColumnKeepsSixteenDigits() {
        Price created = Price.QUERY.on(db).create(row("rate", new BigDecimal("1.234567890123456")));

        Price found = Price.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(new BigDecimal("1.234567890123456"), found.getAttribute("rate"));
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.SQLiteModelTest.SingleConnection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.exception.InvalidCastException;
import org.aristonis.diwan.database.query.QueryFactory;
import org.aristonis.diwan.database.sqlite.SQLiteQuery;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The strict decimal cast against a real in-memory SQLite database. */
class StrictDecimalDbTest {

    /** A row with a strict and a rounding decimal. */
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
            casts.put("amount", Casts.decimal(12, 2));
            casts.put("rounded", Casts.decimal(12, 2, RoundingMode.HALF_UP));
            return casts;
        }
    }

    private java.sql.Connection jdbc;
    private QueryFactory db;

    @BeforeEach
    void open() throws SQLException {
        jdbc = DriverManager.getConnection("jdbc:sqlite::memory:");
        Connection connection = Connection.builder().name("sqlite").connectionProvider(new SingleConnection(jdbc))
                .build();
        db = SQLiteQuery.of(connection);
        SQLiteSchema.of(connection).create("prices", t -> {
            t.id();
            t.decimal("amount", 12, 2).nullable();
            t.decimal("rounded", 12, 2).nullable();
        });
    }

    @AfterEach
    void close() throws SQLException {
        jdbc.close();
    }

    @Test
    void strictDecimalRoundTrip() {
        Price created = Price.QUERY.on(db).create(row("amount", new BigDecimal("12345.6"), "rounded", "1.005"));

        Price found = Price.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(new BigDecimal("12345.60"), found.getAttribute("amount"));
        assertEquals(new BigDecimal("1.01"), found.getAttribute("rounded"));
    }

    @Test
    void refusedValueIsNotStored() {
        assertThrows(InvalidCastException.class, () -> Price.QUERY.on(db).create(row("amount", "1.005")));

        assertEquals(0L, db.table("prices").count());
    }
}

package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.casts.Cast;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.pgsql.PostgresQuery;
import org.aristonis.diwan.database.query.QueryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The strict decimal cast on PostgreSQL, which keeps every digit; skipped when the server binaries are missing. */
class StrictDecimalCastPgTest {

    /** A model with a strict decimal(20,2) column. */
    static final class Big extends Model {
        static final ModelQuery<Big> QUERY = ModelQuery.of(Big.class, Big::new);

        @Override
        protected String table() {
            return "bigs";
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
            casts.put("amount", Casts.decimal(20, 2));
            return casts;
        }
    }

    private QueryFactory db;

    @BeforeEach
    void open() {
        Connection connection = PostgresServer.newDatabase();
        db = PostgresQuery.of(connection);
        connection.statement("create table bigs (id bigserial primary key, amount numeric(20,2))");
    }

    @Test
    void pgKeepsSixteen() {
        Big created = Big.QUERY.on(db).create(row("amount", new BigDecimal("12345678901234.56")));

        Big found = Big.QUERY.on(db).findOrFail(created.getKey());

        assertEquals(new BigDecimal("12345678901234.56"), found.getAttribute("amount"));
    }
}

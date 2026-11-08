package org.aristonis.diwan.database.pgsql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.schema.Collation;
import org.aristonis.diwan.database.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresCollationTest {

    private Connection db;
    private Schema schema;

    @BeforeEach
    void open() {
        db = PostgresServer.newDatabase();
        schema = PostgresSchema.of(db);
    }

    private static List<String> sqlOf(List<LoggedQuery> queries) {
        List<String> sql = new ArrayList<>();
        for (LoggedQuery query : queries) {
            sql.add(query.sql());
        }
        return sql;
    }

    @Test
    void collationIsWritten() {
        schema.create("t", table -> table.id());
        List<String> created = sqlOf(schema.pretend(s -> s.create("u", table -> {
            table.string("name").collation(Collation.BINARY);
            table.string("label").collation("en_US");
        })));
        List<String> added = sqlOf(schema.pretend(s -> s.table("t", table -> table.string("tag").collation("C"))));

        assertTrue(created.get(0).contains("\"name\" varchar(255) collate \"C\" not null"), created.get(0));
        assertTrue(created.get(0).contains("\"label\" varchar(255) collate \"en_US\" not null"), created.get(0));
        assertTrue(added.get(0).contains("add column \"tag\" varchar(255) collate \"C\" not null"), added.get(0));
    }

    @Test
    void changeSetsCollation() {
        schema.create("t", table -> table.string("name"));

        List<String> sql = sqlOf(schema.pretend(s -> s.table("t",
                table -> table.string("name").collation("C").change())));
        schema.table("t", table -> table.string("name").collation("C").change());
        db.insert("insert into t (name) values ('Alpha'), ('Zulu'), ('alpha')");

        assertTrue(sql.get(0).contains("alter column \"name\" type varchar(255) collate \"C\" using"), sql.get(0));
        assertEquals(Arrays.<Object>asList("Alpha", "Zulu", "alpha"), PostgresQuery.of(db).table("t").orderBy("name").pluck("name"));
    }

    @Test
    void binaryOrderMatchesOnBothDatabases() {
        schema.create("t", table -> table.string("name").collation(Collation.BINARY));
        db.insert("insert into t (name) values ('Alpha'), ('Zulu'), ('alpha')");

        assertEquals(Arrays.<Object>asList("Alpha", "Zulu", "alpha"), PostgresQuery.of(db).table("t").orderBy("name").pluck("name"));
    }
}

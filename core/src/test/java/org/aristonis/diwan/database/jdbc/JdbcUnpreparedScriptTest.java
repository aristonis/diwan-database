package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcUnpreparedScriptTest {

    @TempDir
    Path folder;

    @Test
    void aWholeScriptRunsOnSqlite() {
        Connection db = Connection.builder().name("sqlite")
                .dataSource(new CountingDataSource(folder.resolve("s.db").toString())).build();

        db.unprepared("create table a (n integer); create table b (n integer);"
                + " create trigger copy after insert on a begin insert into b values (new.n); end;"
                + " insert into a values (1); insert into a values (2);");

        assertEquals(2L, ((Number) db.scalar("select count(*) from b").get()).longValue());
    }
}

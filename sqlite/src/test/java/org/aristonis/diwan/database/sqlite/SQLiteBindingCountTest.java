package org.aristonis.diwan.database.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SQLiteBindingCountTest {

    @TempDir
    Path folder;

    private Connection connection;

    @BeforeEach
    void open() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("bindings.db"));
        connection = Connection.builder().name("sqlite").dataSource(dataSource).build();
        connection.statement("create table t (a integer, b integer)");
        connection.insert("insert into t (a, b) values (?, ?)", 1, 2);
    }

    @Test
    void moreValuesThanPlaceholdersFailWithAQueryException() {
        assertThrows(QueryException.class, () -> connection.select("select * from t where a = ?", 1, 2));
    }

    @Test
    void theRightNumberOfValuesRuns() {
        assertEquals(1, connection.select("select * from t where a = ? and b = ?", 1, 2).size());
    }
}

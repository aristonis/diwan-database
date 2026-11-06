package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.exception.ForeignKeyViolationException;
import org.aristonis.diwan.database.exception.QueryException;
import org.aristonis.diwan.database.exception.UniqueConstraintViolationException;
import org.aristonis.diwan.database.exception.DriverException;
import org.aristonis.diwan.database.driver.DriverFailureKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failure kinds from the real SQLite JDBC driver, which reports only 8-bit codes. */
class FailureKindsSqliteTest {

    @TempDir
    Path folder;

    private Connection db;

    @BeforeEach
    void connect() {
        db = Connection.builder().name("sqlite")
                .dataSource(new CountingDataSource(folder.resolve("k.db").toString()).withForeignKeys()).build();
        db.statement("create table parents (id integer primary key, email text unique)");
        db.statement("create table kids (parent_id integer not null references parents (id))");
        db.insert("insert into parents values (1, 'a@x')");
    }

    @Test
    void aForeignKeyViolationHasItsOwnExceptionAndKind() {
        ForeignKeyViolationException error = assertThrows(ForeignKeyViolationException.class,
                () -> db.insert("insert into kids values (?)", 99));

        assertTrue(((DriverException) error.getCause()).kinds().contains(DriverFailureKind.FOREIGN_KEY_VIOLATION));
        assertFalse(error.getMessage().contains("99"));
    }

    @Test
    void uniqueAndNotNullAreToldApart() {
        assertThrows(UniqueConstraintViolationException.class,
                () -> db.insert("insert into parents values (2, ?)", "a@x"));
        QueryException notNull = assertThrows(QueryException.class,
                () -> db.insert("insert into kids values (?)", (Object) null));
        assertFalse(notNull instanceof UniqueConstraintViolationException);
        assertFalse(notNull instanceof ForeignKeyViolationException);
        assertEquals(19, ((DriverException) notNull.getCause()).vendorCode());
    }
}

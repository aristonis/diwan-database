package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.ColumnInfo;
import org.aristonis.diwan.database.schema.IndexType;
import org.aristonis.diwan.database.schema.Schema;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Column listing through the Android driver, which reads the columns SQLite reports on API 28. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidColumnsTest {

    private static final String VERSION_TAG = "DIWAN_SQLITE_VERSION=";
    private static final String TABLE = "t";

    private SQLiteDatabase database;
    private Connection db;
    private Schema schema;

    @Before
    public void createTable() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
        schema = SQLiteSchema.of(db);
        db.statement("create table t (id integer primary key, name varchar(255) not null default 'x',"
                + " note text collate nocase, n int)");
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void getColumnsReportsPrimaryKeyAsAutoIncrement() {
        ColumnInfo id = column("id");

        assertTrue("Integer primary key is auto-increment", id.isAutoIncrement());
    }

    @Test
    public void getColumnsReportsTypeNullabilityAndDefaultOfNotNullColumn() {
        ColumnInfo name = column("name");

        assertEquals("Declared type with length", "varchar(255)", name.type());
        assertEquals("Type name without length", "varchar", name.typeName());
        assertFalse("NOT NULL column is not nullable", name.isNullable());
        assertEquals("Default expression", "'x'", name.defaultValue().orElseThrow());
    }

    @Test
    public void getColumnsReportsCollationOfCollatedColumn() {
        ColumnInfo note = column("note");

        assertEquals("Collation", "nocase", note.collation().orElseThrow());
    }

    @Test
    public void getColumnsReportsColumnWithoutConstraintsAsNullable() {
        ColumnInfo n = column("n");

        assertTrue("Plain column is nullable", n.isNullable());
    }

    @Test
    public void hasColumnsFindsExistingColumnsIgnoringCase() {
        assertTrue(schema.hasColumns(TABLE, List.of("ID", "Name")));
    }

    @Test
    public void hasIndexFindsPrimaryKeyOnIdColumn() {
        assertTrue(schema.hasIndex(TABLE, List.of("id"), IndexType.PRIMARY));
    }

    @Test
    public void recordsDeviceSqliteVersionAndListsColumnsForAnyVersion() {
        String version = String.valueOf(db.scalar("select sqlite_version()").orElseThrow());
        System.out.println(VERSION_TAG + version);

        assertEquals("All four columns are listed", 4, schema.getColumns(TABLE).size());
    }

    private ColumnInfo column(String name) {
        return schema.getColumns(TABLE).stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Column not listed: " + name));
    }
}

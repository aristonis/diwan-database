package org.aristonis.diwan.database.android;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.QueryException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Two result columns with one label are refused over Android's SQLiteDatabase, as on JDBC. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidColumnLabelsTest {

    private SQLiteDatabase database;
    private Connection db;

    @Before
    public void open() {
        database = SQLiteDatabase.create(null);
        db = Connection.builder().name("android").driver(AndroidSqliteDriver.of(database)).build();
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void refusesTwoColumnsWithTheSameLabelAndNamesTheLabel() {
        QueryException error = assertThrows(QueryException.class, () -> db.select("select 1 as a, 2 as a"));

        assertTrue(String.valueOf(error.getCause()).contains("a"));
    }
}

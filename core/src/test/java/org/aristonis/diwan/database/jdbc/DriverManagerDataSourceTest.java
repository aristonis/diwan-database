package org.aristonis.diwan.database.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DriverManagerDataSourceTest {

    private static final String UNKNOWN_URL = "jdbc:diwan-no-such-driver://host/db";

    @Test
    void toStringHidesThePassword() {
        DriverManagerDataSource source = new DriverManagerDataSource(UNKNOWN_URL, "app", "s3cret-value");

        String text = source.toString();

        assertFalse(text.contains("s3cret-value"), text);
        assertTrue(text.contains("app"), text);
        assertTrue(text.contains("DriverManagerDataSource"), text);
    }

    @Test
    void toStringMasksAPasswordParameterInTheUrl() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:diwan://h/db?user=a&password=s3cret-value&ssl=true", null, null);

        String text = source.toString();

        assertFalse(text.contains("s3cret-value"), text);
        assertTrue(text.contains("password=***&ssl=true"), text);
    }

    @Test
    void failsWithAnUnknownUrlWithoutLeakingThePassword() {
        DriverManagerDataSource source = new DriverManagerDataSource(UNKNOWN_URL, "app", "s3cret-value");

        SQLException e = assertThrows(SQLException.class, source::getConnection);
        SQLException other = assertThrows(SQLException.class, () -> source.getConnection("u", "s3cret-value"));

        assertFalse(String.valueOf(e.getMessage()).contains("s3cret-value"));
        assertFalse(String.valueOf(other.getMessage()).contains("s3cret-value"));
    }

    @Test
    void rejectsANullOrEmptyUrl() {
        assertThrows(NullPointerException.class, () -> new DriverManagerDataSource(null, "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new DriverManagerDataSource("", "a", "b"));
    }

    @Test
    void unsupportedDataSourceFeaturesBehaveSensibly() throws SQLException {
        DataSource source = new DriverManagerDataSource(UNKNOWN_URL, null, null);

        assertNull(source.getLogWriter());
        assertEquals(0, source.getLoginTimeout());
        assertThrows(SQLFeatureNotSupportedException.class, source::getParentLogger);
        assertThrows(SQLFeatureNotSupportedException.class, () -> source.setLogWriter(null));
        assertThrows(SQLFeatureNotSupportedException.class, () -> source.setLoginTimeout(5));
    }

    @Test
    void unwrapReturnsItselfForItsOwnTypesAndFailsForOthers() throws SQLException {
        DataSource source = new DriverManagerDataSource(UNKNOWN_URL, null, null);

        assertTrue(source.isWrapperFor(DataSource.class));
        assertSame(source, source.unwrap(DataSource.class));
        assertFalse(source.isWrapperFor(Runnable.class));
        assertThrows(SQLException.class, () -> source.unwrap(Runnable.class));
    }

    @Test
    void toStringMasksUserInfoAndAlternatePasswordKeys() {
        String text = new DriverManagerDataSource("jdbc:diwan://app:s3cret-value@h/db?pwd=s3cret-two&passwd=s3cret-three",
                null, null).toString();

        assertFalse(text.contains("s3cret"), text);
        assertTrue(text.contains("app:***@h"), text);
    }

    @Test
    void theConnectionErrorMasksSecretsInTheUrlAndKeepsTheSqlState() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:diwan-no-such-driver://app:s3cret-value@h/db?pwd=s3cret-two", null, null);

        SQLException e = assertThrows(SQLException.class, source::getConnection);

        assertFalse(String.valueOf(e.getMessage()).contains("s3cret"), e.getMessage());
        assertTrue(e.getMessage().contains("***"), e.getMessage());
        assertEquals("08001", e.getSQLState());
    }
}

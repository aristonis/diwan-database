package org.aristonis.photisma.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.aristonis.photisma.database.driver.Driver;
import org.aristonis.photisma.database.driver.DriverCapability;
import org.aristonis.photisma.database.driver.DriverInfo;
import org.aristonis.photisma.database.driver.DriverSession;
import org.junit.jupiter.api.Test;

class ConnectionBuilderTest {

    private static final Driver NO_SESSIONS = new Driver() {
        @Override
        public DriverSession open() {
            throw new AssertionError("a builder must not open a session");
        }

        @Override
        public DriverInfo info() {
            return new DriverInfo("none", "0", EnumSet.noneOf(DriverCapability.class));
        }
    };

    @Test
    void buildsAConnectionWithItsNameAndAnEmptyPrefixByDefault() {
        Connection connection = Connection.builder().name("main").driver(NO_SESSIONS).build();

        assertEquals("main", connection.getName());
        assertEquals("", connection.getTablePrefix());
        assertEquals(0, connection.transactionLevel());
    }

    @Test
    void keepsTheConfiguredTablePrefix() {
        Connection connection = Connection.builder().name("main").driver(NO_SESSIONS).tablePrefix("app_").build();

        assertEquals("app_", connection.getTablePrefix());
    }

    @Test
    void refusesToBuildWithoutAName() {
        ConnectionBuilder builder = Connection.builder().driver(NO_SESSIONS);

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void refusesToBuildWithoutADriver() {
        ConnectionBuilder builder = Connection.builder().name("main");

        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void refusesASecondDriverSource() {
        ConnectionBuilder builder = Connection.builder().name("main").driver(NO_SESSIONS);

        assertThrows(IllegalStateException.class, () -> builder.driver(NO_SESSIONS));
    }

    @Test
    void refusesNullSettings() {
        ConnectionBuilder builder = Connection.builder();

        assertThrows(NullPointerException.class, () -> builder.name(null));
        assertThrows(NullPointerException.class, () -> builder.driver(null));
        assertThrows(NullPointerException.class, () -> builder.tablePrefix(null));
        assertThrows(NullPointerException.class, () -> builder.onCleanupFailure(null));
    }

    @Test
    void refusesABlankName() {
        assertThrows(IllegalArgumentException.class, () -> Connection.builder().name(" "));
    }
}

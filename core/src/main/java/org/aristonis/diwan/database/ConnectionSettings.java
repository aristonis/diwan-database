package org.aristonis.diwan.database;

import java.util.function.Consumer;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverInfo;

/** Holds the settings fixed by {@link ConnectionBuilder#build()}; immutable and safe to share. */
final class ConnectionSettings {

    private final String name;
    private final String tablePrefix;
    private final boolean showBindingsInErrors;
    private final boolean strictTransactions;
    private final Consumer<Throwable> cleanupFailureHandler;
    private final Driver driver;

    ConnectionSettings(String name, String tablePrefix, boolean showBindingsInErrors,
            boolean strictTransactions, Consumer<Throwable> cleanupFailureHandler, Driver driver) {
        this.name = name;
        this.tablePrefix = tablePrefix;
        this.showBindingsInErrors = showBindingsInErrors;
        this.strictTransactions = strictTransactions;
        this.cleanupFailureHandler = cleanupFailureHandler;
        this.driver = driver;
    }

    String name() {
        return name;
    }

    String tablePrefix() {
        return tablePrefix;
    }

    boolean showBindingsInErrors() {
        return showBindingsInErrors;
    }

    boolean strictTransactions() {
        return strictTransactions;
    }

    Consumer<Throwable> cleanupFailureHandler() {
        return cleanupFailureHandler;
    }

    DriverInfo driverInfo() {
        return driver.info();
    }
}

package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.ConnectionBuilder;
import org.aristonis.diwan.database.event.ConnectionEvents;
import org.aristonis.diwan.database.event.ConnectionListener;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.driver.Driver;
import org.aristonis.diwan.database.driver.DriverInfo;

/** Holds the settings fixed by {@link ConnectionBuilder#build()}, and the events shared by the connection family. */
@Internal
public final class ConnectionSettings {

    private final String name;
    private final String tablePrefix;
    private final boolean showBindingsInErrors;
    private final boolean strictTransactions;
    private final Consumer<Throwable> cleanupFailureHandler;
    private final Driver driver;
    private final ConnectionEvents events;

    @Internal
    public ConnectionSettings(String name, String tablePrefix, boolean showBindingsInErrors,
            boolean strictTransactions, Consumer<Throwable> cleanupFailureHandler, Driver driver,
            int queryLogLimit, List<ConnectionListener> listeners) {
        this.name = name;
        this.tablePrefix = tablePrefix;
        this.showBindingsInErrors = showBindingsInErrors;
        this.strictTransactions = strictTransactions;
        this.cleanupFailureHandler = cleanupFailureHandler;
        this.driver = driver;
        this.events = new ConnectionEvents(queryLogLimit, listeners, showBindingsInErrors);
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

    /** Returns the listeners, query log and slow-query handlers shared by the whole family. */
    ConnectionEvents events() {
        return events;
    }

    DriverInfo driverInfo() {
        return driver.info();
    }
}

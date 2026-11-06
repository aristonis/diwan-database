package org.aristonis.diwan.database.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.aristonis.diwan.database.event.ConnectionListener;
import org.aristonis.diwan.database.migrations.event.MigrationListener;

/** The listeners a {@link DatabaseManager.Builder} collected, in registration order. */
final class ManagerListeners {

    private final List<ConnectionListener> connection = new ArrayList<>();
    private final List<ManagerListener> manager = new ArrayList<>();
    private final List<MigrationListener> migration = new ArrayList<>();

    void addConnection(ConnectionListener listener) {
        connection.add(Objects.requireNonNull(listener, "listener"));
    }

    void addManager(ManagerListener listener) {
        manager.add(Objects.requireNonNull(listener, "listener"));
    }

    void addMigration(MigrationListener listener) {
        migration.add(Objects.requireNonNull(listener, "listener"));
    }

    List<ConnectionListener> connection() {
        return connection;
    }

    List<ManagerListener> manager() {
        return manager;
    }

    List<MigrationListener> migration() {
        return migration;
    }

    /** Returns a snapshot, so later builder calls never reach a built manager. */
    ManagerListeners copy() {
        ManagerListeners copy = new ManagerListeners();
        copy.connection.addAll(connection);
        copy.manager.addAll(manager);
        copy.migration.addAll(migration);
        return copy;
    }
}

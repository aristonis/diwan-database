package org.aristonis.diwan.database.migrations.internal;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.migrations.Migrator;
import org.aristonis.diwan.database.migrations.event.MigrationEvent;
import org.aristonis.diwan.database.migrations.event.MigrationListener;
import org.aristonis.diwan.database.migrations.event.MigrationsEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.aristonis.diwan.database.exception.MigrationFailedException.Direction;

/**
 * Calls the listeners of a {@link Migrator} and applies the failure rules of {@link MigrationListener}.
 *
 * <p>Every method except {@link #migrationFailed} lets a listener's exception escape and skips the
 * listeners after it; the migrator places the calls so that the escape comes before the work or
 * after the work is recorded. {@link #migrationFailed} never throws: a listener's exception is added
 * as suppressed to the failure it was told about, whether it is an exception or an {@link Error}, so
 * the listener never replaces the migration's own failure.
 */
@Internal
public final class MigrationNotifier {

    private final List<MigrationListener> listeners;
    private final String repositoryConnection;

    @Internal
    public MigrationNotifier(List<MigrationListener> listeners, String repositoryConnection) {
        this.listeners = Collections.unmodifiableList(new ArrayList<>(listeners));
        this.repositoryConnection = repositoryConnection;
    }

    /** Builds the event for a whole call; it names the connection that holds the migrations table. */
    @Internal
    public MigrationsEvent whole(Direction direction, boolean isPretend) {
        return new MigrationsEvent(direction, isPretend, repositoryConnection);
    }

    @Internal
    public void migrationsStarted(MigrationsEvent event) {
        each(listener -> listener.migrationsStarted(event));
    }

    @Internal
    public void migrationsEnded(MigrationsEvent event) {
        each(listener -> listener.migrationsEnded(event));
    }

    @Internal
    public void noPendingMigrations(MigrationsEvent event) {
        each(listener -> listener.noPendingMigrations(event));
    }

    @Internal
    public void migrationStarted(MigrationEvent event) {
        each(listener -> listener.migrationStarted(event));
    }

    @Internal
    public void migrationEnded(MigrationEvent event) {
        each(listener -> listener.migrationEnded(event));
    }

    @Internal
    public void migrationSkipped(MigrationEvent event) {
        each(listener -> listener.migrationSkipped(event));
    }

    @Internal
    public void migrationFailed(MigrationEvent event, Throwable failure) {
        for (MigrationListener listener : listeners) {
            try {
                listener.migrationFailed(event, failure);
            } catch (Throwable listenerFailure) {
                failure.addSuppressed(listenerFailure);
            }
        }
    }

    private void each(Consumer<MigrationListener> call) {
        for (MigrationListener listener : listeners) {
            call.accept(listener);
        }
    }
}

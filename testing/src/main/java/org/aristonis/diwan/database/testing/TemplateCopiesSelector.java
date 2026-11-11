package org.aristonis.diwan.database.testing;

import org.aristonis.diwan.database.schema.Schema;

/** Chooses the {@link TemplateCopies} of a run by the database the root connection is on. */
final class TemplateCopiesSelector {

    private static final String PGSQL = "pgsql";
    private static final String SQLITE = "sqlite";

    private TemplateCopiesSelector() {
    }

    /**
     * Chooses the copies for the database behind the run connection.
     *
     * @param settings the extension's settings, never {@code null}
     * @param run the run's root connection, never {@code null}
     * @return the copies, never {@code null}
     * @throws IllegalStateException if the database is not PostgreSQL or SQLite, is SQLite
     *     without a {@code directory}, which means an in-memory target, or is PostgreSQL with one
     */
    static TemplateCopies choose(TemplateSettings settings, RunConnection run) {
        Schema schema = settings.schema().apply(run.connection());
        String driver = schema.driverName();
        if (PGSQL.equals(driver)) {
            if (settings.directory() != null) {
                throw new IllegalStateException("directory() applies to SQLite template mode, not to PostgreSQL:"
                        + " a PostgreSQL copy is a database on the server. Remove directory(Path)");
            }
            return new PostgresTemplateCopies(settings.name(), run, settings.schema(), settings.strategy(),
                    settings.isDroppingStale());
        }
        if (SQLITE.equals(driver)) {
            if (settings.directory() == null) {
                throw new IllegalStateException("Template mode cannot copy an in-memory SQLite database. Give"
                        + " directory(Path) so each test copies a template file, or use rollback or truncate mode");
            }
            return new SQLiteFileTemplateCopies(settings.name(), run, settings.directory(),
                    settings.schema(), settings.isDroppingStale());
        }
        throw new IllegalStateException("Template mode supports PostgreSQL and SQLite, not '" + driver + "'");
    }
}

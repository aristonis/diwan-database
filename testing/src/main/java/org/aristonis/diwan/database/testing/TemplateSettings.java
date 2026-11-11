package org.aristonis.diwan.database.testing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javax.sql.DataSource;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.CopyStrategy;
import org.aristonis.diwan.database.schema.Schema;

/**
 * What {@link RefreshDatabase} in template mode was given: the arguments of its factory and its
 * options. Immutable; each {@code with} method returns a changed copy.
 */
final class TemplateSettings {

    private final DataSource root;
    private final Function<String, DatabaseManager> perDatabase;
    private final Function<Connection, Schema> schema;
    private final List<Migration> migrations;
    private final TemplateName name;
    private final CopyStrategy strategy;
    private final Path directory;
    private final boolean isDroppingStale;
    private final TemplateCopies.Factory copies;

    private TemplateSettings(DataSource root, Function<String, DatabaseManager> perDatabase,
            Function<Connection, Schema> schema, List<Migration> migrations, TemplateName name,
            CopyStrategy strategy, Path directory, boolean isDroppingStale, TemplateCopies.Factory copies) {
        this.root = root;
        this.perDatabase = perDatabase;
        this.schema = schema;
        this.migrations = migrations;
        this.name = name;
        this.strategy = strategy;
        this.directory = directory;
        this.isDroppingStale = isDroppingStale;
        this.copies = copies;
    }

    /**
     * Checks and keeps the arguments of the template factory; no option is set.
     *
     * @param root the root data source, never {@code null}
     * @param perDatabase opens a manager on a database name, never {@code null}
     * @param schema builds the schema builder of a connection, never {@code null}
     * @param migrations the migrations, never {@code null}, without {@code null} elements; copied
     * @return the settings, never {@code null}
     * @throws NullPointerException if an argument or an element is {@code null}
     */
    static TemplateSettings of(DataSource root, Function<String, DatabaseManager> perDatabase,
            Function<Connection, Schema> schema, List<Migration> migrations) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(perDatabase, "perDatabase");
        Objects.requireNonNull(schema, "schema");
        List<Migration> copy = new ArrayList<>(Objects.requireNonNull(migrations, "migrations"));
        for (Migration migration : copy) {
            Objects.requireNonNull(migration, "A migration is null");
        }
        return new TemplateSettings(root, perDatabase, schema, Collections.unmodifiableList(copy),
                TemplateName.of(copy), null, null, false, TemplateCopiesSelector::choose);
    }

    TemplateSettings withStrategy(CopyStrategy value) {
        Objects.requireNonNull(value, "strategy");
        return new TemplateSettings(root, perDatabase, schema, migrations, name, value, directory,
                isDroppingStale, copies);
    }

    TemplateSettings withDirectory(Path value) {
        Objects.requireNonNull(value, "directory");
        return new TemplateSettings(root, perDatabase, schema, migrations, name, strategy, value,
                isDroppingStale, copies);
    }

    TemplateSettings withDroppingStale() {
        return new TemplateSettings(root, perDatabase, schema, migrations, name, strategy, directory, true, copies);
    }

    /** Swaps the engine-specific copies, so tests can run the extension without a real engine. */
    TemplateSettings withCopies(TemplateCopies.Factory value) {
        Objects.requireNonNull(value, "copies");
        return new TemplateSettings(root, perDatabase, schema, migrations, name, strategy, directory,
                isDroppingStale, value);
    }

    /** Tells whether two settings carry the same options, which the run state shared by classes needs. */
    boolean hasSameOptions(TemplateSettings other) {
        return strategy == other.strategy && Objects.equals(directory, other.directory)
                && isDroppingStale == other.isDroppingStale;
    }

    DataSource root() {
        return root;
    }

    Function<String, DatabaseManager> perDatabase() {
        return perDatabase;
    }

    Function<Connection, Schema> schema() {
        return schema;
    }

    List<Migration> migrations() {
        return migrations;
    }

    TemplateName name() {
        return name;
    }

    /** Returns the explicit copy strategy, or {@code null} for the engine's default. */
    CopyStrategy strategy() {
        return strategy;
    }

    /** Returns the directory of SQLite file copies, or {@code null} when none was set. */
    Path directory() {
        return directory;
    }

    boolean isDroppingStale() {
        return isDroppingStale;
    }

    TemplateCopies.Factory copies() {
        return copies;
    }
}

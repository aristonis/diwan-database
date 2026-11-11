package org.aristonis.diwan.database.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.manager.ConnectionConfig;
import org.aristonis.diwan.database.manager.DatabaseConfig;
import org.aristonis.diwan.database.manager.DatabaseManager;
import org.aristonis.diwan.database.sqlite.SQLiteConnector;

/**
 * A {@link TemplateCopies} over plain SQLite files, with no lock and no sweep, that records what
 * the extension asked of it. Lets the extension run without PostgreSQL.
 */
final class FakeCopies implements TemplateCopies {

    private final Path directory;
    private final Path template;
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final boolean isFailingPrepare;
    private final boolean isFailingDrop;

    FakeCopies(Path directory, boolean isFailingPrepare, boolean isFailingDrop) {
        this.directory = directory;
        this.template = directory.resolve("template.db");
        this.isFailingPrepare = isFailingPrepare;
        this.isFailingDrop = isFailingDrop;
    }

    /** Returns a manager over one SQLite file, the shape {@code perDatabase} has in template mode. */
    static DatabaseManager manager(String file) {
        ConnectionConfig connection = ConnectionConfig.builder("main").driver("sqlite").database(file).build();
        DatabaseConfig config = DatabaseConfig.builder().defaultConnection("main").connection(connection).build();
        return DatabaseManager.builder().config(config).connector(new SQLiteConnector()).build();
    }

    /** Returns the events in order: {@code prepare}, {@code sweep}, {@code create:n}, {@code drop:file}, {@code close}. */
    List<String> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    @Override
    public void prepare(Function<String, DatabaseManager> perDatabase, Consumer<Connection> migrate) {
        events.add("prepare");
        if (isFailingPrepare) {
            throw new IllegalStateException("the template cannot be built");
        }
        TemplateCopies.migrateInto(perDatabase, template.toString(), migrate);
    }

    @Override
    public void sweep() {
        events.add("sweep");
    }

    @Override
    public String createCopy(long n) {
        events.add("create:" + n);
        Path copy = directory.resolve("copy_" + n + ".db");
        try {
            Files.copy(template, copy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy the template to " + copy, e);
        }
        return copy.toString();
    }

    @Override
    public void dropCopy(String copy) {
        events.add("drop:" + copy);
        if (isFailingDrop) {
            throw new IllegalStateException("the copy cannot be dropped: " + copy);
        }
        try {
            Files.deleteIfExists(Path.of(copy));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + copy, e);
        }
    }

    @Override
    public void close() {
        events.add("close");
    }
}

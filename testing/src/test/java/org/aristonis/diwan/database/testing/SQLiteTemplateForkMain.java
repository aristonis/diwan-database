package org.aristonis.diwan.database.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.sqlite.SQLiteDataSource;

/**
 * Child JVM of {@link SQLiteTemplateForkTest}: waits for a go file, builds the same template as
 * the parent and prints how often it migrated and how many rows its copy holds.
 */
public final class SQLiteTemplateForkMain {

    private static final long POLL_MILLIS = 10;

    private SQLiteTemplateForkMain() {
    }

    /**
     * Runs the child.
     *
     * @param args the directory and the go file path
     * @throws Exception if the build or the wait fails
     */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path go = Path.of(args[1]);
        SQLiteDataSource root = new SQLiteDataSource();
        root.setUrl("jdbc:sqlite:" + directory.resolve("child-root.db"));
        RunConnection run = RunConnection.open(root);
        try {
            System.out.println("ready");
            System.out.flush();
            while (!Files.exists(go)) {
                Thread.sleep(POLL_MILLIS);
            }
            AtomicInteger migrated = new AtomicInteger();
            try (SQLiteFileTemplateCopies copies = new SQLiteFileTemplateCopies(
                    TemplateName.of(Fixtures.base()), run, directory, SQLiteSchema::of, false)) {
                copies.prepare(FakeCopies::manager, c -> {
                    migrated.incrementAndGet();
                    SQLiteTemplateModeTest.migrate(c, SQLiteTemplateForkTest.ROWS, SQLiteTemplateForkTest.DELAY_MILLIS);
                });
                String copy = copies.createCopy(1);
                System.out.println("migrated=" + migrated.get() + " rows=" + SQLiteTemplateModeTest.count(copy));
                copies.dropCopy(copy);
            }
        } finally {
            run.close();
        }
    }
}

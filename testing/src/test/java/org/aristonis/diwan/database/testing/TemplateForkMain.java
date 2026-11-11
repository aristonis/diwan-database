package org.aristonis.diwan.database.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.pgsql.PostgresSchema;
import org.aristonis.diwan.database.schema.Schema;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The child JVM of the multi-process template test: it prepares the same template as its parent
 * at the same moment, makes one copy, drops it and exits.
 *
 * <p>The arguments are the server's host, its port and the name of a database to connect to. The
 * system properties {@value #MIGRATION_PROPERTY} and {@value #COUNTER_PROPERTY} name the one
 * migration and the file where each run of it leaves a line. The child prints {@code ready}, waits
 * for a line on its standard input, runs, and prints {@code done}.
 */
public final class TemplateForkMain {

    /** System property: the name of the migration, so parent and child build the same template. */
    static final String MIGRATION_PROPERTY = "diwan.fork.migration";

    /** System property: the file that gets one line each time the migration runs. */
    static final String COUNTER_PROPERTY = "diwan.fork.counter";

    private static final long BUILD_HOLD_MILLIS = 1500;

    private TemplateForkMain() {
    }

    /**
     * Runs the child side of the test.
     *
     * @param args host, port and database of the server
     * @throws Exception if the run fails; the process then exits with a failure status
     */
    public static void main(String[] args) throws Exception {
        PGSimpleDataSource server = new PGSimpleDataSource();
        server.setServerNames(new String[] {args[0]});
        server.setPortNumbers(new int[] {Integer.parseInt(args[1])});
        server.setDatabaseName(args[2]);
        server.setUser("postgres");
        System.out.println("ready");
        System.out.flush();
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        runOnce(server);
        System.out.println("done");
    }

    /** Returns the migration list both sides use. */
    static List<Migration> migrations() {
        return List.of(new Probe(System.getProperty(MIGRATION_PROPERTY)));
    }

    /**
     * Prepares the template as a run does, makes and drops one copy, and closes the run.
     *
     * @param server the server to use, never {@code null}
     */
    static void runOnce(PGSimpleDataSource server) {
        List<Migration> list = migrations();
        RunConnection run = RunConnection.open(server);
        try {
            PostgresTemplateCopies copies = new PostgresTemplateCopies(TemplateName.of(list), run,
                    PostgresSchema::of, null, false);
            try {
                copies.prepare(database -> PostgresTemplateFixtures.manager(server, database, "postgres"),
                        connection -> RefreshDatabase.migrate(PostgresSchema::of, list, connection));
                copies.sweep();
                copies.dropCopy(copies.createCopy(1));
            } finally {
                copies.close();
            }
        } finally {
            run.close();
        }
    }

    /** Leaves a line in the counter file, then holds the build open so the other side must wait. */
    static final class Probe extends Migration {
        private final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void up(Schema schema) {
            try {
                Files.writeString(Path.of(System.getProperty(COUNTER_PROPERTY)), "built\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Thread.sleep(BUILD_HOLD_MILLIS);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write the counter file", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while holding the build", e);
            }
            schema.create("items", table -> {
                table.id();
                table.string("label");
            });
        }
    }
}

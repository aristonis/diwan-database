package org.aristonis.diwan.database.pgsql;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * A throwaway PostgreSQL for the tests: one cluster per test JVM, started from the local binaries
 * in a temporary folder, and a fresh database for each caller.
 *
 * <p>The binaries come from {@code DIWAN_PG_BIN}, else {@code pg_config --bindir}, else the path.
 * Without them the tests are skipped, unless {@code DIWAN_PG_REQUIRED} is set, as in CI, where a
 * missing server must fail the build rather than shrink it.
 */
final class PostgresServer {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static PostgresServer started;
    private static String unavailable;

    private final String bin;
    private final Path folder;
    private final int port;

    private PostgresServer(String bin, Path folder, int port) {
        this.bin = bin;
        this.folder = folder;
        this.port = port;
    }

    /** Returns a connection to a new, empty database, or skips the test when PostgreSQL is not installed. */
    static Connection newDatabase() {
        PostgresServer server = get();
        String name = "t" + DATABASES.incrementAndGet();
        Connection admin = server.connect("postgres");
        admin.statement("create database " + name);
        return server.connect(name);
    }

    static PGSimpleDataSource dataSource(String database) {
        PostgresServer server = get();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {server.port});
        source.setDatabaseName(database);
        source.setUser("postgres");
        return source;
    }

    private Connection connect(String database) {
        return Connection.builder().name("pgsql").dataSource(dataSource(database)).build();
    }

    private static synchronized PostgresServer get() {
        if (started == null && unavailable == null) {
            try {
                started = start();
            } catch (IOException | RuntimeException e) {
                unavailable = String.valueOf(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unavailable = "interrupted";
            }
        }
        if (started == null && System.getenv("DIWAN_PG_REQUIRED") != null) {
            throw new IllegalStateException("PostgreSQL is required but did not start: " + unavailable);
        }
        Assumptions.assumeTrue(started != null, () -> "PostgreSQL tests skipped: " + unavailable);
        return started;
    }

    private static PostgresServer start() throws IOException, InterruptedException {
        String bin = binaries();
        // A short folder name keeps the socket path under the Unix limit; no spaces for pg_ctl -o.
        Path folder = Files.createTempDirectory(Paths.get("/tmp"), "dpg");
        try {
            Path data = folder.resolve("data");
            run(bin, Arrays.asList("initdb", "-D", data.toString(), "-U", "postgres", "--auth=trust", "-E", "UTF8",
                    "--no-locale"), folder);
            int port = freePort();
            try {
                startOn(bin, folder, port);
            } catch (IllegalStateException e) {
                // Another process took the port between the probe and the start: try once more.
                port = freePort();
                startOn(bin, folder, port);
            }
            PostgresServer server = new PostgresServer(bin, folder, port);
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            return server;
        } catch (IOException | RuntimeException | InterruptedException e) {
            delete(folder);
            throw e;
        }
    }

    private static void startOn(String bin, Path folder, int port) throws IOException, InterruptedException {
        run(bin, Arrays.asList("pg_ctl", "-D", folder.resolve("data").toString(), "-l",
                folder.resolve("log").toString(), "-w", "-o",
                "-p " + port + " -k " + folder + " -c listen_addresses=127.0.0.1", "start"), folder);
    }

    private void stop() {
        try {
            run(bin, Arrays.asList("pg_ctl", "-D", folder.resolve("data").toString(), "-m", "immediate", "stop"),
                    folder);
        } catch (IOException | RuntimeException e) {
            // The JVM is exiting; the folder is removed below either way.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delete(folder);
    }

    private static String binaries() throws IOException, InterruptedException {
        String fromEnv = System.getenv("DIWAN_PG_BIN");
        if (fromEnv != null) {
            return fromEnv;
        }
        try {
            Process process = new ProcessBuilder("pg_config", "--bindir").redirectErrorStream(true).start();
            String out = new String(readAll(process), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && !out.isEmpty()) {
                return out;
            }
        } catch (IOException e) {
            // No pg_config: rely on the path.
        }
        return "";
    }

    private static byte[] readAll(Process process) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void delete(Path folder) {
        try (java.util.stream.Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // Left for the system's temporary-folder cleanup.
        }
    }

    private static void run(String bin, List<String> command, Path folder) throws IOException, InterruptedException {
        List<String> full = new java.util.ArrayList<>(command);
        if (!bin.isEmpty()) {
            full.set(0, Paths.get(bin, command.get(0)).toString());
        }
        Process process = new ProcessBuilder(full).redirectErrorStream(true)
                .redirectOutput(folder.resolve(command.get(0) + ".out").toFile()).start();
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IllegalStateException(command.get(0) + " failed; see " + folder);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

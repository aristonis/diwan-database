package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

/** Two JVMs prepare the same template at once; it is built once and both runs get their copy. */
class PostgresTemplateForkTest {

    private static final long START_TIMEOUT_SECONDS = 60;
    private static final long EXIT_TIMEOUT_SECONDS = 120;

    @TempDir
    Path folder;

    @Test
    void twoJvmsBuildOneTemplate() throws Exception {
        PGSimpleDataSource server = PostgresServer.dataSource("postgres");
        Path counter = folder.resolve("counter.txt");
        String migration = "2026_11_11_fork_" + UUID.randomUUID().toString().replace('-', '_');
        System.setProperty(TemplateForkMain.MIGRATION_PROPERTY, migration);
        System.setProperty(TemplateForkMain.COUNTER_PROPERTY, counter.toString());
        String hash8 = TemplateName.of(TemplateForkMain.migrations()).hash8();
        Process child = null;
        try {
            child = startChild(server, migration, counter);
            Output output = new Output(child);
            assertTrue(output.ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "The child did not start: " + output.lines());
            OutputStream in = child.getOutputStream();
            in.write("go\n".getBytes(StandardCharsets.UTF_8));
            in.flush();

            TemplateForkMain.runOnce(server);

            assertTrue(child.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "The child did not exit: " + output.lines());
            assertEquals(0, child.exitValue(), output.lines().toString());
            assertTrue(output.lines().contains("done"), output.lines().toString());
            assertEquals(1, Files.readAllLines(counter).size());
            assertEquals(1, PostgresTemplateFixtures.count("diwan\\_tpl\\_" + hash8));
            assertEquals(0, PostgresTemplateFixtures.count("diwan\\_tpl\\_" + hash8 + "\\_b\\_%"));
            assertEquals(0, PostgresTemplateFixtures.count("diwan\\_t\\_" + hash8 + "\\_%"));
        } finally {
            if (child != null) {
                child.destroyForcibly();
            }
            System.clearProperty(TemplateForkMain.MIGRATION_PROPERTY);
            System.clearProperty(TemplateForkMain.COUNTER_PROPERTY);
            PostgresTemplateFixtures.dropAll("diwan\\_t\\_" + hash8 + "\\_%");
            PostgresTemplateFixtures.dropAll("diwan\\_tpl\\_" + hash8 + "%");
        }
    }

    private static Process startChild(PGSimpleDataSource server, String migration, Path counter) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "-D" + TemplateForkMain.MIGRATION_PROPERTY + "=" + migration,
                "-D" + TemplateForkMain.COUNTER_PROPERTY + "=" + counter, TemplateForkMain.class.getName(),
                server.getServerNames()[0], String.valueOf(server.getPortNumbers()[0]), "postgres")
                .redirectErrorStream(true).start();
    }

    /** Reads the child's output on a helper thread, so a child that prints nothing cannot block the test. */
    private static final class Output {
        final CountDownLatch ready = new CountDownLatch(1);
        private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        Output(Process child) {
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                        if ("ready".equals(line)) {
                            ready.countDown();
                        }
                    }
                } catch (IOException e) {
                    lines.add("read failed: " + e.getMessage());
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        List<String> lines() {
            synchronized (lines) {
                return new ArrayList<>(lines);
            }
        }
    }
}

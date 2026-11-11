package org.aristonis.diwan.database.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.sqlite.SQLiteSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** Two JVMs that need the same template at the same moment build it once. */
class SQLiteTemplateForkTest {

    static final int ROWS = 5;
    static final long DELAY_MILLIS = 1500;
    private static final long TIMEOUT_SECONDS = 60;

    @TempDir
    Path folder;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void twoJvmsBuildOneTemplate() throws Exception {
        Path go = folder.resolve("go");
        Process child = startChild(go);
        SQLiteDataSource root = new SQLiteDataSource();
        root.setUrl("jdbc:sqlite:" + folder.resolve("root.db"));
        RunConnection run = RunConnection.open(root);
        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            awaitLine(out, "ready");
            AtomicInteger migrated = new AtomicInteger();
            Files.createFile(go);

            try (SQLiteFileTemplateCopies copies = new SQLiteFileTemplateCopies(
                    TemplateName.of(Fixtures.base()), run, folder, SQLiteSchema::of, false)) {
                copies.prepare(FakeCopies::manager, c -> {
                    migrated.incrementAndGet();
                    SQLiteTemplateModeTest.migrate(c, ROWS, DELAY_MILLIS);
                });
                String copy = copies.createCopy(1);
                assertEquals(ROWS, SQLiteTemplateModeTest.count(copy));
            }

            String result = awaitLine(out, "migrated=");
            assertTrue(child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            int childMigrated = Integer.parseInt(result.substring("migrated=".length(), result.indexOf(' ')));
            assertEquals(1, migrated.get() + childMigrated, result);
            assertTrue(result.endsWith("rows=" + ROWS), result);
        } finally {
            child.destroyForcibly();
            run.close();
        }
    }

    private static Process startChild(Path go) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path directory = go.getParent();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SQLiteTemplateForkMain.class.getName(), directory.toString(), go.toString())
                .redirectErrorStream(true).start();
    }

    // A JVM may print notices first, such as "Picked up JAVA_TOOL_OPTIONS", so lines are skipped until one matches.
    private static String awaitLine(BufferedReader in, String prefix) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        assertNotNull(line, "The child ended before printing " + prefix);
        return line;
    }
}

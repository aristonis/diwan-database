package org.aristonis.diwan.database.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertiesConfigSourceTest {

    private static final String TEXT = "database.default=main\n"
            + "database.connections.main.driver=sqlite\n"
            + "database.connections.main.database=caf\u00e9.db\n"
            + "database.connections.cache.driver=sqlite\n";

    @TempDir
    Path folder;

    private static void assertSample(PropertiesConfigSource source) {
        assertEquals("main", source.get("database.default").orElseThrow());
        assertEquals("caf\u00e9.db", source.get("database.connections.main.database").orElseThrow());
        assertEquals(Arrays.asList("cache", "main"), Arrays.asList(source.keys("database.connections").toArray()));
    }

    @Test
    void loadsAFileAsUtf8() throws IOException {
        Path file = folder.resolve("diwan.properties");
        Files.write(file, TEXT.getBytes(StandardCharsets.UTF_8));

        assertSample(PropertiesConfigSource.of(file));
    }

    @Test
    void namesThePathWhenTheFileIsMissing() {
        Path file = folder.resolve("missing.properties");

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> PropertiesConfigSource.of(file));

        assertTrue(e.getMessage().contains(file.toString()), e.getMessage());
    }

    @Test
    void namesThePathWhenThePathIsAFolder() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> PropertiesConfigSource.of(folder));

        assertTrue(e.getMessage().contains(folder.toString()), e.getMessage());
    }

    @Test
    void loadsAStreamAsUtf8AndLeavesItOpen() throws IOException {
        boolean[] closed = {false};
        InputStream stream = new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        assertSample(PropertiesConfigSource.of(stream));

        assertFalse(closed[0]);
        stream.close();
    }

    @Test
    void loadsAClasspathResourceWithOrWithoutALeadingSlash() {
        assertSample(PropertiesConfigSource.fromClasspath("diwan-config-test.properties"));
        assertSample(PropertiesConfigSource.fromClasspath("/diwan-config-test.properties"));
    }

    @Test
    void namesTheResourceWhenItIsNotOnTheClasspath() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> PropertiesConfigSource.fromClasspath("no-such-diwan.properties"));

        assertTrue(e.getMessage().contains("no-such-diwan.properties"), e.getMessage());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> PropertiesConfigSource.of((Path) null));
        assertThrows(NullPointerException.class, () -> PropertiesConfigSource.of((InputStream) null));
        assertThrows(NullPointerException.class, () -> PropertiesConfigSource.fromClasspath(null));
    }

    @Test
    void toStringShowsTheCountAndNoValues() {
        PropertiesConfigSource source = PropertiesConfigSource.of(
                new ByteArrayInputStream("database.password=s3cret-value\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("PropertiesConfigSource[keys=1]", source.toString());
    }
}

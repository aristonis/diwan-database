package org.aristonis.diwan.database.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A public class in an internal package is public only for other Diwan packages, and says so. */
class InternalMarkerTest {

    @Test
    void everyPublicInternalClassIsMarked() throws IOException {
        List<String> unmarked = new ArrayList<>();
        for (String module : List.of("core", "sqlite", "pgsql", "eloquent", "android")) {
            Path main = Paths.get("..", module, "src", "main", "java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : (Iterable<Path>) files
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.getParent().getFileName().toString().equals("internal"))::iterator) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    if (text.contains("\npublic ") && !text.contains("\n@Internal\n")) {
                        unmarked.add(file.toString());
                    }
                }
            }
        }
        assertEquals(List.of(), unmarked);
    }
}

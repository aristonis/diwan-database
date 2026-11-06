package org.aristonis.diwan.database;

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

/** Keeps every main source file of every module within the project's size limit. */
class FileSizeTest {

    private static final int MAX_LINES = 800;

    @Test
    void everyMainFileStaysWithinTheLimit() throws IOException {
        List<String> tooLong = new ArrayList<>();
        for (String module : List.of("core", "sqlite", "pgsql", "eloquent", "android")) {
            Path main = Paths.get("..", module, "src", "main", "java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    int lines = Files.readAllLines(file, StandardCharsets.UTF_8).size();
                    if (lines > MAX_LINES) {
                        tooLong.add(module + "/" + file.getFileName() + " " + lines);
                    }
                }
            }
        }
        assertEquals(List.of(), tooLong);
    }
}

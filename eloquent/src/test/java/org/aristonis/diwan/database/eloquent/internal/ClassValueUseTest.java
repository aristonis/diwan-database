package org.aristonis.diwan.database.eloquent.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code java.lang.ClassValue} inside {@link ClassValueCache}, so the rest of the module
 * loads on Android below API 34, which has no {@code ClassValue}.
 *
 * <p>The rule scans every main source file and fails on a file that uses {@code ClassValue} as a
 * type. A file counts as a use when it contains {@code ClassValue<} or {@code extends ClassValue}.
 * It also fails on the name {@code java.lang.ClassValue} anywhere except in {@link ClassCache},
 * which names it once as a probe string for {@code Class.forName}. The check is textual, so a
 * comment that spells out one of these forms is a use too; reword the comment in that case.
 */
class ClassValueUseTest {

    private static final String PACKAGE_DIR = "src/main/java/org/aristonis/diwan/database/eloquent";
    private static final String OWNER = "internal/ClassValueCache.java";
    private static final String PROBE_OWNER = "internal/ClassCache.java";
    private static final String QUALIFIED_NAME = "java.lang.ClassValue";
    private static final List<String> TYPE_MARKS = List.of("ClassValue<", "extends ClassValue");

    @Test
    void onlyClassValueCacheUsesClassValueAsAType() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = sourceDir();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.equals(OWNER)) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String checked = relative.equals(PROBE_OWNER)
                        ? text.replace(QUALIFIED_NAME, "") : text;
                if (mentionsClassValue(checked)) {
                    offenders.add(relative);
                }
            }
        }

        assertEquals(Collections.emptyList(), offenders);
    }

    private static boolean mentionsClassValue(String text) {
        return text.contains(QUALIFIED_NAME) || TYPE_MARKS.stream().anyMatch(text::contains);
    }

    private static Path sourceDir() {
        for (String base : new String[] {".", "eloquent", "diwan-database/eloquent"}) {
            Path candidate = Paths.get(base, PACKAGE_DIR);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("eloquent main sources not found from " + Paths.get("").toAbsolutePath());
    }
}

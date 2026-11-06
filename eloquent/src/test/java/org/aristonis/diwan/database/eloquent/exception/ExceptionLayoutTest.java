package org.aristonis.diwan.database.eloquent.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aristonis.diwan.database.exception.DatabaseException;
import org.junit.jupiter.api.Test;

/** Every model-layer exception lives in this package, and every runtime failure shares one parent. */
class ExceptionLayoutTest {

    private static final Path MAIN = Paths.get("src/main/java");

    @Test
    void everyExceptionIsInTheExceptionPackage() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> outside = files
                    .filter(p -> p.getFileName().toString().endsWith("Exception.java"))
                    .filter(p -> !p.getParent().endsWith(Paths.get("eloquent", "exception")))
                    .map(Path::toString)
                    .collect(Collectors.toList());
            assertEquals(List.of(), outside);
        }
    }

    @Test
    void runtimeFailuresExtendEloquentException() {
        assertTrue(DatabaseException.class.isAssignableFrom(EloquentException.class));
        for (Class<?> type : List.of(ModelNotFoundException.class, MassAssignmentException.class,
                MultipleRecordsFoundException.class, ClassMorphViolationException.class, JsonException.class,
                LazyLoadingViolationException.class, RelationNotFoundException.class)) {
            assertTrue(EloquentException.class.isAssignableFrom(type), type.getName());
        }
    }

    @Test
    void callerMistakesKeepTheJdkTypes() {
        assertTrue(IllegalArgumentException.class.isAssignableFrom(InvalidCastException.class));
        for (Class<?> type : List.of(InvalidRelationException.class, UnregisteredModelException.class)) {
            assertTrue(IllegalStateException.class.isAssignableFrom(type), type.getName());
        }
    }
}

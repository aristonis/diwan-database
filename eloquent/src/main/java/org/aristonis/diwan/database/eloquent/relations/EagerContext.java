package org.aristonis.diwan.database.eloquent.relations;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;

/**
 * What one relation's eager results hand back to the eager loader besides the models: the eager
 * loads a user constraint set on the eager query with {@code with(...)}.
 *
 * <p>The loader merges them into the next level's nested map, so they run once over all chunks
 * and not inside each chunk's {@code get()}. Only the first capture counts: the constraint runs
 * once for the probe and once per chunk, and each run sets the same loads again, so merging every
 * run would apply a nested constraint once per run and repeat its bindings. Single thread, one
 * instance per {@code eagerResults} call.
 */
final class EagerContext {

    private Map<String, Consumer<EloquentBuilder<?>>> eagerLoads = Collections.emptyMap();

    private boolean isCaptured;

    /**
     * Records the eager loads of the first constraint run; later calls are ignored.
     *
     * @param loads the eager loads the constraint set, never {@code null}, copied
     */
    void capture(Map<String, Consumer<EloquentBuilder<?>>> loads) {
        Objects.requireNonNull(loads, "loads");
        if (isCaptured) {
            return;
        }
        isCaptured = true;
        eagerLoads = Collections.unmodifiableMap(new LinkedHashMap<>(loads));
    }

    /**
     * Returns the captured eager loads.
     *
     * @return the loads in the order the constraint set them, unmodifiable, never {@code null}
     */
    Map<String, Consumer<EloquentBuilder<?>>> eagerLoads() {
        return eagerLoads;
    }
}

package org.aristonis.diwan.database.eloquent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The mass-assignment layer of {@link Model}: {@code fill} sets only the keys the model allows.
 *
 * <p>Rules as Laravel's: a key in {@link #getFillable()} is fillable; a key in
 * {@link #getGuarded()} (compared ignoring case) is not, and {@code ["*"]} guards every key; with
 * an empty fillable list, any other key is fillable unless it holds a dot or starts with an
 * underscore. Laravel also refuses a guarded-list model's keys that are not table columns, which
 * needs a schema lookup; this layer does not, so a model that guards by list should prefer a
 * fillable list.
 */
abstract class ModelGuards extends ModelRelations {

    private static final String EVERY_KEY = "*";

    // Per thread, so one thread's unguarded block never opens another thread's models.
    private static final ThreadLocal<int[]> UNGUARDED_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile boolean preventSilentDiscards;

    ModelGuards() {
    }

    /**
     * Runs a callback with mass assignment unguarded on the calling thread, restoring the guard
     * afterwards even when the callback throws. Calls nest.
     *
     * @param <R> the type of the result
     * @param callback the work to run, never {@code null}
     * @return the callback's result, may be {@code null}
     * @throws NullPointerException if {@code callback} is {@code null}
     */
    public static <R> R unguarded(Supplier<R> callback) {
        Objects.requireNonNull(callback, "callback");
        int[] depth = UNGUARDED_DEPTH.get();
        depth[0]++;
        try {
            return callback.get();
        } finally {
            depth[0]--;
            if (depth[0] == 0) {
                UNGUARDED_DEPTH.remove();
            }
        }
    }

    /**
     * Returns whether mass assignment is unguarded on the calling thread.
     *
     * @return whether an {@link #unguarded(Supplier)} block is running
     */
    public static boolean isUnguarded() {
        return UNGUARDED_DEPTH.get()[0] > 0;
    }

    /**
     * Sets, for every model in the process, whether {@code fill} throws for keys it would
     * otherwise drop silently. Off by default.
     *
     * @apiNote Process-wide, as in Laravel. A single model can opt in by overriding
     *     {@link #preventsSilentlyDiscardingAttributes()}.
     * @param prevent whether to throw
     */
    public static void preventSilentlyDiscardingAttributes(boolean prevent) {
        preventSilentDiscards = prevent;
    }

    /**
     * Returns whether this model's {@code fill} throws for keys it would drop; the process-wide
     * setting by default.
     *
     * @return whether to throw
     */
    protected boolean preventsSilentlyDiscardingAttributes() {
        return preventSilentDiscards;
    }

    /**
     * Sets the fillable attributes from a map; other keys are dropped, or refused when the model
     * is totally guarded or prevents silent discards.
     *
     * @param values the attributes, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code values} is {@code null}
     * @throws MassAssignmentException if the model is totally guarded and a key is not fillable:
     *     {@code "Add [key] to fillable property to allow mass assignment on [Class]."}; or if it
     *     prevents silent discards and keys were dropped: {@code "Add fillable property [a, b] to
     *     allow mass assignment on [Class]."}
     */
    public Model fill(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        boolean totallyGuarded = totallyGuarded();
        boolean prevent = preventsSilentlyDiscardingAttributes();
        Map<String, Object> candidates = fillableFromArray(values);
        for (Map.Entry<String, Object> entry : candidates.entrySet()) {
            String key = entry.getKey();
            if (isFillable(key)) {
                setAttribute(key, entry.getValue());
            } else if (totallyGuarded || prevent) {
                throw new MassAssignmentException(String.format(Locale.ROOT,
                        "Add [%s] to fillable property to allow mass assignment on [%s].", key, getClass().getName()));
            }
        }
        if (candidates.size() != values.size() && prevent) {
            List<String> dropped = new ArrayList<>();
            for (String key : values.keySet()) {
                if (!candidates.containsKey(key)) {
                    dropped.add(key);
                }
            }
            throw new MassAssignmentException(String.format(Locale.ROOT,
                    "Add fillable property [%s] to allow mass assignment on [%s].", String.join(", ", dropped),
                    getClass().getName()));
        }
        return self();
    }

    /**
     * Sets every attribute from a map, ignoring the guards.
     *
     * @param values the attributes, never {@code null}
     * @return this model
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public Model forceFill(Map<String, ?> values) {
        return unguarded(() -> fill(values));
    }

    /**
     * Returns whether mass assignment may set a key.
     *
     * @param key the attribute, never {@code null}
     * @return whether the key is fillable
     */
    public boolean isFillable(String key) {
        if (isUnguarded()) {
            return true;
        }
        List<String> fillable = fillable();
        if (fillable.contains(key)) {
            return true;
        }
        if (isGuarded(key)) {
            return false;
        }
        return fillable.isEmpty() && key.indexOf('.') < 0 && !key.startsWith("_");
    }

    /**
     * Returns whether a key is guarded: every key under {@code ["*"]}, else a key in the guarded
     * list, ignoring case.
     *
     * @param key the attribute, never {@code null}
     * @return whether the key is guarded
     */
    public boolean isGuarded(String key) {
        List<String> guarded = guarded();
        if (guarded.isEmpty()) {
            return false;
        }
        if (guarded.size() == 1 && EVERY_KEY.equals(guarded.get(0))) {
            return true;
        }
        for (String name : guarded) {
            if (name.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the model allows no mass assignment at all: no fillable keys and
     * {@code ["*"]} guarded.
     *
     * @return whether the model is totally guarded
     */
    public boolean totallyGuarded() {
        List<String> guarded = guarded();
        return fillable().isEmpty() && guarded.size() == 1 && EVERY_KEY.equals(guarded.get(0));
    }

    private Map<String, Object> fillableFromArray(Map<String, ?> values) {
        List<String> fillable = fillable();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean keepAll = fillable.isEmpty() || isUnguarded();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (keepAll || fillable.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}

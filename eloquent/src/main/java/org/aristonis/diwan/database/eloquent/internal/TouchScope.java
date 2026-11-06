package org.aristonis.diwan.database.eloquent.internal;

import org.aristonis.diwan.database.Internal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Holds the model classes whose owner touches are switched off on the current thread, the state
 * behind {@code Model.withoutTouching}.
 *
 * <p>The touch runs deep inside {@code save()}, which takes no options, so the scope has to be
 * bound to the running thread rather than passed down. It is held in a plain {@code ThreadLocal}
 * — not an inheritable one — so a thread started inside the work, and every other thread,
 * touches normally.
 *
 * <p>Each scope puts back the list it found when it ends, also after an exception. Laravel
 * instead removes the scope's classes on exit, which drops a class an outer scope listed too; a
 * restored list keeps nested scopes over the same class correct. When the outermost scope ends
 * the entry is removed, so a pooled thread carries nothing into its next task and the
 * {@code ThreadLocal} pins no model class or class loader.
 *
 * <p>Only the relation touch reads this scope. A model's own {@code updated_at} and
 * {@code Model.touch()} are not affected.
 *
 * <p>Internal: not API. Safe to use from any thread; each thread sees only its own scope.
 */
@Internal
public final class TouchScope {

    /** The ignored classes of this thread: an unmodifiable list, or unset when no scope is open. */
    private static final ThreadLocal<List<Class<?>>> IGNORED = new ThreadLocal<>();

    private TouchScope() {
    }

    /**
     * Runs work with owner touches of the given classes and their subclasses switched off on this
     * thread, adding to any scope already open.
     *
     * @param <V> the result type
     * @param classes the classes to ignore, never {@code null} and holding no {@code null}; may be
     *        empty; copied, so a later change to it has no effect
     * @param work the work, never {@code null}
     * @return the value the work returns, may be {@code null}
     * @throws NullPointerException if {@code classes}, an item of it, or {@code work} is
     *         {@code null}; no scope is opened then
     */
    public static <V> V run(Collection<? extends Class<?>> classes, Supplier<V> work) {
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(work, "work");
        for (Class<?> type : classes) {
            Objects.requireNonNull(type, "classes item");
        }
        List<Class<?>> previous = IGNORED.get();
        List<Class<?>> next = previous == null ? new ArrayList<Class<?>>() : new ArrayList<>(previous);
        next.addAll(classes);
        IGNORED.set(Collections.unmodifiableList(next));
        try {
            return work.get();
        } finally {
            // Put back what was there, never "remove these classes": an outer scope over the same
            // class must survive the inner one ending.
            if (previous == null) {
                IGNORED.remove();
            } else {
                IGNORED.set(previous);
            }
        }
    }

    /**
     * Returns whether owner touches of a class are switched off on this thread.
     *
     * @param touched the class of the model that would be touched, never {@code null}
     * @return whether a listed class is that class or a superclass of it
     * @throws NullPointerException if {@code touched} is {@code null}
     */
    public static boolean isIgnoring(Class<?> touched) {
        Objects.requireNonNull(touched, "touched");
        List<Class<?>> ignored = IGNORED.get();
        if (ignored == null) {
            return false;
        }
        for (Class<?> type : ignored) {
            if (type.isAssignableFrom(touched)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a scope is open on this thread, even one listing no class.
     *
     * @return whether {@link #run(Collection, Supplier)} is running on this thread
     */
    public static boolean isActive() {
        return IGNORED.get() != null;
    }
}

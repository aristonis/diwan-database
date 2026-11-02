package org.aristonis.diwan.database.eloquent.internal;

import java.util.Objects;

/**
 * Holds the two internal bridges, each set once from a static initialiser.
 *
 * <p>{@code ModelQuery} registers the {@link EloquentAccess} implementation when it is
 * initialised, and {@code Relation} registers the {@link RelationAccess} one. Code in the model
 * package calls {@code Relation.ensureInitialised()} before its first {@link #relations()} call,
 * because a class literal alone does not run a static initialiser.
 *
 * <p>Internal: not API. Safe to use from any thread: each holder publishes its value through a
 * {@code volatile} field and refuses a second value.
 */
public final class Access {

    private static final Holder<EloquentAccess> ELOQUENT = new Holder<>("eloquent");
    private static final Holder<RelationAccess> RELATIONS = new Holder<>("relations");

    private Access() {
    }

    /**
     * Registers the model package's bridge.
     *
     * @param access the bridge, never {@code null}
     * @throws NullPointerException if {@code access} is {@code null}
     * @throws IllegalStateException if a bridge is already registered
     */
    public static void setEloquent(EloquentAccess access) {
        ELOQUENT.set(access);
    }

    /**
     * Returns the model package's bridge.
     *
     * @return the bridge, never {@code null}
     * @throws IllegalStateException if none is registered yet
     */
    public static EloquentAccess eloquent() {
        return ELOQUENT.get();
    }

    /**
     * Registers the relation package's bridge.
     *
     * @param access the bridge, never {@code null}
     * @throws NullPointerException if {@code access} is {@code null}
     * @throws IllegalStateException if a bridge is already registered
     */
    public static void setRelations(RelationAccess access) {
        RELATIONS.set(access);
    }

    /**
     * Returns the relation package's bridge.
     *
     * @return the bridge, never {@code null}
     * @throws IllegalStateException if none is registered yet
     */
    public static RelationAccess relations() {
        return RELATIONS.get();
    }

    /**
     * A value set at most once and read without a lock.
     *
     * @param <T> the value type
     */
    static final class Holder<T> {

        private final String name;
        private volatile T value;

        Holder(String name) {
            this.name = name;
        }

        synchronized void set(T newValue) {
            Objects.requireNonNull(newValue, "access");
            if (value != null) {
                throw new IllegalStateException("Internal access [" + name + "] is already set");
            }
            value = newValue;
        }

        T get() {
            T current = value;
            if (current == null) {
                throw new IllegalStateException("Internal access [" + name + "] is not set; the class that"
                        + " registers it has not been initialised");
            }
            return current;
        }

        boolean isSet() {
            return value != null;
        }
    }
}

package org.aristonis.diwan.database.schema;

import java.util.Objects;

/**
 * How a text column compares and sorts its values. It comes in two kinds: {@link #BINARY}, a
 * portable byte-order collation each grammar maps to its own name, and a named collation made by
 * {@link #of(String)}, written to the database as given.
 *
 * <p>The two kinds never equal each other: {@code Collation.of("C")} is not {@code BINARY}, even
 * where the database writes BINARY as {@code C}.
 */
public final class Collation {

    /** The portable byte-order collation; each grammar maps it to the database's own name. */
    public static final Collation BINARY = new Collation("binary", true);

    private final String name;
    private final boolean isPortable;

    private Collation(String name, boolean isPortable) {
        this.name = name;
        this.isPortable = isPortable;
    }

    /**
     * Makes a collation the database knows by name.
     *
     * @param name the database's collation name, never {@code null}
     * @return the collation, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty, or has a quote or control character
     */
    public static Collation of(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A collation name cannot be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\'' || c == '"' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("A collation name cannot hold a quote or control character");
            }
        }
        return new Collation(name, false);
    }

    /**
     * Returns the name: the database's name for a named collation, and the portable id
     * {@code binary} for {@link #BINARY}, which is not a database name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Tells whether this is a portable collation that each grammar maps to its own name.
     *
     * @return {@code true} for {@link #BINARY}
     */
    public boolean isPortable() {
        return isPortable;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Collation)) {
            return false;
        }
        Collation that = (Collation) other;
        return isPortable == that.isPortable && name.equals(that.name);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(name, isPortable);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return isPortable ? "Collation[portable " + name + "]" : "Collation[" + name + "]";
    }
}

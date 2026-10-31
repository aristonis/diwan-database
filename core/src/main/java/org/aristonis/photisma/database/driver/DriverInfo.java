package org.aristonis.photisma.database.driver;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Describes the database behind a {@link Driver}: its name, version and capabilities.
 *
 * <p>Instances are immutable, have value semantics and are safe to share between threads.
 */
public final class DriverInfo {

    private final String databaseName;
    private final String databaseVersion;
    private final Set<DriverCapability> capabilities;

    /**
     * Creates a description of a database.
     *
     * @param databaseName the database product name, never {@code null}
     * @param databaseVersion the database product version, never {@code null}
     * @param capabilities the supported capabilities, never {@code null}, may be empty; the set is
     *     copied and later changes to it have no effect
     * @throws NullPointerException if any argument or any capability is {@code null}
     */
    public DriverInfo(String databaseName, String databaseVersion, Set<DriverCapability> capabilities) {
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        this.databaseVersion = Objects.requireNonNull(databaseVersion, "databaseVersion");
        Objects.requireNonNull(capabilities, "capabilities");
        // EnumSet.copyOf(Collection) throws on an empty set that is not an EnumSet.
        Set<DriverCapability> copy = EnumSet.noneOf(DriverCapability.class);
        for (DriverCapability capability : capabilities) {
            copy.add(Objects.requireNonNull(capability, "capability"));
        }
        this.capabilities = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns the database product name.
     *
     * @return the name, never {@code null}
     */
    public String databaseName() {
        return databaseName;
    }

    /**
     * Returns the database product version.
     *
     * @return the version, never {@code null}
     */
    public String databaseVersion() {
        return databaseVersion;
    }

    /**
     * Returns the supported capabilities.
     *
     * @return an unmodifiable set of capabilities in declaration order, never {@code null}
     */
    public Set<DriverCapability> capabilities() {
        return capabilities;
    }

    /**
     * Tells whether the database supports a capability.
     *
     * @param capability the capability to check, never {@code null}
     * @return {@code true} if the capability is supported
     * @throws NullPointerException if {@code capability} is {@code null}
     */
    public boolean supports(DriverCapability capability) {
        return capabilities.contains(Objects.requireNonNull(capability, "capability"));
    }

    /**
     * Compares this description with another object.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is a {@code DriverInfo} with equal name, version and
     *     capabilities
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DriverInfo)) {
            return false;
        }
        DriverInfo that = (DriverInfo) other;
        return databaseName.equals(that.databaseName)
                && databaseVersion.equals(that.databaseVersion)
                && capabilities.equals(that.capabilities);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(databaseName, databaseVersion, capabilities);
    }

    /**
     * Returns a readable description of the database.
     *
     * <p>The form is {@code DriverInfo[<name> <version>, capabilities=<set>]}, for example
     * {@code DriverInfo[SQLite 3.54, capabilities=[]]}, with capabilities in declaration order.
     *
     * @return the description, never {@code null}
     */
    @Override
    public String toString() {
        return "DriverInfo[" + databaseName + " " + databaseVersion
                + ", capabilities=" + capabilities + "]";
    }
}

package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.migrations.internal.MigrationNames;
import java.util.Objects;
import java.util.Optional;

/**
 * One row of the migrations table: a migration that ran, the batch it ran in and the checksum
 * recorded for it, if any.
 *
 * <p>Instances are immutable, have value semantics and are safe to share between threads.
 */
public final class MigrationEntry {

    private final String name;
    private final int batch;
    private final String checksum;

    /**
     * Creates an entry without a checksum.
     *
     * @param name the migration name, never {@code null}; non-blank, at most 191 characters,
     *     without control characters
     * @param batch the batch number, at least 1
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code batch} is below 1
     */
    public MigrationEntry(String name, int batch) {
        this(name, batch, Optional.empty());
    }

    /**
     * Creates an entry.
     *
     * @param name the migration name, never {@code null}; non-blank, at most 191 characters,
     *     without control characters
     * @param batch the batch number, at least 1
     * @param checksum the checksum recorded for the migration, never {@code null}; empty when none
     *     was recorded
     * @throws NullPointerException if {@code name} or {@code checksum} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code batch} is below 1
     */
    public MigrationEntry(String name, int batch, Optional<String> checksum) {
        this.name = MigrationNames.checkName(name);
        if (batch < 1) {
            throw new IllegalArgumentException("Batch must be at least 1");
        }
        this.batch = batch;
        this.checksum = Objects.requireNonNull(checksum, "checksum").orElse(null);
    }

    /**
     * Returns the migration name.
     *
     * @return the name, never {@code null}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the batch the migration ran in.
     *
     * @return the batch number, at least 1
     */
    public int batch() {
        return batch;
    }

    /**
     * Returns the checksum recorded when the migration ran.
     *
     * @return the checksum, empty when the migration declared none
     */
    public Optional<String> checksum() {
        return Optional.ofNullable(checksum);
    }

    /**
     * Compares this entry with another object.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is an entry with the same name, batch and checksum
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationEntry)) {
            return false;
        }
        MigrationEntry that = (MigrationEntry) other;
        return batch == that.batch && name.equals(that.name) && Objects.equals(checksum, that.checksum);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * (31 * name.hashCode() + batch) + Objects.hashCode(checksum);
    }

    /**
     * Describes the entry for logs and test output.
     *
     * @return the name and batch, never {@code null}
     */
    @Override
    public String toString() {
        return "MigrationEntry[" + name + ", batch " + batch + "]";
    }
}

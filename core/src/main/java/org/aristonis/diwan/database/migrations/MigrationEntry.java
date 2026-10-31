package org.aristonis.diwan.database.migrations;

/**
 * One row of the migrations table: a migration that ran and the batch it ran in.
 *
 * <p>Instances are immutable, have value semantics and are safe to share between threads.
 */
public final class MigrationEntry {

    private final String name;
    private final int batch;

    /**
     * Creates an entry.
     *
     * @param name the migration name, never {@code null}; non-blank, at most 191 characters,
     *     without control characters
     * @param batch the batch number, at least 1
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code batch} is below 1
     */
    public MigrationEntry(String name, int batch) {
        this.name = MigrationNames.checkName(name);
        if (batch < 1) {
            throw new IllegalArgumentException("Batch must be at least 1");
        }
        this.batch = batch;
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
     * Compares this entry with another object.
     *
     * @param other the object to compare with, may be {@code null}
     * @return {@code true} if {@code other} is an entry with the same name and batch
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
        return batch == that.batch && name.equals(that.name);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * name.hashCode() + batch;
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

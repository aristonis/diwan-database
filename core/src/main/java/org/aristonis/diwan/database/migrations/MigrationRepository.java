package org.aristonis.diwan.database.migrations;

import org.aristonis.diwan.database.Internal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records which migrations ran and in which batch.
 *
 * <p>A batch groups the migrations of one run, so a rollback can undo the last run as a unit.
 * Batch numbers start at 1.
 *
 * <p>Methods added to this interface after version 0.1 are default methods, so an implementation
 * written against an earlier version keeps compiling.
 *
 * @implNote Order among entries of the same batch follows the database's collation, so names that
 *     differ only in letter case may sort differently on different databases.
 * @apiNote Internal: not an extension point; {@link Migrator} works with
 *     {@link DatabaseMigrationRepository}; may change without notice.
 */
@Internal
public interface MigrationRepository {

    /**
     * Returns the names of every migration that ran.
     *
     * @return an unmodifiable list ordered by batch, then name, ascending; never {@code null}
     */
    List<String> getRan();

    /**
     * Returns the most recent entries, for a rollback by steps.
     *
     * @param steps the most entries to return, at least 1
     * @return an unmodifiable list ordered by batch, then name, descending; never {@code null}
     * @throws IllegalArgumentException if {@code steps} is below 1
     */
    List<MigrationEntry> getMigrations(int steps);

    /**
     * Returns the entries of one batch.
     *
     * @param batch the batch number
     * @return an unmodifiable list ordered by name, descending; never {@code null}, empty when the
     *     batch has no entries
     */
    List<MigrationEntry> getMigrationsByBatch(int batch);

    /**
     * Returns the entries of the highest batch.
     *
     * @return an unmodifiable list ordered by name, descending; never {@code null}, empty when no
     *     migration ran
     */
    List<MigrationEntry> getLast();

    /**
     * Returns the batch of every migration that ran.
     *
     * @return an unmodifiable map from name to batch, iterated in {@link #getRan()} order; never
     *     {@code null}
     */
    Map<String, Integer> getMigrationBatches();

    /**
     * Records that a migration ran.
     *
     * @param name the migration name, never {@code null}; non-blank, at most 191 characters, without
     *     control characters
     * @param batch the batch number, at least 1
     * @param checksum the checksum the migration declared, or {@code null} to record none, which
     *     the migrator never checks
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code batch} is below 1
     * @throws org.aristonis.diwan.database.exception.UniqueConstraintViolationException if the name is
     *     already recorded
     */
    void log(String name, int batch, String checksum);

    /**
     * Returns the checksum recorded for every migration that ran.
     *
     * @implSpec The default throws {@link UnsupportedOperationException}, so implementations
     *     written before checksums existed keep compiling.
     *
     * @return an unmodifiable map from name to checksum, iterated in {@link #getRan()} order; the
     *     value is empty for a migration that has none; never {@code null}
     * @throws UnsupportedOperationException if the implementation does not keep checksums
     */
    default Map<String, Optional<String>> checksums() {
        throw new UnsupportedOperationException("This repository does not keep checksums");
    }

    /**
     * Replaces the checksum recorded for a migration.
     *
     * @implSpec The default throws {@link UnsupportedOperationException}.
     *
     * @param name the migration name, never {@code null}; a name that is not recorded changes
     *     nothing
     * @param checksum the new checksum, never {@code null}; empty to clear it
     * @throws NullPointerException if an argument is {@code null}
     * @throws UnsupportedOperationException if the implementation does not keep checksums
     */
    default void setChecksum(String name, Optional<String> checksum) {
        throw new UnsupportedOperationException("This repository does not keep checksums");
    }

    /**
     * Removes the record of a migration.
     *
     * @param name the migration name, never {@code null}; a name that is not recorded changes
     *     nothing
     * @throws NullPointerException if {@code name} is {@code null}
     */
    void delete(String name);

    /**
     * Returns the highest batch number recorded.
     *
     * @return the batch number, 0 when no migration ran
     */
    int getLastBatchNumber();

    /**
     * Returns the batch number for the next run.
     *
     * @return {@link #getLastBatchNumber()} plus one
     */
    int getNextBatchNumber();

    /**
     * Creates the migrations table.
     *
     * <p>Call it outside a transaction: MySQL commits any open transaction when it creates a
     * table.
     */
    void createRepository();

    /**
     * Tells whether the migrations table exists.
     *
     * @return {@code true} if the table exists
     */
    boolean repositoryExists();

    /**
     * Drops the migrations table.
     *
     * <p>Call it outside a transaction: MySQL commits any open transaction when it drops a table.
     */
    void deleteRepository();
}

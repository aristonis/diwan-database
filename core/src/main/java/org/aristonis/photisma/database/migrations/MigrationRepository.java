package org.aristonis.photisma.database.migrations;

import java.util.List;
import java.util.Map;

/**
 * Records which migrations ran and in which batch.
 *
 * <p>A batch groups the migrations of one run, so a rollback can undo the last run as a unit.
 * Batch numbers start at 1.
 *
 * <p>Methods added to this interface after version 0.1 are default methods, so implementations
 * outside this library keep compiling.
 *
 * @implNote Order among entries of the same batch follows the database's collation, so names that
 *     differ only in letter case may sort differently on different databases.
 */
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
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is invalid or {@code batch} is below 1
     * @throws org.aristonis.photisma.database.UniqueConstraintViolationException if the name is
     *     already recorded
     */
    void log(String name, int batch);

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

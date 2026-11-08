package org.aristonis.diwan.database.testing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * Assertions on the rows of a database, in the style of Laravel's {@code assertDatabaseHas}.
 *
 * <p>The class has no JUnit dependency: a failed assertion throws {@link AssertionError}, which
 * every test framework reports as a failure. Failure messages name the table and the compared
 * columns and list up to three rows of the table, cut down to those columns and the {@code id}
 * column; a column the test did not ask about is never printed.
 *
 * <p>The model assertions take a {@link Model} from the eloquent module. That module is a
 * compile-time dependency of this one only: an application that calls just the table assertions
 * needs no eloquent on its class path, because the JVM links the model code when a model assertion
 * is first called, not when this class loads. Calling a model assertion without eloquent fails with
 * {@link NoClassDefFoundError}. The model assertions never print the value of a column the model
 * hides through {@link Model#getHidden()}.
 *
 * <p>Instances are immutable and safe to share between threads, as far as the query factory is.
 */
public final class DatabaseAssertions {

    private final QueryFactory db;

    private DatabaseAssertions(QueryFactory db) {
        this.db = db;
    }

    /**
     * Creates assertions that read through a query factory.
     *
     * @param db the query factory, never {@code null}
     * @return the assertions, never {@code null}
     * @throws NullPointerException if {@code db} is {@code null}
     */
    public static DatabaseAssertions of(QueryFactory db) {
        return new DatabaseAssertions(Objects.requireNonNull(db, "db"));
    }

    /**
     * Returns the query factory the assertions read through.
     *
     * @return the query factory, never {@code null}
     */
    QueryFactory db() {
        return db;
    }

    /**
     * Asserts that a row with the given column values exists.
     *
     * @param table the table name, never {@code null} or blank
     * @param values the column values the row must have, never {@code null} or empty; a {@code null}
     *     value matches a {@code null} column
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the table name is blank or {@code values} is empty
     * @throws AssertionError if no row matches; the message lists up to three rows of the table
     */
    public void assertHas(String table, Map<String, ?> values) {
        Map<String, Object> wanted = check(table, values);
        if (AssertionMessages.matching(db, table, wanted).exists()) {
            return;
        }
        throw new AssertionError("No row in [" + table + "] matches " + AssertionMessages.describe(wanted) + ". "
                + AssertionMessages.similarRows(db, table, wanted.keySet()));
    }

    /**
     * Asserts that no row has the given column values.
     *
     * @param table the table name, never {@code null} or blank
     * @param values the column values no row may have, never {@code null} or empty; a {@code null}
     *     value matches a {@code null} column
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the table name is blank or {@code values} is empty
     * @throws AssertionError if a row matches; the message shows up to three matching rows
     */
    public void assertMissing(String table, Map<String, ?> values) {
        Map<String, Object> unwanted = check(table, values);
        long found = AssertionMessages.matching(db, table, unwanted).count();
        if (found == 0) {
            return;
        }
        List<Map<String, Object>> rows = AssertionMessages.matching(db, table, unwanted)
                .limit(AssertionMessages.SAMPLE_ROWS).get();
        throw new AssertionError("Found " + found + " row(s) in [" + table + "] that match "
                + AssertionMessages.describe(unwanted) + ", expected none. Matching rows (first " + rows.size()
                + " of " + found + "): " + AssertionMessages.describeRows(rows, unwanted.keySet()));
    }

    /**
     * Asserts the number of rows in a table.
     *
     * @param table the table name, never {@code null} or blank
     * @param expected the expected number of rows
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the table name is blank
     * @throws AssertionError if the table has another number of rows
     */
    public void assertCount(String table, long expected) {
        checkTable(table);
        long actual = db.table(table).count();
        if (actual != expected) {
            throw new AssertionError("Expected [" + table + "] to have " + expected + " row(s), found " + actual + ".");
        }
    }

    /**
     * Asserts that a table has no rows.
     *
     * @param table the table name, never {@code null} or blank
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the table name is blank
     * @throws AssertionError if the table has rows
     */
    public void assertEmpty(String table) {
        assertCount(table, 0);
    }

    /**
     * Asserts that the model's row exists, found by its table and primary key.
     *
     * @param model the model, never {@code null}, with a primary key column and a key value
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalArgumentException if the model has no primary key column
     * @throws AssertionError if the model has no key value or no row has it; the message names the
     *     table, key column and key value
     */
    public void assertModelExists(Model model) {
        ModelAssertions.exists(this, Objects.requireNonNull(model, "model"));
    }

    /**
     * Asserts that the model's row does not exist, found by its table and primary key.
     *
     * @param model the model, never {@code null}, with a primary key column
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalArgumentException if the model has no primary key column
     * @throws AssertionError if a row has the model's key; a model with no key value passes
     */
    public void assertModelMissing(Model model) {
        ModelAssertions.missing(this, Objects.requireNonNull(model, "model"));
    }

    /**
     * Asserts that the model's row exists and has its deleted-at column set.
     *
     * @param model the model, never {@code null}, of a class that soft-deletes
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalArgumentException if the model has no primary key column
     * @throws AssertionError if the model does not soft-delete, has no key value, has no row, or its
     *     row is not soft deleted
     */
    public void assertSoftDeleted(Model model) {
        ModelAssertions.softDeleted(this, Objects.requireNonNull(model, "model"), true);
    }

    /**
     * Asserts that the model's row exists and has its deleted-at column empty.
     *
     * @param model the model, never {@code null}, of a class that soft-deletes
     * @throws NullPointerException if {@code model} is {@code null}
     * @throws IllegalArgumentException if the model has no primary key column
     * @throws AssertionError if the model does not soft-delete, has no key value, has no row, or its
     *     row is soft deleted
     */
    public void assertNotSoftDeleted(Model model) {
        ModelAssertions.softDeleted(this, Objects.requireNonNull(model, "model"), false);
    }

    private static Map<String, Object> check(String table, Map<String, ?> values) {
        checkTable(table);
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("The column values to compare are empty");
        }
        return new LinkedHashMap<String, Object>(values);
    }

    private static void checkTable(String table) {
        if (Objects.requireNonNull(table, "table").trim().isEmpty()) {
            throw new IllegalArgumentException("The table name is blank");
        }
    }
}

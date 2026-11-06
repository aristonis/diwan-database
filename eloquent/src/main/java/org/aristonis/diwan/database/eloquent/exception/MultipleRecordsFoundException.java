package org.aristonis.diwan.database.eloquent.exception;

import org.aristonis.diwan.database.eloquent.EloquentBuilder;

/**
 * Thrown by {@link EloquentBuilder#sole()} when more than one row matches, with Laravel's message
 * {@code "2 records were found."}.
 */
public final class MultipleRecordsFoundException extends EloquentException {

    private static final long serialVersionUID = 1L;

    /** The number of rows found. */
    private final int count;

    /**
     * Creates the exception for the number of rows found.
     *
     * @param count the number of rows found; {@code sole} reads at most two
     */
    public MultipleRecordsFoundException(int count) {
        super(count + " records were found.");
        this.count = count;
    }

    /**
     * Returns the number of rows found.
     *
     * @return the count
     */
    public int getCount() {
        return count;
    }
}

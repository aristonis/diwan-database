package org.aristonis.diwan.database.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refuses a rollback or reset that would reach records whose migrations the migrator was not given.
 *
 * <p>This usually means older code is rolling back a newer database. Undoing the migrations it does
 * know while leaving the others in place can drop tables that newer migrations still depend on, so
 * the migrator refuses before it undoes anything. Deploy the code that has these migrations, or
 * build the migrator with {@link Migrator.Builder#allowMissingOnRollback()} to undo the known ones
 * and keep the unknown records, as Laravel does.
 */
public final class MissingMigrationsException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Longest part of a name shown in the message. */
    private static final int MAX_SHOWN = 191;

    /** The names of the records without a migration; an {@code ArrayList}, never changed. */
    private final ArrayList<String> names;

    MissingMigrationsException(List<String> names) {
        super(names.size() + " recorded migration(s) are unknown to this migrator, for example "
                + printable(names.get(0)) + "; nothing was rolled back");
        this.names = new ArrayList<>(names);
    }

    // The name is read from the migrations table, so it is clipped and stripped of control
    // characters before it reaches a log line.
    private static String printable(String name) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < name.length() && i < MAX_SHOWN; i++) {
            char c = name.charAt(i);
            text.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? '?' : c);
        }
        return name.length() > MAX_SHOWN ? text + "..." : text.toString();
    }

    /**
     * Returns the names of the records without a migration, newest first.
     *
     * @return an unmodifiable list, never {@code null} or empty
     */
    public List<String> names() {
        return Collections.unmodifiableList(names);
    }
}

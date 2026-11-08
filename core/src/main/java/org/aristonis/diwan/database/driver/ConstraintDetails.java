package org.aristonis.diwan.database.driver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.Internal;

/**
 * Reads the constraint name and column names of a constraint failure.
 *
 * <p>PostgreSQL details come from the driver's structured server error, read by reflection so the
 * core does not depend on the PostgreSQL driver; the free-text DETAIL is never parsed. SQLite
 * details come from the fixed wording of its error text. Names are schema names, never values; a
 * name longer than {@value #MAX_NAME_LENGTH} characters or holding a control character is dropped.
 * For an unnamed SQLite CHECK the key is the check's expression text, still schema and never a value.
 * SQLite column lists are split on {@code ", "} and the first dot, so a name holding either is read
 * wrongly; such names are rare and the result is at worst a wrong or missing column name.
 *
 * @apiNote Internal: for drivers in this library.
 */
@Internal
public final class ConstraintDetails {

    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern SQLITE_COLUMNS = Pattern.compile(
            "(?:UNIQUE|NOT NULL) constraint failed: ([^()]*?)(?: \\(code .*)?\\)?\\s*$");
    private static final Pattern SQLITE_CHECK = Pattern.compile(
            "CHECK constraint failed: (.*?)(?: \\(code .*|\\)\\s*$|\\s*$)");

    private final String key;
    private final List<String> columns;

    private ConstraintDetails(String key, List<String> columns) {
        this.key = key;
        this.columns = columns;
    }

    private static ConstraintDetails none() {
        return new ConstraintDetails(null, Collections.<String>emptyList());
    }

    /**
     * Returns the constraint name.
     *
     * @return the name, or an empty optional when there is none
     */
    public Optional<String> key() {
        return Optional.ofNullable(key);
    }

    /**
     * Returns the column names.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<String> columns() {
        return columns;
    }

    /**
     * Reads the details of a PostgreSQL failure through the driver's structured server error.
     *
     * @param sqlException the SQL exception from the PostgreSQL driver, may be {@code null}
     * @param isUnique {@code true} for a unique violation, which reports the key only
     * @return the details; empty when the exception has no server error or reflection fails, never
     *     {@code null}, never throws
     */
    public static ConstraintDetails postgres(Throwable sqlException, boolean isUnique) {
        try {
            if (sqlException == null) {
                return none();
            }
            Object server = call(sqlException, "getServerErrorMessage");
            if (server == null) {
                return none();
            }
            String key = clean(call(server, "getConstraint"));
            if (isUnique) {
                return new ConstraintDetails(key, Collections.<String>emptyList());
            }
            String column = clean(call(server, "getColumn"));
            return new ConstraintDetails(key,
                    column == null ? Collections.<String>emptyList() : Collections.singletonList(column));
        } catch (RuntimeException | LinkageError e) {
            return none();
        }
    }

    /**
     * Reads the details of a PostgreSQL failure, treating it as a non-unique constraint.
     *
     * @param sqlException the SQL exception from the PostgreSQL driver, may be {@code null}
     * @return the details with the key and the column; empty on any failure, never {@code null}
     */
    public static ConstraintDetails postgres(Throwable sqlException) {
        return postgres(sqlException, false);
    }

    /**
     * Reads the details from SQLite error text.
     *
     * <p>{@code UNIQUE} and {@code NOT NULL} failures give the columns after the table dot, a
     * {@code CHECK} failure gives the constraint name as the key, a foreign key failure gives
     * nothing.
     *
     * @param message the error text, may be {@code null}
     * @return the details, never {@code null}
     */
    public static ConstraintDetails sqlite(String message) {
        if (message == null) {
            return none();
        }
        Matcher columns = SQLITE_COLUMNS.matcher(message);
        if (columns.find()) {
            return new ConstraintDetails(null, columnNames(columns.group(1)));
        }
        Matcher check = SQLITE_CHECK.matcher(message);
        if (check.find()) {
            return new ConstraintDetails(clean(check.group(1)), Collections.<String>emptyList());
        }
        return none();
    }

    private static List<String> columnNames(String list) {
        List<String> names = new ArrayList<>();
        for (String part : list.split(",")) {
            String trimmed = part.trim();
            int dot = trimmed.indexOf('.');
            String name = dot < 0 ? null : clean(trimmed.substring(dot + 1));
            if (name != null) {
                names.add(name);
            }
        }
        return Collections.unmodifiableList(names);
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String clean(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String name = (String) value;
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < ' ' || c == '\u007f' || c == '\u0085' || c == ' ' || c == ' ') {
                return null;
            }
        }
        return name;
    }
}

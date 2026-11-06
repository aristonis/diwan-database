package org.aristonis.diwan.database.migrations.internal;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.migrations.Migration;
import java.util.Objects;

/** Checks migration names and migrations-table names before they reach the database. */
@Internal
public final class MigrationNames {

    /** Longest migration name; matches the {@code varchar(191)} column, which old MySQL can index. */
    @Internal
    public static final int MAX_NAME_LENGTH = 191;

    /** Longest table name; PostgreSQL cuts longer names to 63, so the existence check would miss them. */
    static final int MAX_TABLE_LENGTH = 63;

    private MigrationNames() {
    }

    /**
     * Checks a migration name.
     *
     * @param name the name, may be {@code null}
     * @return the same name
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is blank, too long or has a control character
     */
    @Internal
    public static String checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Migration name is blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Migration name is longer than " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("Migration name contains a control character");
            }
        }
        return name;
    }

    /**
     * Checks a full table name, prefix included.
     *
     * <p>Only lowercase letters, digits and underscores are allowed, with no leading digit. The name
     * goes into SQL text unquoted, so this check is what keeps it from carrying SQL; lowercase also
     * matches how PostgreSQL stores unquoted names, which the existence check relies on.
     *
     * @param table the table name, may be {@code null}
     * @return the same name
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws IllegalArgumentException if the name breaks the rule above or is longer than
     *     {@value #MAX_TABLE_LENGTH} characters
     */
    @Internal
    public static String checkTable(String table) {
        Objects.requireNonNull(table, "table");
        if (table.isEmpty() || table.length() > MAX_TABLE_LENGTH || !isPlainIdentifier(table)) {
            // The name is not echoed: it may come from configuration a caller does not control.
            throw new IllegalArgumentException("Migrations table name must match [a-z_][a-z0-9_]* and be at most "
                    + MAX_TABLE_LENGTH + " characters, prefix included");
        }
        return table;
    }

    private static boolean isPlainIdentifier(String table) {
        for (int i = 0; i < table.length(); i++) {
            char c = table.charAt(i);
            boolean isAllowed = (c >= 'a' && c <= 'z') || c == '_' || (i > 0 && c >= '0' && c <= '9');
            if (!isAllowed) {
                return false;
            }
        }
        return true;
    }
}

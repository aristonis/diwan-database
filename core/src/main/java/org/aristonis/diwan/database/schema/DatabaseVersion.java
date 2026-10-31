package org.aristonis.diwan.database.schema;

import java.util.Objects;

/**
 * Compares a database's version text with a minimum, for grammars whose SQL depends on the
 * version.
 *
 * <p>The text is split at dots; each part counts by its leading digits, so {@code "18.3 (Debian)"}
 * reads as 18.3, and a part without digits counts as 0. Missing parts count as 0.
 */
public final class DatabaseVersion {

    private DatabaseVersion() {
    }

    /**
     * Tells whether a version is at least a minimum.
     *
     * @param version the version text the database reports, never {@code null}
     * @param minimum the minimum, most significant part first, such as {@code 3, 35, 0}
     * @return {@code true} if {@code version} is equal to or later than {@code minimum}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static boolean atLeast(String version, int... minimum) {
        Objects.requireNonNull(minimum, "minimum");
        String[] parts = Objects.requireNonNull(version, "version").trim().split("\\.");
        for (int i = 0; i < minimum.length; i++) {
            int part = i < parts.length ? leadingNumber(parts[i]) : 0;
            if (part != minimum[i]) {
                return part > minimum[i];
            }
        }
        return true;
    }

    private static int leadingNumber(String text) {
        int end = 0;
        String trimmed = text.trim();
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            // More digits than an int holds: later than any real minimum.
            return Integer.MAX_VALUE;
        }
    }
}

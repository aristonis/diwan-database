package org.aristonis.diwan.database.sqlite;

import org.aristonis.diwan.database.Internal;

/**
 * Compares SQLite version strings such as {@code 3.45.1}, for the checks that depend on what the
 * running SQLite can do (the {@code returning} clause from 3.35, {@code on conflict do update}
 * from 3.24).
 *
 * <p>Each part is compared as a whole number, so {@code 3.9.9} is older than {@code 3.35.0}. A
 * missing minor or patch part counts as 0, and trailing text after a number, as in
 * {@code 3.45.1-rc1}, is ignored.
 *
 * @apiNote Internal: public only so the Android module can reach it; may change without notice.
 */
@Internal
public final class SqliteVersion {

    private static final int PARTS = 3;

    private SqliteVersion() {
    }

    /**
     * Returns whether a version string can be compared at all.
     *
     * @param version the version the database reports, may be {@code null}
     * @return {@code true} if the major part, and any minor or patch part present, starts with a
     *     digit; {@code false} for {@code null}, an empty string or text such as {@code junk}
     */
    public static boolean isReadable(String version) {
        return parse(version) != null;
    }

    /**
     * Returns whether a version is at least the given one. A version that cannot be read counts
     * as older, so a feature check fails closed.
     *
     * @param version the version the database reports, may be {@code null}
     * @param major the major part of the least version
     * @param minor the minor part of the least version
     * @param patch the patch part of the least version
     * @return {@code true} if {@code version} is the least version or newer; {@code false} if it
     *     is older or cannot be read
     */
    public static boolean atLeast(String version, int major, int minor, int patch) {
        int[] least = {major, minor, patch};
        int[] actual = parse(version);
        if (actual == null) {
            return false;
        }
        for (int i = 0; i < PARTS; i++) {
            if (actual[i] != least[i]) {
                return actual[i] > least[i];
            }
        }
        return true;
    }

    // Returns null when the major or a present minor or patch part has no leading digits.
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.");
        int[] numbers = new int[PARTS];
        for (int i = 0; i < PARTS && i < parts.length; i++) {
            int end = 0;
            while (end < parts[i].length() && parts[i].charAt(end) >= '0' && parts[i].charAt(end) <= '9') {
                end++;
            }
            if (end == 0 || end > 9) {
                return null;
            }
            numbers[i] = Integer.parseInt(parts[i].substring(0, end));
        }
        return numbers;
    }
}

package org.aristonis.diwan.database.testing;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationChecksum;

/**
 * Names the databases of template mode after the migration list they were built from.
 *
 * <p>The same list, in the same order and with the same checksums, always gives the same
 * {@link #hash8() hash}, so a template built by an earlier run is found again; any change to the
 * list gives another hash and so another template. Every name this class returns holds only
 * {@code [a-z0-9_]} and is shorter than {@value #MAX_LENGTH} characters, PostgreSQL's identifier
 * limit, so it needs no escaping beyond the quoting the grammar applies anyway.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
final class TemplateName {

    /** Names must stay under this many characters. */
    static final int MAX_LENGTH = 62;

    private static final int RUN_ID_LENGTH = 6;
    private static final int HASH_LENGTH = 8;
    private static final int BASE_36 = 36;
    private static final int BASE_16 = 16;
    private static final Pattern SAFE = Pattern.compile("[a-z0-9_]+");
    private static final Pattern RUN_ID_FORM = Pattern.compile("[0-9a-z]{" + RUN_ID_LENGTH + "}");
    private static final Pattern COPY = Pattern.compile(
            "diwan_t_[0-9a-f]{" + HASH_LENGTH + "}_([0-9a-z]{" + RUN_ID_LENGTH + "})_[0-9]+");
    private static final Pattern BUILDING = Pattern.compile(
            "diwan_tpl_[0-9a-f]{" + HASH_LENGTH + "}_b_([0-9a-z]{" + RUN_ID_LENGTH + "})");

    /** One id per JVM, so two runs sharing a server never name the same copy. */
    private static final String RUN_ID = newRunId();

    private final String hash8;

    private TemplateName(String hash8) {
        this.hash8 = hash8;
    }

    /**
     * Names the databases of a migration list.
     *
     * @param migrations the migrations, in run order, never {@code null}, without {@code null}
     *     elements
     * @return the name set, never {@code null}
     * @throws NullPointerException if the list or an element is {@code null}
     */
    static TemplateName of(List<Migration> migrations) {
        String text = signature(migrations);
        return new TemplateName(MigrationChecksum.of(text).substring(0, HASH_LENGTH));
    }

    /**
     * Describes a migration list as text: class, name and checksum of each migration, in order.
     *
     * @param migrations the migrations, never {@code null}, without {@code null} elements
     * @return the text, never {@code null}; equal lists give equal text
     * @throws NullPointerException if the list or an element is {@code null}
     */
    static String signature(List<Migration> migrations) {
        StringBuilder signature = new StringBuilder();
        for (Migration migration : migrations) {
            signature.append(migration.getClass().getName()).append('#').append(migration.name())
                    .append('#').append(migration.checksum().orElse("")).append(';');
        }
        return signature.toString();
    }

    /**
     * Returns the id of this JVM's run: {@value #RUN_ID_LENGTH} lowercase base-36 characters.
     *
     * @return the id, never {@code null}; the same value for the life of the JVM
     */
    static String runId() {
        return RUN_ID;
    }

    /**
     * Turns a run id into the unsigned 32-bit key of an advisory lock.
     *
     * @param runId a run id as {@link #runId()} returns, never {@code null}
     * @return the key, read as an unsigned 32-bit number
     * @throws IllegalArgumentException if {@code runId} is not {@value #RUN_ID_LENGTH} base-36
     *     characters
     */
    static int runKey(String runId) {
        checkRunId(runId);
        return Integer.parseUnsignedInt(runId, BASE_36);
    }

    /**
     * Returns the hash of the migration list.
     *
     * @return {@value #HASH_LENGTH} lowercase hexadecimal characters, never {@code null}
     */
    String hash8() {
        return hash8;
    }

    /**
     * Returns the hash as an unsigned 32-bit key, for the lock that serialises building a template.
     *
     * @return the key, read as an unsigned 32-bit number
     */
    int hash32() {
        return Integer.parseUnsignedInt(hash8, BASE_16);
    }

    /**
     * Returns the name of the finished template.
     *
     * @return the name, never {@code null}
     */
    String template() {
        return checked("diwan_tpl_" + hash8);
    }

    /**
     * Returns the name a run builds the template under before it is moved to its final name, so a
     * run that dies mid-build never leaves a half-migrated template under the final name.
     *
     * @param runId the building run's id, never {@code null}
     * @return the name, never {@code null}
     * @throws IllegalArgumentException if {@code runId} is not {@value #RUN_ID_LENGTH} base-36
     *     characters
     */
    String building(String runId) {
        checkRunId(runId);
        return checked("diwan_tpl_" + hash8 + "_b_" + runId);
    }

    /**
     * Returns the name of one test's copy.
     *
     * @param runId the owning run's id, never {@code null}
     * @param n the copy's number within the run, zero or more
     * @return the name, never {@code null}
     * @throws IllegalArgumentException if {@code runId} is not {@value #RUN_ID_LENGTH} base-36
     *     characters, or {@code n} is negative
     */
    String copy(String runId, long n) {
        checkRunId(runId);
        if (n < 0) {
            throw new IllegalArgumentException("A copy number must not be negative: " + n);
        }
        return checked("diwan_t_" + hash8 + "_" + runId + "_" + n);
    }

    /**
     * Reads the run id out of a copy name or a building name.
     *
     * @param name a database name, never {@code null}
     * @return the run id, or empty when {@code name} is neither a copy nor a building name, which
     *     includes every finished template
     * @throws NullPointerException if {@code name} is {@code null}
     */
    static Optional<String> runIdOf(String name) {
        Matcher copy = COPY.matcher(name);
        if (copy.matches()) {
            return Optional.of(copy.group(1));
        }
        Matcher building = BUILDING.matcher(name);
        return building.matches() ? Optional.of(building.group(1)) : Optional.<String>empty();
    }

    private static void checkRunId(String runId) {
        if (runId == null || !RUN_ID_FORM.matcher(runId).matches()) {
            throw new IllegalArgumentException("A run id is " + RUN_ID_LENGTH + " characters of [0-9a-z]: " + runId);
        }
    }

    private static String checked(String name) {
        if (!SAFE.matcher(name).matches() || name.length() > MAX_LENGTH) {
            throw new IllegalStateException("The generated database name is not a safe identifier: " + name);
        }
        return name;
    }

    private static String newRunId() {
        SecureRandom random = new SecureRandom();
        StringBuilder id = new StringBuilder(RUN_ID_LENGTH);
        for (int i = 0; i < RUN_ID_LENGTH; i++) {
            id.append(Character.forDigit(random.nextInt(BASE_36), BASE_36));
        }
        return id.toString();
    }
}

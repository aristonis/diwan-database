package org.aristonis.diwan.database.exception;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.migrations.Migrator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Refuses a run because a migration that already ran no longer matches the checksum recorded for it.
 *
 * <p>A migration that was edited after it ran has not changed the database it already changed, so
 * the code and the schema have drifted apart. The message lists every such migration with the
 * recorded and the current checksum. To fix it, restore the old checksum value, or accept the
 * current ones with {@link Migrator#repairChecksums(List)}. Nothing ran when this is thrown.
 */
public final class MigrationChecksumException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Longest part of a name shown in the message. */
    private static final int MAX_SHOWN = 191;

    /** The mismatches; an {@code ArrayList}, never changed. */
    private final ArrayList<Mismatch> mismatches;

    /**
     * Creates the exception for the given mismatches.
     *
     * @param mismatches the migrations whose checksums differ, never {@code null} or empty
     */
    @Internal
    public MigrationChecksumException(List<Mismatch> mismatches) {
        super(message(mismatches));
        this.mismatches = new ArrayList<>(mismatches);
    }

    private static String message(List<Mismatch> found) {
        StringBuilder text = new StringBuilder();
        text.append(found.size()).append(" migration(s) changed after they ran:");
        for (Mismatch mismatch : found) {
            text.append("\n  ").append(printable(mismatch.name())).append(": recorded ")
                    .append(printable(mismatch.stored())).append(", current ")
                    .append(printable(mismatch.current()));
        }
        text.append("\nRestore the old checksum value, or call repairChecksums() to accept the current ones;")
                .append(" nothing was run");
        return text.toString();
    }

    // Names and recorded checksums come from the database, so they are clipped and stripped of
    // control characters before they reach a log line.
    private static String printable(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length() && i < MAX_SHOWN; i++) {
            char c = value.charAt(i);
            text.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? '?' : c);
        }
        return value.length() > MAX_SHOWN ? text + "..." : text.toString();
    }

    /**
     * Returns every migration whose checksum differs.
     *
     * @return an unmodifiable list in migration name order, never {@code null} or empty
     */
    public List<Mismatch> mismatches() {
        return Collections.unmodifiableList(mismatches);
    }

    /** One migration whose declared checksum differs from the recorded one. */
    public static final class Mismatch implements java.io.Serializable {

        private static final long serialVersionUID = 1L;

        /** The migration name. */
        private final String name;

        /** The checksum recorded when the migration ran. */
        private final String stored;

        /** The checksum the migration declares now. */
        private final String current;

        /**
         * Creates a mismatch.
         *
         * @param name the migration name
         * @param stored the recorded checksum
         * @param current the declared checksum
         */
        @Internal
        public Mismatch(String name, String stored, String current) {
            this.name = name;
            this.stored = stored;
            this.current = current;
        }

        /**
         * Returns the migration name.
         *
         * @return the name, never {@code null}
         */
        public String name() {
            return name;
        }

        /**
         * Returns the checksum recorded when the migration ran.
         *
         * @return the recorded checksum, never {@code null}
         */
        public String stored() {
            return stored;
        }

        /**
         * Returns the checksum the migration declares now.
         *
         * @return the current checksum, never {@code null}
         */
        public String current() {
            return current;
        }
    }
}

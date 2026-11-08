package org.aristonis.diwan.database.migrations.internal;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.exception.MigrationChecksumException;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.migrations.MigrationChecksum;
import org.aristonis.diwan.database.migrations.MigrationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Compares the checksums migrations declare with the ones the migrations table holds. */
@Internal
public final class MigrationChecksums {

    private MigrationChecksums() {
    }

    /**
     * Reads a migration's declared checksum and checks that it fits the column.
     *
     * @param migration the migration, never {@code null}
     * @return the checksum, or empty when the migration declares none
     * @throws IllegalStateException if the value is null, blank, longer than the column or holds
     *     control characters
     */
    @Internal
    public static Optional<String> declared(Migration migration) {
        Optional<String> declared = migration.checksum();
        if (declared == null) {
            throw new IllegalStateException(
                    "The checksum of " + migration.name() + " is null; return Optional.empty()");
        }
        if (declared.isPresent()) {
            String value = declared.get();
            boolean isValid = !value.trim().isEmpty() && value.length() <= MigrationChecksum.LENGTH;
            for (int i = 0; isValid && i < value.length(); i++) {
                isValid = !Character.isISOControl(value.charAt(i));
            }
            if (!isValid) {
                throw new IllegalStateException("The checksum of " + migration.name() + " must be 1 to "
                        + MigrationChecksum.LENGTH + " characters without control characters");
            }
        }
        return declared;
    }

    /**
     * Finds the ran migrations whose stored and declared checksums are both present and differ.
     *
     * @param stored the stored checksums by migration name
     * @param migrations the migrations to compare, in the order to report
     * @return the mismatches, empty when everything matches
     */
    static List<MigrationChecksumException.Mismatch> mismatches(Map<String, Optional<String>> stored,
            Collection<Migration> migrations) {
        List<MigrationChecksumException.Mismatch> found = new ArrayList<>();
        for (Migration migration : migrations) {
            Optional<String> recorded = stored.get(migration.name());
            if (recorded == null || !recorded.isPresent()) {
                continue;
            }
            Optional<String> current = declared(migration);
            if (current.isPresent() && !current.get().equals(recorded.get())) {
                found.add(new MigrationChecksumException.Mismatch(migration.name(), recorded.get(), current.get()));
            }
        }
        return found;
    }

    /**
     * Refuses when any declared checksum is unusable or any ran migration changed.
     *
     * @param stored the stored checksums by migration name
     * @param migrations the migrations to compare
     * @throws IllegalStateException if a migration declares a null, blank, too long or
     *     control-character checksum, whether or not it ran
     * @throws MigrationChecksumException if a stored and a declared checksum differ
     */
    @Internal
    public static void validate(Map<String, Optional<String>> stored, Collection<Migration> migrations) {
        for (Migration migration : migrations) {
            declared(migration);
        }
        List<MigrationChecksumException.Mismatch> found = mismatches(stored, migrations);
        if (!found.isEmpty()) {
            throw new MigrationChecksumException(found);
        }
    }

    /**
     * Writes the declared checksum of every ran migration whose stored value is empty.
     *
     * @param repository where the checksums are kept
     * @param stored the stored checksums by migration name
     * @param migrations the migrations to adopt
     */
    @Internal
    public static void adopt(MigrationRepository repository, Map<String, Optional<String>> stored,
            Collection<Migration> migrations) {
        for (Migration migration : migrations) {
            Optional<String> recorded = stored.get(migration.name());
            if (recorded == null || recorded.isPresent()) {
                continue;
            }
            Optional<String> current = declared(migration);
            if (current.isPresent()) {
                repository.setChecksum(migration.name(), current);
            }
        }
    }

    /**
     * Rewrites the stored checksum of every ran migration whose declared checksum differs.
     *
     * @param repository where the checksums are kept
     * @param migrations the migrations to repair
     * @return how many records were rewritten
     */
    @Internal
    public static int repair(MigrationRepository repository, Collection<Migration> migrations) {
        Map<String, Optional<String>> stored = repository.checksums();
        int changed = 0;
        for (Migration migration : migrations) {
            Optional<String> recorded = stored.get(migration.name());
            Optional<String> current = declared(migration);
            if (recorded != null && current.isPresent() && !current.equals(recorded)) {
                repository.setChecksum(migration.name(), current);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Removes every stored checksum.
     *
     * @param repository where the checksums are kept
     * @return how many records held a checksum
     */
    @Internal
    public static int clear(MigrationRepository repository) {
        int changed = 0;
        for (Map.Entry<String, Optional<String>> entry : repository.checksums().entrySet()) {
            if (entry.getValue().isPresent()) {
                repository.setChecksum(entry.getKey(), Optional.<String>empty());
                changed++;
            }
        }
        return changed;
    }
}

package org.aristonis.diwan.database.migrations;

import java.util.function.Supplier;

/** Runs one migrator call while holding the migration lock. */
final class LockedCall {

    private LockedCall() {
    }

    /**
     * Takes the lock, runs the work and gives the lock back, whether the work succeeded or failed.
     *
     * <p>If the work succeeded but the release fails, the release failure is thrown and the result is
     * lost, though the work itself has already been committed.
     *
     * @param lock the migration lock
     * @param work the call to run under the lock
     * @param <T> the type of the call's result
     * @return the call's result
     */
    static <T> T run(MigrationLock lock, Supplier<T> work) {
        lock.acquire();
        T results;
        try {
            results = work.get();
        } catch (RuntimeException | Error failure) {
            explainQuietly(lock, failure);
            try {
                lock.release();
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
        lock.release();
        return results;
    }

    // A lock that cannot explain a failure must not hide it: the explanation's own failure is suppressed.
    private static void explainQuietly(MigrationLock lock, Throwable failure) {
        if (!(failure instanceof RuntimeException)) {
            return;
        }
        try {
            lock.explain((RuntimeException) failure);
        } catch (RuntimeException explainFailure) {
            failure.addSuppressed(explainFailure);
        }
    }
}

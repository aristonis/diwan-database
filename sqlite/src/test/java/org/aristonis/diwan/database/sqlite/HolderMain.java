package org.aristonis.diwan.database.sqlite;

import java.io.File;
import org.aristonis.diwan.database.migrations.FileMigrationLock;

/** Test program run in a child JVM: takes the migration lock of a database file, prints held, then sleeps. */
public final class HolderMain {

    private static final long SLEEP_MILLIS = 120_000L;

    private HolderMain() {
    }

    /**
     * Takes the lock and keeps it until the process is killed or the sleep ends.
     *
     * @param args the database file path, one argument
     * @throws InterruptedException if the sleep is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        File db = new File(args[0]);
        FileMigrationLock lock = new FileMigrationLock(() -> db);
        lock.acquire();
        System.out.println("held");
        System.out.flush();
        Thread.sleep(SLEEP_MILLIS);
        lock.release();
    }
}

package org.aristonis.diwan.database.event;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.event.ConnectionEvents;

/** The four moments of a transaction level that {@link ConnectionEvents#transaction} reports. */
@Internal
public enum TransactionPhase {
    /** After BEGIN or SAVEPOINT succeeded. */
    BEGINNING,
    /** Before COMMIT or RELEASE. */
    COMMITTING,
    /** After COMMIT or RELEASE succeeded. */
    COMMITTED,
    /** After ROLLBACK or ROLLBACK TO. */
    ROLLED_BACK
}

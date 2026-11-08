package org.aristonis.diwan.database.testing;

import java.util.Collections;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;

/**
 * The bodies of the model assertions. Only this class touches the eloquent module's code, so
 * {@link DatabaseAssertions} loads without it until a model assertion is called.
 */
final class ModelAssertions {

    private static final String HIDDEN = "[hidden]";

    private ModelAssertions() {
    }

    static void exists(DatabaseAssertions assertions, Model model) {
        String key = keyName(model);
        Object value = model.getKey();
        if (value == null) {
            throw new AssertionError("Model [" + model.getClass().getName() + "] has no value for its key [" + key
                    + "], so it is not in [" + model.getTable() + "].");
        }
        if (!hasRow(assertions.db(), model, value, false)) {
            throw new AssertionError("No row in [" + model.getTable() + "] has " + key + " = " + shown(model, key, value)
                    + ". " + similar(assertions, model, key));
        }
    }

    static void missing(DatabaseAssertions assertions, Model model) {
        String key = keyName(model);
        Object value = model.getKey();
        if (value != null && hasRow(assertions.db(), model, value, false)) {
            throw new AssertionError("Found a row in [" + model.getTable() + "] with " + key + " = "
                    + shown(model, key, value) + ", expected none.");
        }
    }

    static void softDeleted(DatabaseAssertions assertions, Model model, boolean isExpectedDeleted) {
        if (!model.isSoftDeletable()) {
            throw new AssertionError("Model [" + model.getClass().getName()
                    + "] does not soft-delete, so it cannot be asserted as "
                    + (isExpectedDeleted ? "soft deleted." : "not soft deleted."));
        }
        exists(assertions, model);
        String key = keyName(model);
        Object value = model.getKey();
        boolean isDeleted = hasRow(assertions.db(), model, value, true);
        if (isDeleted != isExpectedDeleted) {
            throw new AssertionError("The row in [" + model.getTable() + "] with " + key + " = "
                    + shown(model, key, value) + (isDeleted ? " is soft deleted, expected it not to be."
                            : " is not soft deleted, expected it to be."));
        }
    }

    /** Tells whether a row has the key and, if asked, a set deleted-at column. */
    private static boolean hasRow(QueryFactory db, Model model, Object value, boolean isDeletedOnly) {
        Builder query = db.table(model.getTable()).where(model.getKeyName(), value);
        if (isDeletedOnly) {
            query.whereNotNull(model.getDeletedAtColumn());
        }
        return query.exists();
    }

    private static String similar(DatabaseAssertions assertions, Model model, String key) {
        // Listing rows would print the key values of a hidden key column.
        if (model.getHidden().contains(key)) {
            return "Similar rows are not listed because the key column is hidden.";
        }
        return AssertionMessages.similarRows(assertions.db(), model.getTable(), Collections.singletonList(key));
    }

    private static String keyName(Model model) {
        String key = model.getKeyName();
        if (key == null) {
            throw new IllegalArgumentException("Model [" + model.getClass().getName() + "] has no primary key column");
        }
        return key;
    }

    private static String shown(Model model, String column, Object value) {
        return model.getHidden().contains(column) ? HIDDEN : String.valueOf(value);
    }
}

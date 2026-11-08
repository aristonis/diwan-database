package org.aristonis.diwan.database.testing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.QueryFactory;

/** Builds the failure text of the database assertions and shows nothing but the columns asked about. */
final class AssertionMessages {

    /** How many rows a failure message lists. */
    static final int SAMPLE_ROWS = 3;

    /** The key column shown next to the compared columns when a row has one. */
    private static final String KEY_COLUMN = "id";

    private AssertionMessages() {
    }

    /** Applies equality conditions to a query; a {@code null} value compares with {@code is null}. */
    static Builder matching(QueryFactory db, String table, Map<String, ?> values) {
        Builder query = db.table(table);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                query.whereNull(entry.getKey());
            } else {
                query.where(entry.getKey(), entry.getValue());
            }
        }
        return query;
    }

    /** Formats conditions as {@code {a=1, b=x}}, in the order given. */
    static String describe(Map<String, ?> values) {
        StringBuilder text = new StringBuilder("{");
        boolean isFirst = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!isFirst) {
                text.append(", ");
            }
            isFirst = false;
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.append('}').toString();
    }

    /**
     * Lists the first rows of the table, cut down to the compared columns and the key column, or
     * says the table is empty.
     */
    static String similarRows(QueryFactory db, String table, Collection<String> compared) {
        long total = db.table(table).count();
        if (total == 0) {
            return "The table is empty.";
        }
        List<Map<String, Object>> rows = db.table(table).limit(SAMPLE_ROWS).get();
        return "Similar rows (first " + rows.size() + " of " + total + "): " + describeRows(rows, compared);
    }

    /** Formats rows cut down to the compared columns and the key column. */
    static String describeRows(List<Map<String, Object>> rows, Collection<String> compared) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            parts.add(describe(project(row, compared)));
        }
        return String.join(", ", parts);
    }

    private static Map<String, Object> project(Map<String, Object> row, Collection<String> compared) {
        Map<String, Object> shown = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(KEY_COLUMN) || containsIgnoreCase(compared, entry.getKey())) {
                shown.put(entry.getKey(), entry.getValue());
            }
        }
        return shown;
    }

    private static boolean containsIgnoreCase(Collection<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}

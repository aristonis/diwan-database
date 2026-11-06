package org.aristonis.diwan.database.internal;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.LoggedQuery;
import org.aristonis.diwan.database.event.QueryExecuted;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Keeps the most recent statements, with compact copies of their bindings; safe for concurrent use. */
@Internal
public final class QueryLog {

    private static final int MAX_LOGGED_STRING = 1024;

    private static final String REDACTED = "?";

    private final int limit;
    private final boolean isKeepingBindings;
    private final Object lock = new Object();
    private final Deque<LoggedQuery> entries = new ArrayDeque<LoggedQuery>();

    /** Creates a log of at most {@code limit} entries; without {@code isKeepingBindings} bindings become {@code ?}. */
    @Internal
    public QueryLog(int limit, boolean isKeepingBindings) {
        this.limit = limit;
        this.isKeepingBindings = isKeepingBindings;
    }

    /** Appends the statement, dropping the oldest entry when the log is full. */
    @Internal
    public void record(QueryExecuted event) {
        List<Object> bindings = isKeepingBindings ? compact(event.bindings()) : redact(event.bindings());
        LoggedQuery entry = new LoggedQuery(event.sql(), bindings);
        synchronized (lock) {
            if (entries.size() >= limit) {
                entries.removeFirst();
            }
            entries.addLast(entry);
        }
    }

    /** Returns an unmodifiable copy of the entries, oldest first. */
    @Internal
    public List<LoggedQuery> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<LoggedQuery>(entries));
        }
    }

    @Internal
    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    private static List<Object> redact(List<Object> bindings) {
        return new ArrayList<Object>(Collections.nCopies(bindings.size(), REDACTED));
    }

    private static List<Object> compact(List<Object> bindings) {
        List<Object> result = new ArrayList<Object>(bindings.size());
        for (Object value : bindings) {
            if (value instanceof byte[]) {
                result.add("<byte[" + ((byte[]) value).length + "]>");
            } else if (value instanceof String && ((String) value).length() > MAX_LOGGED_STRING) {
                result.add(cut((String) value));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private static String cut(String text) {
        int end = MAX_LOGGED_STRING;
        // Do not split a surrogate pair.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "...(" + text.length() + " chars)";
    }
}

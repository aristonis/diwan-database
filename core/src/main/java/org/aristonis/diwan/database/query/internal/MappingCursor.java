package org.aristonis.diwan.database.query.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.QueryCursor;

/**
 * The one implementation of {@link QueryCursor}: a driver {@link RowCursor} read through a mapper.
 *
 * <p>Every view made by {@link #map(Function)}, and the stream, share one state with the cursor
 * they came from: whether it was already read. They also share the driver cursor, which holds the
 * closed flag, so a close through any view closes all of them and a second close does nothing.
 *
 * @param <T> the type of the elements
 * @apiNote Internal: built by the connections and the model builder; may change without notice.
 */
@Internal
public final class MappingCursor<T> implements QueryCursor<T> {

    private final RowCursor rows;
    private final Function<Map<String, Object>, T> mapper;
    private final State state;

    /**
     * Wraps a driver cursor, mapping each row.
     *
     * @param rows the open driver cursor, never {@code null}
     * @param mapper the row mapping, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public MappingCursor(RowCursor rows, Function<Map<String, Object>, T> mapper) {
        this(Objects.requireNonNull(rows, "rows"), Objects.requireNonNull(mapper, "mapper"), new State());
    }

    private MappingCursor(RowCursor rows, Function<Map<String, Object>, T> mapper, State state) {
        this.rows = rows;
        this.mapper = mapper;
        this.state = state;
    }

    @Override
    public Iterator<T> iterator() {
        if (state.isConsumed) {
            throw new IllegalStateException("The cursor was already iterated");
        }
        state.isConsumed = true;
        return new RowIterator();
    }

    @Override
    public Stream<T> stream() {
        Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED);
        return StreamSupport.stream(spliterator, false).onClose(this::close);
    }

    @Override
    public <R> QueryCursor<R> map(Function<? super T, ? extends R> next) {
        Objects.requireNonNull(next, "mapper");
        Function<Map<String, Object>, T> first = mapper;
        return new MappingCursor<R>(rows, row -> next.apply(first.apply(row)), state);
    }

    @Override
    public void close() {
        rows.close();
    }

    @Override
    public boolean isClosed() {
        return rows.isClosed();
    }

    private final class RowIterator implements Iterator<T> {

        @Override
        public boolean hasNext() {
            if (rows.isClosed()) {
                return false;
            }
            if (rows.hasNext()) {
                return true;
            }
            // A loop run to the end releases the session without a close() call.
            rows.close();
            return false;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                return mapper.apply(rows.next());
            } catch (RuntimeException | Error failure) {
                // A failed row ends the read, so the session is given back now rather than left to
                // a caller that may not reach close().
                try {
                    close();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
    }

    private static final class State {
        private boolean isConsumed;
    }
}

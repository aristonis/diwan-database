package org.aristonis.diwan.database.query.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.aristonis.diwan.database.driver.RowCursor;
import org.aristonis.diwan.database.query.QueryCursor;
import org.junit.jupiter.api.Test;

class MappingCursorTest {

    private static Map<String, Object> row(long id) {
        return Collections.<String, Object>singletonMap("id", id);
    }

    private static MappingCursor<Long> ids(FakeRows rows) {
        return new MappingCursor<Long>(rows, r -> (Long) r.get("id"));
    }

    @Test
    void iteratorWorksOnceAndASecondCallIsRefused() {
        QueryCursor<Long> cursor = ids(new FakeRows(1, 2));

        List<Long> seen = new ArrayList<>();
        cursor.iterator().forEachRemaining(seen::add);

        assertEquals(Arrays.asList(1L, 2L), seen);
        assertThrows(IllegalStateException.class, cursor::iterator);
    }

    @Test
    void aMapViewSharesTheSingleUseWithItsSource() {
        QueryCursor<Long> source = ids(new FakeRows(1, 2));
        QueryCursor<String> view = source.map(id -> "n" + id);

        assertEquals("n1", view.iterator().next());

        assertThrows(IllegalStateException.class, source::iterator);
        assertThrows(IllegalStateException.class, view::stream);
    }

    @Test
    void mapsCompose() {
        QueryCursor<Integer> view = ids(new FakeRows(5)).map(id -> id * 2).map(Long::intValue);

        assertEquals(10, view.iterator().next().intValue());
    }

    @Test
    void streamIsLazyAndClosingItClosesTheRows() {
        FakeRows rows = new FakeRows(1, 2, 3);

        try (Stream<Long> stream = ids(rows).stream()) {
            assertEquals(0, rows.read);
            assertEquals(Arrays.asList(1L, 2L), stream.limit(2).collect(Collectors.toList()));
            assertFalse(rows.isClosed());
        }

        assertTrue(rows.isClosed());
    }

    @Test
    void aStreamAfterTheIteratorIsRefused() {
        QueryCursor<Long> cursor = ids(new FakeRows(1));
        cursor.iterator();

        assertThrows(IllegalStateException.class, cursor::stream);
    }

    @Test
    void aMapperThatThrowsClosesTheRowsAndRethrows() {
        FakeRows rows = new FakeRows(1, 2);
        QueryCursor<Long> cursor = new MappingCursor<Long>(rows, r -> {
            throw new IllegalArgumentException("bad row");
        });
        Iterator<Long> iterator = cursor.iterator();

        assertThrows(IllegalArgumentException.class, iterator::next);

        assertTrue(rows.isClosed());
        assertFalse(iterator.hasNext());
    }

    @Test
    void aCloseThatThrowsDuringAMapperFailureKeepsTheOriginalFailure() {
        FakeRows rows = new FakeRows(1);
        rows.closeFailure = new IllegalArgumentException("close failed");
        QueryCursor<Long> cursor = new MappingCursor<Long>(rows, r -> {
            throw new UnsupportedOperationException("bad row");
        });
        Iterator<Long> iterator = cursor.iterator();

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class, iterator::next);

        assertEquals("bad row", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("close failed", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void everyViewAnswersIsClosedFromTheSharedRows() {
        QueryCursor<Long> source = ids(new FakeRows(1));
        QueryCursor<String> view = source.map(String::valueOf);
        QueryCursor<String> deeper = view.map(String::trim);

        assertFalse(source.isClosed());
        assertFalse(deeper.isClosed());
        view.close();

        assertTrue(source.isClosed());
        assertTrue(view.isClosed());
        assertTrue(deeper.isClosed());
    }

    @Test
    void closeIsIdempotentAcrossViews() {
        FakeRows rows = new FakeRows(1);
        QueryCursor<Long> source = ids(rows);
        QueryCursor<String> view = source.map(String::valueOf);

        view.close();
        source.close();
        view.close();

        assertEquals(1, rows.closeCalls);
        assertTrue(rows.isClosed());
    }

    @Test
    void hasNextFalseClosesTheRows() {
        FakeRows rows = new FakeRows(1);
        Iterator<Long> iterator = ids(rows).iterator();

        assertTrue(iterator.hasNext());
        iterator.next();
        assertFalse(iterator.hasNext());

        assertTrue(rows.isClosed());
    }

    @Test
    void readingAfterCloseFindsNothing() {
        FakeRows rows = new FakeRows(1, 2);
        QueryCursor<Long> cursor = ids(rows);
        Iterator<Long> iterator = cursor.iterator();

        cursor.close();

        assertFalse(iterator.hasNext());
        assertThrows(java.util.NoSuchElementException.class, iterator::next);
    }

    /** Scripted driver rows that count reads and closes. */
    private static final class FakeRows implements RowCursor {

        private final Iterator<Map<String, Object>> rows;
        private boolean isClosed;
        RuntimeException closeFailure;
        int read;
        int closeCalls;

        FakeRows(long... ids) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (long id : ids) {
                list.add(row(id));
            }
            this.rows = list.iterator();
        }

        @Override
        public Iterator<Map<String, Object>> iterator() {
            return this;
        }

        @Override
        public boolean hasNext() {
            return !isClosed && rows.hasNext();
        }

        @Override
        public Map<String, Object> next() {
            read++;
            return rows.next();
        }

        @Override
        public void close() {
            if (!isClosed) {
                closeCalls++;
            }
            isClosed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }
    }
}

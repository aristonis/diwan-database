package org.aristonis.diwan.database.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The keyset logic of cursor pagination, ported from Laravel's {@code paginateUsingCursor}: the
 * order columns a cursor keys on, the nested condition that starts a page after (or before) a
 * cursor, and the cursors of the pages around a page.
 */
final class PaginationOrders {

    private static final String ASC = "asc";
    private static final String DESC = "desc";

    private PaginationOrders() {
    }

    /**
     * Checks the query is ordered, flips every order when reading backwards, and returns the
     * column orders the cursor keys on; raw and subquery orders are left out, as in Laravel.
     */
    static List<Order.Column> prepare(Builder query, boolean isReversed) {
        ChunkSupport.enforceOrderBy(query);
        if (isReversed) {
            query.orders = flipped(query.orders);
            query.unionOrders = flipped(query.unionOrders);
        }
        List<Order.Column> columns = new ArrayList<>();
        for (Order order : query.unionOrders.isEmpty() ? query.orders : query.unionOrders) {
            if (order instanceof Order.Column) {
                columns.add((Order.Column) order);
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("Cursor pagination needs at least one order by a column");
        }
        return Collections.unmodifiableList(columns);
    }

    /** Refuses a cursor whose parameters are not exactly the order columns. */
    static void requireMatching(Cursor cursor, List<Order.Column> orders) {
        Set<String> names = new HashSet<>();
        for (Order.Column order : orders) {
            names.add(order.column());
        }
        if (!names.equals(cursor.parameters().keySet())) {
            throw new IllegalArgumentException("The cursor does not match the query's order columns " + names);
        }
    }

    /**
     * Adds {@code (c1 > ? or (c1 = ? and (c2 > ? or ...)))} to the query and to each union part,
     * with {@code <} for a descending column, and collects the union bindings again.
     */
    static void addConditions(Builder query, List<Order.Column> orders, Cursor cursor) {
        query.where(group -> keyset(group, query, orders, cursor, 0, query));
        if (query.unions.isEmpty()) {
            return;
        }
        List<Union> parts = new ArrayList<>();
        List<Object> unionBindings = new ArrayList<>();
        for (Union union : query.unions) {
            Builder part = union.builder().clone();
            part.where(group -> keyset(group, part, orders, cursor, 0, query));
            parts.add(new Union(part, union.isAll()));
            unionBindings.addAll(part.getBindings());
        }
        query.unions = parts;
        query.setBindings(unionBindings, BindingType.UNION);
    }

    // Laravel resolves the first column of a union part against the part, deeper ones against the
    // outer query; outer is that query.
    private static void keyset(Builder group, Builder source, List<Order.Column> orders, Cursor cursor, int index,
            Builder outer) {
        Order.Column order = orders.get(index);
        String column = order.column();
        String original = originalColumn(source, column);
        boolean isAlias = !original.equals(column);
        Object value = cursor.parameter(column);
        compare(group, original, isAlias, ASC.equals(order.direction()) ? ">" : "<", value);
        if (index < orders.size() - 1) {
            group.orWhere(next -> {
                compare(next, original, isAlias, "=", value);
                next.where(deeper -> keyset(deeper, outer, orders, cursor, index + 1, outer));
            });
        }
    }

    private static void compare(Builder query, String original, boolean isAlias, String operator, Object value) {
        // Only a select alias for an expression such as "count(x)" is written as given, as Laravel
        // does; the select list comes from the developer. An alias for a plain column and every
        // other order column are names and are quoted, so they cannot carry SQL.
        if (isAlias && (original.indexOf('(') >= 0 || original.indexOf(')') >= 0)) {
            query.whereRaw(original + " " + operator + " ?", Collections.singletonList(value));
        } else {
            query.where(original, operator, value);
        }
    }

    /**
     * Returns what a cursor parameter names in the select list: for {@code "users.id as uid"} and
     * {@code uid}, {@code users.id}. Laravel's {@code getOriginalColumnNameForCursorPagination},
     * splitting at the last {@code " as "}; expression columns are skipped.
     */
    static String originalColumn(BuilderState query, String parameter) {
        for (Object column : query.columns) {
            if (!(column instanceof String)) {
                continue;
            }
            String text = (String) column;
            int position = text.toLowerCase(Locale.ROOT).lastIndexOf(" as ");
            if (position < 0) {
                continue;
            }
            String alias = text.substring(position + 4);
            if (parameter.equals(alias) || query.grammar.wrap(parameter).equals(alias)) {
                return text.substring(0, position);
            }
        }
        return parameter;
    }

    /** Builds the page as Laravel's cursor paginator does from {@code perPage + 1} rows. */
    static CursorPage page(List<Map<String, Object>> rows, int perPage, Cursor cursor, List<Order.Column> orders) {
        boolean hasMore = rows.size() > perPage;
        List<Map<String, Object>> items = new ArrayList<>(rows.subList(0, Math.min(perPage, rows.size())));
        boolean isBackwards = cursor != null && cursor.pointsToPreviousItems();
        if (isBackwards) {
            Collections.reverse(items);
        }
        Cursor next = null;
        Cursor previous = null;
        boolean isLastForward = cursor == null || cursor.pointsToNextItems();
        if (!items.isEmpty() && !(isLastForward && !hasMore)) {
            next = new Cursor(parametersOf(items.get(items.size() - 1), orders), true);
        }
        if (!items.isEmpty() && cursor != null && !(isBackwards && !hasMore)) {
            previous = new Cursor(parametersOf(items.get(0), orders), false);
        }
        return new CursorPage(items, perPage, next, previous);
    }

    // Laravel reads $item[$name] ?? $item[Str::afterLast($name, '.')].
    private static Map<String, Object> parametersOf(Map<String, Object> row, List<Order.Column> orders) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Order.Column order : orders) {
            String name = order.column();
            Object value = row.get(name);
            if (value == null) {
                value = row.get(name.substring(name.lastIndexOf('.') + 1));
            }
            parameters.put(name, value);
        }
        return parameters;
    }

    private static List<Order> flipped(List<Order> orders) {
        List<Order> result = new ArrayList<>(orders.size());
        for (Order order : orders) {
            if (order instanceof Order.Column) {
                Order.Column column = (Order.Column) order;
                result.add(new Order.Column(column.column(), flip(column.direction())));
            } else if (order instanceof Order.Sub) {
                Order.Sub sub = (Order.Sub) order;
                result.add(new Order.Sub(sub.sql(), flip(sub.direction())));
            } else {
                result.add(order);
            }
        }
        return result;
    }

    private static String flip(String direction) {
        return ASC.equals(direction) ? DESC : ASC;
    }
}

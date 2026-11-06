package org.aristonis.diwan.database.eloquent.relations;

import org.aristonis.diwan.database.eloquent.exception.RelationNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.casts.Casts;
import org.aristonis.diwan.database.eloquent.internal.Access;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.Expression;

/**
 * Adds one aggregate subselect per relation to a query, as Laravel's
 * {@code QueriesRelationships::withAggregate}.
 *
 * <p>Each subselect is the relation's existence query ({@code whereColumn} on the keys, a
 * {@code laravel_reserved_N} alias for a self relation from the outer builder's counter), with
 * the aggregate expression as its only column. The column is qualified after the alias is taken,
 * so the expression and the {@code from} clause always name the same alias. A constraint runs on the
 * subselect itself, as for {@code whereHas}: it may add joins, and its wheres are wrapped in one
 * where group. The columns, orders, limit and offset are then cleared, and a groupBy, having,
 * {@code distinct} or union is refused; {@code exists} drops only the orders and keeps the limit
 * and offset. {@code exists} is written as {@code exists(...) as alias} and cast to boolean
 * through the builder's cast overlay; every other value keeps the driver's type.
 *
 * <p>Every name, the function and every constraint are checked and built before the outer
 * query changes, so a refused call leaves it as it was.
 */
final class RelationAggregates {

    /** The aggregate functions accepted, lower case. */
    static final Set<String> FUNCTIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(
                    Arrays.asList("count", "max", "min", "sum", "avg", "exists")));

    private static final String EXISTS = "exists";
    private static final String STAR = "*";
    private static final String AS = "as";
    private static final String TABLE_ALIAS = " as ";
    private static final int ALIAS_SEGMENTS = 3;
    private static final int FUNCTION_SHOWN = 80;
    private static final String UCWORDS_DELIMITERS = " \t\r\n\f\u000B";

    private RelationAggregates() {
    }

    /** One relation of the call: its name, explicit alias, constraint and resolved relation. */
    private static final class Entry {
        final String name;
        final String alias;
        final Consumer<EloquentBuilder<?>> constraint;
        final Relation<?, ?> relation;

        Entry(String name, String alias, Consumer<EloquentBuilder<?>> constraint, Relation<?, ?> relation) {
            this.name = name;
            this.alias = alias;
            this.constraint = constraint;
            this.relation = relation;
        }
    }

    /**
     * Adds an aggregate subselect per relation to a query, in map order.
     *
     * @param outer the query the subselects are added to, never {@code null}
     * @param relations the constraints keyed by raw name ({@code "posts"} or
     *     {@code "posts as total"}), as parsed by {@code WithParser.parseAggregates}, never
     *     {@code null}; a {@code null} constraint is none; empty does nothing
     * @param column the aggregated column: a {@code String} ({@code "*"} for count and exists) or
     *     an {@link Expression}, never {@code null}
     * @param function the aggregate function in any case, one of count, max, min, sum, avg and
     *     exists, or {@code null} for one related row's column
     * @throws NullPointerException if {@code outer}, {@code relations} or {@code column} is
     *     {@code null}
     * @throws IllegalArgumentException if the function is not one of the six, the column is
     *     neither a {@code String} nor an {@link Expression}, or a constraint adds a groupBy, having,
     *     {@code distinct} or union to a subselect other than {@code exists}
     * @throws RelationNotFoundException if a name is unknown, is a dot path, or has a malformed
     *     {@code as} alias
     */
    static void add(EloquentBuilder<?> outer, Map<String, Consumer<EloquentBuilder<?>>> relations, Object column,
            String function) {
        Objects.requireNonNull(outer, "outer");
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(column, "column");
        if (relations.isEmpty()) {
            return;
        }
        Model model = outer.getModel();
        String fn = checkFunction(function, model);
        String columnText = columnText(column);
        List<Entry> entries = resolve(outer, relations);
        List<Consumer<Builder>> selects = new ArrayList<>(entries.size());
        Map<String, Object> existsCasts = new LinkedHashMap<>();
        for (Entry entry : entries) {
            selects.add(subselect(outer, entry, column, fn, columnText, existsCasts));
        }
        String from = fromName(outer);
        outer.query(base -> {
            if (base.getColumns().isEmpty()) {
                base.select(from + ".*");
            }
            selects.forEach(select -> select.accept(base));
        });
        if (!existsCasts.isEmpty()) {
            outer.withCasts(existsCasts);
        }
    }

    /**
     * Checks the function, the column and every relation name of a call without building a query
     * or touching a connection, so a call that will run no query still rejects a bad name.
     *
     * @param model the model the relations are named on, never {@code null}
     * @param relations the constraints keyed by raw name, as for
     *     {@link #add(EloquentBuilder, Map, Object, String)}, never {@code null}
     * @param column the aggregated column, never {@code null}
     * @param function the aggregate function, or {@code null} for one related row's column
     * @throws NullPointerException if an argument other than {@code function}, or a name, is
     *     {@code null}
     * @throws IllegalArgumentException if the function is not one of the six, or the column is
     *     neither a {@code String} nor an {@link Expression}
     * @throws RelationNotFoundException if a name is unknown or is a dot path
     */
    static void check(Model model, Map<String, Consumer<EloquentBuilder<?>>> relations, Object column,
            String function) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(column, "column");
        if (relations.isEmpty()) {
            return;
        }
        checkFunction(function, model);
        columnText(column);
        List<String> known = Access.eloquent().relationMap(model).names();
        for (String key : relations.keySet()) {
            String name = relationName(Objects.requireNonNull(key, "relation name"));
            if (name.indexOf('.') >= 0 || !known.contains(name)) {
                throw new RelationNotFoundException(model.getClass(), key, known);
            }
        }
    }

    // "posts as total" names "posts"; any other shape is the name itself.
    private static String relationName(String key) {
        String[] segments = key.split(" ", -1);
        if (segments.length == ALIAS_SEGMENTS && AS.equals(segments[1].toLowerCase(Locale.ROOT))) {
            return segments[0];
        }
        return key;
    }

    private static String checkFunction(String function, Model model) {
        if (function == null) {
            return null;
        }
        String fn = function.toLowerCase(Locale.ROOT);
        if (!FUNCTIONS.contains(fn)) {
            String shown = function.length() > FUNCTION_SHOWN
                    ? function.substring(0, FUNCTION_SHOWN) + "..."
                    : function;
            throw new IllegalArgumentException("Unknown aggregate function [" + shown + "] for ["
                    + model.getClass().getName() + "]; use one of " + FUNCTIONS);
        }
        return fn;
    }

    private static String columnText(Object column) {
        if (column instanceof Expression) {
            return ((Expression) column).sql();
        }
        if (column instanceof String) {
            return (String) column;
        }
        throw new IllegalArgumentException("An aggregate column is a String or an Expression, not ["
                + column.getClass().getName() + "]");
    }

    // Every name is resolved before anything is built, so an unknown name or a dot path
    // refuses the whole call with the outer query untouched.
    private static List<Entry> resolve(EloquentBuilder<?> outer, Map<String, Consumer<EloquentBuilder<?>>> relations) {
        Model model = outer.getModel();
        List<Entry> entries = new ArrayList<>(relations.size());
        for (Map.Entry<String, Consumer<EloquentBuilder<?>>> raw : relations.entrySet()) {
            String key = Objects.requireNonNull(raw.getKey(), "relation name");
            String name = key;
            String alias = null;
            String[] segments = key.split(" ", -1);
            if (segments.length == ALIAS_SEGMENTS && AS.equals(segments[1].toLowerCase(Locale.ROOT))) {
                name = segments[0];
                alias = segments[2];
            }
            if (name.indexOf('.') >= 0) {
                throw new RelationNotFoundException(model.getClass(), key, RelationRegistry.of(model).names());
            }
            entries.add(new Entry(name, alias, raw.getValue(), model.relation(name)));
        }
        return entries;
    }

    private static Consumer<Builder> subselect(EloquentBuilder<?> outer, Entry entry, Object column, String fn,
            String columnText, Map<String, Object> existsCasts) {
        EloquentBuilder<?> sub = entry.relation.existenceQuery(outer, Access.eloquent().aliases(outer));
        Access.eloquent().shareAliases(outer, sub);
        if (entry.constraint != null) {
            ExistenceConstraint.run(sub, entry.constraint);
        }
        entry.relation.completeExistence(sub);
        prepare(sub, entry.name, fn);
        String expression = expression(sub, column, fn);
        sub.query(base -> base.select(Expression.raw(expression)));
        String alias = entry.alias != null ? entry.alias : snakeAlias(entry.name, fn, columnText);
        Builder built = sub.toBase();
        if (EXISTS.equals(fn)) {
            existsCasts.put(alias, Casts.bool());
            String sql = "exists(" + built.toSql() + ") as " + built.getGrammar().wrap(alias);
            List<Object> bindings = built.getBindings();
            return base -> base.selectRaw(sql, bindings);
        }
        return base -> base.selectSub(built, alias);
    }

    // A value aggregate is one row, so it is prepared like a count existence query. An exists
    // aggregate renders EXISTS, so, as for whereHas, only its orders are dropped and its limit and
    // offset stay. One related row's column takes limit 1 in place of the constraint's limit.
    private static void prepare(EloquentBuilder<?> sub, String relation, String fn) {
        if (EXISTS.equals(fn)) {
            sub.query(Builder::clearColumns);
            sub.query(Builder::reorder);
            return;
        }
        ExistenceConstraint.prepareValue(sub, relation, "aggregate");
        if (fn == null) {
            sub.query(base -> base.limit(1));
        }
    }

    // Laravel qualifies a bare column with the self alias by peeking the counter; here the
    // existence query has already aliased the related model, so qualifyColumn names that alias.
    private static String expression(EloquentBuilder<?> sub, Object column, String fn) {
        String value;
        if (column instanceof Expression) {
            value = ((Expression) column).sql();
        } else {
            String name = (String) column;
            value = STAR.equals(name) ? STAR : sub.toBase().getGrammar().wrap(sub.getModel().qualifyColumn(name));
        }
        if (fn == null || EXISTS.equals(fn)) {
            return value;
        }
        return fn + "(" + value + ")";
    }

    /**
     * Returns the name the outer query's rows are selected through: the alias of
     * {@code "users as u"}, the table otherwise, the model's table when the query reads a
     * subquery.
     *
     * @param outer the outer query, never {@code null}
     * @return the name, never {@code null}
     */
    static String fromName(EloquentBuilder<?> outer) {
        String from = outer.toBase().getFrom();
        if (from == null) {
            return outer.getModel().getTable();
        }
        int alias = from.toLowerCase(Locale.ROOT).lastIndexOf(TABLE_ALIAS);
        return alias < 0 ? from.trim() : from.substring(alias + TABLE_ALIAS.length()).trim();
    }

    /**
     * Returns the default alias of an aggregate, as Laravel's
     * {@code Str::snake(preg_replace('/[^[:alnum:][:space:]_]/u', '', "$name $function " . strtolower($column)))}.
     *
     * @param name the relation name, never {@code null}
     * @param function the lower-case function, or {@code null} for none
     * @param column the column text or expression text, never {@code null}
     * @return the alias, such as {@code posts_max_votes}, never {@code null}
     */
    static String snakeAlias(String name, String function, String column) {
        String text = name + " " + (function == null ? "" : function) + " " + column.toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints().filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c) || c == '_')
                .forEach(kept::appendCodePoint);
        return snake(kept.toString());
    }

    // Str::snake: ctype_lower text is unchanged; otherwise ucwords, drop whitespace, put "_"
    // before each ASCII capital that follows another character, lower-case everything.
    private static String snake(String text) {
        if (isAsciiLower(text)) {
            return text;
        }
        StringBuilder words = new StringBuilder(text.length());
        boolean isWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            words.append(isWordStart && c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c);
            isWordStart = UCWORDS_DELIMITERS.indexOf(c) >= 0;
        }
        StringBuilder snaked = new StringBuilder(words.length() + 8);
        words.codePoints().filter(c -> !Character.isWhitespace(c)).forEach(c -> {
            if (c >= 'A' && c <= 'Z' && snaked.length() > 0) {
                snaked.append('_');
            }
            snaked.appendCodePoint(c);
        });
        return snaked.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiLower(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}

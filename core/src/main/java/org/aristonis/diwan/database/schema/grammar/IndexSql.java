package org.aristonis.diwan.database.schema.grammar;

import org.aristonis.diwan.database.Internal;
import org.aristonis.diwan.database.schema.Collation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Writes the column list and the {@code WHERE} clause of an index command for a grammar.
 *
 * <p>Quoting and the database's own spelling of booleans and collations come from the grammar's
 * protected hooks.
 */
@Internal
public final class IndexSql {

    private IndexSql() {
    }

    /**
     * Writes the index columns with their collation and descending order, such as
     * {@code "a" collate nocase desc, "b"}.
     *
     * @param grammar the grammar that quotes names, never {@code null}
     * @param command the index command, never {@code null}
     * @return the column list, never {@code null}; ascending order, the default, is not written
     */
    public static String parts(SchemaGrammar grammar, Command command) {
        return columnParts(grammar, command);
    }

    /**
     * Writes the index columns for any grammar layer; see {@link #parts(SchemaGrammar, Command)}.
     *
     * @param grammar the grammar layer that quotes names, never {@code null}
     * @param command the index command, never {@code null}
     * @return the column list, never {@code null}
     */
    static String columnParts(SchemaTypes grammar, Command command) {
        Map<String, String> orders = map(command, Command.ORDERS);
        Map<String, String> collations = map(command, Command.COLLATIONS);
        StringBuilder sql = new StringBuilder();
        for (String column : command.strings(Command.COLUMNS)) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(grammar.wrap(column));
            String collation = collations.get(column);
            if (collation != null) {
                sql.append(" collate ").append(grammar.wrap(grammar.collationName(Collation.of(collation))));
            }
            if ("desc".equals(orders.get(column))) {
                sql.append(" desc");
            }
        }
        return sql.toString();
    }

    /**
     * Writes the {@code WHERE} clause of a partial index.
     *
     * @param grammar the grammar that quotes names and values, never {@code null}
     * @param command the index command, never {@code null}
     * @return an empty string, or a space followed by {@code where} and the conditions joined by
     *     {@code and}
     */
    public static String where(SchemaGrammar grammar, Command command) {
        return whereClause(grammar, command);
    }

    /**
     * Writes the {@code WHERE} clause for any grammar layer; see {@link #where(SchemaGrammar, Command)}.
     *
     * @param grammar the grammar layer that quotes names and values, never {@code null}
     * @param command the index command, never {@code null}
     * @return an empty string, or a space followed by {@code where} and the conditions joined by
     *     {@code and}
     */
    static String whereClause(SchemaTypes grammar, Command command) {
        Object value = command.get(Command.WHERE).orElse(null);
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder(" where ");
        boolean isFirst = true;
        for (Object element : (List<?>) value) {
            IndexCondition condition = (IndexCondition) element;
            if (!isFirst) {
                sql.append(" and ");
            }
            isFirst = false;
            sql.append(grammar.wrap(condition.column()));
            switch (condition.kind()) {
                case NULL:
                    sql.append(" is null");
                    break;
                case NOT_NULL:
                    sql.append(" is not null");
                    break;
                default:
                    sql.append(" = ").append(valueLiteral(grammar, condition.value()));
            }
        }
        return sql.toString();
    }

    /**
     * Writes a checked condition value as SQL.
     *
     * @param grammar the grammar that spells booleans, never {@code null}
     * @param value a text, boolean or number the index definition accepted, never {@code null}
     * @return the literal; text in single quotes with each single quote doubled, numbers unquoted
     * @throws IllegalArgumentException if text holds a NUL character
     */
    public static String literal(SchemaGrammar grammar, Object value) {
        return valueLiteral(grammar, value);
    }

    /**
     * Writes a checked condition value as SQL for any grammar layer; see
     * {@link #literal(SchemaGrammar, Object)}.
     *
     * @param grammar the grammar layer that spells booleans, never {@code null}
     * @param value a text, boolean or number the index definition accepted, never {@code null}
     * @return the literal, never {@code null}
     * @throws IllegalArgumentException if text holds a NUL character
     */
    static String valueLiteral(SchemaTypes grammar, Object value) {
        if (value instanceof Boolean) {
            return grammar.booleanLiteral((Boolean) value);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return grammar.literal(value.toString());
    }

    private static Map<String, String> map(Command command, String key) {
        Object value = command.get(key).orElse(null);
        if (!(value instanceof Map)) {
            return java.util.Collections.emptyMap();
        }
        @SuppressWarnings("unchecked")
        Map<String, String> typed = (Map<String, String>) value;
        return typed;
    }
}

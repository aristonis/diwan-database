package org.aristonis.diwan.database.schema.grammar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexSqlTest {

    private static final SchemaGrammar GRAMMAR = new SchemaGrammar() {
    };

    private static Command command(List<IndexCondition> where, Map<String, String> orders,
            Map<String, String> collations) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(Command.INDEX, "i");
        attributes.put(Command.COLUMNS, Arrays.asList("a", "b"));
        if (where != null) {
            attributes.put(Command.WHERE, where);
        }
        if (orders != null) {
            attributes.put(Command.ORDERS, orders);
        }
        if (collations != null) {
            attributes.put(Command.COLLATIONS, collations);
        }
        return new Command(Command.PLAIN_INDEX, attributes);
    }

    private static IndexCondition equals(String column, Object value) {
        return new IndexCondition(column, IndexCondition.Kind.EQUALS, value);
    }

    @Test
    void quoteInTextValueIsDoubled() {
        Command command = command(Collections.singletonList(equals("name", "O'Brien")), null, null);

        assertEquals(" where \"name\" = 'O''Brien'", IndexSql.where(GRAMMAR, command));
    }

    @Test
    void booleanIsOneOrZeroInTheBaseGrammar() {
        Command command = command(Arrays.asList(equals("a", true), equals("b", false)), null, null);

        assertEquals(" where \"a\" = 1 and \"b\" = 0", IndexSql.where(GRAMMAR, command));
    }

    @Test
    void conditionsAreJoinedByAnd() {
        Command command = command(Arrays.asList(equals("a", "x"),
                new IndexCondition("b", IndexCondition.Kind.NULL, null),
                new IndexCondition("c", IndexCondition.Kind.NOT_NULL, null)), null, null);

        assertEquals(" where \"a\" = 'x' and \"b\" is null and \"c\" is not null", IndexSql.where(GRAMMAR, command));
    }

    @Test
    void noConditionsGiveNoWhere() {
        assertEquals("", IndexSql.where(GRAMMAR, command(null, null, null)));
    }

    @Test
    void partsWriteCollationAndDescOnly() {
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put("a", "desc");
        orders.put("b", "asc");
        Map<String, String> collations = Collections.singletonMap("a", "nocase");

        assertEquals("\"a\" collate \"nocase\" desc, \"b\"", IndexSql.parts(GRAMMAR, command(null, orders, collations)));
    }

    @Test
    void partsWithoutOptionsAreTheColumns() {
        assertEquals("\"a\", \"b\"", IndexSql.parts(GRAMMAR, command(null, null, null)));
    }

    @Test
    void numbersAreUnquotedAndBigDecimalIsPlain() {
        assertEquals("12345678901234567890.05", IndexSql.literal(GRAMMAR, new BigDecimal("1.234567890123456789005E+19")));
        assertEquals("1.5", IndexSql.literal(GRAMMAR, 1.5d));
        assertEquals("7", IndexSql.literal(GRAMMAR, 7L));
    }
}

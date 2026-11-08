package org.aristonis.diwan.database.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the base multi-table truncate. Package-private: grammars reach it through
 * {@link QueryGrammar#compileTruncateMany(List, boolean)}.
 */
final class TruncateGrammar {

    private TruncateGrammar() {
    }

    /**
     * Compiles one {@code truncate table "t"} per table.
     *
     * @param grammar the grammar that wraps table names, never {@code null}
     * @param tables the table names, never {@code null}
     * @param cascade whether the caller asked for cascade
     * @return each statement mapped to no bindings, in table order, unmodifiable, never {@code null}
     * @throws UnsupportedOperationException if {@code cascade} is {@code true}
     */
    static Map<String, List<Object>> eachTable(GrammarQuoting grammar, List<String> tables, boolean cascade) {
        if (cascade) {
            throw new UnsupportedOperationException("This grammar cannot truncate with cascade");
        }
        Map<String, List<Object>> statements = new LinkedHashMap<>();
        for (String table : tables) {
            statements.put("truncate table " + grammar.wrapTable(table), Collections.emptyList());
        }
        return Collections.unmodifiableMap(statements);
    }
}

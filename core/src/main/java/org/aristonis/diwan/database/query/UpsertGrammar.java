package org.aristonis.diwan.database.query;

import org.aristonis.diwan.database.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code on conflict} clause shared by the upsert of every database grammar that
 * supports one. Package-private: database grammars reach it through
 * {@link QueryGrammar#compileOnConflict(List, List, Map)}.
 */
final class UpsertGrammar {

    private UpsertGrammar() {
    }

    /**
     * Compiles {@code on conflict ("a", "b") do update set ...}, or {@code do nothing} when there
     * is nothing to set.
     *
     * @param grammar the grammar that wraps names and writes placeholders, never {@code null}
     * @param uniqueBy the columns of the unique index that decides a conflict, never empty
     * @param excluded the columns to take from the row that was refused, written as
     *     {@code "c" = "excluded"."c"}, never {@code null}
     * @param values the columns to set to a given value, in order, never {@code null}; an
     *     {@link Expression} is written as given, anything else as {@code ?}
     * @return the clause, never {@code null}
     */
    static String onConflict(GrammarQuoting grammar, List<String> uniqueBy, List<String> excluded,
            Map<String, ?> values) {
        String target = "on conflict (" + grammar.columnize(uniqueBy) + ")";
        List<String> assignments = new ArrayList<>(excluded.size() + values.size());
        for (String column : excluded) {
            String wrapped = grammar.wrap(column);
            assignments.add(wrapped + " = " + grammar.wrap("excluded") + "." + wrapped);
        }
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            assignments.add(grammar.wrap(entry.getKey()) + " = " + grammar.parameter(entry.getValue()));
        }
        if (assignments.isEmpty()) {
            return target + " do nothing";
        }
        return target + " do update set " + String.join(", ", assignments);
    }
}

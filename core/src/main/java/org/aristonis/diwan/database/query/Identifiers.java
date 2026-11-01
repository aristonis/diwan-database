package org.aristonis.diwan.database.query;

import java.util.Objects;

/**
 * Quotes SQL identifiers; the one place where both the query grammar and the schema grammar
 * escape a name.
 *
 * <p>Not meant for application code: grammars call it through their own {@code wrap} methods.
 * It is public only because Java has no access level shared between the {@code query} and
 * {@code schema} packages.
 */
public final class Identifiers {

    private Identifiers() {
    }

    /**
     * Quotes one identifier segment, doubling any quote character inside it so the name cannot end
     * the identifier early.
     *
     * @param name the identifier, never {@code null}; written as one segment, so a dot in it is
     *     part of the name
     * @param quote the quote character of the database, for example {@code '"'}
     * @return the quoted identifier, never {@code null}
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static String quote(String name, char quote) {
        Objects.requireNonNull(name, "name");
        String mark = String.valueOf(quote);
        return mark + name.replace(mark, mark + mark) + mark;
    }
}

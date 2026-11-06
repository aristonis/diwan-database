package org.aristonis.diwan.database.schema.internal;

import org.aristonis.diwan.database.Internal;
import java.util.Objects;

/** Checks table, column and index names before they reach a grammar. */
@Internal
public final class SchemaNames {

    private SchemaNames() {
    }

    /**
     * Checks a name.
     *
     * <p>Quoting makes any other character safe, so only names that cannot be quoted reliably are
     * refused. The message does not repeat the name, which may come from outside the program.
     *
     * @param kind what the name is for, used in the message
     * @param name the name, may be {@code null}
     * @return the same name
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if the name is empty or has a control character
     */
    @Internal
    public static String check(String kind, String name) {
        Objects.requireNonNull(name, kind);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The " + kind + " name is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("The " + kind + " name contains a control character");
            }
        }
        return name;
    }
}

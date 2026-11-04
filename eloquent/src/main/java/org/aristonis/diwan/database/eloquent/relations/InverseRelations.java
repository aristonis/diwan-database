package org.aristonis.diwan.database.eloquent.relations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.internal.Access;

/**
 * The name check and the name guessing behind {@code chaperone}, as Laravel's
 * {@code SupportsInverseRelations}: the inverse name must be a relation of the related model,
 * any shape, checked by name only.
 *
 * <p>Guessing tries, in order, without duplicates or empty names: the relation's bare foreign
 * key before the parent's key name, camel cased ({@code user_id} gives {@code user}); the
 * parent's {@code getForeignKey()} the same way; the parent's simple class name, camel cased;
 * {@code owner}; and {@code parent} when the parent and the related model are the same class.
 * The second and third use class names, which R8 renames on Android, so there a caller should
 * pass the name. Stateless, safe to share across threads.
 */
final class InverseRelations {

    private static final String OWNER = "owner";

    private static final String PARENT = "parent";

    // Str::studly splits on mb_split('\s+'); these are the ASCII whitespace characters.
    private static final String WHITESPACE = " \t\r\n\f\u000B";

    private InverseRelations() {
    }

    /**
     * Returns the given inverse name when the related model knows it as a relation.
     *
     * @param relation the relation the inverse is set on, never {@code null}
     * @param name the inverse relation name, never {@code null}
     * @return the name, never {@code null}
     * @throws NullPointerException if an argument, or the map the related model returns, is
     *     {@code null}
     * @throws RelationNotFoundException if the related model has no relation of that name
     */
    static String check(HasOneOrMany<?, ?> relation, String name) {
        Objects.requireNonNull(name, "name");
        List<String> known = knownNames(relation);
        if (!known.contains(name)) {
            throw new RelationNotFoundException(relation.getRelated().getClass(), name, known);
        }
        return name;
    }

    /**
     * Returns the first guessed inverse name the related model knows as a relation.
     *
     * @param relation the relation the inverse is set on, never {@code null}
     * @return the name, never {@code null}
     * @throws NullPointerException if the relation, or the map the related model returns, is
     *     {@code null}
     * @throws RelationNotFoundException if no candidate is a relation of the related model; its
     *     relation name is {@code inverse of <name> (tried: <candidates>)}
     */
    static String guess(HasOneOrMany<?, ?> relation) {
        List<String> known = knownNames(relation);
        List<String> candidates = candidates(relation);
        for (String candidate : candidates) {
            if (known.contains(candidate)) {
                return candidate;
            }
        }
        String label = relation.getRelationName() != null ? relation.getRelationName()
                : relation.getParent().getClass().getName() + "." + relation.getForeignKeyName();
        String asked = "inverse of " + label + " (tried: " + String.join(", ", candidates) + ")";
        throw new RelationNotFoundException(relation.getRelated().getClass(), asked, known);
    }

    /**
     * Returns the given pivot relation name when the pivot class declares it.
     *
     * @param sample an instance of the pivot class, never {@code null}
     * @param name the relation name, never {@code null}
     * @return the name, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     * @throws RelationNotFoundException if the pivot class declares no relation of that name
     */
    static String checkPivot(Model sample, String name) {
        Objects.requireNonNull(name, "name");
        List<String> known = declaredNames(sample);
        if (!known.contains(name)) {
            throw new RelationNotFoundException(sample.getClass(), name, known);
        }
        return name;
    }

    /**
     * Returns the first guessed relation name the pivot class declares, or {@code null} when none
     * matches.
     *
     * <p>A miss is quiet: the caller decides whether a missing inverse is an error.
     *
     * @param sample an instance of the pivot class, never {@code null}
     * @param key the pivot's key column, such as {@code user_id}, never {@code null}
     * @param model the model the key points to, never {@code null}
     * @return the name, or {@code null} when no candidate is declared
     * @throws NullPointerException if an argument is {@code null}
     */
    static String guessPivot(Model sample, String key, Model model) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(model, "model");
        List<String> known = declaredNames(sample);
        for (String candidate : pivotCandidates(key, model)) {
            if (known.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the guessing candidates in order, Laravel's {@code getPossibleInverseRelations}.
     *
     * @param relation the relation the inverse is set on, never {@code null}
     * @return the candidates, without duplicates or empty names, mutable, never {@code null}
     * @throws NullPointerException if the relation is {@code null}
     */
    static List<String> candidates(HasOneOrMany<?, ?> relation) {
        Model parent = relation.getParent();
        String keyName = parent.getKeyName();
        Set<String> names = new LinkedHashSet<>();
        names.add(camel(beforeLast(relation.getForeignKeyName(), keyName)));
        names.add(camel(beforeLast(parent.getForeignKey(), keyName)));
        names.add(camel(parent.getClass().getSimpleName()));
        names.add(OWNER);
        if (parent.getClass() == relation.getRelated().getClass()) {
            names.add(PARENT);
        }
        names.remove("");
        return new ArrayList<>(names);
    }

    /**
     * Returns the text before the last occurrence of a search, as Laravel's
     * {@code Str::beforeLast}.
     *
     * @param text the text, never {@code null}
     * @param search the search, never {@code null}
     * @return the text before the last occurrence, or the whole text when the search is empty or
     *     absent, never {@code null}
     */
    static String beforeLast(String text, String search) {
        if (search.isEmpty()) {
            return text;
        }
        int at = text.lastIndexOf(search);
        return at < 0 ? text : text.substring(0, at);
    }

    /**
     * Returns a name in camel case, as Laravel's {@code Str::camel}:
     * {@code lcfirst(Str::studly($value))}.
     *
     * @param text the text, never {@code null}
     * @return the camel case text, such as {@code blogPost} for {@code blog_post}, never
     *     {@code null}
     */
    static String camel(String text) {
        String studly = studly(text);
        if (studly.isEmpty()) {
            return studly;
        }
        // PHP lcfirst changes only an ASCII capital.
        char first = studly.charAt(0);
        return first >= 'A' && first <= 'Z'
                ? (char) (first - 'A' + 'a') + studly.substring(1)
                : studly;
    }

    // Str::studly: "-" and "_" become spaces, split on whitespace runs, Str::ucfirst each word
    // (multibyte upper case of the first character), join without separators.
    private static String studly(String text) {
        String spaced = text.replace('-', ' ').replace('_', ' ');
        StringBuilder out = new StringBuilder(spaced.length());
        boolean isWordStart = true;
        for (int i = 0; i < spaced.length();) {
            int c = spaced.codePointAt(i);
            int width = Character.charCount(c);
            if (WHITESPACE.indexOf(c) >= 0) {
                isWordStart = true;
            } else if (isWordStart) {
                out.append(spaced.substring(i, i + width).toUpperCase(Locale.ROOT));
                isWordStart = false;
            } else {
                out.appendCodePoint(c);
            }
            i += width;
        }
        return out.toString();
    }

    // The declared names, read from relations() without running a factory: the registry would
    // build every relation of the related class, and a factory there that calls chaperone()
    // (a self relation, or two classes chaperoning each other) would re-enter the registry
    // while it is still being built and never finish. Laravel's isRelation checks the name only.
    private static List<String> knownNames(HasOneOrMany<?, ?> relation) {
        return Access.eloquent().relationMap(relation.getRelated()).names();
    }

    // The pivot's declared names, read the same way as knownNames: no factory runs, so resolving
    // a chaperone cannot re-enter the registry while a relation map is still being built.
    private static List<String> declaredNames(Model sample) {
        return Access.eloquent().relationMap(Objects.requireNonNull(sample, "sample")).names();
    }

    // Laravel's guess order for a pivot key: the key without its model's key name, then the
    // model's class name; empty and repeated names are dropped.
    private static List<String> pivotCandidates(String key, Model model) {
        Set<String> names = new LinkedHashSet<>();
        names.add(camel(beforeLast(key, model.getKeyName())));
        names.add(camel(model.getClass().getSimpleName()));
        names.remove("");
        return new ArrayList<>(names);
    }
}

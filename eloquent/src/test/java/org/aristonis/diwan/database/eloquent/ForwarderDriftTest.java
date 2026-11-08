package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.aristonis.diwan.database.query.Builder;
import org.junit.jupiter.api.Test;

/**
 * Keeps the builder forwarders honest: every public where, orWhere and order method of the query
 * builder is either forwarded by {@link EloquentBuilder} with the same parameters, or listed here
 * as a deliberate omission reachable through {@code query(Consumer)}.
 */
class ForwarderDriftTest {

    /** Eloquent-only where methods: the relation existence and morph families and the Eloquent groups. */
    private static final Set<String> ELOQUENT_ONLY = new HashSet<>(Arrays.asList("whereHas", "orWhereHas",
            "whereDoesntHave", "orWhereDoesntHave", "whereRelation", "orWhereRelation", "whereGroup", "orWhereGroup", "whereHasMorph", "orWhereHasMorph",
            "whereDoesntHaveMorph", "orWhereDoesntHaveMorph", "whereMorphRelation", "orWhereMorphRelation",
            "whereMorphDoesntHaveRelation", "orWhereMorphDoesntHaveRelation", "whereMorphedTo", "orWhereMorphedTo",
            "whereNotMorphedTo", "orWhereNotMorphedTo", "whereBelongsTo", "orWhereBelongsTo"));

    private static final Set<String> OMITTED = new HashSet<>(Arrays.asList(OmittedForwarders.LIST));

    private static Set<String> signatures(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Method method : type.getMethods()) {
            String name = method.getName();
            // Bridges are kept: javac adds them to a public class for methods it inherits from a
            // package-private one, and they then stand for those methods in getMethods().
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (name.startsWith("where") || name.startsWith("orWhere") || name.startsWith("order")) {
                StringBuilder sig = new StringBuilder(name).append('(');
                for (Class<?> parameter : method.getParameterTypes()) {
                    sig.append(parameter.getSimpleName()).append(',');
                }
                out.add(sig.append(')').toString());
            }
        }
        return out;
    }

    @Test
    void everyBuilderClauseIsForwardedOrListedAsOmitted() {
        Set<String> builder = signatures(Builder.class);
        Set<String> eloquent = signatures(EloquentBuilder.class);
        eloquent.removeIf(s -> s.startsWith("whereKey")
                || ELOQUENT_ONLY.contains(s.substring(0, s.indexOf('('))));

        Set<String> missing = new TreeSet<>(builder);
        missing.removeAll(eloquent);
        missing.removeAll(OMITTED);
        Set<String> stale = new TreeSet<>(OMITTED);
        stale.removeAll(builder);
        stale.addAll(intersection(OMITTED, eloquent));
        Set<String> extra = new TreeSet<>(eloquent);
        extra.removeAll(builder);

        assertEquals(new TreeSet<String>(), missing, "forward these or list them in OmittedForwarders");
        assertEquals(new TreeSet<String>(), stale, "remove these from OmittedForwarders");
        assertEquals(new TreeSet<String>(), extra, "forwarders whose parameters differ from the builder's");
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.retainAll(b);
        return out;
    }
}

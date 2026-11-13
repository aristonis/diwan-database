package org.aristonis.diwan.database.eloquent.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A list of attribute entries a factory cycles through, one entry per model.
 *
 * <p>The model at position {@code i} takes entry {@code i % size()}. The position starts at zero on
 * every factory call, because the sequence stores no counter. Immutable and safe to share across
 * threads.
 */
public final class Sequence {

    private final List<IntFunction<Map<String, Object>>> entries;

    private Sequence(List<IntFunction<Map<String, Object>>> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * Returns a sequence that cycles through fixed entries.
     *
     * @param entries the entries, copied, never {@code null} or empty, none {@code null}
     * @return the sequence, never {@code null}
     * @throws NullPointerException if {@code entries} or an entry is {@code null}
     * @throws IllegalArgumentException if there are no entries
     */
    @SafeVarargs
    public static Sequence of(Map<String, Object>... entries) {
        Objects.requireNonNull(entries, "entries");
        List<IntFunction<Map<String, Object>>> list = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            list.add(fixed(entry));
        }
        return nonEmpty(list);
    }

    /**
     * Returns a sequence whose entries are built from the position of the model.
     *
     * @param entries the functions, each given the position of the model from zero, never
     *     {@code null} or empty, none {@code null}
     * @return the sequence, never {@code null}
     * @throws NullPointerException if {@code entries} or a function is {@code null}
     * @throws IllegalArgumentException if there are no entries
     */
    @SafeVarargs
    public static Sequence byIndex(IntFunction<Map<String, Object>>... entries) {
        Objects.requireNonNull(entries, "entries");
        List<IntFunction<Map<String, Object>>> list = new ArrayList<>();
        for (IntFunction<Map<String, Object>> entry : entries) {
            list.add(Objects.requireNonNull(entry, "entry"));
        }
        return nonEmpty(list);
    }

    /**
     * Returns the product of lists: one entry for each combination, the first list changing
     * slowest. Keys of a later list win over equal keys of an earlier one.
     *
     * @param lists the lists, never {@code null} or empty, none {@code null} or empty
     * @return the sequence, never {@code null}
     * @throws NullPointerException if {@code lists}, a list or an entry is {@code null}
     * @throws IllegalArgumentException if there is no list, a list is empty or the product has more
     *     entries than an {@code int} counts
     */
    @SafeVarargs
    public static Sequence crossJoin(List<Map<String, Object>>... lists) {
        Objects.requireNonNull(lists, "lists");
        if (lists.length == 0) {
            throw new IllegalArgumentException("crossJoin needs at least one list");
        }
        List<List<Map<String, Object>>> all = new ArrayList<>();
        for (List<Map<String, Object>> list : lists) {
            all.add(list);
        }
        checkProduct(all);
        List<Map<String, Object>> product = Collections.singletonList(Collections.<String, Object>emptyMap());
        for (List<Map<String, Object>> list : all) {
            product = join(product, list);
        }
        List<IntFunction<Map<String, Object>>> out = new ArrayList<>();
        for (Map<String, Object> entry : product) {
            out.add(fixed(entry));
        }
        return nonEmpty(out);
    }

    /**
     * Returns a sequence with one more fixed entry at the end.
     *
     * @param entry the entry, copied, never {@code null}
     * @return a new sequence, never {@code null}
     * @throws NullPointerException if {@code entry} is {@code null}
     */
    public Sequence and(Map<String, Object> entry) {
        return added(fixed(entry));
    }

    /**
     * Returns a sequence with one more entry, built from the position of the model, at the end.
     *
     * @param entry the function, never {@code null}
     * @return a new sequence, never {@code null}
     * @throws NullPointerException if {@code entry} is {@code null}
     */
    public Sequence and(IntFunction<Map<String, Object>> entry) {
        return added(Objects.requireNonNull(entry, "entry"));
    }

    /**
     * Returns the number of entries.
     *
     * @return the count, at least one
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns the entry for the model at a position.
     *
     * @param index the position of the model, from zero
     * @return the entry, never {@code null}
     * @throws NullPointerException if the entry function returns {@code null}
     */
    Map<String, Object> at(int index) {
        Map<String, Object> entry = entries.get(index % entries.size()).apply(index);
        if (entry == null) {
            throw new NullPointerException("Sequence entry [" + index + "] is null");
        }
        return entry;
    }

    private Sequence added(IntFunction<Map<String, Object>> entry) {
        List<IntFunction<Map<String, Object>>> list = new ArrayList<>(entries);
        list.add(entry);
        return new Sequence(list);
    }

    private static Sequence nonEmpty(List<IntFunction<Map<String, Object>>> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("A sequence needs at least one entry");
        }
        return new Sequence(list);
    }

    private static IntFunction<Map<String, Object>> fixed(Map<String, Object> entry) {
        Map<String, Object> source = Objects.requireNonNull(entry, "entry");
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(source));
        return index -> copy;
    }

    private static void checkProduct(List<List<Map<String, Object>>> lists) {
        int product = 1;
        for (int i = 0; i < lists.size(); i++) {
            List<Map<String, Object>> list = Objects.requireNonNull(lists.get(i), "list");
            if (list.isEmpty()) {
                throw new IllegalArgumentException("crossJoin list at position [" + i + "] is empty");
            }
            try {
                product = Math.multiplyExact(product, list.size());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("crossJoin result is larger than the largest sequence", e);
            }
        }
    }

    private static List<Map<String, Object>> join(List<Map<String, Object>> left, List<Map<String, Object>> right) {
        List<Map<String, Object>> out = new ArrayList<>(left.size() * right.size());
        for (Map<String, Object> head : left) {
            for (Map<String, Object> tail : right) {
                Map<String, Object> merged = new LinkedHashMap<>(head);
                merged.putAll(Objects.requireNonNull(tail, "entry"));
                out.add(merged);
            }
        }
        return out;
    }
}

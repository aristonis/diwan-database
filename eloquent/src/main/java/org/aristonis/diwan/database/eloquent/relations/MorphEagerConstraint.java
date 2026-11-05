package org.aristonis.diwan.database.eloquent.relations;

import java.util.Objects;
import java.util.function.Consumer;
import org.aristonis.diwan.database.eloquent.EloquentBuilder;

/**
 * What {@code withMorph} stores in the eager load map under a morph-to relation's name: the
 * per-class {@link MorphEagerOptions} plus an optional plain constraint for every class.
 *
 * <p>As a plain {@code Consumer} it runs only the plain constraint. {@link MorphTo} recognises it
 * and reads the options per class. Merging two constraints for one path must keep the options,
 * so the merge points call {@link #combine(Consumer, Consumer)} and not
 * {@link Consumer#andThen}.
 *
 * <p>Not part of the supported API; the builder's parser merges constraints through the internal
 * bridge.
 */
final class MorphEagerConstraint implements Consumer<EloquentBuilder<?>> {

    private final MorphEagerOptions options;

    private final Consumer<EloquentBuilder<?>> plain;

    private MorphEagerConstraint(MorphEagerOptions options, Consumer<EloquentBuilder<?>> plain) {
        this.options = options;
        this.plain = plain;
    }

    /**
     * Builds a constraint from options and an optional plain constraint. The options callback runs
     * at once, so an unknown class or alias throws here.
     *
     * @param options fills the per-class options, never {@code null}
     * @param plain the constraint for every class, or {@code null} for none
     * @return the constraint, never {@code null}
     * @throws NullPointerException if {@code options} is {@code null}
     */
    static MorphEagerConstraint of(Consumer<MorphEagerOptions> options, Consumer<EloquentBuilder<?>> plain) {
        Objects.requireNonNull(options, "options");
        MorphEagerOptions filled = new MorphEagerOptions();
        options.accept(filled);
        return new MorphEagerConstraint(filled, plain);
    }

    /**
     * Merges two constraints for one path, the first running first. When either is a
     * {@code MorphEagerConstraint} the result is a new one with both option sets and both plain
     * constraints; otherwise the result is {@code a.andThen(b)}.
     *
     * @param a the constraint added first, never {@code null}
     * @param b the constraint added second, never {@code null}
     * @return the merged constraint, never {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    static Consumer<EloquentBuilder<?>> combine(Consumer<EloquentBuilder<?>> a,
            Consumer<EloquentBuilder<?>> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (!(a instanceof MorphEagerConstraint) && !(b instanceof MorphEagerConstraint)) {
            return a.andThen(b);
        }
        MorphEagerOptions merged = new MorphEagerOptions();
        if (a instanceof MorphEagerConstraint) {
            merged.mergeFrom(((MorphEagerConstraint) a).options);
        }
        if (b instanceof MorphEagerConstraint) {
            merged.mergeFrom(((MorphEagerConstraint) b).options);
        }
        return new MorphEagerConstraint(merged, chainPlain(plainOf(a), plainOf(b)));
    }

    @Override
    public void accept(EloquentBuilder<?> query) {
        if (plain != null) {
            plain.accept(query);
        }
    }

    /** The per-class options. */
    MorphEagerOptions options() {
        return options;
    }

    private static Consumer<EloquentBuilder<?>> plainOf(Consumer<EloquentBuilder<?>> constraint) {
        return constraint instanceof MorphEagerConstraint ? ((MorphEagerConstraint) constraint).plain : constraint;
    }

    private static Consumer<EloquentBuilder<?>> chainPlain(Consumer<EloquentBuilder<?>> first,
            Consumer<EloquentBuilder<?>> second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first.andThen(second);
    }
}

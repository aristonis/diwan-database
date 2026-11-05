package org.aristonis.diwan.database.eloquent;

/**
 * Hides soft-deleted rows: adds {@code deleted_at is null} on the model's qualified deleted-at
 * column. It is attached first to every builder of a model whose {@code softDeletes()} is true.
 * Stateless.
 */
public final class SoftDeletingScope implements Scope {

    /** The key the scope is registered under, whatever the class is renamed to. */
    public static final String NAME = "softDeleting";

    static final SoftDeletingScope INSTANCE = new SoftDeletingScope();

    /** Creates the scope, so {@code withoutGlobalScope(SoftDeletingScope.class)} reads naturally. */
    public SoftDeletingScope() {
    }

    /**
     * Adds {@code deleted_at is null} to the builder.
     *
     * @param builder the builder to constrain, never {@code null}
     * @param model the model the builder reads, never {@code null}
     */
    @Override
    public void apply(EloquentBuilder<?> builder, Model model) {
        builder.whereNull(model.getQualifiedDeletedAtColumn());
    }
}

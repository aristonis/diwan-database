/**
 * Internal bridges between the Eloquent packages: not API, and may change in any release.
 *
 * <p>The relation types in {@code org.aristonis.diwan.database.eloquent.relations} keep their hooks
 * package-private, and the model layers in {@code org.aristonis.diwan.database.eloquent} keep
 * theirs. The few calls each side needs from the other go through the two interfaces here,
 * {@link org.aristonis.diwan.database.eloquent.internal.EloquentAccess} and
 * {@link org.aristonis.diwan.database.eloquent.internal.RelationAccess}. Each package registers its
 * implementation once from a static initialiser into
 * {@link org.aristonis.diwan.database.eloquent.internal.Access}, so no reflection is used and the
 * code survives R8 shrinking unchanged.
 *
 * <p>This package holds the bridges and the self-relation alias counter only. It holds no model,
 * relation or query logic, and it is left out of the published Javadoc. Application code must not
 * call it.
 */
package org.aristonis.diwan.database.eloquent.internal;

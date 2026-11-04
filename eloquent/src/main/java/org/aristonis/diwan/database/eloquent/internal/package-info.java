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
 * <p>{@link org.aristonis.diwan.database.eloquent.internal.VisitPath} is the identity path and
 * done set that serialization, touching owners and {@code push()} pass down their own recursion, so
 * a model is not entered twice on one path and, for touching and {@code push()}, not walked twice
 * in one call. {@link org.aristonis.diwan.database.eloquent.internal.TouchScope} holds the classes
 * {@code withoutTouching} ignores, per thread, restored when each scope ends.
 *
 * <p>This package holds the bridges, the self-relation alias counter, the visit path and the touch
 * scope only. It holds no model,
 * relation or query logic, and it is left out of the published Javadoc. Application code must not
 * call it.
 */
package org.aristonis.diwan.database.eloquent.internal;

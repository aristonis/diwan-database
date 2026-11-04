/**
 * Eloquent relations: the relation types a model declares, their name map, and their exceptions.
 *
 * <p>A model declares each relation as a typed method that builds it, and lists the methods in a
 * {@link org.aristonis.diwan.database.eloquent.relations.RelationMap} returned from
 * {@code relations()}, the way {@code casts()} lists casts:
 *
 * <pre>{@code
 * public final class Post extends Model {
 *     public static final ModelQuery<Post> query = ModelQuery.of(Post.class, Post::new);
 *
 *     public HasMany<Comment> comments() {
 *         return hasMany(Comment.class);
 *     }
 *
 *     protected RelationMap<Post> relations() {
 *         return RelationMap.of(Post.class).add("comments", Post::comments);
 *     }
 * }
 * }</pre>
 *
 * <p>{@code hasOne} and {@code hasMany} relations can link each child back to its parent with
 * {@code chaperone()} or {@code chaperone(name)}. Without a name the inverse is guessed from the
 * foreign key, the parent's foreign key, the parent's class name, {@code owner} and, for a self
 * relation, {@code parent}. R8 renames classes on Android, so pass the name there.
 *
 * <p>This package holds the public relation types, the name map and factory, the three relation
 * exceptions, and the package-private machinery behind eager loading, existence queries, relation
 * aggregates and their {@code load*} form, touching owners and inverse linking. It
 * does not hold relation state on a model or the builder's {@code with} and {@code has} methods;
 * those stay in {@code org.aristonis.diwan.database.eloquent}. The cycle between the two packages
 * is accepted, as it is for casts. Neither package reaches the other's package-private members
 * except through {@code org.aristonis.diwan.database.eloquent.internal}, which is not API.
 */
package org.aristonis.diwan.database.eloquent.relations;

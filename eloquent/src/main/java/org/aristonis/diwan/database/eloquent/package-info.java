/**
 * Eloquent models: one class per table over the query builder, in the style of Laravel's Eloquent.
 *
 * <p>A model extends {@link org.aristonis.diwan.database.eloquent.Model}, declares a
 * {@link org.aristonis.diwan.database.eloquent.ModelQuery} entry and overrides the protected
 * configuration methods it needs:
 *
 * <pre>{@code
 * public final class User extends Model {
 *     public static final ModelQuery<User> query = ModelQuery.of(User.class, User::new);
 *
 *     protected List<String> fillable() {
 *         return Arrays.asList("name", "email");
 *     }
 *
 *     protected RelationMap<?> relations() {
 *         return RelationMap.of(User.class).add("posts", User::posts);
 *     }
 *
 *     public HasMany<Post> posts() {
 *         return hasMany(Post.class);
 *     }
 * }
 *
 * User user = User.query.on(db).create(attributes);
 * ModelCollection<User> users = User.query.on(db).with("posts").get();
 * }</pre>
 *
 * <p>This package holds the model, its builder, collection and page types, and their exceptions.
 * It does not hold SQL: every statement goes through the core query builder, so the SQL is the
 * builder's. Casts live in {@code eloquent.casts}, the JSON codec in {@code eloquent.json} and the
 * relation types in {@code eloquent.relations}; {@code eloquent.internal} bridges the packages and
 * is not API. Scopes, soft deletes and event dispatchers are not here yet; the protected hook
 * methods on the model are the place they will attach.
 *
 * <p>Beyond reading and writing relations, a query adds relation aggregates as columns
 * ({@code withCount}, {@code withMax}, {@code withMin}, {@code withSum}, {@code withAvg},
 * {@code withExists}, {@code withAggregate}) and loaded models get them with the {@code load*}
 * methods; {@code withCasts} and {@code mergeCasts} add casts to one query's models or one
 * instance; {@code touches()} names the owners a save or delete touches, and
 * {@code withoutTouching} turns that off for named classes on the calling thread for one call;
 * {@code push()} saves a model and its loaded relations. Touching and {@code push()} visit each
 * model once per call and never lazy load. {@code touchOwners()}, {@code push()} and
 * {@code toMap()} recurse once per level of the loaded graph, so a very deep chain can overflow a
 * small thread stack.
 *
 * <p>A related model's class must have run its static initialiser before a relation names it,
 * because a class literal such as {@code Post.class} does not run it. Touch each model's
 * {@code query} entry at startup.
 */
package org.aristonis.diwan.database.eloquent;

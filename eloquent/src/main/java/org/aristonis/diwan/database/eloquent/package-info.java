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
 * }
 *
 * User user = User.query.on(db).create(attributes);
 * }</pre>
 *
 * <p>This package holds the model, its builder, collection and page types, and their exceptions.
 * It does not hold SQL: every statement goes through the core query builder, so the SQL is the
 * builder's. Casts, relations, scopes, soft deletes and event dispatchers are not here yet; the
 * protected hook methods on the model are the place they will attach.
 */
package org.aristonis.diwan.database.eloquent;

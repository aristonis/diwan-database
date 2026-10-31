/**
 * Defines the {@link org.aristonis.diwan.database.Connection} API every part of Diwan Database sends
 * SQL through, and the exceptions it reports.
 *
 * <p>This package holds {@link org.aristonis.diwan.database.Connection}, its
 * {@link org.aristonis.diwan.database.ConnectionBuilder}, the transaction and pretend callback
 * types, {@link org.aristonis.diwan.database.LoggedQuery}, the unchecked exceptions such as
 * {@link org.aristonis.diwan.database.QueryException}, and the package-private implementations
 * of {@code Connection}. Callers depend on the interfaces only.
 *
 * <p>This package does not hold database drivers, which live in
 * {@link org.aristonis.diwan.database.driver} and the adapter packages, nor any SQL grammar.
 */
package org.aristonis.diwan.database;

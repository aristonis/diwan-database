/**
 * Defines the {@link org.aristonis.photisma.database.Connection} API every part of Photisma sends
 * SQL through, and the exceptions it reports.
 *
 * <p>This package holds {@link org.aristonis.photisma.database.Connection}, its
 * {@link org.aristonis.photisma.database.ConnectionBuilder}, the transaction and pretend callback
 * types, {@link org.aristonis.photisma.database.LoggedQuery}, the unchecked exceptions such as
 * {@link org.aristonis.photisma.database.QueryException}, and the package-private implementations
 * of {@code Connection}. Callers depend on the interfaces only.
 *
 * <p>This package does not hold database drivers, which live in
 * {@link org.aristonis.photisma.database.driver} and the adapter packages, nor any SQL grammar.
 */
package org.aristonis.photisma.database;

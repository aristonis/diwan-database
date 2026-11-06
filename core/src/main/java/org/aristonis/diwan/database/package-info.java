/**
 * Defines the {@link org.aristonis.diwan.database.Connection} API every part of Diwan Database sends
 * SQL through.
 *
 * <p>This package holds {@link org.aristonis.diwan.database.Connection}, its
 * {@link org.aristonis.diwan.database.ConnectionBuilder}, the transaction and pretend callback
 * types, {@link org.aristonis.diwan.database.LoggedQuery} and
 * {@link org.aristonis.diwan.database.Expression}. The exceptions it reports live in
 * {@link org.aristonis.diwan.database.exception}, its events in
 * {@link org.aristonis.diwan.database.event}, and the implementations of {@code Connection} in an
 * internal package. Callers depend on the interfaces only.
 *
 * <p>This package does not hold database drivers, which live in
 * {@link org.aristonis.diwan.database.driver} and the adapter packages, nor any SQL grammar.
 */
package org.aristonis.diwan.database;

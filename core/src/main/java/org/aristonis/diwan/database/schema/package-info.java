/**
 * Schema building: describe tables with a {@link org.aristonis.diwan.database.schema.Blueprint}
 * and run them through a {@link org.aristonis.diwan.database.schema.Schema}.
 *
 * <p>The SQL for each database comes from a
 * {@link org.aristonis.diwan.database.schema.grammar.SchemaGrammar}, which lives in that database's
 * module, for example {@code diwan-database-sqlite}. The core never depends on a grammar
 * module; the application passes the grammar in.
 */
package org.aristonis.diwan.database.schema;

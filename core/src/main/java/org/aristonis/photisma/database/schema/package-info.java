/**
 * Schema building: describe tables with a {@link org.aristonis.photisma.database.schema.Blueprint}
 * and run them through a {@link org.aristonis.photisma.database.schema.Schema}.
 *
 * <p>The SQL for each database comes from a
 * {@link org.aristonis.photisma.database.schema.SchemaGrammar}, which lives in that database's
 * module, for example {@code photisma-database-sqlite}. The core never depends on a grammar
 * module; the application passes the grammar in.
 */
package org.aristonis.photisma.database.schema;

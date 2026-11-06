/**
 * The query builder: build reads and writes fluently and compile them to SQL for one database.
 *
 * <p>Start from a {@link org.aristonis.diwan.database.query.QueryFactory}, which ties a
 * connection to the {@link org.aristonis.diwan.database.query.QueryGrammar} and
 * {@link org.aristonis.diwan.database.query.Processor} of its database. Each database module
 * supplies its own grammar and processor; the core never depends on one and holds only the base
 * grammar, which writes Laravel's generic SQL.
 *
 * <p>This package holds the builder, its clause records and the base grammar; raw SQL is an
 * {@link org.aristonis.diwan.database.Expression} from the root package. It does not hold
 * database-specific SQL,
 * schema building, or a row type: rows are plain maps, as {@link org.aristonis.diwan.database.Connection}
 * returns them.
 */
package org.aristonis.diwan.database.query;

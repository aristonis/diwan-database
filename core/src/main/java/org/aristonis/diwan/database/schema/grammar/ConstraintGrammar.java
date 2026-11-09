package org.aristonis.diwan.database.schema.grammar;

import java.util.List;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.schema.Blueprint;

/**
 * Compiles the index, key and check commands of a blueprint.
 *
 * <p>Split out of {@link SchemaGrammar} to keep that class small; it is not part of the public API.
 */
abstract class ConstraintGrammar extends SchemaTypes {

    /** Creates the constraint layer of a grammar. */
    protected ConstraintGrammar() {
    }

    /**
     * Compiles {@link Command#UNIQUE}.
     *
     * @implSpec The base returns {@code create unique index <index> on <table> (<columns>)}, with {@code where} for a
     *     partial index.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileUnique(Blueprint blueprint, Command command, Connection connection) {
        // Safe cast: SchemaGrammar is the only subclass of this package-private class.
        return "create unique index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table())
                + " (" + IndexSql.parts((SchemaGrammar) this, command) + ")"
                + IndexSql.where((SchemaGrammar) this, command);
    }

    /**
     * Compiles {@link Command#PLAIN_INDEX}.
     *
     * @implSpec The base returns {@code create index <index> on <table> (<columns>)}, with {@code where} for a
     *     partial index.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     */
    protected String compileIndex(Blueprint blueprint, Command command, Connection connection) {
        // Safe cast: SchemaGrammar is the only subclass of this package-private class.
        return "create index " + wrap(command.string(Command.INDEX)) + " on " + wrapTable(blueprint.table())
                + " (" + IndexSql.parts((SchemaGrammar) this, command) + ")"
                + IndexSql.where((SchemaGrammar) this, command);
    }

    /**
     * Compiles {@link Command#DROP_INDEX} and {@link Command#DROP_UNIQUE}.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statement, never {@code null}
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected String compileDropIndex(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#PRIMARY}: the primary key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compilePrimary(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#FOREIGN}: a foreign key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileForeign(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_PRIMARY}: dropping the primary key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropPrimary(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_FOREIGN}: dropping a foreign key.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropForeign(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#RENAME_INDEX}: renaming an index.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileRenameIndex(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#CHECK}: adding a named table check.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileCheck(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }

    /**
     * Compiles {@link Command#DROP_CHECK}: dropping a named table check.
     *
     * @implSpec The base refuses the command.
     *
     * @param blueprint the blueprint, never {@code null}
     * @param command the command, never {@code null}
     * @param connection the connection, never {@code null}
     * @return the statements, never {@code null}, may be empty
     * @throws UnsupportedOperationException if the grammar does not support the command
     */
    protected List<String> compileDropCheck(Blueprint blueprint, Command command, Connection connection) {
        throw unsupported("The command " + command.name());
    }
}

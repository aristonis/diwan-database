package org.aristonis.diwan.database.pgsql;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aristonis.diwan.database.query.Builder;
import org.aristonis.diwan.database.query.Processor;

/**
 * Processes PostgreSQL query results, a hand port of Laravel's
 * {@code Illuminate\Database\Query\Processors\PostgresProcessor}.
 *
 * <p>Instances hold no state and are safe to share between threads.
 */
public class PostgresProcessor extends Processor {

    /** Creates a processor. */
    public PostgresProcessor() {
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Runs {@code sql}, an {@code insert ... returning} statement, as a read on
     *     {@code query.getConnection()} and returns the {@code sequence} column of the row it
     *     returns.
     * @throws IllegalStateException if the statement returns no row or no {@code sequence} column,
     *     for example when the connection only pretends to run statements
     */
    @Override
    public Object processInsertGetId(Builder query, String sql, List<Object> values, String sequence) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(sequence, "sequence");
        Map<String, Object> row = query.getConnection().selectOne(sql, values)
                .orElseThrow(() -> new IllegalStateException("The insert returned no row"));
        if (!row.containsKey(sequence)) {
            throw new IllegalStateException("The insert returned no column named " + sequence);
        }
        return toId(row.get(sequence));
    }
}

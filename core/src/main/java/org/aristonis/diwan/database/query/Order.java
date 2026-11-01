package org.aristonis.diwan.database.query;

/**
 * One entry of an order by clause. The entries are final classes rather than records or a sealed
 * hierarchy, for Android API 28, as for {@link Where}.
 */
public interface Order {

    /**
     * A column and its direction.
     */
    final class Column implements Order {
        private final String column;
        private final String direction;

        /**
         * Creates the entry.
         *
         * @param column the column name
         * @param direction {@code asc} or {@code desc}
         */
        public Column(String column, String direction) {
            this.column = column;
            this.direction = direction;
        }

        /**
         * Returns the column name.
         *
         * @return the column name
         */
        public String column() {
            return column;
        }

        /**
         * Returns the direction.
         *
         * @return {@code asc} or {@code desc}
         */
        public String direction() {
            return direction;
        }
    }

    /**
     * Trusted SQL written as given, for example {@code RANDOM()}.
     */
    final class Raw implements Order {
        private final String sql;

        /**
         * Creates the entry.
         *
         * @param sql the SQL
         */
        public Raw(String sql) {
            this.sql = sql;
        }

        /**
         * Returns the SQL.
         *
         * @return the SQL
         */
        public String sql() {
            return sql;
        }
    }

    /**
     * A subquery and its direction: {@code (select ...) desc}. The SQL was compiled when the entry
     * was added, as Laravel does, and its bindings went to the order bucket at that moment.
     */
    final class Sub implements Order {
        private final String sql;
        private final String direction;

        /**
         * Creates the entry.
         *
         * @param sql the subquery's SQL, without parentheses
         * @param direction {@code asc} or {@code desc}
         */
        public Sub(String sql, String direction) {
            this.sql = sql;
            this.direction = direction;
        }

        /**
         * Returns the subquery's SQL.
         *
         * @return the SQL, without parentheses
         */
        public String sql() {
            return sql;
        }

        /**
         * Returns the direction.
         *
         * @return {@code asc} or {@code desc}
         */
        public String direction() {
            return direction;
        }
    }
}

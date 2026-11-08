package org.aristonis.diwan.database.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aristonis.diwan.database.Connection;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.migrations.Migration;
import org.aristonis.diwan.database.schema.Schema;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlite.SQLiteDataSource;

/** Migrations, models and databases shared by the testing-module tests. */
final class Fixtures {

    /** How often each migration ran since the last {@link #resetCounts()}. */
    static final AtomicInteger USERS_RUNS = new AtomicInteger();
    static final AtomicInteger POSTS_RUNS = new AtomicInteger();
    static final AtomicInteger TAGS_RUNS = new AtomicInteger();

    private Fixtures() {
    }

    static void resetCounts() {
        USERS_RUNS.set(0);
        POSTS_RUNS.set(0);
        TAGS_RUNS.set(0);
    }

    /** The users and posts migrations; posts refer to users. */
    static List<Migration> base() {
        return Arrays.asList(new CreateUsers(), new CreatePosts());
    }

    /** The base migrations plus a tags table. */
    static List<Migration> withTags() {
        return Arrays.asList(new CreateUsers(), new CreatePosts(), new CreateTags());
    }

    /** Opens a SQLite database in a new temporary file, with foreign keys enforced. */
    static Connection sqlite(String name) {
        return sqlite(name, "");
    }

    /** Opens a SQLite database in a new temporary file, with foreign keys enforced and a table prefix. */
    static Connection sqlite(String name, String prefix) {
        try {
            Path file = Files.createTempFile("diwan-testing", ".db");
            file.toFile().deleteOnExit();
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + file + "?foreign_keys=true");
            return Connection.builder().name(name).dataSource(dataSource).tablePrefix(prefix).build();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create a temporary SQLite file", e);
        }
    }

    /** Opens a new database on the throwaway PostgreSQL, or skips the test when there is none. */
    static Connection postgres(String name) {
        return postgres(name, "");
    }

    /** Opens a new database on the throwaway PostgreSQL, with a table prefix. */
    static Connection postgres(String name, String prefix) {
        return Connection.builder().name(name).dataSource(PostgresServer.dataSource(PostgresServer.newDatabaseName()))
                .tablePrefix(prefix).build();
    }

    /** Opens a new database whose search path is {@code public} then {@code extra}. */
    static Connection postgresWithExtraSchema(String name) {
        PGSimpleDataSource source = PostgresServer.dataSource(PostgresServer.newDatabaseName());
        source.setCurrentSchema("public,extra");
        return Connection.builder().name(name).dataSource(source).build();
    }

    /** The base migrations plus a table in the {@code extra} schema and a {@code spatial_ref_sys} table. */
    static List<Migration> withExtraSchema() {
        return Arrays.asList(new CreateUsers(), new CreatePosts(), new CreateExtras());
    }

    /** The base migrations plus one that fails. */
    static List<Migration> failing() {
        return Arrays.asList(new CreateUsers(), new Failing());
    }

    /** Creates {@code extra.audit} and {@code spatial_ref_sys}, for PostgreSQL. */
    static final class CreateExtras extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000004_create_extras";
        }

        @Override
        public void up(Schema schema) {
            schema.statement("create schema if not exists extra");
            schema.statement("create table extra.audit (id serial primary key, note text)");
            schema.statement("create table if not exists spatial_ref_sys (id int)");
        }
    }

    /** A migration that always fails. */
    static final class Failing extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000005_failing";
        }

        @Override
        public void up(Schema schema) {
            throw new IllegalStateException("migration boom");
        }
    }

    /** Creates {@code users}: a key, a name, a password and a soft-delete column. */
    static final class CreateUsers extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000001_create_users";
        }

        @Override
        public void up(Schema schema) {
            USERS_RUNS.incrementAndGet();
            schema.create("users", table -> {
                table.id();
                table.string("name");
                table.string("password").nullable();
                table.timestamp("deleted_at").nullable();
            });
        }
    }

    /** Creates {@code posts}, each referring to a user. */
    static final class CreatePosts extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000002_create_posts";
        }

        @Override
        public void up(Schema schema) {
            POSTS_RUNS.incrementAndGet();
            schema.create("posts", table -> {
                table.id();
                table.foreignId("user_id").constrained();
                table.string("title");
            });
        }
    }

    /** Creates {@code tags}, a table whose model does not soft-delete. */
    static final class CreateTags extends Migration {
        @Override
        public String name() {
            return "2026_01_01_000003_create_tags";
        }

        @Override
        public void up(Schema schema) {
            TAGS_RUNS.incrementAndGet();
            schema.create("tags", table -> {
                table.id();
                table.string("label");
            });
        }
    }

    /** A soft-deleting model with a hidden password. */
    static final class User extends Model {
        static final ModelQuery<User> QUERY = ModelQuery.of(User.class, User::new);

        @Override
        protected String table() {
            return "users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }

        @Override
        protected List<String> hidden() {
            return Collections.singletonList("password");
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }

    /** A model without soft deletes. */
    static final class Tag extends Model {
        static final ModelQuery<Tag> QUERY = ModelQuery.of(Tag.class, Tag::new);

        @Override
        protected String table() {
            return "tags";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected List<String> guarded() {
            return Collections.emptyList();
        }
    }
}

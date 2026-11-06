package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code touch()} on a has-many-through relation sets the update timestamp of the far rows it
 * reads: a soft-deleted through parent keeps its rows untouched, and a touch scope switches the
 * touch off.
 */
class ThroughTouchTest {

    private static final String OLD = "2000-01-01 00:00:00";
    private static final String TRASHED_AT = "2020-01-01 00:00:00";

    /** A country that reaches articles through its users. */
    static final class TouchCountry extends Model {
        static final ModelQuery<TouchCountry> QUERY = ModelQuery.of(TouchCountry.class, TouchCountry::new);

        @Override
        protected String table() {
            return "touch_countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected RelationMap<?> relations() {
            return RelationMap.of(TouchCountry.class).add("articles", TouchCountry::articles);
        }

        HasManyThrough<TouchArticle> articles() {
            return hasManyThrough(TouchArticle.class, TouchUser.class, "country_id", "user_id", "id", "id");
        }
    }

    /** A soft-deleting user, the through parent. */
    static final class TouchUser extends Model {
        static final ModelQuery<TouchUser> QUERY = ModelQuery.of(TouchUser.class, TouchUser::new);

        @Override
        protected String table() {
            return "touch_users";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        @Override
        protected boolean softDeletes() {
            return true;
        }
    }

    /** An article keeping timestamps. */
    static final class TouchArticle extends Model {
        static final ModelQuery<TouchArticle> QUERY = ModelQuery.of(TouchArticle.class, TouchArticle::new);

        @Override
        protected String table() {
            return "touch_articles";
        }
    }

    private RelationDb db;

    @BeforeEach
    void open() throws SQLException {
        db = new RelationDb();
        db.real.statement("create table touch_countries (id integer primary key, name varchar)");
        db.real.statement("create table touch_users (id integer primary key, country_id integer, deleted_at varchar)");
        db.real.statement("create table touch_articles (id integer primary key, user_id integer,"
                + " created_at varchar, updated_at varchar)");
        db.real.statement("insert into touch_countries (id, name) values (1, 'us'), (2, 'fr')");
        db.real.statement("insert into touch_users (id, country_id) values (1, 1), (2, 1), (3, 2)");
        db.real.statement("insert into touch_articles (id, user_id, updated_at)"
                + " values (1, 1, '" + OLD + "'), (2, 2, '" + OLD + "'), (3, 3, '" + OLD + "')");
        if (TouchCountry.QUERY == null || TouchUser.QUERY == null || TouchArticle.QUERY == null) {
            throw new IllegalStateException("a model entry is not initialised");
        }
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private Object updatedAt(int id) {
        return db.real.selectOne("select updated_at from touch_articles where id = ?", id)
                .orElseThrow().get("updated_at");
    }

    private TouchCountry country() {
        return TouchCountry.QUERY.on(db.db).findOrFail(1);
    }

    @Test
    void touchSetsTheUpdateTimestampOfEveryFarRowOfThatParent() {
        // Arrange
        TouchCountry country = country();

        // Act
        country.articles().touch();

        // Assert
        assertNotEquals(OLD, updatedAt(1));
        assertNotEquals(OLD, updatedAt(2));
        assertEquals(OLD, updatedAt(3));
    }

    @Test
    void touchSkipsRowsOfASoftDeletedThroughParent() {
        // Arrange
        db.real.statement("update touch_users set deleted_at = '" + TRASHED_AT + "' where id = 1");
        TouchCountry country = country();

        // Act
        country.articles().touch();

        // Assert
        assertEquals(OLD, updatedAt(1));
        assertNotEquals(OLD, updatedAt(2));
    }

    @Test
    void touchDoesNothingInsideWithoutTouchingForTheFarClass() {
        // Arrange
        TouchCountry country = country();

        // Act
        Model.withoutTouching(TouchArticle.class, () -> {
            country.articles().touch();
        });

        // Assert
        assertEquals(OLD, updatedAt(1));
        assertEquals(OLD, updatedAt(2));
        assertEquals(OLD, updatedAt(3));
    }

    @Test
    void touchReachesRowsAgainAfterTheScopeEnds() {
        // Arrange
        TouchCountry country = country();
        Model.withoutTouching(TouchArticle.class, () -> {
            country.articles().touch();
        });

        // Act
        country.articles().touch();

        // Assert
        assertNotEquals(OLD, updatedAt(1));
    }
}

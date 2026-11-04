package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import org.aristonis.diwan.database.eloquent.Model;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.ModelQuery;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.Post;
import org.aristonis.diwan.database.eloquent.relations.ThroughModels.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The declaration of a through relation: the keys the builders resolve, the refusal of a null key,
 * and the connection the through model runs on. The relations sit on a local {@code Country} so the
 * default keys come from the class name.
 */
class ModelThroughRelationsTest {

    private static final String FIRST_KEY_NAME = "firstKey";
    private static final String SECOND_KEY_NAME = "secondKey";
    private static final String LOCAL_KEY_NAME = "localKey";
    private static final String SECOND_LOCAL_KEY_NAME = "secondLocalKey";
    private static final String THROUGH_NAME = "through";

    private ThroughDb db;

    @BeforeEach
    void open() throws SQLException {
        db = ThroughDb.open();
        db.seed();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void defaultKeysFollowTheModels() {
        // Arrange
        Country country = Country.QUERY.on(db.db).newModel();

        // Act
        HasManyThrough<Post> posts = country.manyByDefaults();

        // Assert
        assertEquals("country_id", posts.getFirstKeyName());
        assertEquals("users.country_id", posts.getQualifiedFirstKeyName());
        assertEquals("posts.user_id", posts.getQualifiedForeignKeyName());
        assertEquals("countries.id", posts.getQualifiedLocalKeyName());
        assertEquals("users.id", posts.getQualifiedSecondLocalKeyName());
    }

    @Test
    void everyOverloadResolvesItsDefaults() {
        // Arrange
        Country country = Country.QUERY.on(db.db).newModel();

        // Act and Assert
        assertDefaultKeys(country.manyByDefaults());
        assertDefaultKeys(country.manyByFirstKey());
        assertDefaultKeys(country.manyByFirstAndSecondKey());
        assertDefaultKeys(country.manyByFirstSecondAndLocalKey());
        assertDefaultKeys(country.manyByAllKeys());
        assertDefaultKeys(country.oneByDefaults());
        assertDefaultKeys(country.oneByFirstKey());
        assertDefaultKeys(country.oneByFirstAndSecondKey());
        assertDefaultKeys(country.oneByFirstSecondAndLocalKey());
        assertDefaultKeys(country.oneByAllKeys());
    }

    @Test
    void nullKeyIsRefused() {
        // Arrange
        Country country = Country.QUERY.on(db.db).newModel();

        // Act and Assert
        assertEquals(FIRST_KEY_NAME,
                assertThrows(NullPointerException.class, country::manyWithNullFirstKey).getMessage());
        assertEquals(SECOND_KEY_NAME,
                assertThrows(NullPointerException.class, country::manyWithNullSecondKey).getMessage());
        assertEquals(LOCAL_KEY_NAME,
                assertThrows(NullPointerException.class, country::manyWithNullLocalKey).getMessage());
        assertEquals(SECOND_LOCAL_KEY_NAME,
                assertThrows(NullPointerException.class, country::manyWithNullSecondLocalKey).getMessage());
        assertEquals(THROUGH_NAME,
                assertThrows(NullPointerException.class, country::manyWithNullThroughModel).getMessage());
    }

    @Test
    void throughModelTakesTheParentConnection() {
        // Arrange
        Country country = Country.QUERY.on(db.db).findOrFail(1);
        HasManyThrough<Post> posts = country.manyByDefaults();
        db.log.clear();

        // Act
        Model through = posts.throughParent();
        ModelCollection<Post> rows = posts.get();

        // Assert
        assertSame(db.db, through.getQueryFactory());
        assertEquals(3, rows.size());
        assertEquals(1, db.log.sql().size());
    }

    private static void assertDefaultKeys(HasOneOrManyThrough<?, ?> relation) {
        assertEquals("users.country_id", relation.getQualifiedFirstKeyName());
        assertEquals("posts.user_id", relation.getQualifiedForeignKeyName());
        assertEquals("countries.id", relation.getQualifiedLocalKeyName());
        assertEquals("users.id", relation.getQualifiedSecondLocalKeyName());
    }

    /**
     * A country with users, reaching posts through them, built with each overload of the builders.
     */
    static final class Country extends Model {
        static final ModelQuery<Country> QUERY = ModelQuery.of(Country.class, Country::new);

        @Override
        protected String table() {
            return "countries";
        }

        @Override
        protected boolean timestamps() {
            return false;
        }

        HasManyThrough<Post> manyByDefaults() {
            return hasManyThrough(Post.class, User.class);
        }

        HasManyThrough<Post> manyByFirstKey() {
            return hasManyThrough(Post.class, User.class, "country_id");
        }

        HasManyThrough<Post> manyByFirstAndSecondKey() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id");
        }

        HasManyThrough<Post> manyByFirstSecondAndLocalKey() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id");
        }

        HasManyThrough<Post> manyByAllKeys() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", "id");
        }

        HasOneThrough<Post> oneByDefaults() {
            return hasOneThrough(Post.class, User.class);
        }

        HasOneThrough<Post> oneByFirstKey() {
            return hasOneThrough(Post.class, User.class, "country_id");
        }

        HasOneThrough<Post> oneByFirstAndSecondKey() {
            return hasOneThrough(Post.class, User.class, "country_id", "user_id");
        }

        HasOneThrough<Post> oneByFirstSecondAndLocalKey() {
            return hasOneThrough(Post.class, User.class, "country_id", "user_id", "id");
        }

        HasOneThrough<Post> oneByAllKeys() {
            return hasOneThrough(Post.class, User.class, "country_id", "user_id", "id", "id");
        }

        HasManyThrough<Post> manyWithNullFirstKey() {
            return hasManyThrough(Post.class, User.class, (String) null);
        }

        HasManyThrough<Post> manyWithNullSecondKey() {
            return hasManyThrough(Post.class, User.class, "country_id", (String) null);
        }

        HasManyThrough<Post> manyWithNullLocalKey() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", (String) null);
        }

        HasManyThrough<Post> manyWithNullSecondLocalKey() {
            return hasManyThrough(Post.class, User.class, "country_id", "user_id", "id", (String) null);
        }

        HasManyThrough<Post> manyWithNullThroughModel() {
            return hasManyThrough(Post.class, null);
        }
    }
}

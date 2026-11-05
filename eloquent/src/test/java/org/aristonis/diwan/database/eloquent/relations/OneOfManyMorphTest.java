package org.aristonis.diwan.database.eloquent.relations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.aristonis.diwan.database.eloquent.ModelCollection;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Db;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Image;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Post;
import org.aristonis.diwan.database.eloquent.relations.OneOfManyModels.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One-of-many over a morph relation on SQLite: posts and videos with the same keys each see only the
 * latest image of their own type, lazily, eagerly and in existence queries.
 */
class OneOfManyMorphTest {

    private Db db;

    @BeforeEach
    void open() throws SQLException {
        db = Db.open();
        OneOfManyModels.mapAliases();
        db.post(1);
        db.post(2);
        db.video(1);
        db.video(2);
        db.image(1, "post", 1, OneOfManyModels.day(1));
        db.image(2, "post", 1, OneOfManyModels.day(2));
        db.image(3, "video", 1, OneOfManyModels.day(3));
        db.image(4, "video", 1, OneOfManyModels.day(1));
        db.image(9, "video", 1, OneOfManyModels.day(9));
        db.image(5, "video", 2, OneOfManyModels.day(5));
        db.clearLog();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    private static Long keyOf(org.aristonis.diwan.database.eloquent.Model model) {
        return OneOfManyModels.key(model);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void morphOneStaysInType() {
        // Arrange
        Post post = Post.QUERY.on(db.db).findOrFail(1);
        Video video = Video.QUERY.on(db.db).findOrFail(1);

        // Act
        Image postImage = post.latestImage().getResults();
        Image videoImage = video.latestImage().getResults();

        // Assert: image 9 is newer than any post image but belongs to a video
        assertEquals(2L, keyOf(postImage));
        assertEquals(9L, keyOf(videoImage));
    }

    @Test
    void lazyQueryFiltersByTypeInsideAndOutsideTheSubquery() {
        // Arrange
        Post post = Post.QUERY.on(db.db).findOrFail(1);
        db.clearLog();

        // Act
        post.latestImage().getResults();

        // Assert
        Db.Query read = db.queries().get(0);
        assertEquals(2, occurrences(read.sql(), "\"imageable_type\" = ?"), read.sql());
        assertTrue(read.sql().matches(".*group by [^)]*\"images\".\"imageable_type\".*"), read.sql());
        List<String> bound = new ArrayList<>();
        for (Object binding : read.bindings()) {
            bound.add(String.valueOf(binding));
        }
        Collections.sort(bound);
        assertEquals(java.util.Arrays.asList("1", "1", "post", "post"), bound);
    }

    @Test
    void eagerPicksPerOwnerAndType() {
        // Arrange and Act
        ModelCollection<Post> posts = Post.QUERY.on(db.db).query().with("latestImage").orderBy("id").get();
        ModelCollection<Video> videos = Video.QUERY.on(db.db).query().with("latestImage").orderBy("id").get();

        // Assert: post 2 has no image although video 2 has one
        assertEquals(2L, keyOf(posts.get(0).<Image>related("latestImage")));
        assertNull(posts.get(1).<Image>related("latestImage"));
        assertEquals(9L, keyOf(videos.get(0).<Image>related("latestImage")));
        assertEquals(5L, keyOf(videos.get(1).<Image>related("latestImage")));
    }

    @Test
    void oneFromMorphManyPicksTheSameRow() {
        // Arrange
        Post post = Post.QUERY.on(db.db).findOrFail(1);

        // Act
        Image viaMany = post.latestImageViaMany().getResults();

        // Assert
        assertEquals(2L, keyOf(viaMany));
    }

    @Test
    void whereHasJudgesThePickedImageOfTheOwnType() {
        // Arrange and Act
        ModelCollection<Post> postsWithImage2 = Post.QUERY.on(db.db).query()
                .whereHas("latestImage", q -> q.where("images.id", "=", 2)).get();
        ModelCollection<Post> postsWithImage9 = Post.QUERY.on(db.db).query()
                .whereHas("latestImage", q -> q.where("images.id", "=", 9)).get();
        ModelCollection<Post> without = Post.QUERY.on(db.db).query().doesntHave("latestImage").get();

        // Assert
        assertEquals(Collections.singletonList(1L), OneOfManyModels.keys(postsWithImage2));
        assertEquals(Collections.emptyList(), OneOfManyModels.keys(postsWithImage9));
        assertEquals(Collections.singletonList(2L), OneOfManyModels.keys(without));
    }
}

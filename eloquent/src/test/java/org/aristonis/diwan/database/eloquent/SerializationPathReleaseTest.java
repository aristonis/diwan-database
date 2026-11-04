package org.aristonis.diwan.database.eloquent;

import static org.aristonis.diwan.database.eloquent.FakeDb.row;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.aristonis.diwan.database.eloquent.TestModels.BlogPost;
import org.aristonis.diwan.database.eloquent.TestModels.User;
import org.aristonis.diwan.database.eloquent.internal.VisitPath;
import org.junit.jupiter.api.Test;

/** One shared {@link VisitPath} is left empty when serialisation throws part way down. */
class SerializationPathReleaseTest {

    @Test
    void pathReleasedWhenNestedSerializationThrows() {
        User user = new User();
        user.setRawAttributes(row("id", 1, "name", "Ann"), true);
        BlogPost post = new BlogPost();
        post.setRawAttributes(row("id", 1, "title", "a"), true);
        post.setRelation("author", user);
        user.setRelation("posts", new ModelCollection<>(List.of(post), BlogPost.QUERY));
        user.append("no_such_accessor");
        VisitPath path = new VisitPath();

        assertThrows(IllegalStateException.class, () -> post.toMap(path));

        assertFalse(path.contains(post));
        assertFalse(path.contains(user));
    }
}

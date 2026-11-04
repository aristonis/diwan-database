package org.aristonis.diwan.database.eloquent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.aristonis.diwan.database.eloquent.internal.Access;
import org.junit.jupiter.api.Test;

/**
 * Checks that a model class no code has touched yet still gets its registry entry loaded when a
 * caller asks for it by class literal.
 */
class UntouchedRegistryTest {

    /** A model whose entry only its static initialiser makes; no other test names this class. */
    static final class Untouched extends Model {
        static final ModelQuery<Untouched> QUERY = ModelQuery.of(Untouched.class, Untouched::new);
    }

    @Test
    void newRegisteredBuildsUntouchedModelFromClassLiteral() {
        assertNull(ModelQuery.registered(Untouched.class));

        Untouched model = Access.eloquent().newRegistered(Untouched.class, null);

        assertNotNull(model);
        assertSame(Untouched.class, model.getClass());
        assertSame(Untouched.class, ModelQuery.registeredOrLoad(Untouched.class).getModelClass());
    }
}

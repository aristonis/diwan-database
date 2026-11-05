package org.aristonis.diwan.database.eloquent;

import java.util.Objects;
import java.util.function.Supplier;
import org.aristonis.diwan.database.seeding.SeedContext;
import org.aristonis.diwan.database.seeding.Seeder;

/**
 * Helpers that join seeders to models.
 *
 * <p>Laravel's {@code WithoutModelEvents} trait mutes model events while a seeder runs; the
 * wrapper from {@link #withoutModelEvents(Supplier)} does the same with
 * {@link Model#withoutEvents(Runnable)}.
 */
public final class ModelSeeding {

    private ModelSeeding() {
    }

    /**
     * Wraps a seeder so that no model listener fires while it runs.
     *
     * <p>The mute covers the calling thread only, as for {@link Model#withoutEvents(Runnable)}, and
     * it ends when the seeder returns or throws. Seeders the wrapped seeder calls run inside the
     * mute. Pass the result wherever a seeder supplier is accepted, for example
     * {@code manager.seed(ModelSeeding.withoutModelEvents(UserSeeder::new))}.
     *
     * @param seeder the supplier of the seeder to wrap, never {@code null}
     * @return a supplier of a seeder that runs {@code seeder} with model events silenced, never
     *     {@code null}
     * @throws NullPointerException if {@code seeder} is {@code null}
     */
    public static Supplier<Seeder> withoutModelEvents(Supplier<? extends Seeder> seeder) {
        Objects.requireNonNull(seeder, "seeder");
        return () -> new Seeder() {
            @Override
            protected void run(SeedContext context) {
                Runnable work = () -> context.call(seeder);
                Model.withoutEvents(work);
            }
        };
    }
}

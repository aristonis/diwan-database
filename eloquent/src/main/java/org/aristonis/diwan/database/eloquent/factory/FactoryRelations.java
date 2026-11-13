package org.aristonis.diwan.database.eloquent.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * The immutable relation settings of one factory: its has links, for links, recycle pool, recycle
 * mode and seed, and the parents it must not create.
 */
final class FactoryRelations {

    private static final FactoryRelations NONE = new FactoryRelations(Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), RecycleMode.IN_TURN, null, false,
            Collections.emptySet(), Collections.emptySet());

    private final List<HasLink> hasLinks;
    private final List<ForLink> forLinks;
    private final List<Model> pool;
    private final RecycleMode mode;
    private final Long seed;
    private final boolean excludesAll;
    private final Set<Class<?>> excludedClasses;
    private final Set<String> excludedNames;

    private FactoryRelations(
            List<HasLink> hasLinks,
            List<ForLink> forLinks,
            List<Model> pool,
            RecycleMode mode,
            Long seed,
            boolean excludesAll,
            Set<Class<?>> excludedClasses,
            Set<String> excludedNames) {
        this.hasLinks = Collections.unmodifiableList(new ArrayList<>(hasLinks));
        this.forLinks = Collections.unmodifiableList(new ArrayList<>(forLinks));
        this.pool = Collections.unmodifiableList(new ArrayList<>(pool));
        this.mode = mode;
        this.seed = seed;
        this.excludesAll = excludesAll;
        this.excludedClasses = Collections.unmodifiableSet(new LinkedHashSet<>(excludedClasses));
        this.excludedNames = Collections.unmodifiableSet(new LinkedHashSet<>(excludedNames));
    }

    /**
     * Returns relation settings with no link, no pool and no exclusion, in {@link RecycleMode#IN_TURN}.
     *
     * @return the settings, never {@code null}
     */
    static FactoryRelations none() {
        return NONE;
    }

    /**
     * Returns the has and has-attached links, in the order they were added.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<HasLink> hasLinks() {
        return hasLinks;
    }

    /**
     * Returns the for links, in the order they were added.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<ForLink> forLinks() {
        return forLinks;
    }

    /**
     * Returns the recycled models, one entry per instance.
     *
     * @return an unmodifiable list, never {@code null}
     */
    List<Model> pool() {
        return pool;
    }

    /**
     * Returns how a model is picked from the pool.
     *
     * @return the mode, never {@code null}
     */
    RecycleMode mode() {
        return mode;
    }

    /**
     * Returns the seed of the random pick.
     *
     * @return the seed, or {@code null} when the pick is unseeded
     */
    Long seed() {
        return seed;
    }

    /**
     * Returns whether every nested factory value of the model is replaced by {@code null}.
     *
     * @return {@code true} when no parent is created from an attribute value
     */
    boolean excludesAll() {
        return excludesAll;
    }

    /**
     * Returns whether a nested factory value is replaced by {@code null} instead of creating a
     * model.
     *
     * @param modelClass the exact class of the models the nested factory builds, never {@code null}
     * @param attributeName the attribute that holds the factory, never {@code null}
     * @return {@code true} when all parents, this class or this attribute name is excluded
     */
    boolean excludes(Class<?> modelClass, String attributeName) {
        return excludesAll || excludedClasses.contains(modelClass) || excludedNames.contains(attributeName);
    }

    /**
     * Returns these settings with one more has or has-attached link.
     *
     * @param link the link, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code link} is {@code null}
     */
    FactoryRelations withHas(HasLink link) {
        List<HasLink> next = new ArrayList<>(hasLinks);
        next.add(Objects.requireNonNull(link, "link"));
        return new FactoryRelations(next, forLinks, pool, mode, seed, excludesAll, excludedClasses, excludedNames);
    }

    /**
     * Returns these settings with one more for link.
     *
     * @param link the link, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code link} is {@code null}
     */
    FactoryRelations withFor(ForLink link) {
        List<ForLink> next = new ArrayList<>(forLinks);
        next.add(Objects.requireNonNull(link, "link"));
        return new FactoryRelations(hasLinks, next, pool, mode, seed, excludesAll, excludedClasses, excludedNames);
    }

    /**
     * Returns these settings with models added to the pool, one entry per instance.
     *
     * @param models the models to add, never {@code null} and without {@code null} entries
     * @return the new settings, or this one when {@code models} is empty, never {@code null}
     * @throws NullPointerException if {@code models} or an entry is {@code null}
     */
    FactoryRelations withRecycled(List<? extends Model> models) {
        Objects.requireNonNull(models, "models");
        List<Model> next = new ArrayList<>(pool);
        for (Model model : models) {
            Objects.requireNonNull(model, "model");
            if (!containsInstance(next, model)) {
                next.add(model);
            }
        }
        if (models.isEmpty()) {
            return this;
        }
        return new FactoryRelations(hasLinks, forLinks, next, mode, seed, excludesAll, excludedClasses,
                excludedNames);
    }

    /**
     * Returns these settings with another pick mode, keeping a seed that is already set.
     *
     * @param newMode the mode, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code newMode} is {@code null}
     */
    FactoryRelations withMode(RecycleMode newMode) {
        return new FactoryRelations(hasLinks, forLinks, pool, Objects.requireNonNull(newMode, "mode"), seed,
                excludesAll, excludedClasses, excludedNames);
    }

    /**
     * Returns these settings with the random pick mode and a seed.
     *
     * @param newSeed the seed, or {@code null} for an unseeded pick that clears a seed already set
     * @return the new settings, never {@code null}
     */
    FactoryRelations withRandom(Long newSeed) {
        return new FactoryRelations(hasLinks, forLinks, pool, RecycleMode.RANDOM, newSeed, excludesAll,
                excludedClasses, excludedNames);
    }

    /**
     * Returns these settings that replace every nested factory value by {@code null}.
     *
     * @return the new settings, never {@code null}
     */
    FactoryRelations withExcludedAll() {
        return new FactoryRelations(hasLinks, forLinks, pool, mode, seed, true, excludedClasses, excludedNames);
    }

    /**
     * Returns these settings with model classes added to the excluded ones.
     *
     * @param classes the exact classes, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code classes} or an entry is {@code null}
     */
    FactoryRelations withExcludedClasses(List<Class<? extends Model>> classes) {
        Set<Class<?>> next = new LinkedHashSet<>(excludedClasses);
        for (Class<? extends Model> modelClass : Objects.requireNonNull(classes, "models")) {
            next.add(Objects.requireNonNull(modelClass, "model class"));
        }
        return new FactoryRelations(hasLinks, forLinks, pool, mode, seed, excludesAll, next, excludedNames);
    }

    /**
     * Returns these settings with attribute names added to the excluded ones.
     *
     * @param names the attribute names, never {@code null}
     * @return the new settings, never {@code null}
     * @throws NullPointerException if {@code names} or an entry is {@code null}
     */
    FactoryRelations withExcludedNames(List<String> names) {
        Set<String> next = new LinkedHashSet<>(excludedNames);
        for (String name : Objects.requireNonNull(names, "attributeNames")) {
            next.add(Objects.requireNonNull(name, "attribute name"));
        }
        return new FactoryRelations(hasLinks, forLinks, pool, mode, seed, excludesAll, excludedClasses, next);
    }

    private static boolean containsInstance(List<Model> models, Model model) {
        for (Model existing : models) {
            if (existing == model) {
                return true;
            }
        }
        return false;
    }
}

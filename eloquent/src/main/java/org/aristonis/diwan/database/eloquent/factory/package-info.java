/**
 * Model factories: build models, or insert rows, from a base definition and layered states.
 *
 * <p>This package holds {@link org.aristonis.diwan.database.eloquent.factory.Factory}, which makes,
 * creates and inserts models, and {@link org.aristonis.diwan.database.eloquent.factory.Sequence},
 * which cycles attribute entries across the models of one call.
 *
 * <p>Relations are part of the package. A factory can create children after each model is saved,
 * attach related models, and give a model its parent first. A nested factory or model value stores
 * its key. A {@link org.aristonis.diwan.database.eloquent.factory.RecycleMode} says how saved models
 * are reused instead of creating parents. Parents are created before the model that needs them, and
 * every terminal call builds its own run context, so a factory never keeps state between calls.
 *
 * <p>It does not generate fake data: a definition supplies every value itself. It has no guessing
 * of factory names from model names and no runtime macros.
 */
package org.aristonis.diwan.database.eloquent.factory;

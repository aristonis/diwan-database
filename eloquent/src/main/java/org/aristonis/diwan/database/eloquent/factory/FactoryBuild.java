package org.aristonis.diwan.database.eloquent.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.aristonis.diwan.database.eloquent.Model;

/**
 * Builds models for one run: makes them, creates them with their children, and inserts their rows.
 */
final class FactoryBuild {

    private FactoryBuild() {
    }

    /**
     * Makes one unsaved model and runs the after-making hooks on it.
     *
     * @param spec the settings of the factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @param index the position of the model, from zero
     * @param <M> the model type
     * @return the model, never {@code null}
     */
    static <M extends Model> M makeOne(FactorySpec<M> spec, FactoryRun run, int index) {
        M model = spec.effectiveQuery().newModel();
        model.forceFill(FactoryRows.row(spec, index, run));
        for (BiConsumer<M, Model> hook : spec.afterMaking()) {
            hook.accept(model, run.parent());
        }
        return model;
    }

    /**
     * Makes one model, saves it, creates its children, then runs the after-creating hooks on it.
     *
     * @param spec the settings of the factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @param index the position of the model, from zero
     * @param <M> the model type
     * @return the saved model, never {@code null}
     * @throws IllegalStateException if a model event hook stopped the save or no row was stored
     */
    static <M extends Model> M createOne(FactorySpec<M> spec, FactoryRun run, int index) {
        M model = makeOne(spec, run, index);
        if (!model.save() || !model.exists()) {
            throw new IllegalStateException("Saving [" + model.getClass().getName()
                    + "] was stopped by a model event hook or stored no row");
        }
        FactoryChildren.createFor(model, spec, run);
        for (BiConsumer<M, Model> hook : spec.afterCreating()) {
            hook.accept(model, run.parent());
        }
        return model;
    }

    /**
     * Makes a number of unsaved models.
     *
     * @param spec the settings of the factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @param count the number of models, below 1 for none
     * @param <M> the model type
     * @return a new mutable list the caller owns, empty when {@code count} is below 1
     */
    static <M extends Model> List<M> makeList(FactorySpec<M> spec, FactoryRun run, int count) {
        List<M> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(makeOne(spec, run, i));
        }
        return out;
    }

    /**
     * Creates a number of models.
     *
     * @param spec the settings of the factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @param count the number of models, below 1 for none
     * @param <M> the model type
     * @return a new mutable list the caller owns, empty when {@code count} is below 1
     * @throws IllegalStateException if a model event hook stopped a save or no row was stored
     */
    static <M extends Model> List<M> createList(FactorySpec<M> spec, FactoryRun run, int count) {
        List<M> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(createOne(spec, run, i));
        }
        return out;
    }

    /**
     * Inserts a number of rows in one statement, with no model, hook or child.
     *
     * @param spec the settings of the factory, never {@code null}
     * @param run the frame of the run, never {@code null}
     * @param count the number of rows, below 1 for none
     * @param <M> the model type
     * @throws IllegalArgumentException if the rows do not have the same keys
     */
    static <M extends Model> void insert(FactorySpec<M> spec, FactoryRun run, int count) {
        List<Map<String, Object>> rows = FactoryRows.rows(spec, run, count);
        if (rows.isEmpty()) {
            return;
        }
        spec.effectiveQuery().query().fillAndInsert(rows);
    }
}

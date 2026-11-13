package org.aristonis.diwan.database.eloquent.factory;

/**
 * How a factory picks one model from its recycle pool when a nested value or a parent of that model
 * class is needed.
 *
 * <p>The pool of one terminal call is grouped by the exact class of each model, and the pick runs
 * only inside that call, so two calls on one factory never share a position.
 */
public enum RecycleMode {

    /**
     * Takes the pool models one after the other and starts again at the first after the last.
     * This is the default.
     */
    IN_TURN,

    /**
     * Takes a random pool model each time, from an unseeded generator or from one built from the
     * seed given to {@link Factory#recycleRandom(long)}.
     */
    RANDOM,

    /**
     * Always takes the first pool model of the class.
     */
    FIRST
}

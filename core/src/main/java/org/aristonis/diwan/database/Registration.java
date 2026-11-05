package org.aristonis.diwan.database;

/** Handle that undoes one registration on {@link ConnectionEvents}. */
public interface Registration {

    /**
     * Removes the registered listener or handler.
     *
     * <p>Calling this more than once, or from any thread, is safe; later calls do nothing.
     */
    void remove();
}

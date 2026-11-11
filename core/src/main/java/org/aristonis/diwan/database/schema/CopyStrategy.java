package org.aristonis.diwan.database.schema;

/**
 * How a database server copies a template when it creates a database from one.
 *
 * <p>Only databases with a choice honour it; PostgreSQL 15 and later send it as the
 * {@code STRATEGY} clause of {@code CREATE DATABASE}.
 */
public enum CopyStrategy {

    /** Copies the template's files block by block; cheap to start, but forces two checkpoints. */
    FILE_COPY,

    /** Replays the template through the write-ahead log; no checkpoint, but more log written. */
    WAL_LOG
}

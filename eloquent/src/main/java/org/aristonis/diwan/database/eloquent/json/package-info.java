/**
 * The JSON codec behind Eloquent's JSON casts and model serialization.
 *
 * <p>{@link org.aristonis.diwan.database.eloquent.json.JsonCodec#standard()} is a strict RFC 8259
 * parser and a compact writer with no third-party dependency. Decoded numbers are {@code Long},
 * {@code BigInteger} or {@code BigDecimal} — never {@code Double} — so a decode followed by an
 * encode never rounds.
 *
 * <p>This package holds the codec contract, its exception and the standard implementation. It does
 * not hold casts, model code or any database type.
 */
package org.aristonis.diwan.database.eloquent.json;

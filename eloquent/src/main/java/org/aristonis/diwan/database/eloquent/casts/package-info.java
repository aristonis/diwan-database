/**
 * Attribute casts for Eloquent models: the cast contract and the built-in casts.
 *
 * <p>This package holds the cast SPI and the built-in casts, plus their shared conversion support.
 * It does not hold the model layers that apply casts; those stay package-private in
 * {@code org.aristonis.diwan.database.eloquent}. The cycle between the two packages is accepted
 * because this package holds only the SPI and the built-ins.
 */
package org.aristonis.diwan.database.eloquent.casts;

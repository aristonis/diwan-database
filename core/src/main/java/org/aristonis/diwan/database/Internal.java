package org.aristonis.diwan.database;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type or member that is not part of the public API and may change in any release.
 *
 * <p>Such an element is {@code public} only because other Diwan modules, the dialect grammars and
 * drivers, need to reach it across packages. Application code must not depend on it. The retention
 * is {@code RUNTIME} so that tools and tests can find the marker by reflection.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
}

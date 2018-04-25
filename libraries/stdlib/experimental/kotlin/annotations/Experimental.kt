/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

/**
 * Signals that the annotated annotation class is a marker of an experimental API. Any declaration annotated with that marker is thus
 * considered an experimental declaration and its call sites should accept the experimental aspect of it either by using [UseExperimental],
 * or by being annotated with that marker themselves, effectively causing further propagation of that experimental aspect.
 */
@Target(ANNOTATION_CLASS)
@Retention(BINARY)
@SinceKotlin("1.3")
@Suppress("ANNOTATION_CLASS_MEMBER")
annotation class Experimental(val level: Level = Level.ERROR) {
    /**
     * Severity of the diagnostic that should be reported on usages of experimental API which did not explicitly accept the experimental aspect
     * of that API either by using [UseExperimental] or by being annotated with the corresponding marker annotation.
     */
    enum class Level {
        /** Specifies that a warning should be reported on incorrect usages of this experimental API. */
        WARNING,
        /** Specifies that an error should be reported on incorrect usages of this experimental API. */
        ERROR,
    }
}

/**
 * Allows to use experimental API denoted by the given markers in the annotated file, declaration, or expression.
 * If a declaration is annotated with [UseExperimental], its usages are **not** required to opt-in to that experimental API.
 */
@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION, FILE)
@Retention(SOURCE)
@SinceKotlin("1.3")
annotation class UseExperimental(
    vararg val markerClass: KClass<out Annotation>
)

// !USE_EXPERIMENTAL: kotlin.Experimental
// !DIAGNOSTICS: -UNUSED_PARAMETER

import kotlin.reflect.KClass
import kotlin.Experimental.Level.*

fun f1(e: <!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>Experimental<!>) {}
fun f2(u: <!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>UseExperimental<!>) {}

typealias Experimental0 = <!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>Experimental<!>
typealias UseExperimental0 = <!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>UseExperimental<!>
fun f3(e: Experimental0 /* TODO */) {}
fun f4(u: UseExperimental0 /* TODO */) {}


annotation class A(vararg val k: KClass<*>)

@A(<!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>Experimental<!>::class, <!EXPERIMENTAL_CAN_ONLY_BE_USED_AS_ANNOTATION!>UseExperimental<!>::class)
fun f5() {}


@Experimental
annotation class Marker

fun f6(m: <!EXPERIMENTAL_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_USE_EXPERIMENTAL!>Marker<!>) {}
fun f7(): List<<!EXPERIMENTAL_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_USE_EXPERIMENTAL!>Marker<!>>? = null

typealias Marker0 = <!EXPERIMENTAL_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_USE_EXPERIMENTAL!>Marker<!>

fun f8(m: <!EXPERIMENTAL_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_USE_EXPERIMENTAL!>Marker0<!>) {}

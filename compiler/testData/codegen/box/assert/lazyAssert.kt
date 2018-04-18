// KOTLIN_CONFIGURATION_FLAGS: +JVM.ENABLE_LAZY_ASSERTIONS
// WITH_RUNTIME
// FULL_JDK

import java.lang.reflect.Field
import java.lang.reflect.Modifier

fun setDesiredAssertionStatus(v: Boolean) {
    @Suppress("INVISIBLE_REFERENCE")
    val field = kotlin._Assertions.javaClass.getField("ENABLED")
    val modifiers = Field::class.java.getDeclaredField("modifiers");
    modifiers.isAccessible = true
    modifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
    field.set(null, v)
}

fun checkTrue(): Boolean {
    var hit = false
    val l = { hit = true; true }
    assert(l())
    return hit
}

fun checkTrueWithMessage(): Boolean {
    var hit = false
    val l = { hit = true; true }
    assert(l()) { "BOOYA!" }
    return hit
}

fun checkFalse(): Boolean {
    var hit = false
    val l = { hit = true; false }
    assert(l())
    return hit
}

fun checkFalseWithMessage(): Boolean {
    var hit = false
    val l = { hit = true; false }
    assert(l()) { "BOOYA!" }
    return hit
}

fun box(): String {
    setDesiredAssertionStatus(false)
    if (checkTrue()) return "Assert is not lazy 0"
    setDesiredAssertionStatus(true)
    if (!checkTrue()) return "Assert did not hit 0"

    setDesiredAssertionStatus(false)
    if (checkTrueWithMessage()) return "Assert is not lazy 1"
    setDesiredAssertionStatus(true)
    if (!checkTrueWithMessage()) return "Assert did not hit 1"

    setDesiredAssertionStatus(false)
    if (checkFalse()) return "Assert is not lazy 2"
    setDesiredAssertionStatus(true)
    try {
        checkFalse()
        return "Assert did not hit 2"
    } catch (ignore: AssertionError) {
    }

    setDesiredAssertionStatus(false)
    if (checkFalseWithMessage()) return "Assert is not lazy 3"
    setDesiredAssertionStatus(true)
    try {
        checkFalseWithMessage()
        return "Assert did not hit 3"
    } catch (ignore: AssertionError) {
    }

    return "OK"
}
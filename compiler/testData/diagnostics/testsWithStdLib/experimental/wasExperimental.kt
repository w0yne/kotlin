// !API_VERSION: 1.1
// !USE_EXPERIMENTAL: kotlin.Experimental
// !DIAGNOSTICS: -INVISIBLE_MEMBER -INVISIBLE_REFERENCE -NEWER_VERSION_IN_SINCE_KOTLIN

@SinceKotlin("1.2")
fun newPublishedFun() {}


@Experimental
annotation class Marker

@SinceKotlin("1.2")
@WasExperimental(Marker::class)
fun newFunExperimentalInThePast() {}


fun use1() {
    <!UNRESOLVED_REFERENCE!>newPublishedFun<!>()
    <!UNRESOLVED_REFERENCE!>newFunExperimentalInThePast<!>()
}

@UseExperimental(Marker::class)
fun use2() {
    <!UNRESOLVED_REFERENCE!>newPublishedFun<!>()
    newFunExperimentalInThePast()
}

@Marker
fun use3() {
    <!UNRESOLVED_REFERENCE!>newPublishedFun<!>()
    newFunExperimentalInThePast()
}

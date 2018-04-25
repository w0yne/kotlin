package org.jetbrains.kotlin

abstract class TypeToken<T>
inline fun <reified T> a() = (object: TypeToken<T>() {}).javaClass.genericSuperclass.toString()
inline fun <reified T> b() = a<T>()

fun main(args: Array<String>) {
    println()
}
/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.jvm

import com.intellij.lang.jvm.JvmClass
import com.intellij.lang.jvm.JvmElement
import com.intellij.lang.jvm.JvmMethod
import com.intellij.lang.jvm.JvmParameter
import com.intellij.lang.jvm.source.JvmDeclarationSearch
import com.intellij.lang.jvm.source.JvmDeclarationSearcher
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.psi.KtFile
import kotlin.reflect.KClass

class KotlinJvmDeclarationSearcherTest : KotlinLightCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE

    fun testAggregatedDeclarations() {

        val file = myFixture.addFileToProject(
            "Declaraions.kt", """

            class SomeClass(val field: String) {

                lateinit var anotherField:String

                constructor(i: Int): this(i.toString()){}

                fun foo():Unit {}

                @JvmOverloads
                fun bar(a: Long = 1L){}

            }

        """.trimIndent()
        ) as KtFile

        val psiElementToDeclatation = collectJvmDeclarations(file)

        assertMatches(
            psiElementToDeclatation.entries,
            JvmDeclared("class SomeClass", JvmClass::class),
            JvmDeclared("(val field: String)", JvmMethod::class),
            JvmDeclared("lateinit var anotherField", JvmMethod::class, JvmMethod::class, com.intellij.lang.jvm.JvmField::class),
            JvmDeclared("val field: String", JvmParameter::class, JvmMethod::class),
            JvmDeclared("constructor(i: Int)", JvmMethod::class),
            JvmDeclared("i: Int", JvmParameter::class),
            JvmDeclared("a: Long", JvmParameter::class),
            JvmDeclared("fun foo()", JvmMethod::class),
            JvmDeclared("fun bar", JvmMethod::class, JvmMethod::class)
        )


    }


    fun testClassDeclaration() {

        myFixture.configureByText(
            "Declaraions.kt", """

            class Some<caret>Class(val field: String)
        """.trimIndent()
        )

        val elementsByIdentifier = JvmDeclarationSearch.getElementsByIdentifier(myFixture.file.findElementAt(myFixture.caretOffset)!!)

        UsefulTestCase.assertNotEmpty(elementsByIdentifier.toList())

    }

    private fun collectJvmDeclarations(file: KtFile): MutableMap<PsiElement, List<JvmElement>> {
        val declarationSearcher = JvmDeclarationSearcher.EP.forLanguage(KotlinLanguage.INSTANCE)!!

        val map = mutableMapOf<PsiElement, List<JvmElement>>()

        file.accept(object : PsiRecursiveElementVisitor() {

            override fun visitElement(element: PsiElement) {
                val declarations = declarationSearcher.findDeclarations(element)
                if (declarations.isNotEmpty()) {
                    map[element] = declarations.toList()
                }
                super.visitElement(element)
            }
        })
        return map
    }

}

private class JvmDeclared(val textToContain: String, vararg jvmClasses: KClass<out JvmElement>) :
    Function1<Map.Entry<PsiElement, List<JvmElement>>, Boolean> {
    private val jvmClasses = jvmClasses.toList()

    override fun invoke(p1: Map.Entry<PsiElement, List<JvmElement>>): Boolean {
        val (psi, jvmElements) = p1
        if (!psi.text.contains(textToContain)) return false

        return matchElementsToConditions(jvmElements, jvmClasses.map { { value: JvmElement -> it.isInstance(value) } }).succeed
    }

    override fun toString(): String = "JvmDeclaration contains text '$textToContain' and produces $jvmClasses"
}

fun <T> assertMatches(elements: Collection<T>, vararg conditions: (T) -> Boolean) {
    val matchResult = matchElementsToConditions(elements, conditions.toList())
    when (matchResult) {
        is MatchResult.UnmatchedCondition ->
            throw AssertionError("no one of ${elements.joinToString { it.toString() }} matches the ${matchResult.condition}")
        is MatchResult.UnmatchedElements ->
            throw AssertionError("elements ${matchResult.elements.joinToString { it.toString() }} wasn't matched by any condition")
    }
}

private fun <T> matchElementsToConditions(elements: Collection<T>, conditions: List<(T) -> Boolean>): MatchResult<T> {
    val checkList = conditions.toMutableList()
    val elementsToCheck = elements.toMutableList()

    while (checkList.isNotEmpty()) {
        val condition = checkList.removeAt(0)
        val matched = elementsToCheck.find { condition(it) }
                ?: return MatchResult.UnmatchedCondition(condition)
        if (!elementsToCheck.remove(matched))
            throw IllegalStateException("cant remove matched element: $matched")
    }
    if (elementsToCheck.isEmpty())
        return MatchResult.Matched
    return MatchResult.UnmatchedElements(elementsToCheck)
}

private sealed class MatchResult<out T>(val succeed: Boolean) {
    object Matched : MatchResult<Nothing>(true)
    class UnmatchedCondition<T>(val condition: (T) -> Boolean) : MatchResult<T>(false)
    class UnmatchedElements<T>(val elements: List<T>) : MatchResult<T>(false)
}
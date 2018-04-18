/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.lightClasses

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.java.stubs.PsiJavaFileStub
import org.jetbrains.kotlin.asJava.LightClassBuilder
import org.jetbrains.kotlin.asJava.builder.*
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.KtLightField
import org.jetbrains.kotlin.asJava.elements.KtLightFieldImpl
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.KtLightMethodImpl
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.resolve.frontendService
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.NotNullableUserDataProperty
import org.jetbrains.kotlin.psi.debugText.getDebugText
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.jvm.diagnostics.JvmDeclarationOriginKind
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

typealias ExactLightClassContextProvider = () -> LightClassConstructionContext
typealias DummyLightClassContextProvider = (() -> LightClassConstructionContext?)?
typealias DiagnosticsHolderProvider = () -> KtElement

sealed class LazyLightClassDataHolder(
    builder: LightClassBuilder,
    exactContextProvider: ExactLightClassContextProvider,
    dummyContextProvider: DummyLightClassContextProvider,
    private val diagnosticsHolderProvider: DiagnosticsHolderProvider
) : LightClassDataHolder {

    class DiagnosticsHolder(private val storageManager: StorageManager) {
        private val computedLightClassDiagnostics = hashMapOf<LazyLightClassDataHolder, Diagnostics>()

        fun putDiagnostics(lazyLightClassDataHolder: LazyLightClassDataHolder, diagnostics: Diagnostics) {
            if (diagnostics.isEmpty()) return
            storageManager.compute {
                computedLightClassDiagnostics[lazyLightClassDataHolder] = diagnostics
            }
        }

        fun getComputedDiagnostics(lazyLightClassDataHolder: LazyLightClassDataHolder): Diagnostics? =
            storageManager.compute { computedLightClassDiagnostics[lazyLightClassDataHolder] }
    }

    private val exactResultLazyValue = lazyPub {
        val (stub, _, diagnostics) = builder(exactContextProvider())
        diagnosticsHolderProvider().getResolutionFacade().frontendService<LazyLightClassDataHolder.DiagnosticsHolder>()
            .putDiagnostics(this, diagnostics)
        stub
    }

    private val lazyInexactStub by lazyPub {
        dummyContextProvider?.let { provider -> provider()?.let { context -> builder.invoke(context).stub } }
    }

    private val inexactStub: PsiJavaFileStub?
        get() = if (exactResultLazyValue.isInitialized()) null else lazyInexactStub

    override val javaFileStub by exactResultLazyValue

    override val extraDiagnostics: Diagnostics
        get() {
            // run light class builder
            exactResultLazyValue.value

            return diagnosticsHolderProvider().getResolutionFacade().frontendService<LazyLightClassDataHolder.DiagnosticsHolder>().getComputedDiagnostics(
                this
            ) ?: Diagnostics.EMPTY
        }

    // for facade or defaultImpls
    override fun findData(findDelegate: (PsiJavaFileStub) -> PsiClass): LightClassData =
        LazyLightClassData { stub ->
            findDelegate(stub)
        }

    class ForClass(
        builder: LightClassBuilder,
        exactContextProvider: ExactLightClassContextProvider,
        dummyContextProvider: DummyLightClassContextProvider,
        diagnosticsHolderProvider: DiagnosticsHolderProvider
    ) : LazyLightClassDataHolder(builder, exactContextProvider, dummyContextProvider, diagnosticsHolderProvider),
        LightClassDataHolder.ForClass {
        override fun findDataForClassOrObject(classOrObject: KtClassOrObject): LightClassData =
            LazyLightClassData { stub ->
                stub.findDelegate(classOrObject)
            }
    }

    class ForFacade(
        builder: LightClassBuilder,
        exactContextProvider: ExactLightClassContextProvider,
        dummyContextProvider: DummyLightClassContextProvider,
        diagnosticsHolderProvider: DiagnosticsHolderProvider
    ) : LazyLightClassDataHolder(builder, exactContextProvider, dummyContextProvider, diagnosticsHolderProvider),
        LightClassDataHolder.ForFacade

    class ForScript(
        builder: LightClassBuilder,
        exactContextProvider: ExactLightClassContextProvider,
        dummyContextProvider: DummyLightClassContextProvider,
        diagnosticsHolderProvider: DiagnosticsHolderProvider
    ) : LazyLightClassDataHolder(builder, exactContextProvider, dummyContextProvider, diagnosticsHolderProvider),
        LightClassDataHolder.ForScript

    private inner class LazyLightClassData(
        findDelegate: (PsiJavaFileStub) -> PsiClass
    ) : LightClassData {
        override val clsDelegate: PsiClass by lazyPub { findDelegate(javaFileStub) }

        private val dummyDelegate: PsiClass? get() = clsDelegate//by lazyPub { /*inexactStub?.let(findDelegate)*/ }

        override val supertypes: Array<PsiClassType>
            get() = super.supertypes


        override val extendsListNames: Array<String>? by lazyPub {
            computeSupertypeList(false)
        }

        private fun computeSupertypeList(interfaces: Boolean): Array<String> {
            val klassOrObject = diagnosticsHolderProvider() as? KtClassOrObject ?: return emptyArray()
            return (klassOrObject.getResolutionFacade().resolveToDescriptor(
                klassOrObject,
                BodyResolveMode.PARTIAL
            ) as ClassDescriptor).typeConstructor.supertypes.mapNotNull {
                it.constructor.declarationDescriptor?.safeAs<ClassDescriptor>()?.takeIf {
                    it.kind.isInterfaceOnJvm() == interfaces
                }?.fqNameSafe?.asString()
            }.toTypedArray()
        }

        override val implementsListNames: Array<String> by lazyPub {
            computeSupertypeList(true)
        }

        override fun getOwnFields(containingClass: KtLightClass): List<KtLightField> {
            if (dummyDelegate == null) return KtLightFieldImpl.fromClsFields(clsDelegate, containingClass)
            return dummyDelegate!!.fields.map { dummyField ->
                val fieldOrigin = KtLightFieldImpl.getOrigin(dummyField)

                val fieldName = dummyField.name!!
                KtLightFieldImpl.lazy(dummyField, fieldOrigin, containingClass) {
                    clsDelegate.findFieldByName(fieldName, false).assertMatches(dummyField, containingClass)
                }
            }
        }

        override fun getOwnMethods(containingClass: KtLightClass): List<KtLightMethod> {
            //if (dummyDelegate == null) return KtLightMethodImpl.fromClsMethods(clsDelegate, containingClass)

            containingClass.kotlinOrigin?.let {
                return it.declarations.filterIsInstance<KtFunction>().map {
                    KtLightMethodImpl.lazy(
                        null,
                        containingClass,
                        LightMemberOriginForDeclaration(it, JvmDeclarationOriginKind.OTHER),
                        name = it.name
                    ) {
                        val exactDelegateMethod = clsDelegate.findMethodsByName(it.name, false).firstOrNull()

                        exactDelegateMethod!!
                    }
                }
            }

            return dummyDelegate!!.methods.map { dummyMethod ->
                val methodOrigin = KtLightMethodImpl.getOrigin(dummyMethod)

                KtLightMethodImpl.lazy(dummyMethod, containingClass, methodOrigin) {
                    val dummyIndex = dummyMethod.memberIndex!!

                    val byMemberIndex: (PsiMethod) -> Boolean = { it.memberIndex == dummyIndex }

                    /* Searching all methods may be necessary in some cases where we failed to rollback optimization:
                            Overriding internal member that was final

                       Resulting light member is not consistent in this case, so this should happen only for erroneous code
                    */
                    val exactDelegateMethod = clsDelegate.findMethodsByName(dummyMethod.name, false).firstOrNull(byMemberIndex)
                            ?: clsDelegate.methods.firstOrNull(byMemberIndex)
                    exactDelegateMethod.assertMatches(dummyMethod, containingClass)
                }
            }
        }
    }

    private fun <T : PsiMember> T?.assertMatches(dummyMember: T, containingClass: KtLightClass): T {
        if (this == null) throw LazyLightClassMemberMatchingError.NoMatch(dummyMember, containingClass)

        val parameterCountMatches = (this as? PsiMethod)?.parameterList?.parametersCount ?: 0 ==
                (dummyMember as? PsiMethod)?.parameterList?.parametersCount ?: 0
        if (this.memberIndex != dummyMember.memberIndex || !parameterCountMatches) {
            throw LazyLightClassMemberMatchingError.WrongMatch(this, dummyMember, containingClass)
        }

        return this
    }
}

private fun ClassKind.isInterfaceOnJvm() = this == ClassKind.INTERFACE || this == ClassKind.ANNOTATION_CLASS

private sealed class LazyLightClassMemberMatchingError(message: String, containingClass: KtLightClass) :
    KotlinExceptionWithAttachments(message) {

    init {
        containingClass.kotlinOrigin?.hasLightClassMatchingErrors = true
        withAttachment("class.kt", (containingClass.kotlinOrigin)?.getDebugText())
    }

    class NoMatch(dummyMember: PsiMember, containingClass: KtLightClass) : LazyLightClassMemberMatchingError(
        "Couldn't match ${dummyMember.debugName}", containingClass
    )

    class WrongMatch(realMember: PsiMember, dummyMember: PsiMember, containingClass: KtLightClass) : LazyLightClassMemberMatchingError(
        "Matched ${dummyMember.debugName} to ${realMember.debugName}", containingClass
    )
}

private val PsiMember.debugName
    get() = "${this::class.java.simpleName}:${this.name} ${this.memberIndex}" + if (this is PsiMethod) " (with ${parameterList.parametersCount} parameters)" else ""

var KtClassOrObject.hasLightClassMatchingErrors: Boolean by NotNullableUserDataProperty(Key.create("LIGHT_CLASS_MATCHING_ERRORS"), false)

/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.jvm.checkers

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.platform.PlatformToKotlinClassMap
import org.jetbrains.kotlin.resolve.calls.checkers.CallChecker
import org.jetbrains.kotlin.resolve.calls.checkers.CallCheckerContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class PlatformClassConstructorCallChecker(private val platformToKotlinMap: PlatformToKotlinClassMap) : CallChecker {
    override fun check(resolvedCall: ResolvedCall<*>, reportOn: PsiElement, context: CallCheckerContext) {
        val constructorDescriptor = resolvedCall.resultingDescriptor as? ConstructorDescriptor ?: return
        val classDescriptor = constructorDescriptor.constructedClass
        val kotlinClasses = platformToKotlinMap.mapPlatformClass(classDescriptor)
        if (kotlinClasses.isNotEmpty()) {
            context.trace.report(Errors.PLATFORM_CLASS_CONSTRUCTION.on(reportOn, classDescriptor.fqNameSafe, kotlinClasses))
        }
    }
}

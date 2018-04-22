/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.jvm

import com.intellij.lang.jvm.JvmElement
import com.intellij.lang.jvm.source.JvmDeclarationSearcher
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.psi.KtElement

class KotlinDeclarationSearcher : JvmDeclarationSearcher {
    override fun findDeclarations(declaringElement: PsiElement): Collection<JvmElement> =
        when (declaringElement) {
            is KtElement -> declaringElement.toLightElements().mapNotNull { it as? JvmElement }
            else -> emptyList()
        }
}
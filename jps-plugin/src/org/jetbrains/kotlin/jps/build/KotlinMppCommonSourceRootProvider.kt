/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build

import org.jetbrains.jps.builders.AdditionalRootsProviderService
import org.jetbrains.jps.builders.BuildTarget
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType
import org.jetbrains.jps.builders.java.JavaSourceRootDescriptor
import org.jetbrains.jps.builders.storage.BuildDataPaths
import org.jetbrains.jps.incremental.ModuleBuildTarget
import org.jetbrains.kotlin.jps.platforms.kotlinData

/**
 * Required for Multiplatform Projects.
 *
 * Adds all the source roots of the expectedBy modules to the platform modules.
 */
class KotlinMppCommonSourceRootProvider : AdditionalRootsProviderService<JavaSourceRootDescriptor>(JavaModuleBuildTargetType.ALL_TYPES) {
    override fun getAdditionalRoots(
        target: BuildTarget<JavaSourceRootDescriptor>,
        dataPaths: BuildDataPaths?
    ): List<JavaSourceRootDescriptor> {
        val kotlinTarget = (target as? ModuleBuildTarget)?.kotlinData ?: return listOf()

        return kotlinTarget.expectedBy.flatMap {
            it.module.getSourceRoots(it.sourceRootType).map {
                JavaSourceRootDescriptor(
                    it.file,
                    target,
                    it.properties.isForGeneratedSources,
                    false,
                    it.properties.packagePrefix,
                    setOf()
                )
            }
        }
    }
}
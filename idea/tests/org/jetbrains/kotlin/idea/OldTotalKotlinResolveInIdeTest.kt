/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea

import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.ModuleTestCase
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.stubindex.KotlinExactPackagesIndex
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatform
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File
import kotlin.system.measureNanoTime

class OldTotalKotlinResolveInIdeTest : ModuleTestCase() {


    val projectRootFile = File(".")


    override fun setUpModule() {
        super.setUpModule()

        ModuleRootModificationUtil.addContentRoot(module, VfsUtil.findFileByIoFile(projectRootFile, true)!!)

        ModuleRootModificationUtil.updateModel(module) {
            projectRootFile.walkTopDown().onEnter {
                it.name.toLowerCase() !in setOf("testdata", "resources")
            }.filter {
                it.isDirectory && (it.name in setOf("src", "test", "tests"))
            }.forEach { dir ->
                val vdir = VfsUtil.findFileByIoFile(dir, true)!!
                it.contentEntries.single().addSourceFolder(vdir, false)
            }
        }

    }

    fun testTotalKotlin() {
        val scope = GlobalSearchScope.moduleScope(module)

        var count = 0L
        var returnTypesCount = 0

        val exactPackagesIndex = KotlinExactPackagesIndex.getInstance()
        var time = 0L

        var lightClassTime = 0L

        val kotlinCacheService = KotlinCacheService.getInstance(project)

        exactPackagesIndex.getAllKeys(project).forEach { fqNameStr ->
            if (fqNameStr.isEmpty()) return@forEach
            val moduleDescriptors = exactPackagesIndex.get(fqNameStr, project, scope).map {
                kotlinCacheService.getResolutionFacadeByFile(it, JvmPlatform).moduleDescriptor
            }.distinct()
            if (moduleDescriptors.isEmpty()) return@forEach
            println("$fqNameStr: total: ${moduleDescriptors.size}")
            moduleDescriptors.forEach { moduleDescriptor ->
                time += measureNanoTime {
//
                    val topLevelDescriptors =
                        moduleDescriptor.getPackage(FqName(fqNameStr)).memberScope.getContributedDescriptors()
                            .filterNot { it is JavaClassDescriptor }

////                    ForceResolveUtil.forceResolveAllContents(topLevelDescriptors)
//
                    count += topLevelDescriptors.size

//
//                    topLevelDescriptors.forEach {
//                        eerrorTypes += it.countErrorTypes()
//                        types += it.countTypes()
//                    }

//                    topLevelDescriptors.filterIsInstance<ClassifierDescriptor>()
//                        .forEach {
//
//                            val contributedDescriptors = it.defaultType.memberScope.getContributedDescriptors()
////                            ForceResolveUtil.forceResolveAllContents(contributedDescriptors)
//                            contributedDescriptors.forEach {
//                                eerrorTypes += it.countErrorTypes()
//                                types += it.countTypes()
//                            }
//
//                            count += contributedDescriptors.size
//                        }
                }

                lightClassTime += measureNanoTime {
                    val topLevelDescriptors = moduleDescriptor.getPackage(FqName(fqNameStr)).memberScope.getContributedDescriptors()
                    topLevelDescriptors.filterIsInstance<ClassifierDescriptor>().forEach {
                        try {
                            (DescriptorToSourceUtilsIde.getAnyDeclaration(project, it)?.safeAs<KtClassOrObject>())?.toLightClass()
                                ?.methods?.forEach {
                                returnTypesCount += it.returnType?.hashCode() ?: 0
                            }
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }


                Unit
            }
        }

        println("Total time: ${(time * 1e-6).toLong()} ms")
        println("Total light-class time: ${(lightClassTime * 1e-6).toLong()} ms")
        println("Total descriptors count: $count")
    }
}

package org.jetbrains.kotlin.gradle.plugin.android

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.api.ApkVariant
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.VariantManager
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.builder.model.SourceProvider
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.internal.DefaultDomainObjectSet
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.api.tasks.util.PatternFilterable

import java.io.File

object AndroidGradleWrapper {
    fun getRuntimeJars(basePlugin: BasePlugin, baseExtension: BaseExtension) {
        throw Error()
    }

    fun srcDir(androidSourceSet: Any, kotlinDirSet: Any) {
        throw Error()
    }

    fun getResourceFilter(androidSourceSet: Any): PatternFilterable {
        throw Error()
    }

    fun getVariantName(variant: Any): String {
        throw Error()
    }

    private fun getJackOptions(variantData: Any): Any? {
        throw Error()
    }

    fun isJackEnabled(variantData: Any): Boolean {
        throw Error()
    }

    fun getJavaTask(variantData: Any): AbstractCompile? {
        throw Error()
    }

    private fun getJavacTask(baseVariantData: Any): AbstractCompile? {
        throw Error()
    }

    private fun getJavaCompile(baseVariantData: Any): AbstractCompile? {
        throw Error()
    }

    fun getJavaSrcDirs(androidSourceSet: Any): Set<File> {
        throw Error()
    }

    fun setNoJdk(kotlinOptionsExtension: Any) {
        throw Error()
    }

    fun getProductFlavorsNames(variant: ApkVariant): List<String> {
        throw Error()
    }

    fun getProductFlavorsSourceSets(extension: BaseExtension): List<AndroidSourceSet> {
        throw Error()
    }

    fun getAnnotationProcessorOptionsFromAndroidVariant(variantData: Any?): Map<String, String>? {
        throw Error()
    }

    fun getTestVariants(extension: BaseExtension): DefaultDomainObjectSet<TestVariant> {
        throw Error()
    }

    fun getRClassFolder(variant: BaseVariant): List<File> {
        throw Error()
    }

    fun getVariantDataManager(plugin: BasePlugin): VariantManager {
        throw Error()
    }

    fun getJavaSources(variantData: BaseVariantData<*>): List<File> {
        throw Error()
    }

    fun getJarToAarMapping(variantData: BaseVariantData<*>): Map<File, File> {
        throw Error()
    }

    private fun getLibraryArtifactFile(lib: Any) {
        throw Error()
    }

    private fun getVariantLibraryDependencies(variantData: BaseVariantData<*>): Iterable<Any>? {
        throw Error()
    }
}

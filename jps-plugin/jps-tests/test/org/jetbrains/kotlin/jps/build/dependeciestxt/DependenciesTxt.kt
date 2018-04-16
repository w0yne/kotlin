/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.jps.build.dependeciestxt

import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.jetbrains.jps.model.java.JpsJavaDependencyScope
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.config.TargetPlatformKind
import org.jetbrains.kotlin.jps.build.dependeciestxt.generated.DependenciesTxtLexer
import org.jetbrains.kotlin.jps.build.dependeciestxt.generated.DependenciesTxtParser
import org.jetbrains.kotlin.jps.build.dependeciestxt.generated.DependenciesTxtParser.*
import java.io.File

/**
 * Dependencies description file.
 * See [README.md] for more details.
 */
data class DependenciesTxt(
    val fileName: String,
    val modules: List<Module>, val dependencies: List<Dependency>
) {
    override fun toString() = fileName

    data class Module(val name: String) {
        /**
         * Facet should not be created for old tests
         */
        var kotlinFacetSettings: KotlinFacetSettings? = null

        lateinit var jpsModule: JpsModule

        val dependencies = mutableListOf<Dependency>()
        val usages = mutableListOf<Dependency>()

        val isCommonModule
            get() = kotlinFacetSettings?.targetPlatformKind == TargetPlatformKind.Common

        val isJvmModule
            get() = kotlinFacetSettings?.targetPlatformKind is TargetPlatformKind.Jvm

        val expectedBy
            get() = dependencies.filter { it.expectedBy }

        val generateEditingTests: Boolean
            get() = true
    }

    data class Dependency(
        val from: Module,
        val to: Module,
        val scope: JpsJavaDependencyScope,
        val expectedBy: Boolean,
        val exported: Boolean
    ) {
        init {
            from.dependencies.add(this)
            to.usages.add(this)
        }
    }
}

class DependenciesTxtBuilder {
    val modules = mutableMapOf<String, ModuleRef>()
    val dependencies = mutableListOf<DependencyBuilder>()

    /**
     * Reference to module which can be defined later
     */
    class ModuleRef(name: String) {
        var defined: Boolean = false
        var actual: DependenciesTxt.Module = DependenciesTxt.Module(name)

        fun build(): DependenciesTxt.Module {
            val result = actual
            val kotlinFacetSettings = result.kotlinFacetSettings
            if (kotlinFacetSettings != null) {
                kotlinFacetSettings.implementedModuleNames = result.dependencies.filter { it.expectedBy }.map { it.to.name }
            }
            return result
        }
    }

    /**
     * Temporary object for resolving references to modules.
     */
    data class DependencyBuilder(
        val from: ModuleRef,
        val to: ModuleRef,
        val scope: JpsJavaDependencyScope,
        val expectedBy: Boolean,
        val exported: Boolean
    ) {
        fun build() = DependenciesTxt.Dependency(from.actual, to.actual, scope, expectedBy, exported)
    }

    fun readFile(file: File, fileTitle: String = file.toString()): DependenciesTxt {
        val lexer = DependenciesTxtLexer(CharStreams.fromPath(file.toPath()))
        val parser = DependenciesTxtParser(CommonTokenStream(lexer))

        parser.file().def().forEach { def ->
            val moduleDef = def.moduleDef()
            val dependencyDef = def.dependencyDef()

            when {
                moduleDef != null -> newModule(moduleDef)
                dependencyDef != null -> newDependency(dependencyDef)
            }
        }

        // dependencies build is required for module.build() (module.dependencies will be filled)
        val dependencies = dependencies.map { it.build() }
        return DependenciesTxt(
            fileTitle,
            modules.values.map { it.build() },
            dependencies
        )
    }

    private fun moduleRef(name: String) =
        modules.getOrPut(name) { ModuleRef(name) }

    private fun moduleRef(refNode: ModuleRefContext) =
        moduleRef(refNode.ID().text)

    fun newModule(def: ModuleDefContext): DependenciesTxt.Module {
        val name = def.ID().text

        val module = DependenciesTxt.Module(name)
        val kotlinFacetSettings = KotlinFacetSettings()
        module.kotlinFacetSettings = kotlinFacetSettings

        kotlinFacetSettings.useProjectSettings = false
        kotlinFacetSettings.compilerSettings = CompilerSettings().also {
            it.additionalArguments = "-version -Xmulti-platform"
        }

        val moduleRef = moduleRef(name)
        check(!moduleRef.defined) { "Module `$name` already defined" }
        moduleRef.defined = true
        moduleRef.actual = module

        def.attrs().accept { key, value ->
            if (value == null) {
                when (key) {
                    "common" -> kotlinFacetSettings.compilerArguments = K2MetadataCompilerArguments()
                    "jvm" -> kotlinFacetSettings.compilerArguments = K2JVMCompilerArguments()
                    "js" -> kotlinFacetSettings.compilerArguments = K2JSCompilerArguments()
                    else -> error("Unknown module flag `$key`")
                }
            } else error("Unknown module property `$key`")
        }

        return module
    }

    fun newDependency(def: DependencyDefContext): DependencyBuilder? {
        val from = def.moduleRef(0)
        val to = def.moduleRef(1)

        if (to == null) {
            // `x -> ` should just create undefined module `x`
            moduleRef(from)

            check(def.attrs() == null) {
                "Attributes are not allowed for `x -> ` like dependencies. Please use `x [attrs...]` syntax for module attributes."
            }

            return null
        } else {
            var exported = false
            var scope = JpsJavaDependencyScope.COMPILE
            var expectedBy = false

            def.attrs()?.accept { key, value ->
                if (value == null) {
                    when (key) {
                        "exported" -> exported = true
                        "compile" -> scope = JpsJavaDependencyScope.COMPILE
                        "test" -> scope = JpsJavaDependencyScope.TEST
                        "runtime" -> scope = JpsJavaDependencyScope.RUNTIME
                        "provided" -> scope = JpsJavaDependencyScope.PROVIDED
                        "expectedBy" -> expectedBy = true
                        else -> error("Unknown dependency flag `$key`")
                    }
                } else error("Unknown dependency property `$key`")
            }

            return DependencyBuilder(
                from = moduleRef(from),
                to = moduleRef(to),
                scope = scope,
                expectedBy = expectedBy,
                exported = exported
            ).also {
                dependencies.add(it)
            }
        }
    }

    private fun AttrsContext.accept(visit: (key: String, value: String?) -> Unit) {
        attr().forEach {
            val flagRef = it.attrFlagRef()
            val keyValue = it.attrKeyValue()

            when {
                flagRef != null -> visit(flagRef.ID().text, null)
                keyValue != null -> visit(keyValue.attrKeyRef().ID().text, keyValue.attrValueRef().ID().text)
            }
        }
    }
}
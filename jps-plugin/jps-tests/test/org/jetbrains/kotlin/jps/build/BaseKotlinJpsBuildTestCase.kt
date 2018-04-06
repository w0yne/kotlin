/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.jps.build

import org.jetbrains.jps.builders.JpsBuildTestCase
import org.jetbrains.kotlin.cli.common.KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY

abstract class BaseKotlinJpsBuildTestCase : JpsBuildTestCase() {
    private var keepAliveBackup: String? = null

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        JpsUtils.resetCaches()
        System.setProperty("kotlin.jps.tests", "true")
        keepAliveBackup = getKeepAlive()
        setKeepAlive("true")
    }

    @Throws(Exception::class)
    override fun tearDown() {
        JpsUtils.resetCaches()
        System.clearProperty("kotlin.jps.tests")
        super.tearDown()
        myModel = null
        myBuildParams.clear()
        setKeepAlive(keepAliveBackup)
    }

    private fun getKeepAlive() =
        System.getProperty(KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY)

    private fun setKeepAlive(newValue: String?) {
        when (newValue) {
            null -> System.clearProperty(KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY)
            else -> System.setProperty(KOTLIN_COMPILER_ENVIRONMENT_KEEPALIVE_PROPERTY, newValue)
        }
    }
}
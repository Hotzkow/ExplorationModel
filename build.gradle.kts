/*
 * Copyright (c) 2019. 
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
@file:Suppress("SpellCheckingInspection", "UnstableApiUsage")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = "1.4.1-SNAPSHOT"

plugins {
	id("org.jetbrains.kotlin.jvm") apply true
}

apply (plugin= "maven")

repositories {
	mavenCentral()
	maven { url = uri("https://jitpack.io") }
}

dependencies {
	compile ("com.github.hotzkow:platformInterfaceLib") {
		// in theory it should be 'api', but for some reason that does not work for transitive classpath dependencies
		version {
			require("[2.4.1,2.5[")
		}
	}
	implementation ("com.natpryce:konfig:1.6.6.0")  // configuration library
	implementation ("org.slf4j:slf4j-api:1.7.25")

	// we need the jdk dependency instead of stdlib to have enhanced java features like tue 'use' function for try-with-resources
	implementation (group= "org.jetbrains.kotlin", name= "kotlin-stdlib-jdk8")
	implementation ("org.jetbrains.kotlin:kotlin-reflect") // because we need reflection to get annotated property values
	implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.1.1")

	testImplementation (group= "junit", name= "junit", version= "4.12")
}

// compile bytecode to java 8 (default is java 6)
tasks.withType<KotlinCompile> {
	kotlinOptions.jvmTarget = JavaVersion.VERSION_1_8.toString()
}
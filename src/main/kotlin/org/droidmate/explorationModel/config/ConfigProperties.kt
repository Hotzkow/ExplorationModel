package org.droidmate.explorationModel.config

import com.natpryce.konfig.*

object ConfigProperties {
	object Output : PropertyGroup() {
		val outputDir by uriType
		val debugMode by booleanType
	}

	object ModelProperties : PropertyGroup() {
		object path : PropertyGroup() {
			val defaultBaseDir by uriType
			val statesSubDir by uriType
			val imagesSubDir by uriType
			val cleanDirs by booleanType
			val FeatureDir by uriType
		}

		object dump : PropertyGroup() {
			val sep by stringType
			val onEachAction by booleanType

			val stateFileExtension by stringType

			val traceFileExtension by stringType
			val traceFilePrefix by stringType
		}

		object imgDump : PropertyGroup() {
			val states by booleanType
			val widgets by booleanType

			object widget : PropertyGroup() {
				val nonInteractable by booleanType
				val interactable by booleanType
				val onlyWhenNoText by booleanType
			}
		}
	}
}
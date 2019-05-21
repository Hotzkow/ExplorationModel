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

package org.droidmate.explorationModel.factory

import org.droidmate.explorationModel.config.ModelConfig
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget

abstract class ModelProvider<S,W>(val config: ModelConfig)
		where S: State<W>, W: Widget {
	private var model: AbstractModel<S,W>? = null

	abstract fun init(config: ModelConfig): AbstractModel<S,W>

	abstract val stateProvider: StateFactory<S,W>
	abstract val widgetProvider: WidgetFactory<W>
	/** dummy element if a state has to be given but no widget data is available */
	val emptyState get() = stateProvider.empty()
	val emptyWidget get() = widgetProvider.empty()

	fun get() = model ?: init(config).also { model = it }
}

class DefaultModel<S,W>(override val config: ModelConfig,
                             override val stateProvider: StateFactory<S, W>,
                             override val widgetProvider: WidgetFactory<W> )
	: AbstractModel<S,W>()
		where S: State<W>, W: Widget

class DefaultModelProvider(config: ModelConfig): ModelProvider<State<Widget>,Widget>(config){
	override val stateProvider: StateFactory<State<Widget>, Widget> = DefaultStateProvider()
	override val widgetProvider: WidgetFactory<Widget> = DefaultWidgetProvider()

	override fun init(config: ModelConfig): AbstractModel<State<Widget>, Widget> = DefaultModel(config,stateProvider,widgetProvider)
}
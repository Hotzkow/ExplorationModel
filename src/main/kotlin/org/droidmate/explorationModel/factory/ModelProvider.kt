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

abstract class ModelProvider<T: AbstractModel<*,*>>(val config: ModelConfig){
	private var model: T? = null

	protected abstract fun init(config: ModelConfig): T

	fun get() = model ?: init(config).also { model = it }
}

class DefaultModel<S,W>(override val config: ModelConfig,
                             override val stateProvider: StateFactory<S, W>,
                             override val widgetProvider: WidgetFactory<W> )
	: AbstractModel<S,W>()
		where S: State<W>, W: Widget

class DefaultModelProvider(config: ModelConfig): ModelProvider<DefaultModel<State<Widget>,Widget>>(config){
	override fun init(config: ModelConfig): DefaultModel<State<Widget>, Widget>
			= DefaultModel(config,DefaultStateProvider(),DefaultWidgetProvider())
}
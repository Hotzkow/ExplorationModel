package org.droidmate.explorationModel.retention.loading

import kotlinx.coroutines.CoroutineScope
import org.droidmate.explorationModel.debugOut
import org.droidmate.explorationModel.factory.ModelProvider
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger

internal interface ParserI<T,out R, S, W> where S: State<W>, W: Widget {
	val modelProvider: ModelProvider<S, W>
	val model get() = modelProvider.get()

	val processor: suspend (s: List<String>, scope: CoroutineScope) -> T
	suspend fun getElem(e: T): R

	val logger: Logger
	val enableDebug get() = false
	fun log(msg: String)
		 = debugOut("[${Thread.currentThread().name}] $msg", enableDebug)

	val compatibilityMode: Boolean
	val enableChecks: Boolean
	/** assert that a condition [c] is fulfilled or apply the [repair] function if compatibilityMode is enabled
	 * if neither [c] is fulfilled nor compatibilityMode is enabled we throw an assertion error with message [msg]
	 */
	suspend fun verify(msg:String,c: suspend ()->Boolean,repair: suspend ()->Unit){
		if(!enableChecks) return
		if(!compatibilityMode) {
			if (!c())
				throw IllegalStateException("invalid DefaultModel(enable compatibility mode to attempt transformation to valid state):\n$msg")
		} else if(!c()){
			logger.debug("had to apply repair function due to parse error '$msg' in thread [${Thread.currentThread().name}]")
			repair()
		}
	}

	/**
	 * verify that condition [c] is fulfilled and throw an [IllegalStateException] otherwise.
	 * If the model could be automatically repared please use the alternative verify method and provide a repair function
	 */
	suspend fun verify(msg:String,c: suspend ()->Boolean){
		verify(msg,c) {}
	}
}
@file:Suppress("EXPERIMENTAL_API_USAGE")

package org.droidmate.explorationModel.retention.loading

import com.natpryce.konfig.CommandLineOption
import com.natpryce.konfig.getValue
import com.natpryce.konfig.parseArgs
import com.natpryce.konfig.stringType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.explorationModel.*
import org.droidmate.explorationModel.config.*
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.retention.StringCreator
import org.droidmate.explorationModel.retention.StringCreator.headerFor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.coroutines.CoroutineContext

/** public interface, used to parse any model
 * @modelProvider only required for extendedModel types to parse the correct type instance and use overwitten functions, use format (appName,modelCfg)->ExtendedModel
 * **/
@Suppress("unused")
object ModelParser{
	private val logger: Logger = LoggerFactory.getLogger(javaClass)
	@JvmOverloads suspend fun loadModel(config: ModelConfig, watcher: LinkedList<ModelFeatureI> = LinkedList(),
	                                    autoFix: Boolean = false, sequential: Boolean = false, enablePrint: Boolean = false,
	                                    contentReader: ContentReader = ContentReader(config), enableChecks: Boolean = true,
	                                    customHeaderMap: Map<String,String> = emptyMap(),
	                                    modelProvider: (ModelConfig)->Model = {Model.emptyModel(config)})
			: Model{
		if(sequential) return debugT("model loading (sequential)", {
			ModelParserS(config, compatibilityMode = autoFix, enablePrint = enablePrint, reader = contentReader, enableChecks = enableChecks, modelProvider = modelProvider).loadModel(watcher, customHeaderMap)
		}, inMillis = true)
		return debugT("model loading (parallel)", {
			ModelParserP(config, compatibilityMode = autoFix, enablePrint = enablePrint, reader = contentReader, enableChecks = enableChecks, modelProvider = modelProvider).loadModel(watcher, customHeaderMap)
		}, inMillis = true)
	}

	/** returning the model map of origId->newId for states and widgets */
	suspend fun loadAndRepair(config: ModelConfig,
	                          modelProvider: (ModelConfig)->Model = {Model.emptyModel(config)},
	                          sequential: Boolean = true): Triple<Model, Map<ConcreteId,ConcreteId>,Map<ConcreteId,ConcreteId>>{
		val parser = if (sequential)
			ModelParserS(config, compatibilityMode = true, enablePrint = false, reader = ContentReader(config), enableChecks = true, modelProvider = modelProvider)
		else
			ModelParserP(config, compatibilityMode = true, enablePrint = false, reader = ContentReader(config), enableChecks = true, modelProvider = modelProvider)

		val model = parser.loadModel(LinkedList(), emptyMap())
		logger.info("model parsing complete '${config.appName}' : $model, state repairs = ${parser.stateMap.size}, widget repairs = ${parser.widgetMap.size}")
		return Triple(model,parser.stateMap,parser.widgetMap)
	}

}

internal abstract class ModelParserI<T,S,W>: ParserI<T, Pair<Interaction, State>>, CoroutineScope{
//	override 	val enableDebug get() = true

	abstract val config: ModelConfig
	abstract val reader: ContentReader
	abstract val stateParser: StateParserI<S, W>
	abstract val widgetParser: WidgetParserI<W>
	abstract val enablePrint: Boolean
	abstract val isSequential: Boolean
	abstract val stateMap: Map<ConcreteId,ConcreteId>
	abstract val widgetMap: Map<ConcreteId,ConcreteId>

	override val logger: Logger = LoggerFactory.getLogger(javaClass)

	abstract val modelProvider: (ModelConfig)->Model
	override val model by lazy{ modelProvider(config) }
	protected val actionParseJobName: (List<String>)->String = { actionS ->
		"actionParser ${actionS[Interaction.srcStateIdx]}->${actionS[Interaction.resStateIdx]}"}

	// watcher state restoration for ModelFeatureI should be automatically handled via trace.updateAll (these are independent from the explorationContext)
	suspend fun loadModel(watcher: LinkedList<ModelFeatureI> = LinkedList(), customHeaderMap: Map<String,String> = emptyMap()): Model = withContext(this.coroutineContext){
		// this will wait for all coroutines launched in this scope
		stateParser.headerRenaming = customHeaderMap
		// the very first state of any trace is always an empty state which is automatically added on Model initialization
		addEmptyState()
		// start producer who just sends trace paths to the multiple trace processor jobs
		val producer = traceProducer()
		logger.debug("producer successfully launched")
		repeat(if(isSequential) 1 else 5){  // process up to 5 exploration traces in parallel
			launch { traceProcessor( producer, watcher )}
		}
		clearQueues()
		return@withContext model
	}
	private fun clearQueues() {
		stateParser.queue.clear()
		widgetParser.queue.clear()
	}
	abstract suspend fun addEmptyState()

	protected open fun traceProducer() =
		produce<Path>(context = Dispatchers.IO+CoroutineName("trace Producer"), capacity = 5) {
			logger.trace("PRODUCER CALL")
			@Suppress("BlockingMethodInNonBlockingContext") // should be handled by Dispatchers.IO but the highlighting is incorrect when using + context
			Files.list(Paths.get(config.baseDir.toUri())).use { s ->
				s.filter { it.fileName.toString().startsWith(config[ConfigProperties.ModelProperties.dump.traceFilePrefix]) }
					.also {
						for (p in it) {
							logger.trace("producer: trace-file {}",p.fileName)
							send(p)
						}
					}
			}
		}

	private val modelMutex = Mutex()

	private suspend fun traceProcessor(channel: ReceiveChannel<Path>, watcher: LinkedList<ModelFeatureI>) {
		logger.trace("trace processor launched")
		if(enablePrint) logger.info("trace processor launched")
		channel.consumeEach { tracePath ->
			if(enablePrint) logger.info("\nprocess TracePath $tracePath")
			val traceId =
				try {
					UUID.fromString(tracePath.fileName.toString()
						.removePrefix(config[ConfigProperties.ModelProperties.dump.traceFilePrefix])
						.removeSuffix(config[ConfigProperties.ModelProperties.dump.traceFileExtension]))
				}catch(e:IllegalArgumentException){ // tests do not use valid UUIDs but rather int indices
					emptyUUID
				}
			modelMutex.withLock { model.initNewTrace(watcher, traceId) }
				.let { trace ->
					val actionPairs = reader.processLines(tracePath, lineProcessor = processor)
					// use maximal parallelism to process the single actions/states
					if(actionPairs.isNotEmpty()) {
						if (watcher.isEmpty()) {
							val resState = getElem(actionPairs.last()).second
							logger.debug(" wait for completion of actions")
							trace.updateAll(actionPairs.map { getElem(it).first }, resState)
						}  // update trace actions
						else {
							logger.debug(" wait for completion of EACH action")
							actionPairs.forEach { getElem(it).let { (action, resState) -> trace.update(action, resState) } }
						}
					} else {
						logger.info("trace $traceId is empty")
					}
				}
			logger.debug("CONSUMED trace {}",tracePath.fileName)
		}
	}

	/** parse a single action this function is called in the processor either asynchronous (Deferred) or sequential (blocking) */
	suspend fun parseAction(actionS: List<String>): Pair<Interaction, State> {
		if(enablePrint) println("\n\t ---> parse action $actionS")
		val resId = ConcreteId.fromString(actionS[Interaction.resStateIdx])!!
		val resState = stateParser.queue.computeIfAbsent(resId, stateParser.parseIfAbsent(coroutineContext)).getState()
		val targetWidgetId = widgetParser.fixedWidgetId(actionS[Interaction.widgetIdx])

		val srcId = ConcreteId.fromString(actionS[Interaction.srcStateIdx])!!
		val srcState = stateParser.queue.computeIfAbsent(srcId,stateParser.parseIfAbsent(coroutineContext)).getState()
		verify("ERROR stateId changed, recompute target widget $targetWidgetId in source state $srcId", {

			log("wait for srcState $srcId")
			targetWidgetId == null || resState.stateId == resId
		}){ // repair function
			val actionType = actionS[Interaction.actionTypeIdx]
			var srcS = stateParser.queue[srcId]
			while(srcS == null) { // due to concurrency the value is not yet written to queue -> wait a bit
				delay(5)
				srcS = stateParser.queue[srcId]
			}
			val mapped = widgetMap[targetWidgetId]
			val target = srcState.widgets.find{it.id == mapped} ?: srcState.widgets.find{it.id == targetWidgetId && rightActionType(it, actionType)}
			if(targetWidgetId != null && target == null) {
				logger.warn("no id mappig found for source widget $targetWidgetId choose the first match with same uid")
				val possibleTargets = srcState.widgets.filter {
					targetWidgetId.uid == it.uid && rightActionType(it, actionType)
				}.let{
					if(it.isEmpty()){
						val uidM = srcState.widgets.filter { targetWidgetId.uid == it.uid }
						logger.warn("cannot find any element with matching actiontype (e.g. if there was a forced click to a non-clickable target), choose first from $uidM")
						uidM
					} else it
				}
				when (possibleTargets.size) {
					0 -> throw IllegalStateException("cannot re-compute targetWidget $targetWidgetId in state $srcId")
					1 -> widgetParser.addFixedWidgetId(targetWidgetId, possibleTargets.first().id)
					else -> {
						logger.warn("WARN there are multiple options for the interacted target widget we just chose the first one")
						widgetParser.addFixedWidgetId(targetWidgetId, possibleTargets.first().id)
					}
				}
			}
		}
		val targetWidget = widgetParser.fixedWidgetId(targetWidgetId)?.let { tId ->
			srcState.widgets.find { it.id == tId }
		}
		val fixedActionS = mutableListOf<String>().apply { addAll(actionS) }
		fixedActionS[Interaction.resStateIdx] = resState.stateId.toString()
		fixedActionS[Interaction.srcStateIdx] = srcState.stateId.toString()  //do NOT use local val srcId as that may be the old id

		if(actionS!=fixedActionS)
			logger.trace("id's changed due to automatic repair new action is \n $fixedActionS\n instead of \n $actionS")

		return Pair(StringCreator.parseActionPropertyString(fixedActionS, targetWidget), resState)
			.also { (action,_) ->
				log("\n computed TRACE ${actionS[Interaction.resStateIdx]}: $action")
			}
	}
	private val rightActionType: (Widget, actionType: String)->Boolean = { w, t ->
		w.enabled && when{
			t.isTick() -> w.checked != null
			t.isClick() -> w.clickable || w.checked != null || w.longClickable  // allow for long-clickable due to clickOrLongClickAction
			t.isLongClick() -> w.longClickable
			t.isTextInsert() -> w.isInputField
			else -> false
		}
	}


	@Suppress("ReplaceSingleLineLet")
	suspend fun S.getState() = this.let{ e ->  stateParser.getElem(e) }

	companion object {

		/**
		 * helping/debug function to manually load a model.
		 * The directory containing the 'model' folder and the app name have to be specified, e.g.
		 * '--Output-outputDir=pathToModelDir --appName=sampleApp'
		 * --Core-debugMode=true (optional for enabling print-outs)
		 */
		@JvmStatic fun main(args: Array<String>) {
			// stateDiff(args)
//			runBlocking {
			val appName by stringType
			val cfg = parseArgs(args,
				CommandLineOption(ConfigProperties.Output.outputDir),
				CommandLineOption(appName)
			).first
			val config = ModelConfig(cfg[appName], true, cfg = cfg)
			//REMARK old models are most likely not compatible, since the idHash may not be unique (probably some bug on UI extraction)
			@Suppress("UNUSED_VARIABLE")
			val headerMap = mapOf(
				"HashId" to headerFor(UiElementPropertiesI::idHash)!!,
				"widget class" to headerFor(UiElementPropertiesI::className)!!,
				"Text" to headerFor(UiElementPropertiesI::text)!!,
				"Description" to headerFor(UiElementPropertiesI::contentDesc)!!,
				"Enabled" to headerFor(UiElementPropertiesI::enabled)!!,
				"Visible" to headerFor(UiElementPropertiesI::definedAsVisible)!!,
				"Clickable" to headerFor(UiElementPropertiesI::clickable)!!,
				"LongClickable" to headerFor(UiElementPropertiesI::longClickable)!!,
				"Scrollable" to headerFor(UiElementPropertiesI::scrollable)!!,
				"Checked" to headerFor(UiElementPropertiesI::checked)!!,
				"Editable" to headerFor(UiElementPropertiesI::isInputField)!!,
				"Focused" to headerFor(UiElementPropertiesI::focused)!!,
				"IsPassword" to headerFor(UiElementPropertiesI::isPassword)!!,
				"XPath" to headerFor(UiElementPropertiesI::xpath)!!,
				"PackageName" to headerFor(UiElementPropertiesI::packageName)!!
				//MISSING translation of BoundsX,..Y,..Width,..Height to visibleBoundaries
				//MISSING instead of parentHash we had parentID persisted
			)

			val m =
				//				loadModel(config, autoFix = false, sequential = true)
				runBlocking { ModelParser.loadModel(config, autoFix = false, sequential = true, enablePrint = false//, customHeaderMap = headerMap
				)}.also { println(it) }
			println("performance test")
			var ts =0L
			var tp =0L
			runBlocking {
				repeat(10) {
					debugT("load time sequential", { ModelParserS(config).loadModel() },
						timer = { time -> ts += time / 1000000 },
						inMillis = true).also { time -> println(time) }
					debugT("load time parallel", { ModelParserP(config).loadModel() },
						timer = { time -> tp += time / 1000000 },
						inMillis = true).also { time -> println(time) }
				}
			}
			println(" overall time \nsequential = $ts avg=${ts/10000.0} \nparallel = $tp avg=${tp/10000.0}")
			/** dump the (repaired) model */ /*
			runBlocking {
				m.dumpModel(ModelConfig("repaired-${config.appName}", cfg = cfg))
				m.modelDumpJob.joinChildren()
			}
			// */
			println("model load finished: ${config.appName} $m")
		}
//		}
	} /** end COMPANION **/

}

internal open class ModelParserP(override val config: ModelConfig, override val reader: ContentReader = ContentReader(config),
                                 override val compatibilityMode: Boolean = false, override val enablePrint: Boolean = false,
                                 override val enableChecks: Boolean = true,
                                 override val modelProvider: (ModelConfig) -> Model = {Model.emptyModel(config)})
	: ModelParserI<Deferred<Pair<Interaction, State>>, Deferred<State>, Deferred<UiElementPropertiesI>>() {
	override val coroutineContext: CoroutineContext by lazy { Job() + CoroutineName("P-ModelParser ${config.appName}(${config.baseDir}") + Dispatchers.IO }
	override val isSequential: Boolean = false

	override val widgetParser by lazy { WidgetParserP(model, compatibilityMode, enableChecks) }
	override val stateParser  by lazy { StateParserP(widgetParser, reader, model, compatibilityMode, enableChecks) }
	override val stateMap by lazy{ stateParser.idMapping }
	override val widgetMap by lazy{ widgetParser.idMapping }

	override val processor: suspend (s: List<String>, CoroutineScope) -> Deferred<Pair<Interaction, State>> = { actionS, _ ->
		CoroutineScope(coroutineContext+Job()).async(CoroutineName(actionParseJobName(actionS))) { parseAction(actionS) }
	}

	override suspend fun addEmptyState() {
		model.emptyState.let{ stateParser.queue[it.stateId] =  CoroutineScope(coroutineContext).async(CoroutineName("empty State")) { it } }
	}

	override suspend fun getElem(e: Deferred<Pair<Interaction, State>>): Pair<Interaction, State> = e.await()

}

private class ModelParserS(override val config: ModelConfig, override val reader: ContentReader = ContentReader(config),
                           override val compatibilityMode: Boolean = false, override val enablePrint: Boolean = false,
                           override val enableChecks: Boolean = true,
                           override val modelProvider: (ModelConfig) -> Model = {Model.emptyModel(config)})
	: ModelParserI<Pair<Interaction, State>, State, UiElementPropertiesI>() {
	override val coroutineContext: CoroutineContext = Job() + CoroutineName("S-ModelParser ${config.appName}(${config.baseDir}") + Dispatchers.IO
	override val isSequential: Boolean = true

	override val widgetParser by lazy { WidgetParserS(model, compatibilityMode, enableChecks) }
	override val stateParser  by lazy { StateParserS(widgetParser, reader, model, compatibilityMode, enableChecks) }
	override val stateMap by lazy{ stateParser.idMapping }
	override val widgetMap by lazy{ widgetParser.idMapping }

	override val processor: suspend (s: List<String>, CoroutineScope) -> Pair<Interaction, State> = { actionS:List<String>, _ ->
		parseAction(actionS)
	}

	override suspend fun addEmptyState() {
		model.emptyState.let{ stateParser.queue[it.stateId] = it }
	}

	override suspend fun getElem(e: Pair<Interaction, State>): Pair<Interaction, State> = e

}
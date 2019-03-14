package org.droidmate.explorationModel.interaction

import org.droidmate.deviceInterface.communication.TimeFormattedLogMessageI
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.explorationModel.ConcreteId
import org.droidmate.explorationModel.emptyId
import org.droidmate.explorationModel.retention.StringCreator
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

typealias DeviceLog = TimeFormattedLogMessageI
typealias DeviceLogs = List<DeviceLog>

@Suppress("DataClassPrivateConstructor")
/**
 * @actionId used to map this interaction to the coresponding result screenshot, which is named according to this value
 */
open class Interaction (
		@property:Persistent("Action", 1) val actionType: String,
		@property:Persistent("Interacted Widget", 2, PType.ConcreteId) val targetWidget: Widget?,
		@property:Persistent("StartTime", 4, PType.DateTime) val startTimestamp: LocalDateTime,
		@property:Persistent("EndTime", 5, PType.DateTime) val endTimestamp: LocalDateTime,
		@property:Persistent("SuccessFul", 6, PType.Boolean) val successful: Boolean,
		@property:Persistent("Exception", 7) val exception: String,
		@property:Persistent("Source State", 0, PType.ConcreteId) val prevState: ConcreteId,
		@property:Persistent("Resulting State", 3, PType.ConcreteId) val resState: ConcreteId,
		@property:Persistent("Action-Id", 9, PType.Int) val actionId: Int,
		@property:Persistent("Data", 8) val data: String = "",
		val deviceLogs: DeviceLogs = emptyList(),
		@Suppress("unused") val meta: String = "") {

	constructor(res: ActionResult, prevStateId: ConcreteId, resStateId: ConcreteId, target: Widget?)
			: this(actionType = res.action.name, targetWidget = target,
			startTimestamp = res.startTimestamp, endTimestamp = res.endTimestamp, successful = res.successful,
			exception = res.exception, prevState = prevStateId, resState = resStateId, data = computeData(res.action),
			deviceLogs = res.deviceLogs,	meta = res.action.id.toString(), actionId = res.action.id)

	/** used for ActionQueue entries */
	constructor(action: ExplorationAction, res: ActionResult, prevStateId: ConcreteId, resStateId: ConcreteId, target: Widget?)
			: this(action.name, target, res.startTimestamp,
			res.endTimestamp, successful = res.successful, exception = res.exception, prevState = prevStateId,
			resState = resStateId, data = computeData(action), deviceLogs = res.deviceLogs, actionId = action.id)

	/** used for ActionQueue start/end Interaction */
	internal constructor(actionName:String, res: ActionResult, prevStateId: ConcreteId, resStateId: ConcreteId)
			: this(actionName, null, res.startTimestamp,
			res.endTimestamp, successful = res.successful, exception = res.exception, prevState = prevStateId,
			resState = resStateId, deviceLogs = res.deviceLogs, actionId = res.action.id)

	/** used for parsing from string */
	constructor(actionType: String, target: Widget?, startTimestamp: LocalDateTime, endTimestamp: LocalDateTime,
	            successful: Boolean, exception: String, resState: ConcreteId, prevState: ConcreteId, data: String = "", actionId: Int)
			: this(actionType = actionType, targetWidget = target, startTimestamp = startTimestamp, endTimestamp = endTimestamp,
			successful = successful, exception = exception, prevState = prevState, resState = resState, data = data, actionId = actionId)


	/**
	 * Time the strategy pool took to select a strategy and a create an action
	 * (used to measure overhead for new exploration strategies)
	 */
	val decisionTime: Long by lazy { ChronoUnit.MILLIS.between(startTimestamp, endTimestamp) }

	companion object {

		@JvmStatic val actionTypeIdx = StringCreator.actionProperties.indexOfFirst { it.property == Interaction::actionType }
		@JvmStatic val widgetIdx = StringCreator.actionProperties.indexOfFirst { it.property == Interaction::targetWidget }
		@JvmStatic val resStateIdx = StringCreator.actionProperties.indexOfFirst { it.property == Interaction::resState }
		@JvmStatic val srcStateIdx = StringCreator.actionProperties.indexOfFirst { it.property == Interaction::prevState }

		@JvmStatic
		fun computeData(e: ExplorationAction):String = when(e){
			is TextInsert -> e.text
			is Swipe -> "${e.start.first},${e.start.second} TO ${e.end.first},${e.end.second}"
			is RotateUI -> e.rotation.toString()
			else -> ""
		}

		@JvmStatic
		val empty: Interaction by lazy {
			Interaction("EMPTY", null, LocalDateTime.MIN, LocalDateTime.MIN, true,
					"root action", emptyId, prevState = emptyId, actionId = -1)
		}

	}

	override fun toString(): String {
		@Suppress("ReplaceSingleLineLet")
		return "$actionType: widget[${targetWidget?.let { it.toString() }}]:\n$prevState->$resState"
	}

	fun copy(prevState: ConcreteId, resState: ConcreteId): Interaction
		= Interaction(actionType = actionType, targetWidget = targetWidget, startTimestamp = startTimestamp,
			endTimestamp = endTimestamp, successful = successful, exception = exception,
			prevState = prevState, resState = resState, data = data, deviceLogs = deviceLogs, meta = meta, actionId = actionId)
}

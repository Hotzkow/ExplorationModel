package org.droidmate.explorationModel

import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.retention.StringCreator

fun Widget.dataString(sep: String) = StringCreator.createPropertyString(this,sep)
fun Interaction.actionString(sep: String) = StringCreator.createActionString(this,sep)
internal val emptyState = State(emptyList())

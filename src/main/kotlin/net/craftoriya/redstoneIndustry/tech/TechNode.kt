package net.craftoriya.redstoneIndustry

import net.craftoriya.adaptersLib.model.ItemContainer
import java.util.BitSet
import java.util.UUID

data class TechNode(
    val id: String,
    val prerequisites: Set<String>,
    val cost: List<ItemContainer>,
    val unlocks: Set<String>
)
class TechTree(nodes: List<TechNode>) {
    val byId: Map<String, TechNode> = nodes.associateBy { it.id }
}

data class Team(val id: UUID, val members: MutableSet<UUID>, val techs: TeamTechStates)

class TeamTechStates(private val unlocked: MutableSet<String> = hashSetOf()) {
    fun isUnlocked(id: String) = id in unlocked
    fun unlock(id: String) = unlocked.add(id)
}

class TeamUnlockState(private val bits: BitSet) {
    fun has(recipeIdx: Int) = bits.get(recipeIdx)
    fun grant(recipeIdx: Int) = bits.set(recipeIdx)
}
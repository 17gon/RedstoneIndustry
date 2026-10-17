package net.craftoriya.redstoneIndustry.tech.state

class TeamTechStates(private val unlocked: MutableSet<String> = hashSetOf()) {
    fun isUnlocked(id: String) = id in unlocked
    fun unlock(id: String) = unlocked.add(id)
}
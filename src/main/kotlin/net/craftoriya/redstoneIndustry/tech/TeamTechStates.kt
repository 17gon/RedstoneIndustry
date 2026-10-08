package net.craftoriya.redstoneIndustry.tech

class TeamTechStates(private val unlocked: MutableSet<String> = hashSetOf()) {
    fun isUnlocked(id: String) = id in unlocked
    fun unlock(id: String) = unlocked.add(id)
}
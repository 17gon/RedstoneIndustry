package net.craftoriya.redstoneIndustry.tech

import java.util.UUID

class TechStates {
    private val teams = HashMap<UUID, TeamTechStates>()
    fun of(player: UUID): TeamTechStates = teams.getOrPut(player) { TeamTechStates() }
}
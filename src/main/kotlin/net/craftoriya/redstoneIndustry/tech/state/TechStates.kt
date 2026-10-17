package net.craftoriya.redstoneIndustry.tech.state

import java.util.UUID

class TechStates {
    private val teams = HashMap<UUID, TeamTechStates>()
    fun of(player: UUID): TeamTechStates = teams.getOrPut(player) { TeamTechStates() }
}
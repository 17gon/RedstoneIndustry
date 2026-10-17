package net.craftoriya.redstoneIndustry.tech.state

import java.util.UUID

data class TechTeam(val id: UUID, val members: MutableSet<UUID>, val techs: TeamTechStates)

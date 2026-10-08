package net.craftoriya.redstoneIndustry.tech

import java.util.UUID

data class TechTeam(val id: UUID, val members: MutableSet<UUID>, val techs: TeamTechStates)

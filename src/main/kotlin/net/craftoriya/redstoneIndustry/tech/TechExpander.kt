package net.craftoriya.redstoneIndustry.tech

import net.craftoriya.redstoneIndustry.tech.dto.TechTreeDto

object TechExpander {
    fun expand(config: TechTreeDto): TechTree =
        TechTree(config.nodes.map { dto ->
            TechNode(
                dto.id, dto.category, dto.icon, dto.prerequisites, dto.cost, dto.time, dto.unlocks,
                dto.boosts.map { TechBoost(it.target, it.stat, it.op, it.value) }
            )
        })
}
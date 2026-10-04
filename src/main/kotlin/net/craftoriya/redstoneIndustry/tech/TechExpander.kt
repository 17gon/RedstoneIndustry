package net.craftoriya.redstoneIndustry

object TechExpander {
    fun expand(config: TechTreeDto): TechTree {
        return TechTree(config.nodes.map { expandNodes(it) })
    }

    private fun expandNodes(dto: TechNodeDto): TechNode {
        return TechNode(dto.id, dto.prerequisites, dto.cost, dto.unlocks)
    }
}
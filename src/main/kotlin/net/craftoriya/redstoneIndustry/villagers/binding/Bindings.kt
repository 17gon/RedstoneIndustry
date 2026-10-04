package net.craftoriya.redstoneIndustry.villagers.binding

import net.craftoriya.adaptersLib.model.BlockContainer
import net.craftoriya.adaptersLib.model.LocationIContainer

enum class ContainerMode { AUTO, IN, OUT, IO, OFF }

data class StationData(
    val input: LocationIContainer?,
    val output: LocationIContainer?,
    val bound: List<LocationIContainer> = emptyList()
)

data class ContainerData(val mode: ContainerMode, val stations: List<LocationIContainer>)

enum class BlockKind { CONTAINER, WORKSTATION, OTHER }

class BlockKinds(
    val containers: Set<String> = setOf("CHEST", "TRAPPED_CHEST"),
    val workstations: Set<String> = setOf(
        "BLAST_FURNACE", "SMOKER", "CARTOGRAPHY_TABLE", "BREWING_STAND", "COMPOSTER", "FLETCHING_TABLE",
        "CAULDRON", "LECTERN", "STONECUTTER", "LOOM", "SMITHING_TABLE", "GRINDSTONE", "BARREL"
    )
) {
    fun of(material: String): BlockKind = when (material) {
        in containers -> BlockKind.CONTAINER
        in workstations -> BlockKind.WORKSTATION
        else -> BlockKind.OTHER
    }

    fun of(block: BlockContainer): BlockKind = of(block.material)
}
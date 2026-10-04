package net.craftoriya.redstoneIndustry.villagers.link

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerMode

data class ContainerRef(
    val location: LocationIContainer,
    val mode: ContainerMode = ContainerMode.AUTO,
    val hopperBelow: Boolean = false
)
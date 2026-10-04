package net.craftoriya.redstoneIndustry

import net.craftoriya.adaptersLib.model.ItemContainer
import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class TechNodeDto(
    val id: String = "",
    val prerequisites: Set<String> = emptySet(),
    val cost: List<ItemContainer> = emptyList(),
    val unlocks: Set<String> = emptySet(),
)
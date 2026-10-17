package net.craftoriya.redstoneIndustry.tech.dto

import net.craftoriya.adaptersLib.model.item.ItemContainer
import org.spongepowered.configurate.objectmapping.ConfigSerializable


@ConfigSerializable
data class TechNodeDto(
    val id: String = "",
    val category: String = "GENERAL",
    val icon: String = "PAPER",
    val prerequisites: Set<String> = emptySet(),
    val cost: List<ItemContainer> = emptyList(),
    val time: Int = 0,
    val unlocks: Set<String> = emptySet(),
    val boosts: List<TechBoostDto> = emptyList()
)
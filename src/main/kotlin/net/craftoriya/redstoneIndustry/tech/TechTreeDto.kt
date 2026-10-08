package net.craftoriya.redstoneIndustry.tech

import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class TechTreeDto(val nodes: List<TechNodeDto> = emptyList())
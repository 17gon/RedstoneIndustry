package net.craftoriya.redstoneIndustry.tech.dto

import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class TechTreeDto(val nodes: List<TechNodeDto> = emptyList())
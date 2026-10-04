package net.craftoriya.redstoneIndustry.villagers

import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class VillagerConfig(
    val containerRange: Int = 8
)
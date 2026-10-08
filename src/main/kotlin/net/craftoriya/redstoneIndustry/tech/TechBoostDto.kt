package net.craftoriya.redstoneIndustry.tech

import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class TechBoostDto(
    val target: String = "",
    val stat: String = "",
    val op: String = "MULTIPLY",
    val value: Double = 1.0
)
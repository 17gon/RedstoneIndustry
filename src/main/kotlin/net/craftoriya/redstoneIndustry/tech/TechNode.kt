package net.craftoriya.redstoneIndustry.tech

import net.craftoriya.adaptersLib.model.ItemContainer


data class TechNode(
    val id: String,
    val category: String,
    val icon: String,
    val prerequisites: Set<String>,
    val cost: List<ItemContainer>,
    val time: Int,
    val unlocks: Set<String>,
    val boosts: List<TechBoost>
)
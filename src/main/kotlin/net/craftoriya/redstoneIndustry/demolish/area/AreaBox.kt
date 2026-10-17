package net.craftoriya.redstoneIndustry.demolish.area

import net.craftoriya.adaptersLib.model.math.Vec3I

data class AreaBox(val min: Vec3I, val max: Vec3I) {
    val volume: Long get() = (max.x - min.x + 1L) * (max.y - min.y + 1L) * (max.z - min.z + 1L)

    fun contains(vec: Vec3I) = vec.x in min.x..max.x && vec.y in min.y..max.y && vec.z in min.z..max.z

    companion object {
        fun of(first: Vec3I, second: Vec3I) = AreaBox(
            Vec3I(minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z)),
            Vec3I(maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z))
        )
    }
}
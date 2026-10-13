package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.Vec3I
import java.util.UUID

data class ZoneData(val id: UUID, val version: Long, val demolish: AreaBox?, val safe: List<AreaBox>) {
    fun isSafe(v: Vec3I) = safe.any { it.contains(v) }

    companion object {
        val ORDER: Comparator<ZoneData> = compareBy<ZoneData>(
            { it.demolish?.min?.x ?: Int.MIN_VALUE },
            { it.demolish?.min?.y ?: Int.MIN_VALUE },
            { it.demolish?.min?.z ?: Int.MIN_VALUE }
        ).thenBy { it.id }
    }
}
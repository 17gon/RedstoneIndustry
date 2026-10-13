package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKind
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds

internal class DemolishRules(
    private val world: IWorldQueryPort,
    private val kinds: BlockKinds
) {
    fun isAir(material: String) = material == "AIR" || material == "CAVE_AIR" || material == "VOID_AIR"

    fun demolishable(zone: DemolishZone, loc: LocationIContainer): Boolean = material(zone, loc) != null

    fun material(zone: DemolishZone, loc: LocationIContainer): String? {
        val block = world.blockAt(loc) ?: return null
        if (isAir(block.material)) return null
        if (kinds.of(block) != BlockKind.OTHER) return null
        if (zone.data.isSafe(loc.vec3I) || loc in zone.protect) return null
        return if (liquidRisk(loc)) null else block.material
    }

    private fun liquidRisk(at: LocationIContainer): Boolean {
        val self = world.info(at) ?: return true
        if (self.liquid || self.waterlogged) return true

        val vec = at.vec3I
        var top = vec.y
        while (top - vec.y < MAX_COLUMN && world.info(LocationIContainer(at.world, Vec3I(vec.x, top + 1, vec.z)))?.falling == true) top++

        return LIQUID_SIDES.any { (dx, dy, dz) ->
            world.info(LocationIContainer(at.world, Vec3I(vec.x + dx, top + dy, vec.z + dz)))?.liquid ?: true
        }
    }

    private companion object {
        const val MAX_COLUMN = 64
        val LIQUID_SIDES = listOf(
            Triple(0, 1, 0), Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1)
        )
    }
}
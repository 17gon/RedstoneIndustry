package net.craftoriya.redstoneIndustry.demolish.rule

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.demolish.work.DemolishWorker
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKind
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds

internal sealed interface Verdict {
    data object Skip : Verdict
    data object Defer : Verdict
    data class Break(val material: String) : Verdict
    data object Scoop : Verdict
    data object Fill : Verdict
}

internal class DemolishRules(
    private val world: IWorldQueryPort,
    private val kinds: BlockKinds
) {
    fun isAir(material: String) = material == "AIR" || material == "CAVE_AIR" || material == "VOID_AIR"

    fun isFluid(material: String) = material == "WATER" || material == "LAVA" || material == "POWDER_SNOW"

    fun decide(zone: DemolishZone, loc: LocationIContainer, force: Boolean): Verdict {
        val block = world.blockAt(loc) ?: return Verdict.Defer
        if (isAir(block.material)) return Verdict.Skip
        if (kinds.of(block) != BlockKind.OTHER) return Verdict.Skip
        if (zone.data.isSafe(loc.vec3I) || loc in zone.protect) return Verdict.Skip

        val info = world.info(loc) ?: return Verdict.Defer
        if (info.scoopable) return Verdict.Scoop
        if (info.liquid) return Verdict.Fill
        if (info.waterlogged) return Verdict.Skip

        if (!force) {
            if (zone.filter.isNotEmpty()) {
                if (block.material !in zone.filter) return Verdict.Skip
            } else if (!info.fullBlock) {
                return Verdict.Skip
            }
        }
        if (hazardAround(loc)) return Verdict.Defer
        if (!force && !exposed(loc)) return Verdict.Defer
        return Verdict.Break(block.material)
    }

    private fun exposed(at: LocationIContainer): Boolean {
        val v = at.vec3I
        return FACES.any { (dx, dy, dz) ->
            val i = world.info(LocationIContainer(at.world, Vec3I(v.x + dx, v.y + dy, v.z + dz)))
            i != null && i.passable && !i.liquid
        }
    }

    private fun hazardAround(at: LocationIContainer): Boolean {
        val vec = at.vec3I
        var top = vec.y
        while (top - vec.y < MAX_COLUMN &&
            world.info(LocationIContainer(at.world, Vec3I(vec.x, top + 1, vec.z)))?.falling == true
        ) top++

        return LIQUID_SIDES.any { (dx, dy, dz) ->
            val i = world.info(LocationIContainer(at.world, Vec3I(vec.x + dx, top + dy, vec.z + dz)))
            if (i == null) true else i.liquid || i.scoopable
        }
    }

    fun blocks(w: DemolishWorker): Int = w.carried.filter { world.canPlace(it.material) }.sumOf { it.count }

    fun useBlock(w: DemolishWorker, place: (String) -> Boolean): Boolean {
        val i = w.carried.indexOfFirst { it.count > 0 && world.canPlace(it.material) }
        if (i < 0) return false
        val item = w.carried[i]
        if (!place(item.material)) return false
        if (item.count <= 1) w.carried.removeAt(i) else w.carried[i] = item.copy(count = item.count - 1)
        return true
    }

    private companion object {
        const val MAX_COLUMN = 64
        val LIQUID_SIDES = listOf(
            Triple(0, 1, 0), Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1)
        )
        val FACES = LIQUID_SIDES + Triple(0, -1, 0)
    }
}
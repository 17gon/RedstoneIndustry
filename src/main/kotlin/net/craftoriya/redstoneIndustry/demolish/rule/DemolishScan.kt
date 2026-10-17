package net.craftoriya.redstoneIndustry.demolish.rule

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.demolish.area.AreaBox
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import kotlin.math.abs

internal class Slice(val cells: ArrayDeque<Vec3I>) {
    val retry = ArrayList<Vec3I>()
    var progressed = false
}

internal class BandScan(
    private val box: AreaBox,
    val top: Int,
    val bottom: Int,
    private val lanes: List<List<Int>>,
    private val startForward: Boolean,
) {
    private val width = box.max.x - box.min.x + 1
    private var lane = 0
    private var step = 0

    val exhausted: Boolean get() = lane >= lanes.size

    fun next(): Pair<Int, List<Int>>? {
        if (exhausted) return null
        val zs = lanes[lane]
        val forward = startForward xor (lane % 2 == 1)
        val x = if (forward) box.min.x + step else box.max.x - step
        if (++step >= width) {
            step = 0
            lane++
        }
        return x to zs
    }
}

internal class DemolishScan(
    private val world: IWorldQueryPort,
    private val rules: DemolishRules
) {
    fun claim(zone: DemolishZone): Slice? {
        zone.retryQueue.removeFirstOrNull()?.let { return it }
        val scan = zone.scan ?: return null
        var budget = SLICES_PER_CLAIM
        while (budget-- > 0) {
            val (x, zs) = scan.next() ?: return null
            slice(zone, scan, x, zs)?.let { return it }
        }
        return null
    }

    fun advance(zone: DemolishZone) {
        val scan = zone.scan
        if (scan == null) {
            begin(zone)
            return
        }
        if (!scan.exhausted || zone.retryQueue.isNotEmpty()) return
        if (zone.workers.values.any { it.slice != null || it.climb != null || it.carried.isNotEmpty() }) return

        if (zone.skipped.isNotEmpty() && zone.progress) {
            zone.retryQueue.addAll(zone.skipped)
            zone.skipped.clear()
            zone.progress = false
            return
        }
        zone.skipped.clear()
        zone.progress = false
        zone.climbFailed = false
        zone.scan = null
        zone.badStands.clear()
        zone.layerY -= BAND
        if (zone.layerY < zone.box.min.y) {
            if (zone.cycleBroke) {
                zone.cycleBroke = false
                zone.layerY = zone.box.max.y
            } else {
                zone.done = true
            }
        }
    }

    private fun begin(zone: DemolishZone) {
        val b = zone.box
        val o = zone.workers.values.firstNotNullOfOrNull { it.link?.output?.vec3I } ?: b.min
        val zs: List<Int> =
            if (abs(o.z - b.max.z) < abs(o.z - b.min.z)) (b.max.z downTo b.min.z).toList() else (b.min.z..b.max.z).toList()
        val forward = abs(o.x - b.min.x) <= abs(o.x - b.max.x)
        val bottom = maxOf(zone.layerY - (BAND - 1), b.min.y)
        zone.scan = BandScan(b, zone.layerY, bottom, zs.chunked(LANE_WIDTH), forward)
    }

    private fun slice(zone: DemolishZone, scan: BandScan, x: Int, zs: List<Int>): Slice? {
        val fluids = ArrayList<Vec3I>()
        val blocks = ArrayList<Vec3I>()

        val below = scan.bottom - 1
        if (below >= zone.box.min.y) {
            for (z in zs) {
                val vec = Vec3I(x, below, z)
                val block = world.blockAt(LocationIContainer(zone.world, vec)) ?: continue
                if (rules.isFluid(block.material)) fluids += vec
            }
        }
        for (z in zs) for (y in scan.top downTo scan.bottom) {
            val vec = Vec3I(x, y, z)
            val block = world.blockAt(LocationIContainer(zone.world, vec)) ?: continue
            when {
                rules.isAir(block.material) -> {}
                rules.isFluid(block.material) -> fluids += vec
                else -> blocks += vec
            }
        }
        if (fluids.isEmpty() && blocks.isEmpty()) return null
        return Slice(ArrayDeque<Vec3I>(fluids.size + blocks.size).also { it.addAll(fluids); it.addAll(blocks) })
    }

    internal companion object {
        const val LANE_WIDTH = 3
        const val BAND = 2
        const val SLICES_PER_CLAIM = 64
    }

}
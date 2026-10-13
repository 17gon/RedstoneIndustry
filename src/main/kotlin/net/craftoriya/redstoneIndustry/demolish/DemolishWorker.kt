package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.ItemSource
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.redstoneIndustry.villagers.StationLinkCache
import java.util.UUID

internal enum class Phase { PICK, MOVE, BREAK, DELIVER }

internal enum class Begin { STARTED, WAIT, SKIP }

internal sealed interface ToolResult {
    data class Ticks(val n: Int) : ToolResult
    data object Missing : ToolResult
    data object Unbreakable : ToolResult
}

internal class Climb(var cell: Vec3I) {
    var prev: Vec3I? = null
    var heading: Pair<Int, Int>? = null
    var spin = 1
    var next: Vec3I? = null
    var steps = 0
    var fixes = 0
    val pending = ArrayDeque<Vec3I>()
}

internal class DemolishWorker(
    val id: UUID,
    val site: LocationIContainer,
    val stats: DemolishStats
) {
    var stop = false
    var phase = Phase.PICK

    val queue = ArrayDeque<Vec3I>()

    var stand: Vec3I? = null
    var atStand = false
    var moveTicks = 0

    var target: LocationIContainer? = null
    var ticksLeft = 0
    var carried: MutableList<ItemContainer> = ArrayList()
    var waitUntil = 0L

    var tool: ItemSource? = null
    var toolBlock = ""
    var toolUntil = 0L
    val held = HashSet<Int>()

    var link: StationLinkCache.ResolvedLink? = null
    var linkUntil = 0L

    var climb: Climb? = null
}

internal fun cellKey(x: Int, y: Int, z: Int): Long =
    ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
package net.craftoriya.redstoneIndustry.demolish.work

import net.craftoriya.adaptersLib.model.item.ItemContainer
import net.craftoriya.adaptersLib.model.item.ItemSource
import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.redstoneIndustry.demolish.rule.Slice
import net.craftoriya.redstoneIndustry.villagers.container.StationLinkCache
import java.util.UUID

internal enum class Phase { PICK, MOVE, BREAK, DELIVER }

internal enum class Act { BREAK, SCOOP, FILL }

internal enum class Begin { STARTED, WAIT, SKIP, DEFER }

internal sealed interface ToolResult {
    data class Ticks(val n: Int) : ToolResult
    data object Missing : ToolResult
    data object Unbreakable : ToolResult
}

internal sealed interface ClimbOp {
    data class Dig(val v: Vec3I) : ClimbOp
    data class Put(val v: Vec3I) : ClimbOp
}

internal class Climb(var cell: Vec3I) {
    var prev: Vec3I? = null
    var heading: Pair<Int, Int>? = null
    var spin = 1
    var next: Vec3I? = null
    var steps = 0
    var fixes = 0
    val pending = ArrayDeque<ClimbOp>()
}

internal class DemolishWorker(
    val id: UUID,
    val site: LocationIContainer
) {
    var stop = false
    var phase = Phase.PICK
    var waitUntil = 0L

    var slice: Slice? = null

    var stand: Vec3I? = null
    var atStand = false
    var moveTicks = 0

    var act = Act.BREAK
    var target: LocationIContainer? = null
    var ticksLeft = 0
    var bucketSrc: ItemSource? = null
    var carried: MutableList<ItemContainer> = ArrayList()

    var tool: ItemSource? = null
    var toolBlock = ""
    var toolUntil = 0L
    val held = HashSet<Int>()

    var link: StationLinkCache.ResolvedLink? = null
    var linkUntil = 0L

    var climb: Climb? = null

    fun carriedItems(): Int = carried.sumOf { it.count }
}
package net.craftoriya.redstoneIndustry.demolish.area

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.redstoneIndustry.demolish.rule.BandScan
import net.craftoriya.redstoneIndustry.demolish.work.DemolishWorker
import net.craftoriya.redstoneIndustry.demolish.rule.Slice
import java.util.UUID

internal class DemolishZone(var data: ZoneData, val world: String, startY: Int? = null) {
    val id: UUID get() = data.id
    var box: AreaBox = data.demolish!!

    var nextReport = 0L
    var refreshAt = 0L

    val workers = LinkedHashMap<UUID, DemolishWorker>()
    var done = false
    var stopping = false

    var layerY = startY ?: box.max.y
    var scan: BandScan? = null

    val retryQueue = ArrayDeque<Slice>()

    val skipped = ArrayList<Slice>()

    var progress = false

    var cycleBroke = false

    var filter: Set<String> = emptySet()
    var protect: Set<LocationIContainer> = emptySet()

    var walk: Set<Vec3I> = emptySet()
    var walkAt = -1000L

    val badStands = HashSet<Vec3I>()
    var climbFailed = false
    var climber: UUID? = null

    fun queued(): Int = retryQueue.sumOf { it.cells.size } + skipped.sumOf { it.cells.size }
}
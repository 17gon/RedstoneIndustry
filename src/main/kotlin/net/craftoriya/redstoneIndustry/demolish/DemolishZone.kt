package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import java.util.UUID

internal class DemolishZone(var data: ZoneData, val world: String, startTick: Long) {
    val id: UUID get() = data.id
    var box: AreaBox = data.demolish!!

    val stats = DemolishStats(startTick)
    var nextReport = 0L
    var refreshAt = 0L

    val workers = LinkedHashMap<UUID, DemolishWorker>()
    var done = false
    var stopping = false

    var layerY = box.max.y
    var layerLoaded = false
    var progress = false
    var dirtyTop: Int? = null

    val leftover = ArrayList<Vec3I>()
    var rebalance = false
    val skipped = ArrayList<Vec3I>()

    var walk: Set<Vec3I> = emptySet()
    var changeVer = 0L
    var walkVer = -1L
    var walkAt = 0L
    val pass = HashMap<Long, Boolean>()

    var filter: Set<String> = emptySet()
    var protect: Set<LocationIContainer> = emptySet()

    var climbFailed = false
    var climber: UUID? = null

    fun queued() = workers.values.sumOf { it.queue.size } + leftover.size
}
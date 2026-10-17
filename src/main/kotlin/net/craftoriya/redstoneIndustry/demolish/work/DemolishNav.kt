package net.craftoriya.redstoneIndustry.demolish.work

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.entity.IEntityPort
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import net.craftoriya.redstoneIndustry.demolish.rule.Slice
import java.util.UUID
import kotlin.math.floor

internal class DemolishNav(
    private val world: IWorldQueryPort,
    private val entities: IEntityPort
) {
    fun standable(at: LocationIContainer): Boolean {
        val v = at.vec3I
        val feet = world.info(at) ?: return false
        val head = world.info(LocationIContainer(at.world, Vec3I(v.x, v.y + 1, v.z))) ?: return false
        val below = world.info(LocationIContainer(at.world, Vec3I(v.x, v.y - 1, v.z))) ?: return false
        return feet.passable && !feet.liquid && !feet.hazard &&
                head.passable && !head.liquid && !head.hazard &&
                below.solid && !below.hazard
    }

    fun feet(id: UUID): Vec3I? {
        val p = entities.position(id) ?: return null
        return Vec3I(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt())
    }

    fun reaches(stand: Vec3I, t: Vec3I): Boolean {
        val dx = (t.x + 0.5) - (stand.x + 0.5)
        val dy = (t.y + 0.5) - (stand.y + EYE)
        val dz = (t.z + 0.5) - (stand.z + 0.5)
        return dx * dx + dy * dy + dz * dz <= REACH * REACH
    }

    fun findStand(zone: DemolishZone, w: DemolishWorker, slice: Slice, target: Vec3I, now: Long): Vec3I? {
        if (zone.walk.isEmpty() || now - zone.walkAt > WALK_TTL) refreshWalk(zone, now)
        val found = search(zone, w, slice, target)
        if (found != null || now - zone.walkAt < REBUILD_MIN) return found
        refreshWalk(zone, now)
        return search(zone, w, slice, target)
    }

    fun refreshWalk(zone: DemolishZone, now: Long) {
        zone.walk = walkable(zone)
        zone.walkAt = now
    }

    private fun search(zone: DemolishZone, w: DemolishWorker, slice: Slice, target: Vec3I): Vec3I? {
        val taken = HashSet<Vec3I>()
        for (o in zone.workers.values) if (o !== w) o.stand?.let { taken += it }

        var best: Vec3I? = null
        var bestCover = 0
        var seen = 0
        for (o in OFFSETS) {
            val c = Vec3I(target.x + o.x, target.y + o.y, target.z + o.z)
            if (c in taken || c in zone.badStands || c !in zone.walk || !reaches(c, target)) continue
            var cover = 0
            for (r in slice.cells) if (reaches(c, r)) cover++
            if (cover > bestCover) {
                best = c
                bestCover = cover
            }
            if (cover == slice.cells.size || ++seen >= MAX_CANDIDATES) break
        }
        return best
    }

    fun walkable(zone: DemolishZone): Set<Vec3I> {
        val box = zone.box
        val worldName = zone.world
        val groundTop = (zone.workers.values.maxOfOrNull { it.site.vec3I.y } ?: return emptySet()) + OUTSIDE_UP
        val memo = HashMap<Vec3I, Boolean>()
        fun ok(v: Vec3I) = memo.getOrPut(v) { standable(LocationIContainer(worldName, v)) }

        val seen = HashSet<Vec3I>()
        val queue = ArrayDeque<Vec3I>()
        for (worker in zone.workers.values) {
            val p = feet(worker.id) ?: continue
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val s = Vec3I(p.x + dx, p.y + dy, p.z + dz)
                if (ok(s) && seen.add(s)) queue += s
            }
        }
        if (queue.isEmpty()) return emptySet()

        while (queue.isNotEmpty() && seen.size < WALK_CAP) {
            val c = queue.removeFirst()
            for ((dx, dz) in HORIZONTAL) for (dy in -1..1) {
                val n = Vec3I(c.x + dx, c.y + dy, c.z + dz)
                if (n.x !in box.min.x - MARGIN..box.max.x + MARGIN) continue
                if (n.y !in box.min.y - MARGIN..box.max.y + MARGIN) continue
                if (n.z !in box.min.z - MARGIN..box.max.z + MARGIN) continue
                val outside = n.x !in box.min.x..box.max.x || n.z !in box.min.z..box.max.z
                if (outside && n.y > groundTop) continue
                if (n in seen || !ok(n)) continue
                seen += n
                queue += n
            }
        }
        return seen
    }

    internal companion object {
        const val REACH = 4.5
        const val EYE = 1.62
        const val RADIUS = 4
        const val MAX_CANDIDATES = 6
        const val WALK_CAP = 4000
        const val WALK_TTL = 100L
        const val REBUILD_MIN = 10L
        const val MARGIN = 6
        const val OUTSIDE_UP = 1
        val HORIZONTAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val OFFSETS: List<Vec3I> = buildList {
            for (dx in -RADIUS..RADIUS) for (dy in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) add(Vec3I(dx, dy, dz))
        }.sortedBy { it.x * it.x + it.y * it.y + it.z * it.z }
    }
}
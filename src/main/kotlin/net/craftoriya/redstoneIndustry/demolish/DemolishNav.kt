package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IEntityPort
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

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

    fun refreshWalk(zone: DemolishZone, worker: DemolishWorker, now: Long): Boolean {
        val fresh = zone.walk.isNotEmpty() &&
                zone.walkVer == zone.changeVer &&
                now - zone.walkAt < WALK_MAX_AGE &&
                standingInWalk(zone, worker)
        if (fresh) {
            zone.stats.walkSkips++
            return false
        }
        zone.walk = walkable(zone, now)
        return true
    }

    private fun standingInWalk(zone: DemolishZone, worker: DemolishWorker): Boolean {
        val pos = entities.position(worker.id) ?: return false
        return Vec3I(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt()) in zone.walk
    }

    fun walkable(zone: DemolishZone, now: Long): Set<Vec3I> {
        zone.stats.walkRebuilds++
        zone.walkVer = zone.changeVer
        zone.walkAt = now
        zone.pass.clear()

        val box = zone.box
        val world = zone.world
        val groundTop = (zone.workers.values.maxOfOrNull { it.site.vec3I.y } ?: return emptySet()) + OUTSIDE_UP
        val memo = HashMap<Vec3I, Boolean>()
        fun ok(v: Vec3I) = memo.getOrPut(v) { standable(LocationIContainer(world, v)) }

        val seen = HashSet<Vec3I>()
        val queue = ArrayDeque<Vec3I>()
        for (worker in zone.workers.values) {
            val p = entities.position(worker.id) ?: continue
            val px = floor(p.x).toInt()
            val py = floor(p.y).toInt()
            val pz = floor(p.z).toInt()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val s = Vec3I(px + dx, py + dy, pz + dz)
                if (ok(s) && seen.add(s)) queue += s
            }
        }
        if (queue.isEmpty()) {
            zone.stats.noStand++
            return emptySet()
        }

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

    fun findStand(zone: DemolishZone, w: DemolishWorker, head: Vec3I): Vec3I? {
        val ahead = w.queue.take(LOOKAHEAD)
        val taken = HashSet<Vec3I>()
        for (o in zone.workers.values) if (o !== w) o.stand?.let { taken += it }

        var best: Vec3I? = null
        var bestScore = -1
        var bestD = Double.MAX_VALUE
        for (c in zone.walk) {
            if (c in taken || !visible(zone, c, head)) continue
            var score = 0
            for (t in ahead) if (visible(zone, c, t)) score++
            val dx = c.x - head.x.toDouble()
            val dy = c.y - head.y.toDouble()
            val dz = c.z - head.z.toDouble()
            val d = dx * dx + dy * dy + dz * dz
            if (score > bestScore || (score == bestScore && d < bestD)) {
                best = c
                bestScore = score
                bestD = d
            }
        }
        return best
    }

    fun reachableIndex(zone: DemolishZone, w: DemolishWorker): Int {
        for ((i, t) in w.queue.withIndex()) {
            if (i >= SCAN) break
            if (findStand(zone, w, t) != null) return i
        }
        return -1
    }

    fun visible(zone: DemolishZone, stand: Vec3I, t: Vec3I): Boolean {
        val ex = stand.x + 0.5
        val ey = stand.y + EYE
        val ez = stand.z + 0.5
        val dx = t.x + 0.5 - ex
        val dy = t.y + 0.5 - ey
        val dz = t.z + 0.5 - ez
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > REACH * REACH) return false
        val dist = sqrt(d2)

        val steps = ceil(dist / 0.25).toInt().coerceAtLeast(1)
        var lx = Int.MIN_VALUE
        var ly = Int.MIN_VALUE
        var lz = Int.MIN_VALUE
        for (i in 1 until steps) {
            val f = i.toDouble() / steps
            val x = floor(ex + dx * f).toInt()
            val y = floor(ey + dy * f).toInt()
            val z = floor(ez + dz * f).toInt()
            if (x == lx && y == ly && z == lz) continue
            lx = x
            ly = y
            lz = z
            if (x == t.x && y == t.y && z == t.z) return true
            if (x == stand.x && z == stand.z && (y == stand.y || y == stand.y + 1)) continue
            if (!passable(zone, x, y, z)) return false
        }
        return true
    }

    private fun passable(zone: DemolishZone, x: Int, y: Int, z: Int): Boolean =
        zone.pass.getOrPut(cellKey(x, y, z)) {
            world.info(LocationIContainer(zone.world, Vec3I(x, y, z)))?.passable ?: false
        }

    internal companion object {
        const val REACH = 4.5
        const val EYE = 1.62
        const val LOOKAHEAD = 12
        const val WALK_CAP = 4000
        const val MARGIN = 6
        const val OUTSIDE_UP = 1
        const val WALK_MAX_AGE = 40L
        const val SCAN = 32
        val HORIZONTAL = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}
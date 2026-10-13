package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IBlockBreakPort
import net.craftoriya.adaptersLib.port.IVillagerControlPort
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.StationLinkCache
import kotlin.math.abs

internal typealias BeginFn = (DemolishZone, DemolishWorker, StationLinkCache.ResolvedLink, Vec3I, Long, Boolean) -> Begin

internal class DemolishClimb(
    private val world: IWorldQueryPort,
    private val breaker: IBlockBreakPort,
    private val villagers: IVillagerControlPort,
    private val nav: DemolishNav,
    private val rules: DemolishRules
) {
    private class Plan(val n: Vec3I, val breaks: List<Vec3I>, val d: Pair<Int, Int>)
    private class Opt(val plan: Plan, val edge: Int, val rank: Int)

    fun needed(zone: DemolishZone, worker: DemolishWorker): Boolean {
        val box = zone.box
        val top = zone.walk
            .filter { it.x in box.min.x - 1..box.max.x + 1 && it.z in box.min.z - 1..box.max.z + 1 }
            .maxOfOrNull { it.y } ?: return false
        return worker.queue.any { it.y > top }
    }

    fun start(zone: DemolishZone, worker: DemolishWorker): Boolean {
        val s: Vec3I? = climbStart(zone, worker)
        if (s == null) {
            zone.climbFailed = true
            zone.stats.climbNoStart++
            return false
        }
        worker.climb = Climb(s)
        zone.climber = worker.id
        zone.stats.climbs++
        return true
    }

    fun step(zone: DemolishZone, worker: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long, begin: BeginFn) {
        val climb = worker.climb ?: return
        if (worker.carried.isNotEmpty()) { worker.phase = Phase.DELIVER; return }
        val world = zone.world

        if (!worker.atStand || worker.stand != climb.cell) {
            worker.stand = climb.cell
            worker.atStand = false
            worker.moveTicks = 0
            worker.phase = Phase.MOVE
            return
        }

        if (climb.pending.isNotEmpty()) {
            val t = climb.pending.first()
            if (this@DemolishClimb.world.info(LocationIContainer(world, t))?.passable == true) { climb.pending.removeFirst(); return }
            when (begin(zone, worker, link, t, now, true)) {
                Begin.STARTED -> climb.pending.removeFirst()
                Begin.WAIT -> {}
                Begin.SKIP -> abort(zone, worker, "cannot break $t")
            }
            return
        }

        val n = climb.next
        if (n != null) {
            val refill = listOf(n, Vec3I(n.x, n.y + 1, n.z))
                .filter { this@DemolishClimb.world.info(LocationIContainer(world, it))?.passable != true }
            if (refill.isNotEmpty()) {
                if (++climb.fixes > MAX_FIX) { abort(zone, worker, "shaft keeps refilling"); return }
                climb.pending.addAll(refill)
                return
            }
            climb.prev = climb.cell
            climb.cell = n
            climb.next = null
            climb.steps++
            zone.stats.climbSteps++
            return
        }

        zone.walk = nav.walkable(zone, now)
        val alt = nav.reachableIndex(zone, worker)
        if (alt >= 0) {
            val t = worker.queue.removeAt(alt)
            worker.queue.addFirst(t)
            release(zone, worker)
            worker.stand = null
            worker.atStand = false
            return
        }
        if (climb.steps >= zone.box.max.y - zone.box.min.y + CLIMB_EXTRA) { abort(zone, worker, "too many steps"); return }

        val plan = planStep(zone, worker, climb) ?: run { abort(zone, worker, "no way from ${climb.cell}"); return }
        val h = climb.heading
        if (h != null && plan.d != h) climb.spin = if (plan.d == turn(h, 1)) 1 else -1
        climb.heading = plan.d
        climb.next = plan.n
        climb.pending.addAll(plan.breaks)
    }

    fun abort(zone: DemolishZone, worker: DemolishWorker, why: String) {
        Trace.log { "demolish: ${worker.id.toString().take(8)} climb abort: $why" }
        zone.stats.climbAborts++
        zone.climbFailed = true
        release(zone, worker)
        worker.stand = null
        worker.atStand = false
        villagers.setTarget(worker.id, null)
    }

    private fun release(zone: DemolishZone, worker: DemolishWorker) {
        worker.climb = null
        if (zone.climber == worker.id) zone.climber = null
    }

    private fun cornerDist(b: AreaBox, v: Vec3I): Int {
        var best = Int.MAX_VALUE
        for (cx in intArrayOf(b.min.x, b.max.x)) for (cz in intArrayOf(b.min.z, b.max.z)) {
            best = minOf(best, abs(v.x - cx) + abs(v.z - cz))
        }
        return best
    }

    private fun climbStart(zone: DemolishZone, worker: DemolishWorker): Vec3I? {
        val box = zone.box
        val near = zone.walk.filter { it.x in box.min.x - 1..box.max.x + 1 && it.z in box.min.z - 1..box.max.z + 1 }
        val sorted = near.sortedWith(compareBy({ -it.y }, { cornerDist(box, it) }))
        val probe = Climb(Vec3I(0, 0, 0))
        for (s in sorted.take(START_TRIES)) {
            probe.cell = s
            if (planStep(zone, worker, probe) != null) return s
        }
        return null
    }

    private fun turn(h: Pair<Int, Int>, spin: Int): Pair<Int, Int> = if (spin >= 0) (-h.second to h.first) else (h.second to -h.first)

    private fun planStep(zone: DemolishZone, worker: DemolishWorker, climb: Climb): Plan? {
        val box = zone.box
        val from = climb.cell
        val opts = ArrayList<Opt>()
        for ((dx, dz) in DemolishNav.HORIZONTAL) {
            val nx = from.x + dx
            val nz = from.z + dz
            if (nx !in box.min.x..box.max.x || nz !in box.min.z..box.max.z) continue
            val prev = climb.prev
            if (prev != null && prev.x == nx && prev.z == nz) continue
            val breaks = stepBreaks(zone, worker, from, nx, nz) ?: continue
            val edge = minOf(nx - box.min.x, box.max.x - nx, nz - box.min.z, box.max.z - nz)
            val d = dx to dz
            val h = climb.heading
            val rank = when {
                h == null || h == d -> 0
                d == turn(h, climb.spin) -> 1
                else -> 2
            }
            opts += Opt(Plan(Vec3I(nx, from.y + 1, nz), breaks, d), edge, rank)
        }
        return opts.minWithOrNull(compareBy<Opt>({ it.edge }, { it.rank }))?.plan
    }

    private fun stepBreaks(zone: DemolishZone, worker: DemolishWorker, from: Vec3I, nx: Int, nz: Int): List<Vec3I>? {
        val world = zone.world
        val floor = this@DemolishClimb.world.info(LocationIContainer(world, Vec3I(nx, from.y, nz))) ?: return null
        if (!floor.solid || floor.hazard || floor.liquid || floor.waterlogged) return null
        val cells = listOf(
            Vec3I(from.x, from.y + 2, from.z),
            Vec3I(nx, from.y + 1, nz),
            Vec3I(nx, from.y + 2, nz),
            Vec3I(nx, from.y + 3, nz)
        )
        val out = ArrayList<Vec3I>()
        for (v in cells) {
            val loc = LocationIContainer(world, v)
            val i = this@DemolishClimb.world.info(loc) ?: return null
            if (i.liquid || i.waterlogged || i.hazard) return null
            if (i.passable) continue
            if (!zone.box.contains(v) || !rules.demolishable(zone, loc) || breaker.breakTicks(worker.id, loc) == null) return null
            out += v
        }
        return out
    }

    private companion object {
        const val START_TRIES = 40
        const val MAX_FIX = 20
        const val CLIMB_EXTRA = 8
    }
}
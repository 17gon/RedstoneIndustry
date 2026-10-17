package net.craftoriya.redstoneIndustry.demolish.work

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IBlockBreakPort
import net.craftoriya.adaptersLib.port.entity.IVillagerControlPort
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.demolish.area.AreaBox
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishRules
import net.craftoriya.redstoneIndustry.demolish.rule.Verdict
import kotlin.math.abs

internal class DemolishClimb(
    private val world: IWorldQueryPort,
    private val breaker: IBlockBreakPort,
    private val villagers: IVillagerControlPort,
    private val nav: DemolishNav,
    private val rules: DemolishRules
) {
    private class Plan(val n: Vec3I, val ops: List<ClimbOp>, val d: Pair<Int, Int>)
    private class Opt(val plan: Plan, val edge: Int, val rank: Int)

    fun needed(worker: DemolishWorker, t: Vec3I): Boolean {
        val f = nav.feet(worker.id) ?: return false
        return t.y >= f.y - 1
    }

    fun start(zone: DemolishZone, worker: DemolishWorker, now: Long): Boolean {
        nav.refreshWalk(zone, now)
        val s: Vec3I? = climbStart(zone, worker)
        if (s == null) {
            Trace.log { "demolish: climb has no start, walk=${zone.walk.size}" }
            zone.climbFailed = true
            return false
        }
        Trace.log { "demolish: climb start $s worker=${worker.id.toString().take(8)}" }
        worker.climb = Climb(s)
        zone.climber = worker.id
        return true
    }

    fun step(zone: DemolishZone, worker: DemolishWorker, now: Long, begin: (Vec3I) -> Begin) {
        val climb = worker.climb ?: return

        if (!worker.atStand || worker.stand != climb.cell) {
            worker.stand = climb.cell
            worker.atStand = false
            worker.moveTicks = 0
            worker.phase = Phase.MOVE
            return
        }

        val op = climb.pending.firstOrNull()
        if (op != null) { run(zone, worker, climb, op, begin); return }

        val n = climb.next
        if (n != null) { settle(zone, worker, climb, n); return }

        val slice = worker.slice
        val t = slice?.cells?.firstOrNull()
        if (slice == null || t == null || nav.findStand(zone, worker, slice, t, now) != null) {
            finish(zone, worker)
            return
        }
        if (climb.steps >= zone.box.max.y - zone.box.min.y + CLIMB_EXTRA) { abort(zone, worker, "too many steps"); return }

        val plan = planStep(zone, worker, climb) ?: run { abort(zone, worker, "no way from ${climb.cell}"); return }
        val h = climb.heading
        if (h != null && plan.d != h) climb.spin = if (plan.d == turn(h, 1)) 1 else -1
        climb.heading = plan.d
        climb.next = plan.n
        climb.pending.addAll(plan.ops)
    }

    fun abort(zone: DemolishZone, worker: DemolishWorker, why: String) {
        Trace.log { "demolish: ${worker.id.toString().take(8)} climb abort: $why" }
        zone.climbFailed = true
        finish(zone, worker)
        villagers.setTarget(worker.id, null)
    }

    private fun finish(zone: DemolishZone, worker: DemolishWorker) {
        worker.climb = null
        if (zone.climber == worker.id) zone.climber = null
        worker.stand = null
        worker.atStand = false
    }

    private fun run(zone: DemolishZone, worker: DemolishWorker, climb: Climb, op: ClimbOp, begin: (Vec3I) -> Begin) {
        when (op) {
            is ClimbOp.Dig -> {
                if (world.info(LocationIContainer(zone.world, op.v))?.passable == true) { climb.pending.removeFirst(); return }
                when (begin(op.v)) {
                    Begin.STARTED -> climb.pending.removeFirst()
                    Begin.WAIT -> {}
                    else -> abort(zone, worker, "cannot break ${op.v}")
                }
            }
            is ClimbOp.Put -> {
                val loc = LocationIContainer(zone.world, op.v)
                if (!rules.useBlock(worker) { breaker.place(loc, it) }) { abort(zone, worker, "cannot place ${op.v}"); return }
                climb.pending.removeFirst()
            }
        }
    }

    private fun settle(zone: DemolishZone, worker: DemolishWorker, climb: Climb, n: Vec3I) {
        val ops = stepOps(zone, worker, climb.cell, n.x, n.z)
        if (ops == null) { abort(zone, worker, "step blocked at $n"); return }
        if (ops.isNotEmpty()) {
            if (++climb.fixes > MAX_FIX) { abort(zone, worker, "shaft keeps changing"); return }
            climb.pending.addAll(ops)
            return
        }
        climb.prev = climb.cell
        climb.cell = n
        climb.next = null
        climb.steps++
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

    private fun turn(h: Pair<Int, Int>, spin: Int): Pair<Int, Int> =
        if (spin >= 0) (-h.second to h.first) else (h.second to -h.first)

    private fun planStep(zone: DemolishZone, worker: DemolishWorker, climb: Climb): Plan? {
        val box = zone.box
        val from = climb.cell
        val have = rules.blocks(worker)
        val opts = ArrayList<Opt>()
        for ((dx, dz) in DemolishNav.HORIZONTAL) {
            val nx = from.x + dx
            val nz = from.z + dz
            if (nx !in box.min.x..box.max.x || nz !in box.min.z..box.max.z) continue
            val prev = climb.prev
            if (prev != null && prev.x == nx && prev.z == nz) continue
            val ops = stepOps(zone, worker, from, nx, nz) ?: continue
            if (ops.count { it is ClimbOp.Put } > have) continue
            val edge = minOf(nx - box.min.x, box.max.x - nx, nz - box.min.z, box.max.z - nz)
            val d = dx to dz
            val h = climb.heading
            val rank = when {
                h == null || h == d -> 0
                d == turn(h, climb.spin) -> 1
                else -> 2
            }
            opts += Opt(Plan(Vec3I(nx, from.y + 1, nz), ops, d), edge, rank)
        }
        return opts.minWithOrNull(compareBy<Opt>({ it.edge }, { it.rank }))?.plan
    }

    private fun stepOps(zone: DemolishZone, worker: DemolishWorker, from: Vec3I, nx: Int, nz: Int): List<ClimbOp>? {
        val name = zone.world
        val dig = ArrayList<ClimbOp>()
        val put = ArrayList<ClimbOp>()

        val floor = Vec3I(nx, from.y, nz)
        val support = Vec3I(nx, from.y - 1, nz)
        val fi = world.info(LocationIContainer(name, floor)) ?: return null
        if (fi.liquid || fi.waterlogged || fi.hazard) return null
        if (fi.passable) {
            val si = world.info(LocationIContainer(name, support)) ?: return null
            if (si.liquid || si.waterlogged || si.hazard) return null
            if (si.passable) put += ClimbOp.Put(support)
            put += ClimbOp.Put(floor)
        }

        val cells = listOf(
            Vec3I(from.x, from.y + 2, from.z),
            Vec3I(nx, from.y + 1, nz),
            Vec3I(nx, from.y + 2, nz),
            Vec3I(nx, from.y + 3, nz)
        )
        for (v in cells) {
            val loc = LocationIContainer(name, v)
            val i = world.info(loc) ?: return null
            if (i.liquid || i.waterlogged || i.hazard) return null
            if (i.passable) continue
            if (!zone.box.contains(v) || rules.decide(zone, loc, true) !is Verdict.Break || breaker.breakTicks(worker.id, loc) == null) return null
            dig += ClimbOp.Dig(v)
        }
        return dig + put
    }

    private companion object {
        const val START_TRIES = 40
        const val MAX_FIX = 20
        const val CLIMB_EXTRA = 8
    }
}
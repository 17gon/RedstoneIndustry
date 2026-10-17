package net.craftoriya.redstoneIndustry.demolish.work

import net.craftoriya.adaptersLib.model.item.ItemContainer
import net.craftoriya.adaptersLib.model.item.ItemSource
import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3D
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IBlockBreakPort
import net.craftoriya.adaptersLib.port.world.IContainerPort
import net.craftoriya.adaptersLib.port.entity.IEntityPort
import net.craftoriya.adaptersLib.port.entity.IVillagerControlPort
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishRules
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishScan
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishTools
import net.craftoriya.redstoneIndustry.demolish.rule.Slice
import net.craftoriya.redstoneIndustry.demolish.rule.Verdict
import net.craftoriya.redstoneIndustry.villagers.container.StationLinkCache
import kotlin.math.abs
import kotlin.math.ceil

internal class DemolishCrew(
    private val world: IWorldQueryPort,
    private val entities: IEntityPort,
    private val containers: IContainerPort,
    private val breaker: IBlockBreakPort,
    private val villagers: IVillagerControlPort,
    private val rules: DemolishRules,
    private val nav: DemolishNav,
    private val tools: DemolishTools,
    private val scan: DemolishScan,
    private val climber: DemolishClimb,
    private val timeFactor: Double
) {
    fun step(zone: DemolishZone, w: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long) {
        when (w.phase) {
            Phase.PICK -> pick(zone, w, link, now)
            Phase.MOVE -> move(zone, w)
            Phase.BREAK -> work(zone, w)
            Phase.DELIVER -> deliver(w, link, now)
        }
    }

    private fun pick(zone: DemolishZone, w: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long) {
        if (w.climb != null) {
            climber.step(zone, w, now) { t -> begin(zone, w, link, t, now, true) }
            return
        }
        if (w.carriedItems() >= CARRY_ITEMS || w.carried.size >= CARRY_STACKS) { toDeliver(w); return }

        val slice = w.slice ?: claim(zone, w, now) ?: return
        val t = slice.cells.firstOrNull() ?: run { finishSlice(zone, w, slice); return }

        val loc = LocationIContainer(zone.world, t)
        val verdict = rules.decide(zone, loc, false)
        when (verdict) {
            Verdict.Skip -> { slice.cells.removeFirst(); return }
            Verdict.Defer -> { slice.cells.removeFirst(); slice.retry += t; return }
            else -> {}
        }
        if (!ready(zone, w, slice, t, now)) return

        when (start(w, link, loc, verdict, now)) {
            Begin.STARTED, Begin.WAIT -> {}
            Begin.SKIP -> slice.cells.remove(t)
            Begin.DEFER -> { slice.cells.remove(t); slice.retry += t }
        }
    }

    private fun claim(zone: DemolishZone, w: DemolishWorker, now: Long): Slice? {
        val s = scan.claim(zone)
        if (s != null) {
            w.slice = s
            return s
        }
        val sc = zone.scan
        if (w.carried.isNotEmpty() && sc != null && sc.exhausted) toDeliver(w) else w.waitUntil = now + IDLE_WAIT
        return null
    }

    private fun finishSlice(zone: DemolishZone, w: DemolishWorker, slice: Slice) {
        if (slice.retry.isNotEmpty()) {
            if (slice.progressed) {
                slice.cells.addAll(slice.retry)
                slice.retry.clear()
                slice.progressed = false
                return
            }
            zone.skipped += Slice(ArrayDeque(slice.retry))
        }
        w.slice = null
    }

    private fun ready(zone: DemolishZone, w: DemolishWorker, slice: Slice, t: Vec3I, now: Long): Boolean {
        val st = w.stand
        if (st != null && w.atStand && nav.reaches(st, t)) return true

        val next = nav.findStand(zone, w, slice, t, now)
        if (next == null) { noStand(zone, w, slice, t, now); return false }
        w.stand = next
        w.atStand = false
        w.moveTicks = 0
        w.phase = Phase.MOVE
        return false
    }

    private fun noStand(zone: DemolishZone, w: DemolishWorker, slice: Slice, t: Vec3I, now: Long) {
        Trace.log { "demolish: no stand for $t worker=${w.id.toString().take(8)} walk=${zone.walk.size} climbFailed=${zone.climbFailed}" }
        if (!zone.climbFailed && climber.needed(w, t)) {
            val other = zone.climber
            if (other != null && other != w.id) { w.waitUntil = now + CLIMB_WAIT; return }
            if (climber.start(zone, w, now)) return
        }
        slice.cells.remove(t)
        slice.retry += t
    }

    private fun begin(zone: DemolishZone, w: DemolishWorker, link: StationLinkCache.ResolvedLink, t: Vec3I, now: Long, force: Boolean): Begin {
        val loc = LocationIContainer(zone.world, t)
        return start(w, link, loc, rules.decide(zone, loc, force), now)
    }

    private fun start(w: DemolishWorker, link: StationLinkCache.ResolvedLink, loc: LocationIContainer, verdict: Verdict, now: Long): Begin =
        when (verdict) {
            is Verdict.Break -> startBreak(w, link, loc, verdict.material, now)
            Verdict.Scoop -> startFluid(w, link, loc, true)
            Verdict.Fill -> startFluid(w, link, loc, false)
            Verdict.Skip -> Begin.SKIP
            Verdict.Defer -> Begin.DEFER
        }

    private fun startBreak(w: DemolishWorker, link: StationLinkCache.ResolvedLink, loc: LocationIContainer, material: String, now: Long): Begin =
        when (val tool = tools.choose(w, link, loc, material, now)) {
            ToolResult.Unbreakable -> Begin.SKIP
            ToolResult.Missing -> { w.waitUntil = now + NO_TOOL_WAIT; Begin.WAIT }
            is ToolResult.Ticks -> {
                entities.display(w.id, w.tool)
                w.act = Act.BREAK
                w.target = loc
                w.ticksLeft = ceil(tool.n * timeFactor).toInt().coerceAtLeast(1)
                w.phase = Phase.BREAK
                Begin.STARTED
            }
        }

    private fun startFluid(w: DemolishWorker, link: StationLinkCache.ResolvedLink, loc: LocationIContainer, scoop: Boolean): Begin {
        if (rules.blocks(w) < 1) return Begin.DEFER
        val src = if (scoop) tools.bucket(w, link) else null
        entities.display(w.id, src)
        w.act = if (src != null) Act.SCOOP else Act.FILL
        w.bucketSrc = src
        w.target = loc
        w.ticksLeft = ceil(FLUID_TICKS * timeFactor).toInt().coerceAtLeast(1)
        w.phase = Phase.BREAK
        return Begin.STARTED
    }

    private fun move(zone: DemolishZone, w: DemolishWorker) {
        val st = w.stand ?: run { w.phase = Phase.PICK; return }
        val pos = entities.position(w.id) ?: run { w.stop = true; return }
        val cx = st.x + 0.5
        val cz = st.z + 0.5
        val dx = pos.x - cx
        val dz = pos.z - cz
        if (dx * dx + dz * dz < 0.5 && abs(pos.y - st.y) < 1.5) {
            w.atStand = true
            w.phase = Phase.PICK
            return
        }
        if (w.moveTicks % 10 == 9 && !nav.standable(LocationIContainer(zone.world, st))) {
            villagers.setTarget(w.id, null)
            w.stand = null
            w.phase = Phase.PICK
            return
        }
        if (w.moveTicks == 0) villagers.setTarget(w.id, Vec3D(cx, st.y.toDouble(), cz), MOVE_SPEED)
        if (++w.moveTicks > MOVE_TIMEOUT) {
            villagers.setTarget(w.id, null)
            Trace.log { "demolish: move timeout ${w.id.toString().take(8)} stand=$st pos=$pos climb=${w.climb != null}" }
            if (w.climb == null) zone.badStands += st
            if (w.climb != null) {
                climber.abort(zone, w, "move timeout")
            } else {
                w.slice?.let { s -> s.cells.removeFirstOrNull()?.let { s.retry += it } }
            }
            w.stand = null
            w.phase = Phase.PICK
        }
    }

    private fun work(zone: DemolishZone, w: DemolishWorker) {
        val target: LocationIContainer = w.target ?: run { w.phase = Phase.PICK; return }
        val vec = target.vec3I
        villagers.faceTo(w.id, Vec3D(vec.x + 0.5, vec.y + 0.5, vec.z + 0.5))
        if (w.ticksLeft % SWING_EVERY == 0) villagers.swingMainHand(w.id)
        if (--w.ticksLeft > 0) return

        if (!zone.box.contains(vec) || zone.data.isSafe(vec)) { w.phase = Phase.PICK; return }
        val info = world.info(target)
        if (info == null || rules.isAir(info.material)) { w.phase = Phase.PICK; return }

        when (w.act) {
            Act.BREAK -> {
                val result = breaker.breakBlock(w.id, target, w.tool ?: ItemSource.HAND)
                merge(w.carried, result.drops)
                if (result.toolBroken) {
                    w.tool?.let { w.held -= it.slot }
                    w.tool = null
                    w.toolUntil = 0L
                    tools.save(w)
                }
            }
            Act.SCOOP -> {
                val src = w.bucketSrc
                val got = if (src != null) breaker.scoop(w.id, target, src) else null
                if (got != null) merge(w.carried, listOf(got))
                fillHole(w, target)
            }
            Act.FILL -> fillHole(w, target)
        }
        zone.progress = true
        zone.cycleBroke = true
        w.slice?.let {
            it.cells.remove(vec)
            it.progressed = true
        }
        w.phase = Phase.PICK
    }

    private fun fillHole(w: DemolishWorker, at: LocationIContainer) {
        rules.useBlock(w) { breaker.place(at, it) }
    }

    private fun toDeliver(w: DemolishWorker) {
        w.moveTicks = 0
        w.phase = Phase.DELIVER
    }

    private fun deliver(w: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long) {
        if (w.carried.isEmpty()) { w.phase = Phase.PICK; return }
        val pos = entities.position(w.id) ?: run { w.stop = true; return }
        val c = link.output.vec3I
        val dx = pos.x - (c.x + 0.5)
        val dy = pos.y - (c.y + 0.5)
        val dz = pos.z - (c.z + 0.5)
        val near = dx * dx + dy * dy + dz * dz <= DELIVER_RANGE * DELIVER_RANGE

        if (!near && w.moveTicks <= MOVE_TIMEOUT) {
            if (w.moveTicks == 0) villagers.setTarget(w.id, Vec3D(c.x + 0.5, c.y + 1.0, c.z + 0.5), MOVE_SPEED)
            w.moveTicks++
            return
        }

        villagers.setTarget(w.id, null)
        w.carried = containers.put(link.output, w.carried).toMutableList()
        if (w.carried.isNotEmpty()) {
            w.waitUntil = now + 20
            return
        }
        w.stand = null
        w.atStand = false
        w.moveTicks = 0
        w.phase = Phase.PICK
    }

    private fun merge(into: MutableList<ItemContainer>, add: List<ItemContainer>) {
        for (i in add) {
            val idx = into.indexOfFirst { it.material == i.material && it.name == i.name && it.properties == i.properties }
            if (idx >= 0) into[idx] = into[idx].copy(count = into[idx].count + i.count) else into += i
        }
    }

    private companion object {
        const val MOVE_SPEED = 0.6
        const val MOVE_TIMEOUT = 200
        const val SWING_EVERY = 4
        const val NO_TOOL_WAIT = 40L
        const val CLIMB_WAIT = 20L
        const val IDLE_WAIT = 10L
        const val FLUID_TICKS = 10
        const val CARRY_ITEMS = 256
        const val CARRY_STACKS = 8
        const val DELIVER_RANGE = 3.0
    }
}
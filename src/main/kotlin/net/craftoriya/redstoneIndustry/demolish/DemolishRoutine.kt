package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainBlockChangeEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainVillagerTickEvent
import net.craftoriya.adaptersLib.model.BlockChange
import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.ItemSource
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.model.Vec3D
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IBlockBreakPort
import net.craftoriya.adaptersLib.port.IBlockDataPort
import net.craftoriya.adaptersLib.port.IContainerPort
import net.craftoriya.adaptersLib.port.IEntityPort
import net.craftoriya.adaptersLib.port.ISchedulerPort
import net.craftoriya.adaptersLib.port.IVillagerControlPort
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.BindingStore
import net.craftoriya.redstoneIndustry.villagers.StationLinkCache
import net.craftoriya.redstoneIndustry.villagers.VillagerWorkRoutine
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil

class DemolishRoutine(
    private val areas: AreaStore,
    private val store: BindingStore,
    private val links: StationLinkCache,
    private val containers: IContainerPort,
    private val world: IWorldQueryPort,
    private val entities: IEntityPort,
    private val breaker: IBlockBreakPort,
    private val villagers: IVillagerControlPort,
    private val scheduler: ISchedulerPort,
    kinds: BlockKinds,
    private val timeFactor: Double,
    blockData: IBlockDataPort
) {
    @Volatile
    var reportEvery: Long = 100L

    private class Resume(val data: ZoneData, val world: String, var layerY: Int)

    private val rules = DemolishRules(world, kinds)
    private val nav = DemolishNav(world, entities)
    private val tools = DemolishTools(containers, entities, world, breaker, blockData)
    private val climber = DemolishClimb(world, breaker, villagers, nav, rules)

    private val zones = HashMap<UUID, DemolishZone>()
    private val workerZone = HashMap<UUID, DemolishZone>()
    private val cooldown = HashMap<UUID, Long>()
    private val resume = HashMap<UUID, Resume>()
    private val nextJoin = HashMap<LocationIContainer, Long>()
    private var idle = 0L
    private var noArea = 0L
    private var joined = 0L

    fun subscribe(bus: DomainEventBus) {
        bus.on<DomainVillagerTickEvent> { onTick(it) }
        bus.on<DomainBlockChangeEvent> { onBlockChange(it) }
    }

    fun exclusive(site: LocationIContainer): Boolean = store.demolish(site) && areas.zoneOf(site)?.demolish != null

    fun snapshot(): List<String> {
        val now = scheduler.currentTick()
        val head = "demolish zones=${zones.size} workers=${workerZone.size} resume=${resume.size} " +
                "idle=$idle noArea=$noArea joined=$joined"
        return listOf(head) + zones.values.map { it.stats.format(it, now) }
    }

    private fun onBlockChange(event: DomainBlockChangeEvent) {
        val loc = event.block.location
        val vec = loc.vec3I
        val place = event.change == BlockChange.PLACE
        val margin: Int = DemolishNav.MARGIN

        for (zone in zones.values) {
            if (zone.world != loc.world) continue
            val box = zone.box
            if (vec.x !in box.min.x - margin..box.max.x + margin || vec.y !in box.min.y - margin..box.max.y + margin || vec.z !in box.min.z - margin..box.max.z + margin) continue
            zone.changeVer++
            zone.pass.remove(cellKey(vec.x, vec.y, vec.z))
            if (place && box.contains(vec) && (vec.y > zone.layerY || (zone.layerLoaded && vec.y >= bandBottom(zone)))) {
                zone.dirtyTop = maxOf(zone.dirtyTop ?: vec.y, vec.y)
            }
        }

        if (place) {
            for (r in resume.values) {
                val b = r.data.demolish ?: continue
                if (r.world == loc.world && b.contains(vec)) r.layerY = maxOf(r.layerY, vec.y)
            }
        }
    }

    private fun onTick(e: DomainVillagerTickEvent) {
        val site = e.jobSite ?: return
        val id = e.villager.id
        val mason = e.profession == RecipeContainer.TradeProfession.MASON
        val inWindow = !e.sleeping && e.worldTime in VillagerWorkRoutine.WORK_START..VillagerWorkRoutine.WORK_END

        val zone = workerZone[id]
        if (zone != null) {
            val w = zone.workers[id]
            if (w != null && (!(mason && inWindow && store.demolish(site)) || w.site != site)) w.stop = true
            return
        }
        if (!mason || !inWindow) {
            if (mason) idle++
            return
        }
        if (!store.demolish(site)) {
            tryJoin(site, e.tick)
            return
        }
        startWorker(e, site)
    }

    private fun tryJoin(site: LocationIContainer, tick: Long) {
        if ((nextJoin[site] ?: 0L) > tick) return
        nextJoin[site] = tick + JOIN_EVERY
        if (areas.join(site) != null) {
            store.setDemolish(site, true)
            joined++
        }
    }

    private fun startWorker(event: DomainVillagerTickEvent, site: LocationIContainer) {
        val data = areas.resolve(site) ?: run { noArea++; return }
        val box = data.demolish ?: run { noArea++; return }
        if ((cooldown[data.id] ?: 0L) > event.tick) return

        var zone = zones[data.id]
        if (zone != null && (zone.done || zone.stopping)) return
        if (zone == null) zone = openZone(data, site.world, box, event.tick)

        val w = DemolishWorker(event.villager.id, site, zone.stats)
        tools.restore(w)
        villagers.setHardControlled(w.id, true)
        villagers.setControlled(w.id, true)
        zone.workers[w.id] = w
        workerZone[w.id] = zone
        zone.rebalance = true
        Trace.log { "demolish: worker ${w.id.toString().take(8)} -> zone ${zone.id.toString().take(8)} crew=${zone.workers.size}" }
    }

    private fun openZone(data: ZoneData, worldName: String, box: AreaBox, tick: Long): DemolishZone {
        val zone = DemolishZone(data, worldName, tick)
        zone.nextReport = tick + reportEvery
        resume[data.id]?.let { r ->
            if (r.data == data && r.layerY in box.min.y..box.max.y) zone.layerY = r.layerY
        }
        zones[data.id] = zone
        Trace.log { "demolish: start zone ${data.id.toString().take(8)} box=${box.min} -> ${box.max} y=${zone.layerY}" }
        scheduler.runRepeating(1L, 1L) {
            runCatching { step(zone) }.getOrElse {
                Trace.log { "demolish: error ${it.message}" }
                zone.stopping = true
                true
            }
        }
        return zone
    }

    private fun step(zone: DemolishZone): Boolean {
        if (zones[zone.id] !== zone) return false
        val now = scheduler.currentTick()

        for (w in zone.workers.values.toList()) {
            if (!w.stop && entities.position(w.id) == null) {
                Trace.log { "demolish: villager gone ${w.id}" }
                w.stop = true
            }
            if (zone.done || zone.stopping) w.stop = true
            if (w.stop) endWorker(zone, w)
        }
        if (zone.workers.isEmpty()) {
            closeZone(zone, now)
            return false
        }

        report(zone, now)
        if (now >= zone.refreshAt) refresh(zone, now)
        if (!zone.stopping) {
            advance(zone)
            balance(zone)
        }
        for (w in zone.workers.values.toList()) stepWorker(zone, w, now)
        return true
    }

    private fun report(zone: DemolishZone, now: Long) {
        val every = reportEvery
        if (every <= 0L || now < zone.nextReport) return
        zone.nextReport = now + every
        Trace.log { zone.stats.format(zone, now) }
    }

    private fun refresh(zone: DemolishZone, now: Long) {
        zone.refreshAt = now + LINK_REFRESH
        zone.pass.clear()

        val latest = areas.latest(zone.id)
        val box = latest?.demolish
        if (latest == null || box == null) { zone.stopping = true; return }
        if (latest != zone.data) {
            zone.data = latest
            zone.box = box
            resetScan(zone)
        }

        zone.protect = zone.workers.values.flatMap { listOfNotNull(it.site, it.link?.input, it.link?.output) }.toSet()

        val sets = zone.workers.values.mapNotNull { w ->
            w.link?.let { l -> containers.contents(l.input).map { it.material }.filter { world.isFullBlock(it) }.toSet() }
        }
        zone.filter = if (sets.isEmpty() || sets.any { it.isEmpty() }) emptySet() else sets.reduce { a, b -> a + b }
    }

    private fun resetScan(zone: DemolishZone) {
        zone.stats.areaResets++
        zone.layerY = zone.box.max.y
        zone.layerLoaded = false
        zone.progress = false
        zone.dirtyTop = null
        zone.leftover.clear()
        zone.skipped.clear()
        zone.climbFailed = false
        zone.climber = null
        zone.walk = emptySet()
        zone.pass.clear()
        zone.changeVer++
        zone.rebalance = true
        for (worker in zone.workers.values) {
            worker.queue.clear()
            worker.climb = null
            worker.stand = null
            worker.atStand = false
            worker.target = null
            if (worker.phase == Phase.MOVE || worker.phase == Phase.BREAK) {
                villagers.setTarget(worker.id, null)
                worker.phase = Phase.PICK
            }
        }
    }

    private fun endWorker(zone: DemolishZone, worker: DemolishWorker) {
        zone.workers.remove(worker.id)
        workerZone.remove(worker.id)
        if (worker.phase == Phase.BREAK) worker.target?.vec3I?.let { zone.leftover += it }
        zone.leftover += worker.queue
        worker.queue.clear()
        zone.rebalance = true
        if (zone.climber == worker.id) zone.climber = null

        villagers.setTarget(worker.id, null)
        villagers.setControlled(worker.id, false)
        entities.display(worker.id, null)
        worker.link?.let { l ->
            tools.giveBack(worker, l)
            if (worker.carried.isNotEmpty()) containers.put(l.output, worker.carried)
        }
        Trace.log { "demolish: worker ${worker.id.toString().take(8)} left zone ${zone.id.toString().take(8)} crew=${zone.workers.size}" }
    }

    private fun closeZone(zone: DemolishZone, now: Long) {
        zones.remove(zone.id)
        if (zone.done) {
            resume.remove(zone.id)
            cooldown[zone.id] = now + if (zone.stats.broke > 0) 60L else DONE_COOLDOWN
        } else {
            val top = maxOf(zone.layerY, zone.dirtyTop ?: zone.layerY).coerceAtMost(zone.box.max.y)
            resume[zone.id] = Resume(zone.data, zone.world, top)
        }
        Trace.log { "demolish: end done=${zone.done} ${zone.stats.format(zone, now)}" }
    }

    private fun advance(zone: DemolishZone) {
        if (!zone.layerLoaded) {
            fillLayer(zone)
            zone.layerLoaded = true
            zone.progress = false
            return
        }
        val idleAll = zone.leftover.isEmpty() && zone.workers.values.all {
            it.queue.isEmpty() && it.carried.isEmpty() && (it.phase == Phase.PICK || it.phase == Phase.DELIVER)
        }
        if (!idleAll) return

        if (zone.skipped.isNotEmpty() && zone.progress) {
            zone.leftover += zone.skipped
            zone.skipped.clear()
            zone.progress = false
            zone.rebalance = true
            return
        }
        zone.skipped.clear()
        zone.layerLoaded = false
        zone.climbFailed = false
        zone.layerY -= BAND
        zone.stats.bands++
        if (zone.layerY < zone.box.min.y) {
            val top = zone.dirtyTop
            if (top != null) {
                zone.dirtyTop = null
                zone.layerY = minOf(top, zone.box.max.y)
                zone.stats.rescans++
            } else {
                zone.done = true
            }
        }
    }

    private fun balance(zone: DemolishZone) {
        if (!zone.rebalance) return
        zone.rebalance = false
        val ws = zone.workers.values.toList()
        if (ws.isEmpty()) return

        val all = ArrayList<Vec3I>(zone.leftover)
        for (w in ws) {
            all += w.queue
            w.queue.clear()
        }
        zone.leftover.clear()
        zone.stats.balances++

        val base = all.size / ws.size
        val extra = all.size % ws.size
        var i = 0
        ws.forEachIndexed { k, w ->
            repeat(base + if (k < extra) 1 else 0) { w.queue.addLast(all[i++]) }
        }
    }

    private fun bandBottom(zone: DemolishZone) = maxOf(zone.layerY - (BAND - 1), zone.box.min.y)

    private fun fillLayer(zone: DemolishZone) {
        val b = zone.box
        val o = zone.workers.values.firstNotNullOfOrNull { it.link?.output?.vec3I } ?: b.min
        val zs: List<Int> =
            if (abs(o.z - b.max.z) < abs(o.z - b.min.z)) (b.max.z downTo b.min.z).toList() else (b.min.z..b.max.z).toList()
        var forward = abs(o.x - b.min.x) <= abs(o.x - b.max.x)
        val top = zone.layerY
        val bot = bandBottom(zone)
        val list = ArrayList<Vec3I>()
        for (lane in zs.chunked(LANE_WIDTH)) {
            val xs: Iterable<Int> = if (forward) b.min.x..b.max.x else b.max.x downTo b.min.x
            for (x in xs) for (z in lane) for (y in top downTo bot) {
                val v = Vec3I(x, y, z)
                if (rules.demolishable(zone, LocationIContainer(zone.world, v))) list += v
            }
            forward = !forward
        }
        zone.leftover += list
        zone.rebalance = true
    }

    private fun stepWorker(zone: DemolishZone, w: DemolishWorker, now: Long) {
        if (now < w.waitUntil) { zone.stats.tickWait(); return }

        if (w.link == null || now >= w.linkUntil) {
            w.linkUntil = now + LINK_REFRESH
            w.link = links.resolve(w.site, now)
        }
        val link = w.link
        if (link == null) { w.waitUntil = now + 20; zone.stats.tickWait(); return }

        zone.stats.count(w.phase)
        when (w.phase) {
            Phase.PICK -> pick(zone, w, link, now)
            Phase.MOVE -> move(zone, w)
            Phase.BREAK -> breakTick(zone, w)
            Phase.DELIVER -> deliver(w, link, now)
        }
    }

    private fun pick(zone: DemolishZone, worker: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long) {
        if (worker.climb != null) { climber.step(zone, worker, link, now, this::begin); return }

        if (worker.queue.isEmpty()) {
            if (worker.carried.isNotEmpty()) { worker.phase = Phase.DELIVER; return }
            steal(zone, worker, now)
            return
        }

        val st = worker.stand
        if (st != null && worker.atStand) {
            val i = worker.queue.indexOfFirst { nav.visible(zone, st, it) }
            if (i >= 0) {
                val t = worker.queue[i]
                if (begin(zone, worker, link, t, now, false) != Begin.WAIT) worker.queue.removeAt(i)
                return
            }
        }

        if (worker.carried.isNotEmpty()) { worker.phase = Phase.DELIVER; return }

        while (worker.queue.isNotEmpty()) {
            val b = world.blockAt(LocationIContainer(zone.world, worker.queue.first()))
            if (b != null && !rules.isAir(b.material)) break
            worker.queue.removeFirst()
            zone.stats.gone++
        }
        if (worker.queue.isEmpty()) return

        var head = worker.queue.first()
        var next = nav.findStand(zone, worker, head)
        if (next == null && nav.refreshWalk(zone, worker, now)) {
            next = nav.findStand(zone, worker, head)
        }
        if (next == null) {
            val alt = nav.reachableIndex(zone, worker)
            if (alt >= 0) {
                head = worker.queue.removeAt(alt)
                worker.queue.addFirst(head)
                next = nav.findStand(zone, worker, head)
            }
        }
        if (next == null) {
            if (!zone.climbFailed && climber.needed(zone, worker)) {
                val other = zone.climber
                if (other != null && other != worker.id) {
                    zone.stats.climbWaits++
                    worker.waitUntil = now + CLIMB_WAIT
                    return
                }
                if (climber.start(zone, worker)) return
            }
            val n = minOf(DemolishNav.SCAN, worker.queue.size)
            repeat(n) { zone.skipped += worker.queue.removeFirst() }
            zone.stats.unreachable++
            zone.stats.skipped += n
            return
        }
        if (!nav.standable(LocationIContainer(zone.world, next))) {
            zone.walk = nav.walkable(zone, now)
            next = nav.findStand(zone, worker, head) ?: run {
                zone.skipped += worker.queue.removeFirst()
                zone.stats.skipped++
                return
            }
        }
        worker.stand = next
        worker.atStand = false
        worker.moveTicks = 0
        worker.phase = Phase.MOVE
    }

    private fun steal(zone: DemolishZone, worker: DemolishWorker, now: Long) {
        val victim = zone.workers.values.filter { it !== worker }.maxByOrNull { it.queue.size }
        if (victim == null || victim.queue.size < STEAL_MIN) {
            worker.waitUntil = now + IDLE_WAIT
            return
        }
        val n = victim.queue.size / 2
        val tail = ArrayList<Vec3I>(n)
        repeat(n) { tail += victim.queue.removeLast() }
        for (i in tail.indices.reversed()) worker.queue.addLast(tail[i])
        zone.stats.steals++
    }

    private fun begin(
        zone: DemolishZone, w: DemolishWorker, link: StationLinkCache.ResolvedLink, t: Vec3I, now: Long, force: Boolean
    ): Begin {
        val loc = LocationIContainer(zone.world, t)
        val material = rules.material(zone, loc) ?: run { zone.stats.rejected++; return Begin.SKIP }
        if (!force && zone.filter.isNotEmpty() && material !in zone.filter) { zone.stats.filtered++; return Begin.SKIP }

        return when (val tool = tools.choose(w, link, loc, material, now)) {
            ToolResult.Unbreakable -> { zone.stats.unbreakable++; Begin.SKIP }
            ToolResult.Missing -> {
                zone.stats.noTool++
                w.waitUntil = now + NO_TOOL_WAIT
                Begin.WAIT
            }
            is ToolResult.Ticks -> {
                entities.display(w.id, w.tool)
                w.target = loc
                w.ticksLeft = ceil(tool.n * timeFactor).toInt().coerceAtLeast(1)
                w.phase = Phase.BREAK
                Begin.STARTED
            }
        }
    }

    private fun move(zone: DemolishZone, worker: DemolishWorker) {
        val st = worker.stand ?: run { worker.phase = Phase.PICK; return }
        val pos = entities.position(worker.id) ?: run { worker.stop = true; return }
        val cx = st.x + 0.5
        val cz = st.z + 0.5
        val dx = pos.x - cx
        val dz = pos.z - cz
        if (dx * dx + dz * dz < 0.5 && abs(pos.y - st.y) < 1.5) {
            worker.atStand = true
            worker.phase = Phase.PICK
            return
        }
        if (worker.moveTicks % 10 == 9 && !nav.standable(LocationIContainer(zone.world, st))) {
            zone.stats.standLost++
            villagers.setTarget(worker.id, null)
            worker.stand = null
            zone.walk = emptySet()
            worker.phase = Phase.PICK
            return
        }
        if (worker.moveTicks == 0) {
            zone.stats.moves++
            villagers.setTarget(worker.id, Vec3D(cx, st.y.toDouble(), cz), MOVE_SPEED)
        }
        if (++worker.moveTicks > MOVE_TIMEOUT) {
            zone.stats.moveTimeouts++
            villagers.setTarget(worker.id, null)
            if (worker.climb != null) climber.abort(zone, worker, "move timeout")
            else worker.queue.removeFirstOrNull()?.let { zone.skipped += it; zone.stats.skipped++ }
            worker.stand = null
            worker.phase = Phase.PICK
        }
    }

    private fun breakTick(zone: DemolishZone, worker: DemolishWorker) {
        val target: LocationIContainer = worker.target ?: run { worker.phase = Phase.PICK; return }
        val vec = target.vec3I
        villagers.faceTo(worker.id, Vec3D(vec.x + 0.5, vec.y + 0.5, vec.z + 0.5))
        if (worker.ticksLeft % SWING_EVERY == 0) villagers.swingMainHand(worker.id)
        if (--worker.ticksLeft > 0) return

        if (!zone.box.contains(vec) || zone.data.isSafe(vec)) {
            zone.stats.rejected++
            worker.phase = Phase.PICK
            return
        }

        val info = world.info(target)
        if (info == null || rules.isAir(info.material)) { worker.phase = Phase.PICK; return }

        val result = breaker.breakBlock(worker.id, target, worker.tool ?: ItemSource.HAND)
        merge(worker.carried, result.drops)
        zone.pass[cellKey(vec.x, vec.y, vec.z)] = true
        if (result.toolBroken) {
            worker.tool?.let { worker.held -= it.slot }
            worker.tool = null
            worker.toolUntil = 0L
            zone.stats.toolBroken++
            tools.save(worker)
        }
        zone.progress = true
        zone.stats.broke++
        zone.stats.dropped += result.drops.sumOf { it.count.toLong() }
        worker.phase = if (worker.carried.size >= CARRY_FLUSH) Phase.DELIVER else Phase.PICK
    }

    private fun deliver(worker: DemolishWorker, link: StationLinkCache.ResolvedLink, now: Long) {
        if (worker.carried.isNotEmpty()) {
            val before = worker.carried.sumOf { it.count.toLong() }
            worker.carried = containers.put(link.output, worker.carried).toMutableList()
            worker.stats.itemsOut += before - worker.carried.sumOf { it.count.toLong() }
            if (worker.carried.isNotEmpty()) {
                worker.stats.deliverRetries++
                worker.waitUntil = now + 20
                return
            }
        }
        worker.phase = Phase.PICK
    }

    private fun merge(into: MutableList<ItemContainer>, add: List<ItemContainer>) {
        for (i in add) {
            val idx = into.indexOfFirst { it.material == i.material && it.name == i.name && it.properties == i.properties }
            if (idx >= 0) into[idx] = into[idx].copy(count = into[idx].count + i.count) else into += i
        }
    }

    private companion object {
        const val LANE_WIDTH = 3
        const val MOVE_SPEED = 0.6
        const val MOVE_TIMEOUT = 200
        const val SWING_EVERY = 4
        const val NO_TOOL_WAIT = 40L
        const val DONE_COOLDOWN = 1200L
        const val LINK_REFRESH = 40L
        const val CARRY_FLUSH = 8
        const val BAND = 2
        const val JOIN_EVERY = 100L
        const val CLIMB_WAIT = 20L
        const val IDLE_WAIT = 10L
        const val STEAL_MIN = 2
    }
}
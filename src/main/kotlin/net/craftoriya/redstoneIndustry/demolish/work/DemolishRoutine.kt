package net.craftoriya.redstoneIndustry.demolish.work

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.block.DomainBlockChangeEvent
import net.craftoriya.adaptersLib.event.domainevents.villager.DomainVillagerTickEvent
import net.craftoriya.adaptersLib.model.block.BlockChange
import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.item.RecipeContainer
import net.craftoriya.adaptersLib.port.world.IBlockBreakPort
import net.craftoriya.adaptersLib.port.world.IBlockDataPort
import net.craftoriya.adaptersLib.port.world.IContainerPort
import net.craftoriya.adaptersLib.port.entity.IEntityPort
import net.craftoriya.adaptersLib.port.system.ISchedulerPort
import net.craftoriya.adaptersLib.port.entity.IVillagerControlPort
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.demolish.area.AreaBox
import net.craftoriya.redstoneIndustry.demolish.area.AreaStore
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone
import net.craftoriya.redstoneIndustry.demolish.area.ZoneData
import net.craftoriya.redstoneIndustry.demolish.debug.DemolishDebug
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishRules
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishScan
import net.craftoriya.redstoneIndustry.demolish.rule.DemolishTools
import net.craftoriya.redstoneIndustry.villagers.binding.BindingStore
import net.craftoriya.redstoneIndustry.villagers.container.StationLinkCache
import net.craftoriya.redstoneIndustry.villagers.work.VillagerWorkRoutine
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import java.util.UUID

class DemolishRoutine(
    private val areas: AreaStore,
    private val store: BindingStore,
    private val links: StationLinkCache,
    private val containers: IContainerPort,
    private val world: IWorldQueryPort,
    private val entities: IEntityPort,
    breaker: IBlockBreakPort,
    private val villagers: IVillagerControlPort,
    private val scheduler: ISchedulerPort,
    kinds: BlockKinds,
    timeFactor: Double,
    private val maxWorkers: Int,
    blockData: IBlockDataPort
) {
    @Volatile
    var reportEvery: Long = 100L

    private class Sleep(val box: AreaBox, val world: String, val wakeAt: Long) {
        var awake = false
        var wakeY: Int? = null
    }

    private val rules = DemolishRules(world, kinds)
    private val nav = DemolishNav(world, entities)
    private val tools = DemolishTools(containers, entities, world, breaker, blockData)
    private val climber = DemolishClimb(world, breaker, villagers, nav, rules)
    private val scan = DemolishScan(world, rules)
    private val crew = DemolishCrew(world, entities, containers, breaker, villagers, rules, nav, tools, scan, climber, timeFactor)

    private val zones = HashMap<UUID, DemolishZone>()
    private val workerZone = HashMap<UUID, DemolishZone>()
    private val sleeping = HashMap<UUID, Sleep>()
    private val nextJoin = HashMap<LocationIContainer, Long>()

    // private var idle = 0L
    // private var noArea = 0L
    // private var joined = 0L

    fun subscribe(bus: DomainEventBus) {
        bus.on<DomainVillagerTickEvent> { onTick(it) }
        bus.on<DomainBlockChangeEvent> { onBlockChange(it) }
    }

    fun exclusive(site: LocationIContainer): Boolean = store.demolish(site) && areas.zoneOf(site)?.demolish != null

    fun snapshot(): List<String> {
        val head = "demolish zones=${zones.size} workers=${workerZone.size} sleeping=${sleeping.size}"
        return listOf(head) + zones.values.map { DemolishDebug.describe(it) }
    }

    private fun onBlockChange(event: DomainBlockChangeEvent) {
        if (event.change == BlockChange.BREAK || sleeping.isEmpty()) return
        val loc = event.block.location
        val vec = loc.vec3I
        for (s in sleeping.values) {
            if (s.world != loc.world || !s.box.contains(vec)) continue
            s.awake = true
            s.wakeY = maxOf(s.wakeY ?: vec.y, vec.y)
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
            // if (mason) idle++
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
            // joined++
        }
    }

    private fun startWorker(event: DomainVillagerTickEvent, site: LocationIContainer) {
        val data = areas.resolve(site) ?: return // noArea++
        val box = data.demolish ?: return // noArea++

        var zone = zones[data.id]
        if (zone != null && (zone.done || zone.stopping)) return
        if (zone == null) {
            val startY = wakeFrom(data.id, box, event.tick) ?: return
            zone = openZone(data, site.world, box, event.tick, startY)
        }

        if (zone.workers.size >= maxWorkers) return
        val worker = DemolishWorker(event.villager.id, site)
        tools.restore(worker)
        villagers.setHardControlled(worker.id, true)
        villagers.setControlled(worker.id, true)
        zone.workers[worker.id] = worker
        workerZone[worker.id] = zone
        Trace.log { "demolish: worker ${worker.id.toString().take(8)} -> zone ${zone.id.toString().take(8)} crew=${zone.workers.size}" }
    }

    private fun wakeFrom(id: UUID, box: AreaBox, now: Long): Int? {
        val s = sleeping[id] ?: return box.max.y
        if (s.box == box && !s.awake && now < s.wakeAt) return null
        sleeping.remove(id)
        val y = s.wakeY
        if (s.box != box || y == null || y > box.max.y || y < box.min.y) return box.max.y
        return box.max.y - ((box.max.y - y) / BAND) * BAND
    }

    private fun openZone(data: ZoneData, worldName: String, box: AreaBox, tick: Long, startY: Int): DemolishZone {
        val zone = DemolishZone(data, worldName, startY)
        zone.nextReport = tick + reportEvery
        zones[data.id] = zone
        Trace.log { "demolish: start zone ${data.id.toString().take(8)} box=${box.min} -> ${box.max} y=$startY" }
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
        if (!zone.stopping) scan.advance(zone)
        for (w in zone.workers.values.toList()) stepWorker(zone, w, now)
        return true
    }

    private fun report(zone: DemolishZone, now: Long) {
        val every = reportEvery
        if (every <= 0L || now < zone.nextReport) return
        zone.nextReport = now + every
        Trace.log { DemolishDebug.describe(zone) }
    }

    private fun refresh(zone: DemolishZone, now: Long) {
        zone.refreshAt = now + LINK_REFRESH

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
            w.link?.let { l -> containers.contents(l.input).map { it.material }.filter { world.isBlock(it) }.toSet() }
        }
        zone.filter = if (sets.isEmpty() || sets.any { it.isEmpty() }) emptySet() else sets.reduce { a, b -> a + b }
    }

    private fun resetScan(zone: DemolishZone) {
        zone.layerY = zone.box.max.y
        zone.scan = null
        zone.retryQueue.clear()
        zone.skipped.clear()
        zone.progress = false
        zone.cycleBroke = false
        zone.climbFailed = false
        zone.climber = null
        zone.walk = emptySet()
        zone.walkAt = -1000L
        zone.badStands.clear()
        for (worker in zone.workers.values) {
            worker.slice = null
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
        worker.slice?.let { s ->
            s.cells.addAll(s.retry)
            if (s.cells.isNotEmpty()) zone.retryQueue.addFirst(s)
        }
        worker.slice = null
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
        if (zone.done) sleeping[zone.id] = Sleep(zone.box, zone.world, now + SLEEP_FALLBACK)
        Trace.log { "demolish: end ${DemolishDebug.describe(zone)}" }
    }

    private fun stepWorker(zone: DemolishZone, w: DemolishWorker, now: Long) {
        if (now < w.waitUntil) return

        if (w.link == null || now >= w.linkUntil) {
            w.linkUntil = now + LINK_REFRESH
            w.link = links.resolve(w.site, now)
        }
        val link = w.link ?: run { w.waitUntil = now + 20; return }
        crew.step(zone, w, link, now)
    }

    private companion object {
        const val LINK_REFRESH = 40L
        const val JOIN_EVERY = 100L
        const val BAND = DemolishScan.BAND
        const val SLEEP_FALLBACK = 6000L
    }
}
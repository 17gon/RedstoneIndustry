package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainBlockChangeEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainVillagerTickEvent
import net.craftoriya.adaptersLib.model.BlockChange
import net.craftoriya.adaptersLib.model.EntityContainer
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.*
import net.craftoriya.redstoneIndustry.common.coords
import net.craftoriya.redstoneIndustry.demolish.AreaKind
import net.craftoriya.redstoneIndustry.demolish.AreaStore
import net.craftoriya.redstoneIndustry.demolish.AreaView
import net.craftoriya.redstoneIndustry.outline.OutlineStyles
import net.craftoriya.redstoneIndustry.demolish.DemolishRoutine
import net.craftoriya.redstoneIndustry.tools.StickToolService
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKind
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import net.craftoriya.redstoneIndustry.villagers.work.WorkRecipeProvider
import java.util.UUID

class VillagerSystem(
    private val bus: DomainEventBus,
    config: VillagerConfig,
    blockData: IBlockDataPort,
    private val world: IWorldQueryPort,
    containers: IContainerPort,
    villagers: IVillagerControlPort,
    outline: IOutlinePort,
    outlineStyles: OutlineStyles,
    feedback: IPlayerFeedbackPort,
    private val scheduler: ISchedulerPort,
    private val snapshot: IVillagerSnapshotPort,
    entities: IEntityPort,
    breaker: IBlockBreakPort,
    recipes: WorkRecipeProvider
) {
    private val kinds = BlockKinds()
    private val store = BindingStore(blockData)
    private val finder = ContainerFinder(world, store, kinds, config.containerRange.coerceAtLeast(1))
    private val links = StationLinkCache(store, finder, SmartRule())
    private val sync = BlockSyncService(store, links, kinds, world)
    private val areas = AreaStore(blockData, config.maxRegionVolume)
    private val areaView = AreaView(areas, outline, outlineStyles)
    private val demolish = DemolishRoutine(
        areas,
        store,
        links,
        containers,
        world,
        entities,
        breaker,
        villagers,
        scheduler,
        kinds,
        config.breakTimeFactor,
        blockData
    )
    private val routine = VillagerWorkRoutine(containers, links, recipes, WorkAnimation(villagers, scheduler), demolish::exclusive)
    private val debug = DebugViewService(store, links, outline, scheduler, areaView, outlineStyles)
    private val stick = StickToolService(store, feedback, debug, links, kinds, finder, areas, areaView)

    fun start() {
        sync.subscribe(bus)
        routine.subscribe(bus)
        demolish.subscribe(bus)
        stick.subscribe(bus)
        bus.on<DomainBlockChangeEvent> {
            if (it.change == BlockChange.BREAK && kinds.of(it.block) == BlockKind.WORKSTATION) {
                areas.clear(it.block.location)
                store.setDemolish(it.block.location, false)
            }
        }
    }

    fun setArea(station: LocationIContainer, kind: AreaKind, a: Vec3I, b: Vec3I): Long {
        require(isStation(station)) { "Not a workstation" }
        val volume = areas.set(station, kind, a, b).volume
        if (kind == AreaKind.DEMOLISH) store.setDemolish(station, true)
        return volume
    }

    fun describeAreas(station: LocationIContainer): String {
        val z = areas.zoneOf(station) ?: return "no zone"
        val d = z.demolish?.let { "demolish ${it.min.x},${it.min.y},${it.min.z} -> ${it.max.x},${it.max.y},${it.max.z}" } ?: "no demolish area"
        return "zone ${z.id.toString().take(8)} v${z.version}: $d, ${z.safe.size} safe, ${areas.sites(z.id).size} stations"
    }

    fun clearArea(station: LocationIContainer, kind: AreaKind?) = areas.clear(station, kind)

    fun toggleDemolish(site: LocationIContainer): Boolean? {
        if (!isStation(site)) return null
        val on = !store.demolish(site)
        store.setDemolish(site, on)
        return on
    }

    private fun isStation(at: LocationIContainer): Boolean {
        val b = world.blockAt(at) ?: return false
        return kinds.of(b) == BlockKind.WORKSTATION
    }

    fun diagnose(): List<String> = snapshot.snapshot().take(15).map {
        "${it.profession} L${it.level} at ${it.position.coords()} site=${it.jobSite?.coords() ?: "none"}: ${VillagerWorkRoutine.reason(it)}"
    }

    fun fakeWork(site: LocationIContainer) {
        bus.publish(
            DomainVillagerTickEvent(
                EntityContainer(UUID.randomUUID(), "VILLAGER", "test"),
                RecipeContainer.TradeProfession.TOOLSMITH, 1,
                site, site, 0.0, false,
                VillagerWorkRoutine.WORK_START, scheduler.currentTick()
            )
        )
    }
}
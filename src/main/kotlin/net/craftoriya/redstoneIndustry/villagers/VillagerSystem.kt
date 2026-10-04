package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.EntityContainer
import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainVillagerTickEvent
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.*
import net.craftoriya.redstoneIndustry.common.coords
import net.craftoriya.redstoneIndustry.tools.StickToolService
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import net.craftoriya.redstoneIndustry.villagers.work.WorkRecipeProvider
import java.util.UUID

class VillagerSystem(
    private val bus: DomainEventBus,
    config: VillagerConfig,
    blockData: IBlockDataPort,
    world: IWorldQueryPort,
    containers: IContainerPort,
    villagers: IVillagerControlPort,
    outline: IOutlinePort,
    feedback: IPlayerFeedbackPort,
    private val scheduler: ISchedulerPort,
    private val snapshot: IVillagerSnapshotPort,
    recipes: WorkRecipeProvider
) {
    private val kinds = BlockKinds()
    private val store = BindingStore(blockData)
    private val finder = ContainerFinder(world, store, kinds, config.containerRange.coerceAtLeast(1))
    private val links = StationLinkCache(store, finder, SmartRule())
    private val sync = BlockSyncService(store, links, kinds, world)
    private val routine = VillagerWorkRoutine(containers, links, recipes, WorkAnimation(villagers, scheduler))
    private val debug = DebugViewService(store, links, outline, scheduler)
    private val stick = StickToolService(store, feedback, debug, links, kinds, finder)

    fun start() {
        sync.subscribe(bus)
        routine.subscribe(bus)
        stick.subscribe(bus)
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
package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.BlockChange
import net.craftoriya.adaptersLib.event.domainevents.DomainBlockChangeEvent
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKind
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerMode

class BlockSyncService(
    private val store: BindingStore,
    private val links: StationLinkCache,
    private val kinds: BlockKinds,
    private val world: IWorldQueryPort
) {
    fun subscribe(bus: DomainEventBus) = bus.on<DomainBlockChangeEvent> { onChange(it) }

    private fun onChange(e: DomainBlockChangeEvent) {
        val b = e.block
        if (b.material == "HOPPER") {
            return hopperChanged(b.location)
        }
        val kind = kinds.of(b)
        if (kind == BlockKind.OTHER) return
        Trace.log { "block ${e.change} $kind ${b.location.vec3I}" }
        when (kind) {
            BlockKind.CONTAINER -> {
                val anchor = b.anchor
                if (e.change == BlockChange.BREAK && anchor == b.location) containerRemoved(anchor)
                else links.invalidateAround(anchor)
            }
            BlockKind.WORKSTATION -> if (e.change == BlockChange.BREAK) stationRemoved(b.location)
            BlockKind.OTHER -> {}
        }
    }

    private fun hopperChanged(at: LocationIContainer) {
        val v = at.vec3I
        val up = world.blockAt(LocationIContainer(at.world, Vec3I(v.x, v.y + 1, v.z))) ?: return
        if (kinds.of(up) == BlockKind.CONTAINER) links.invalidateAround(up.anchor)
    }

    private fun containerRemoved(loc: LocationIContainer) {
        store.container(loc)?.stations?.forEach { st ->
            val s = store.station(st) ?: return@forEach
            store.setStation(st, s.copy(bound = s.bound - loc))
            links.invalidate(st)
        }
        store.removeContainer(loc)
        links.invalidateAround(loc)
    }

    private fun stationRemoved(loc: LocationIContainer) {
        store.station(loc)?.bound?.forEach { c ->
            val d = store.container(c) ?: return@forEach
            val next = d.copy(stations = d.stations - loc)
            if (next.mode == ContainerMode.AUTO && next.stations.isEmpty()) store.removeContainer(c)
            else store.setContainer(c, next)
        }
        store.removeStation(loc)
        links.forget(loc)
    }
}
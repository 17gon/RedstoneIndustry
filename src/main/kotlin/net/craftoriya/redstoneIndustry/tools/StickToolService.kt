package net.craftoriya.redstoneIndustry.tools

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainPlayerInteractEvent
import net.craftoriya.adaptersLib.model.BlockContainer
import net.craftoriya.adaptersLib.model.InteractAction
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IPlayerFeedbackPort
import net.craftoriya.redstoneIndustry.demolish.AreaKind
import net.craftoriya.redstoneIndustry.demolish.AreaStore
import net.craftoriya.redstoneIndustry.demolish.AreaView
import net.craftoriya.redstoneIndustry.villagers.*
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKind
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerData
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerMode
import net.craftoriya.redstoneIndustry.villagers.binding.StationData
import java.util.UUID

class StickToolService(
    private val store: BindingStore,
    private val feedback: IPlayerFeedbackPort,
    private val debug: DebugViewService,
    private val links: StationLinkCache,
    private val kinds: BlockKinds,
    private val finder: ContainerFinder,
    private val areas: AreaStore,
    private val areaView: AreaView
) {
    enum class ToolMode { DEFAULT, BINDER, SCREWDRIVER, DEMOLISH_AREA, SAFE_AREA }

    private val modes = HashMap<UUID, ToolMode>()
    private val pending = HashMap<UUID, LocationIContainer>()
    private val areaStation = HashMap<UUID, LocationIContainer>()
    private val corner = HashMap<UUID, Vec3I>()

    fun subscribe(bus: DomainEventBus) = bus.on<DomainPlayerInteractEvent> { onUse(it) }

    private fun onUse(event: DomainPlayerInteractEvent) {
        if (!event.mainHand || event.item?.material != TOOL) return
        val id = event.player.id

        if (event.sneaking && event.action == InteractAction.RIGHT_AIR) {
            cycle(id)
            return
        }

        val block = event.block ?: return
        val kind = kinds.of(block) ?: return

        if (event.sneaking && event.action == InteractAction.RIGHT_BLOCK && kind == BlockKind.WORKSTATION) {
            feedback.actionBar(id, debug.showStation(id, block.location))
            event.isCancelled = true
            return
        }

        when (modes[id] ?: ToolMode.DEFAULT) {
            ToolMode.DEFAULT -> {}
            ToolMode.BINDER -> binder(event, block, kind)
            ToolMode.SCREWDRIVER -> screwdriver(event, block, kind)
            ToolMode.DEMOLISH_AREA -> area(event, block, kind, AreaKind.DEMOLISH)
            ToolMode.SAFE_AREA -> area(event, block, kind, AreaKind.SAFE)
        }
    }

    private fun cycle(id: UUID) {
        val next = ToolMode.entries[((modes[id] ?: ToolMode.DEFAULT).ordinal + 1) % ToolMode.entries.size]
        modes[id] = next
        pending.remove(id)
        areaStation.remove(id)
        corner.remove(id)
        feedback.glowHeldItem(id, next != ToolMode.DEFAULT)
        feedback.actionBar(id, "Tool: ${next.name.lowercase()}")
    }

    private fun area(e: DomainPlayerInteractEvent, b: BlockContainer, kind: BlockKind, areaKind: AreaKind) {
        val id = e.player.id
        if (e.action == InteractAction.LEFT_BLOCK) {
            if (kind == BlockKind.WORKSTATION && e.sneaking) {
                areas.clear(b.location, areaKind)
                areaView.show(id, b.location)
                feedback.actionBar(id, "Cleared ${areaKind.name.lowercase()}")
                e.isCancelled = true
            } else if (corner.remove(id) != null) {
                feedback.actionBar(id, "Corner cleared")
                e.isCancelled = true
            }
            return
        }
        if (e.action != InteractAction.RIGHT_BLOCK) return
        e.isCancelled = true

        if (kind == BlockKind.WORKSTATION) {
            areaStation[id] = b.location
            corner.remove(id)
            areaView.show(id, b.location)
            feedback.actionBar(id, "Workstation selected")
            return
        }
        val station = areaStation[id] ?: run { feedback.actionBar(id, "Select workstation first"); return }
        val pos = b.location.vec3I
        val first = corner.remove(id)
        if (first == null) {
            corner[id] = pos
            feedback.actionBar(id, "Corner 1: ${pos.x} ${pos.y} ${pos.z}")
            return
        }
        runCatching { areas.set(station, areaKind, first, pos) }
            .onSuccess {
                if (areaKind == AreaKind.DEMOLISH) store.setDemolish(station, true)
                areaView.show(id, station)
                feedback.actionBar(id, "${areaKind.name.lowercase()} area set, ${it.volume} blocks")
            }
            .onFailure { feedback.actionBar(id, it.message ?: "Failed") }
    }

    private fun screwdriver(e: DomainPlayerInteractEvent, b: BlockContainer, kind: BlockKind) {
        if (kind != BlockKind.CONTAINER || !e.sneaking) return
        val step = when (e.action) {
            InteractAction.RIGHT_BLOCK -> 1
            InteractAction.LEFT_BLOCK -> -1
            else -> return
        }
        val at = b.anchor
        val size = ContainerMode.entries.size
        val data = store.container(at) ?: ContainerData(ContainerMode.AUTO, emptyList())
        val next = ContainerMode.entries[(data.mode.ordinal + step + size) % size]
        saveContainer(at, data.copy(mode = next))
        links.invalidateAround(at)
        feedback.actionBar(e.player.id, "Container: $next")
        e.isCancelled = true
    }

    private fun binder(e: DomainPlayerInteractEvent, b: BlockContainer, kind: BlockKind) {
        val id = e.player.id
        if (e.action == InteractAction.LEFT_BLOCK) {
            if (pending.remove(id) != null) {
                feedback.actionBar(id, "Selection cleared")
                e.isCancelled = true
            }
            return
        }
        if (e.action != InteractAction.RIGHT_BLOCK) return
        when (kind) {
            BlockKind.WORKSTATION -> {
                pending[id] = b.location
                feedback.actionBar(id, "Workstation selected")
                e.isCancelled = true
            }
            BlockKind.CONTAINER -> {
                e.isCancelled = true
                val station = pending[id] ?: run { feedback.actionBar(id, "Select workstation first"); return }
                val chest = b.anchor
                if (!isBound(station, chest) && !finder.inRange(station, chest)) {
                    feedback.actionBar(id, "Too far, max range ${finder.range}")
                    return
                }
                toggleBind(station, chest)
                feedback.actionBar(id, if (isBound(station, chest)) "Bound" else "Unbound")
            }
            BlockKind.OTHER -> {}
        }
    }

    private fun isBound(station: LocationIContainer, container: LocationIContainer) =
        store.container(container)?.stations?.contains(station) == true

    private fun toggleBind(station: LocationIContainer, container: LocationIContainer) {
        val c = store.container(container) ?: ContainerData(ContainerMode.AUTO, emptyList())
        val s = store.station(station) ?: StationData(null, null)
        if (station in c.stations) {
            saveContainer(container, c.copy(stations = c.stations - station))
            store.setStation(station, s.copy(
                input = s.input.takeIf { it != container },
                output = s.output.takeIf { it != container },
                bound = s.bound - container
            ))
        } else {
            saveContainer(container, c.copy(stations = c.stations + station))
            store.setStation(station, s.copy(bound = s.bound + container))
        }
        links.invalidate(station)
    }

    private fun saveContainer(at: LocationIContainer, data: ContainerData) {
        if (data.mode == ContainerMode.AUTO && data.stations.isEmpty()) store.removeContainer(at)
        else store.setContainer(at, data)
    }

    private companion object { const val TOOL = "STICK" }
}
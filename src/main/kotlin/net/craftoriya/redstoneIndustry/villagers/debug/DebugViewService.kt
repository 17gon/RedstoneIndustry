package net.craftoriya.redstoneIndustry.villagers.debug

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.outline.OutlineMarker
import net.craftoriya.adaptersLib.port.system.ISchedulerPort
import net.craftoriya.adaptersLib.port.ui.IOutlinePort
import net.craftoriya.redstoneIndustry.common.coords
import net.craftoriya.redstoneIndustry.demolish.area.AreaView
import net.craftoriya.redstoneIndustry.outline.OutlineStyles
import net.craftoriya.redstoneIndustry.villagers.binding.BindingStore
import net.craftoriya.redstoneIndustry.villagers.container.StationLinkCache
import java.util.LinkedHashMap
import java.util.UUID

class DebugViewService(
    private val store: BindingStore,
    private val links: StationLinkCache,
    private val view: IOutlinePort,
    private val scheduler: ISchedulerPort,
    private val areaView: AreaView,
    private val styles: OutlineStyles,
    private val ticks: Int = 200
) {
    fun showStation(playerId: UUID, station: LocationIContainer): String {
        val markers = LinkedHashMap<LocationIContainer, String>()
        store.station(station)?.bound?.forEach { markers[it] = OutlineStyles.BOUND }
        markers[station] = OutlineStyles.WORKSTATION

        val link = links.resolve(station, scheduler.currentTick())
        val text = when {
            link == null -> "No input/output found"
            link.input == link.output -> {
                markers[link.input] = OutlineStyles.IO
                "I/O ${link.input.coords()}"
            }
            else -> {
                markers[link.input] = OutlineStyles.INPUT
                markers[link.output] = OutlineStyles.OUTPUT
                "In ${link.input.coords()} | Out ${link.output.coords()}"
            }
        }
        val all = markers.map { (loc, style) ->
            val v = loc.vec3I
            OutlineMarker(loc.world, v, v, styles[style])
        } + areaView.markers(station)
        view.show(playerId, all, ticks)
        return text
    }
}
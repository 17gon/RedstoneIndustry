package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.IOutlinePort
import net.craftoriya.adaptersLib.port.ISchedulerPort
import net.craftoriya.adaptersLib.port.OutlineMarker
import net.craftoriya.redstoneIndustry.villagers.tool.Rgb
import net.craftoriya.redstoneIndustry.common.coords
import java.util.UUID

class DebugViewService(
    private val store: BindingStore,
    private val links: StationLinkCache,
    private val view: IOutlinePort,
    private val scheduler: ISchedulerPort,
    private val ticks: Int = 200
) {
    fun showStation(playerId: UUID, station: LocationIContainer): String {
        val markers = LinkedHashMap<LocationIContainer, Int>()
        store.station(station)?.bound?.forEach { markers[it] = Rgb.BOUND }
        markers[station] = Rgb.WORKSTATION

        val link = links.resolve(station, scheduler.currentTick())
        val text = when {
            link == null -> "No input/output found"
            link.input == link.output -> {
                markers[link.input] = Rgb.IO
                "I/O ${link.input.coords()}"
            }
            else -> {
                markers[link.input] = Rgb.INPUT
                markers[link.output] = Rgb.OUTPUT
                "In ${link.input.coords()} | Out ${link.output.coords()}"
            }
        }
        view.show(playerId, markers.map { OutlineMarker(it.key, it.value) }, ticks)
        return text
    }
}
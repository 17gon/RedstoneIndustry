package net.craftoriya.redstoneIndustry.demolish.area

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.outline.OutlineMarker
import net.craftoriya.adaptersLib.port.ui.IOutlinePort
import net.craftoriya.redstoneIndustry.outline.OutlineStyles
import java.util.UUID

class AreaView(
    private val areas: AreaStore,
    private val outline: IOutlinePort,
    private val styles: OutlineStyles,
    private val ticks: Int = 200
) {
    fun markers(station: LocationIContainer): List<OutlineMarker> {
        val zone: ZoneData = areas.zoneOf(station) ?: return emptyList()
        val world: String = station.world
        val out = ArrayList<OutlineMarker>()
        zone.demolish?.let { out += OutlineMarker(world, it.min, it.max, styles[OutlineStyles.DEMOLISH]) }
        zone.safe.forEach { out += OutlineMarker(world, it.min, it.max, styles[OutlineStyles.SAFE]) }
        return out
    }

    fun show(playerId: UUID, station: LocationIContainer) =
        outline.show(playerId, markers(station), ticks)
}
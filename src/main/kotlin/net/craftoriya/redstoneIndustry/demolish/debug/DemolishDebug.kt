package net.craftoriya.redstoneIndustry.demolish.debug

import net.craftoriya.redstoneIndustry.demolish.work.Phase
import net.craftoriya.redstoneIndustry.demolish.area.DemolishZone

internal object DemolishDebug {
    fun describe(zone: DemolishZone): String {
        val crew = Phase.entries.joinToString("/") { p -> zone.workers.values.count { it.phase == p }.toString() }
        return "demolish zone ${zone.id.toString().take(8)} w=${zone.workers.size} y=${zone.layerY}>${zone.box.min.y} " +
                "ph[P/M/B/D]=$crew retry=${zone.retryQueue.size} skipped=${zone.skipped.size} " +
                "climber=${zone.climber != null} cycleBroke=${zone.cycleBroke} done=${zone.done}"
    }
}
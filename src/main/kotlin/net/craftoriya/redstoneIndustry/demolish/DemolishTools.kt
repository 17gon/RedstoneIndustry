package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.ItemSource
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.IBlockBreakPort
import net.craftoriya.adaptersLib.port.IBlockDataPort
import net.craftoriya.adaptersLib.port.IContainerPort
import net.craftoriya.adaptersLib.port.IEntityPort
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.StationLinkCache

internal class DemolishTools(
    private val containers: IContainerPort,
    private val entities: IEntityPort,
    private val world: IWorldQueryPort,
    private val breaker: IBlockBreakPort,
    private val data: IBlockDataPort
) {
    fun choose(
        worker: DemolishWorker,
        link: StationLinkCache.ResolvedLink,
        target: LocationIContainer,
        material: String,
        now: Long
    ): ToolResult {
        val cached = worker.tool
        if (cached != null && now < worker.toolUntil && material == worker.toolBlock) {
            val t = breaker.breakTicks(worker.id, target, cached) ?: return ToolResult.Unbreakable
            return ToolResult.Ticks(t)
        }

        var best = Int.MAX_VALUE
        var from: ItemSource? = null

        for ((slot, item) in entities.inventory(worker.id)) {
            if (slot !in worker.held || world.isFullBlock(item.material)) continue
            val src = ItemSource.inventoryOf(worker.id, slot)
            val t = breaker.breakTicks(worker.id, target, src) ?: return ToolResult.Unbreakable
            if (t < best) { best = t; from = src }
        }
        for ((slot, item) in containers.slots(link.input)) {
            if (world.isFullBlock(item.material)) continue
            val src = ItemSource(link.input, slot)
            val t = breaker.breakTicks(worker.id, target, src) ?: return ToolResult.Unbreakable
            if (t < best) { best = t; from = src }
        }

        var src = from ?: run { worker.tool = null; return ToolResult.Missing }
        val chest = src.container
        if (chest != null) {
            val mine = entities.pull(worker.id, chest, src.slot)
            if (mine == null) {
                worker.stats.invFull++
                worker.tool = null
                return ToolResult.Missing
            }
            worker.held += mine
            save(worker)
            worker.stats.toolTaken++
            src = ItemSource.inventoryOf(worker.id, mine)
        }
        worker.tool = src
        worker.toolBlock = material
        worker.toolUntil = now + TOOL_RECHECK
        return ToolResult.Ticks(best)
    }

    fun giveBack(w: DemolishWorker, link: StationLinkCache.ResolvedLink) {
        for (slot in w.held.toList()) {
            val ok = entities.push(w.id, slot, link.input)
            if (ok) w.held -= slot
            Trace.log { "demolish: tool slot=$slot returned=$ok" }
        }
        save(w)
    }

    fun save(worker: DemolishWorker) {
        runCatching {
            if (worker.held.isEmpty()) data.remove(worker.site, KEY)
            else data.set(worker.site, KEY, "${worker.id}:${worker.held.joinToString(",")}")
        }
    }

    fun restore(worker: DemolishWorker) {
        runCatching {
            val raw = data.get(worker.site, KEY) ?: return
            val id = raw.substringBefore(':')
            if (id != worker.id.toString()) {
                data.remove(worker.site, KEY)
                return
            }
            val present = entities.inventory(worker.id).map { it.slot }.toSet()
            raw.substringAfter(':').split(",").mapNotNull { it.toIntOrNull() }
                .filter { it in present }
                .forEach { worker.held += it }
            save(worker)
        }
    }

    private companion object {
        const val TOOL_RECHECK = 100L
        const val KEY = "DH"
    }
}
package net.craftoriya.redstoneIndustry.demolish.rule

import net.craftoriya.adaptersLib.model.item.ItemSource
import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.item.SlotItem
import net.craftoriya.adaptersLib.port.world.IBlockBreakPort
import net.craftoriya.adaptersLib.port.world.IBlockDataPort
import net.craftoriya.adaptersLib.port.world.IContainerPort
import net.craftoriya.adaptersLib.port.entity.IEntityPort
import net.craftoriya.adaptersLib.port.world.IWorldQueryPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.demolish.work.DemolishWorker
import net.craftoriya.redstoneIndustry.demolish.work.ToolResult
import net.craftoriya.redstoneIndustry.villagers.container.StationLinkCache

internal class DemolishTools(
    private val containers: IContainerPort,
    private val entities: IEntityPort,
    private val world: IWorldQueryPort,
    private val breaker: IBlockBreakPort,
    private val data: IBlockDataPort
) {
    private fun isTool(item: SlotItem) = !world.isBlock(item.item.material) && item.item.material != BUCKET

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

        for (item in entities.inventory(worker.id)) {
            if (item.slot !in worker.held || !isTool(item)) continue
            val src = ItemSource.inventoryOf(worker.id, item.slot)
            val t = breaker.breakTicks(worker.id, target, src) ?: return ToolResult.Unbreakable
            if (t < best) { best = t; from = src }
        }
        for (item in containers.slots(link.input)) {
            if (!isTool(item)) continue
            val src = ItemSource(link.input, item.slot)
            val t = breaker.breakTicks(worker.id, target, src) ?: return ToolResult.Unbreakable
            if (t < best) { best = t; from = src }
        }

        var src = from ?: run { worker.tool = null; return ToolResult.Missing }
        val chest = src.container
        if (chest != null) {
            val mine = entities.pull(worker.id, chest, src.slot) ?: run { worker.tool = null; return ToolResult.Missing }
            worker.held += mine
            save(worker)
            src = ItemSource.inventoryOf(worker.id, mine)
        }
        worker.tool = src
        worker.toolBlock = material
        worker.toolUntil = now + TOOL_RECHECK
        return ToolResult.Ticks(best)
    }

    fun bucket(worker: DemolishWorker, link: StationLinkCache.ResolvedLink): ItemSource? {
        val inv = entities.inventory(worker.id)
        inv.firstOrNull { it.slot in worker.held && it.item.material == BUCKET }
            ?.let { return ItemSource.inventoryOf(worker.id, it.slot) }
        worker.held.retainAll(inv.map { it.slot }.toSet())

        val slot = containers.slots(link.input).firstOrNull { it.item.material == BUCKET } ?: return null
        val mine = entities.pull(worker.id, link.input, slot.slot) ?: return null
        worker.held += mine
        save(worker)
        return ItemSource.inventoryOf(worker.id, mine)
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
        const val BUCKET = "BUCKET"
    }
}
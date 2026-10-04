package net.craftoriya.redstoneIndustry.recipe

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainVillagerInteractEvent
import net.craftoriya.adaptersLib.port.ITradeBookPort
import net.craftoriya.adaptersLib.port.TradeApplyMode

class TradeHandlers(
    private val bus: DomainEventBus,
    private val trades: TradeRegistry,
    private val tradeBook: ITradeBookPort
) {
    private var mode = TradeApplyMode.ADD

    fun start() {
        bus.on<DomainVillagerInteractEvent> { event ->
            val matches = trades.get(event.profession, event.level)
            if (matches.isEmpty()) return@on
            tradeBook.applyTrades(event.entity, matches, mode)
            mode = matches[0].mode
        }
    }
}
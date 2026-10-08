package net.craftoriya.redstoneIndustry.menu

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainMenuClickEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainMenuCloseEvent
import net.craftoriya.adaptersLib.model.MenuContainer
import net.craftoriya.adaptersLib.port.IMenuPort
import java.util.UUID

typealias MenuHandler = (DomainMenuClickEvent, String) -> Unit

class MenuService(
    private val port: IMenuPort,
    private val configs: Map<String, MenuConfig>
) {
    private val handlers = HashMap<String, MenuHandler>()
    private val shown = HashMap<UUID, MenuContainer>()

    fun subscribe(bus: DomainEventBus) {
        bus.on<DomainMenuClickEvent> { onClick(it) }
        bus.on<DomainMenuCloseEvent> { shown.remove(it.player.id) }
    }

    fun handler(name: String, handler: MenuHandler) {
        handlers[name] = handler
    }

    fun open(player: UUID, menuId: String, menu: MenuContainer) {
        port.open(player, menuId, menu)
        shown[player] = menu
    }

    fun refresh(player: UUID, menu: MenuContainer) {
        port.refresh(player, menu)
        shown[player] = menu
    }

    fun openConfigured(player: UUID, menuId: String, vars: Map<String, String> = emptyMap()) {
        val config = configs[menuId] ?: return
        open(player, menuId, config.build(vars))
    }

    private fun onClick(e: DomainMenuClickEvent) {
        val action = shown[e.player.id]?.items?.get(e.slot)?.properties?.get(ACTION) ?: return
        handlers[action.substringBefore(':')]?.invoke(e, action.substringAfter(':', ""))
    }

    private companion object { const val ACTION = "action" }
}

package net.craftoriya.redstoneIndustry.menu

import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.MenuContainer
import net.craftoriya.redstoneIndustry.tech.TechNode
import net.craftoriya.redstoneIndustry.tech.TechStates
import net.craftoriya.redstoneIndustry.tech.TechTree
import java.util.UUID

class ResearchMenu(
    private val tree: TechTree,
    private val states: TechStates,
    private val menus: MenuService
) {
    fun register() {
        menus.handler("root") { e, _ -> openRoot(e.player.id) }
        menus.handler("cat") { e, arg ->
            openCategory(e.player.id, arg.substringBefore(','), arg.substringAfter(',', "0").toIntOrNull() ?: 0)
        }
        menus.handler("node") { e, arg -> openNode(e.player.id, arg) }
    }

    fun openRoot(player: UUID) {
        val done = states.of(player)
        val items = HashMap<Int, ItemContainer>()
        tree.categories.entries.take(54).forEachIndexed { i, (name, nodes) ->
            items[i] = ItemContainer(
                name, nodes.first().icon, 1,
                mapOf(
                    "lore" to "${nodes.count { done.isUnlocked(it.id) }}/${nodes.size}",
                    "hide" to "true",
                    "action" to "cat:$name,0"
                )
            )
        }
        val rows = ((items.size + 8) / 9).coerceIn(1, 6)
        menus.open(player, MENU, MenuContainer("Research", rows, items))
    }

    fun openCategory(player: UUID, category: String, page: Int) {
        val nodes = tree.categories[category] ?: return
        val from = page * PAGE
        val items = HashMap<Int, ItemContainer>()
        nodes.drop(from).take(PAGE).forEachIndexed { i, node ->
            items[i] = nodeItem(player, node, false).action("node:${node.id}")
        }
        if (page > 0) items[45] = button("Previous", "ARROW", "cat:$category,${page - 1}")
        items[49] = button("Back", "BARRIER", "root")
        if (from + PAGE < nodes.size) items[53] = button("Next", "ARROW", "cat:$category,${page + 1}")
        menus.open(player, MENU, MenuContainer("Research: $category", 6, items))
    }

    fun openNode(player: UUID, id: String) {
        val node = tree.byId[id] ?: return
        val items = HashMap<Int, ItemContainer>()
        node.prerequisites.take(9).forEachIndexed { i, p ->
            items[i] = nodeItem(player, tree.byId.getValue(p), false).action("node:$p")
        }
        items[13] = nodeItem(player, node, true)
        node.cost.take(9).forEachIndexed { i, cost -> items[18 + i] = cost }
        tree.dependents[id].orEmpty().take(9).forEachIndexed { i, d ->
            items[36 + i] = nodeItem(player, tree.byId.getValue(d), false).action("node:$d")
        }
        items[49] = button("Back", "BARRIER", "cat:${node.category},0")
        menus.open(player, MENU, MenuContainer("Research: ${node.id}", 6, items))
    }

    private fun nodeItem(player: UUID, node: TechNode, detail: Boolean): ItemContainer {
        val done = states.of(player)
        val lore = ArrayList<String>()
        val researched = done.isUnlocked(node.id)
        val missing = node.prerequisites.filterNot(done::isUnlocked)

        lore += when {
            researched -> "Researched"
            missing.isEmpty() -> "Time: ${node.time}"
            else -> "Requires: ${missing.joinToString()}"
        }
        if (detail) {
            if (node.unlocks.isNotEmpty()) lore += "Unlocks: ${node.unlocks.joinToString()}"
            node.boosts.forEach { lore += "${it.target} ${it.stat} ${it.op} ${it.value}" }
        }

        val props = mutableMapOf("lore" to lore.joinToString("\n"), "hide" to "true")
        if (researched) props["glint"] = "true"
        val material = if (researched || missing.isEmpty()) node.icon else "GRAY_STAINED_GLASS_PANE"
        return ItemContainer(node.id, material, 1, props)
    }

    private fun button(name: String, material: String, action: String) =
        ItemContainer(name, material, 1, mapOf("action" to action))

    private fun ItemContainer.action(value: String) = copy(properties = properties + ("action" to value))

    private companion object {
        const val MENU = "research"
        const val PAGE = 45
    }
}
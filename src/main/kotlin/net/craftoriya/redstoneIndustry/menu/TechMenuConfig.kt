package net.craftoriya.redstoneIndustry.menu

import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.MenuContainer
import org.spongepowered.configurate.objectmapping.ConfigSerializable

@ConfigSerializable
data class MenuConfig(
    val title: String = "",
    val rows: Int = 3,
    val items: Map<Int, ItemContainer> = emptyMap()
)

@ConfigSerializable
data class MenusConfig(
    val menus: Map<String, MenuConfig> = emptyMap()
)

fun MenuConfig.build(vars: Map<String, String>): MenuContainer {
    fun String.sub() = vars.entries.fold(this) { s, (k, v) -> s.replace("{$k}", v) }
    return MenuContainer(
        title.sub(),
        rows,
        items.mapValues { (_, i) -> i.copy(name = i.name.sub(), properties = i.properties.mapValues { it.value.sub() }) }
    )
}
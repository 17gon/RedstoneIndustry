package net.craftoriya.redstoneIndustry

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.craftoriya.adaptersLib.AdaptersLib
import net.craftoriya.adaptersLib.adapter.world.BukkitBlockBreakAdapter
import net.craftoriya.adaptersLib.adapter.world.BukkitBlockDataAdapter
import net.craftoriya.adaptersLib.adapter.world.BukkitContainerAdapter
import net.craftoriya.adaptersLib.adapter.entity.BukkitEntityAdapter
import net.craftoriya.adaptersLib.adapter.ui.BukkitMenuAdapter
import net.craftoriya.adaptersLib.adapter.ui.BukkitOutlineAdapter
import net.craftoriya.adaptersLib.adapter.ui.BukkitPlayerFeedbackAdapter
import net.craftoriya.adaptersLib.adapter.recipe.BukkitRecipeBookAdapter
import net.craftoriya.adaptersLib.adapter.system.BukkitSchedulerAdapter
import net.craftoriya.adaptersLib.adapter.recipe.BukkitTradeBookAdapter
import net.craftoriya.adaptersLib.adapter.world.BukkitWorldQueryAdapter
import net.craftoriya.adaptersLib.adapter.entity.BukkitVillagerControlAdapter
import net.craftoriya.adaptersLib.adapter.command.CommandAdapter
import net.craftoriya.adaptersLib.adapter.listener.BlockSyncListener
import net.craftoriya.adaptersLib.adapter.listener.MenuListener
import net.craftoriya.adaptersLib.adapter.listener.PaperEventListener
import net.craftoriya.adaptersLib.adapter.listener.VillagerTickSource
import net.craftoriya.adaptersLib.recipe.RecipesConfig
import net.craftoriya.adaptersLib.config.TagsConfig
import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.player.DomainCommandEvent
import net.craftoriya.adaptersLib.event.domainevents.player.DomainPlayerJumpEvent
import net.craftoriya.adaptersLib.port.entity.IVillagerControlPort
import net.craftoriya.adaptersLib.recipe.RecipeExpander
import net.craftoriya.redstoneIndustry.command.CommandSpecs
import net.craftoriya.redstoneIndustry.command.DebugCommand
import net.craftoriya.redstoneIndustry.command.ResearchCommand
import net.craftoriya.redstoneIndustry.menu.MenuService
import net.craftoriya.redstoneIndustry.menu.MenusConfig
import net.craftoriya.redstoneIndustry.menu.ResearchMenu
import net.craftoriya.redstoneIndustry.outline.OutlineStyles
import net.craftoriya.redstoneIndustry.outline.OutlinesConfig
import net.craftoriya.redstoneIndustry.recipe.RecipeHandlers
import net.craftoriya.redstoneIndustry.recipe.RecipeRegistry
import net.craftoriya.redstoneIndustry.recipe.TradeHandlers
import net.craftoriya.redstoneIndustry.recipe.TradeRegistry
import net.craftoriya.redstoneIndustry.tech.TechExpander
import net.craftoriya.redstoneIndustry.tech.state.TechStates
import net.craftoriya.redstoneIndustry.tech.TechTree
import net.craftoriya.redstoneIndustry.tech.dto.TechTreeDto
import net.craftoriya.redstoneIndustry.villagers.VillagerConfig
import net.craftoriya.redstoneIndustry.villagers.VillagerSystem
import net.craftoriya.redstoneIndustry.villagers.work.TradeWorkRecipes
import org.bukkit.plugin.java.JavaPlugin

class RedstoneIndustry : JavaPlugin() {

    override fun onEnable() {
        val bus = AdaptersLib.eventBus
        val configs = AdaptersLib.configLoader(dataFolder)

        server.pluginManager.registerEvents(PaperEventListener(bus), this)
        server.pluginManager.registerEvents(BlockSyncListener(bus), this)
        server.pluginManager.registerEvents(MenuListener(bus), this)

        val tags = configs.loadOrSave(TagsConfig::class, "tags")
        val recipes = configs.loadOrSave(RecipesConfig::class, "recipes")
        val villagerConfig = configs.loadOrSave(VillagerConfig::class, "villagers")
        val outlineStyles = OutlineStyles(configs.loadOrSave(OutlinesConfig::class, "outlines", OutlineStyles.DEFAULT_CONFIG))

        val recipeHandlers = RecipeHandlers(bus, RecipeRegistry(), BukkitRecipeBookAdapter(AdaptersLib.instance))
        val expanded = RecipeExpander.expand(recipes, tags)
        logger.info("Loaded ${expanded.size} recipes")
        recipeHandlers.load(expanded)
        recipeHandlers.start()

        val tradeRegistry = TradeRegistry(recipes)
        TradeHandlers(bus, tradeRegistry, BukkitTradeBookAdapter()).start()

        val villagerControl = BukkitVillagerControlAdapter()
        val tickSource = VillagerTickSource(this, bus)
        val villagers = VillagerSystem(
            bus,
            villagerConfig,
            BukkitBlockDataAdapter(this),
            BukkitWorldQueryAdapter(),
            BukkitContainerAdapter(),
            villagerControl,
            BukkitOutlineAdapter(this),
            outlineStyles,
            BukkitPlayerFeedbackAdapter(),
            BukkitSchedulerAdapter(this),
            tickSource,
            BukkitEntityAdapter(),
            BukkitBlockBreakAdapter(bus),
            TradeWorkRecipes(tradeRegistry)
        )
        villagers.start()
        tickSource.start()
        val menus = MenuService(BukkitMenuAdapter(), configs.loadOrSave(MenusConfig::class, "menus").menus)
        menus.subscribe(bus)

        val techTree = TechExpander.expand(configs.loadOrSave(TechTreeDto::class, "tech"))
        val techStates = TechStates()
        val researchMenu = ResearchMenu(techTree, techStates, menus)
        researchMenu.register()
        logger.info("Loaded ${techTree.byId.size} techs")

        registerJumpDemo(bus, villagerControl)
        registerCommands(bus, DebugCommand(villagers) { logger.info(it) },
            ResearchCommand(techTree, techStates, researchMenu),
            techTree
        )
    }

    private fun registerCommands(bus: DomainEventBus, debug: DebugCommand, research: ResearchCommand, techTree: TechTree) {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()
            commands.register("debug", "Debug command", CommandAdapter(bus, "debug", CommandSpecs.debug()))
            commands.register("researchmenu", "Open research menu", CommandAdapter(bus, "researchmenu", CommandSpecs.research { techTree.byId.keys }))
        }

        bus.on<DomainCommandEvent> { event ->
            when (event.label) {
                "debug" -> debug.handle(event)
                "researchmenu" -> research.handle(event)
            }
        }
    }

    private fun registerJumpDemo(bus: DomainEventBus, villagers: IVillagerControlPort) {
        bus.on<DomainPlayerJumpEvent> { event ->
            villagers.getNearbyEntities(event.player.position, 5.0, "world")
                .filter { it.type == "VILLAGER" }
                .forEach { villagers.lookAt(it.id, event.player.position) }
        }
    }
}
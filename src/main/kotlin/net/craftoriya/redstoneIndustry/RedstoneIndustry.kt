package net.craftoriya.redstoneIndustry

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.craftoriya.adaptersLib.AdaptersLib
import net.craftoriya.adaptersLib.adapter.BukkitBlockDataAdapter
import net.craftoriya.adaptersLib.adapter.BukkitContainerAdapter
import net.craftoriya.adaptersLib.adapter.BukkitOutlineAdapter
import net.craftoriya.adaptersLib.adapter.BukkitPlayerFeedbackAdapter
import net.craftoriya.adaptersLib.adapter.BukkitRecipeBookAdapter
import net.craftoriya.adaptersLib.adapter.BukkitSchedulerAdapter
import net.craftoriya.adaptersLib.adapter.BukkitTradeBookAdapter
import net.craftoriya.adaptersLib.adapter.BukkitWorldQueryAdapter
import net.craftoriya.adaptersLib.adapter.ai.BukkitVillagerControlAdapter
import net.craftoriya.adaptersLib.adapter.command.CommandAdapter
import net.craftoriya.adaptersLib.adapter.listener.BlockSyncListener
import net.craftoriya.adaptersLib.adapter.listener.PaperEventListener
import net.craftoriya.adaptersLib.adapter.listener.VillagerTickSource
import net.craftoriya.adaptersLib.config.RecipesConfig
import net.craftoriya.adaptersLib.config.TagsConfig
import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainCommandEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainPlayerJumpEvent
import net.craftoriya.adaptersLib.port.IVillagerControlPort
import net.craftoriya.adaptersLib.recipe.RecipeExpander
import net.craftoriya.redstoneIndustry.command.DebugCommand
import net.craftoriya.redstoneIndustry.recipe.RecipeHandlers
import net.craftoriya.redstoneIndustry.recipe.RecipeRegistry
import net.craftoriya.redstoneIndustry.recipe.TradeHandlers
import net.craftoriya.redstoneIndustry.recipe.TradeRegistry
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

        val tags = configs.loadOrSave(TagsConfig::class, "tags")
        val recipes = configs.loadOrSave(RecipesConfig::class, "recipes")
        val villagerConfig = configs.loadOrSave(VillagerConfig::class, "villagers")

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
            BukkitPlayerFeedbackAdapter(),
            BukkitSchedulerAdapter(this),
            tickSource,
            TradeWorkRecipes(tradeRegistry)
        )
        villagers.start()
        tickSource.start()

        registerJumpDemo(bus, villagerControl)
        registerCommands(bus, DebugCommand(villagers) { logger.info(it) })
    }

    private fun registerCommands(bus: DomainEventBus, debug: DebugCommand) {
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()
            commands.register("debug", "Debug command", CommandAdapter(bus, "debug"))
            commands.register("researchmenu", "Open research menu", CommandAdapter(bus, "researchmenu"))
        }

        bus.on<DomainCommandEvent> { event ->
            when (event.label) {
                "debug" -> debug.handle(event)
                "researchmenu" -> event.response.send(event.sender, "Opening research menu...")
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
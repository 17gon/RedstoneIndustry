package net.craftoriya.redstoneIndustry

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.craftoriya.adaptersLib.AdaptersLib
import net.craftoriya.adaptersLib.AdaptersLib.Companion.configLoader
import net.craftoriya.adaptersLib.command.CommandAdapter
import net.craftoriya.adaptersLib.containers.RecipeContainer
import net.craftoriya.adaptersLib.tools.RecipeExpander
import net.craftoriya.adaptersLib.containers.RecipesConfig
import net.craftoriya.adaptersLib.containers.TagsConfig
import net.craftoriya.adaptersLib.event.HandlerPriority
import net.craftoriya.adaptersLib.event.events.DomainCommandEvent
import net.craftoriya.adaptersLib.event.events.DomainPlayerJoinEvent
import net.craftoriya.adaptersLib.event.events.DomainPlayerJumpEvent
import net.craftoriya.adaptersLib.event.events.DomainPrepareItemCraftEvent
import net.craftoriya.adaptersLib.listeners.PaperEventListener
import net.craftoriya.adaptersLib.tools.RecipeBookPort
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

class RedstoneIndustry: JavaPlugin() {
    val registry = RecipeRegistry()

    override fun onEnable() {
        val bus = AdaptersLib.eventBus
        val configLoader = configLoader(dataFolder)

        val jumpAdapter: Listener = PaperEventListener(bus)
        server.pluginManager.registerEvents(jumpAdapter, this)

        val tags = configLoader.loadOrSave(TagsConfig::class, "tags")
        val recipes = configLoader.loadOrSave(RecipesConfig::class, "recipes")
        val recipeBook = RecipeBookPort(AdaptersLib.instance)

        RecipeExpander.expand(recipes, tags).also { list ->
            logger.info("Loaded ${list.size} recipes")
            list.forEach { logger.info("  $it") }
        }.forEachIndexed { i, recipe ->
            registry.register(recipe)
            recipeBook.removeVanillaRecipesFor(recipe.output)
            recipeBook.replaceRecipe("recipe_$i", recipe)
        }

        // --------------------------------------------------------
        // Domain underhood | Will be moved away into their classes
        // --------------------------------------------------------
        bus.on<DomainPlayerJumpEvent>(HandlerPriority.NORMAL) { event ->
            logger.info("${event.player.name} jumped at ${event.player.position}")

            if (event.player.name == "debug") {
                event.isCancelled = true
                logger.info("Cancelled jump for debug player.")
            }
        }

        bus.on<DomainPlayerJoinEvent> { event ->
            registry.allKeys().forEach { key -> recipeBook.discoverFor(event.player, key) }
        }

        logger.info("GamePlugin loaded.")
        // Register commands via lifecycle manager — the new Paper way
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val commands = event.registrar()

            // Each command gets its own adapter instance so label is implicit
            commands.register("debug",        "Debug command", CommandAdapter(bus, "debug"))
            commands.register("researchmenu", "Open research menu",  CommandAdapter(bus, "researchmenu"))
        }

        // Core listens for domain command events — unchanged
        bus.on<DomainCommandEvent> { event ->
            when (event.label) {
                "debug"        -> handleDebug(event)
                "researchmenu" -> handleResearchMenu(event)
            }
        }

        bus.on<DomainPrepareItemCraftEvent> { event ->
            if (event.isRepair) return@on

            val match: RecipeContainer? = registry.findMatch(event.inventoryGrid)
            if (match != null) {
                event.result = match.output
                return@on
            }

            if (registry.claimsOutput(event.inventoryGrid.items[0])) {
                event.result = null
                return@on
            }
            event.result = event.inventoryGrid.items[0]
        }
    }

    private fun handleDebug(event: DomainCommandEvent) {
        if (!event.sender.isPlayer) {
            event.response.sendError(event.sender, "Only players can use this.")
            return
        }
        event.response.send(event.sender, "Debug command works. Args: ${event.args}")
    }

    private fun handleResearchMenu(event: DomainCommandEvent) {
        event.response.send(event.sender, "Opening research menu...")
    }
}

package net.craftoriya.redstoneIndustry.recipe

import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainFurnaceSmeltEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainFurnaceStartSmeltEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainPlayerJoinEvent
import net.craftoriya.adaptersLib.event.domainevents.DomainPrepareItemCraftEvent
import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.port.IRecipeBookPort

class RecipeHandlers(
    private val bus: DomainEventBus,
    private val registry: RecipeRegistry,
    private val recipeBook: IRecipeBookPort
) {
    fun load(recipes: List<RecipeContainer>) {
        recipes.forEachIndexed { i, recipe ->
            registry.register(recipe)
            recipeBook.removeVanillaRecipesFor(recipe.output)
            recipeBook.registerRecipe("recipe_$i", recipe)
        }
    }

    fun start() {
        bus.on<DomainPlayerJoinEvent> { event ->
            registry.allKeys().forEach { recipeBook.discoverFor(event.player, it) }
        }

        bus.on<DomainFurnaceStartSmeltEvent> { event ->
            val recipe = event.domainRecipe
            val match = registry.findCookingMatch(recipe.input, recipe.type) ?: return@on
            if (recipe.input.count < match.input.count) event.isCancelled = true
        }

        bus.on<DomainFurnaceSmeltEvent> { event ->
            val recipe = event.domainRecipe
            val match = registry.findCookingMatch(recipe.input, recipe.type) ?: return@on
            if (recipe.input.count < match.input.count) event.isCancelled = true
            else event.extraToConsume = match.input.count - 1
        }

        bus.on<DomainPrepareItemCraftEvent> { event ->
            if (event.isRepair) return@on
            val grid = event.inventoryGrid
            event.result = registry.findWorkbenchMatch(grid)?.output
                ?: if (registry.claimsOutput(grid.items[0])) null else grid.items[0]
        }
    }
}
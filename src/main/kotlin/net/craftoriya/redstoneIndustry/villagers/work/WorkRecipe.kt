package net.craftoriya.redstoneIndustry.villagers.work

import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.port.TradeApplyMode
import net.craftoriya.redstoneIndustry.recipe.TradeRegistry

data class WorkRecipe(val inputs: List<ItemContainer>, val output: ItemContainer, val tier: Int)

fun interface WorkRecipeProvider {
    fun get(profession: RecipeContainer.TradeProfession, level: Int): List<WorkRecipe>
}

class TradeWorkRecipes(private val trades: TradeRegistry) : WorkRecipeProvider {
    private val cache = HashMap<Pair<RecipeContainer.TradeProfession, Int>, List<WorkRecipe>>()

    override fun get(profession: RecipeContainer.TradeProfession, level: Int): List<WorkRecipe> =
        cache.getOrPut(profession to level) {
            trades.upTo(profession, level)
                .filter { it.mode == TradeApplyMode.ADD && it.ingredients.isNotEmpty() }
                .map { WorkRecipe(mergeByMaterial(it.ingredients), it.output, it.level) }
                .sortedByDescending { it.tier }
        }

    private fun mergeByMaterial(items: List<ItemContainer>) =
        items.groupBy { it.material }.map { (material, group) -> ItemContainer(material = material, count = group.sumOf { it.count }) }
}

sealed interface Pick {
    data class Chosen(val recipe: WorkRecipe) : Pick
    data object None : Pick
    data class Ambiguous(val options: List<WorkRecipe>) : Pick
}

object RecipePicker {
    /**
     * An item in the input chest that equals a recipe output acts as a pattern and narrows the choice.
     * Then the highest tier wins; a tie is ambiguous.
     */
    fun pick(recipes: List<WorkRecipe>, have: Map<String, Int>): Pick {
        val craftable = recipes.filter { recipe -> recipe.inputs.all { (have[it.material] ?: 0) >= it.count } }
        if (craftable.isEmpty()) return Pick.None

        val pool = craftable.filter { (have[it.output.material] ?: 0) > 0 }.ifEmpty { craftable }
        val topTier = pool.maxOf { it.tier }
        val best = pool.filter { it.tier == topTier }
        return if (best.size == 1) Pick.Chosen(best.single()) else Pick.Ambiguous(best)
    }
}
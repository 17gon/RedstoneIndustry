package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.RecipeContainer
import net.craftoriya.adaptersLib.event.DomainEventBus
import net.craftoriya.adaptersLib.event.domainevents.DomainVillagerTickEvent
import net.craftoriya.adaptersLib.model.ItemContainer
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.IContainerPort
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.work.Pick
import net.craftoriya.redstoneIndustry.villagers.work.RecipePicker
import net.craftoriya.redstoneIndustry.villagers.work.WorkRecipe
import net.craftoriya.redstoneIndustry.villagers.work.WorkRecipeProvider
import java.util.UUID

class VillagerWorkRoutine(
    private val containers: IContainerPort,
    private val links: StationLinkCache,
    private val recipes: WorkRecipeProvider,
    private val animation: WorkAnimation
) {
    private class Job(val recipe: WorkRecipe, val site: LocationIContainer, val doneTick: Long)

    private val jobs = HashMap<UUID, Job>()

    fun subscribe(bus: DomainEventBus) = bus.on<DomainVillagerTickEvent> { onTick(it) }

    private fun onTick(e: DomainVillagerTickEvent) {
        val id = e.villager.id
        val job = jobs[id]
        if (job != null) {
            if (e.tick >= job.doneTick) finish(id, job, e.tick)
            return
        }
        if (canWork(e)) start(e, id)
    }

    private fun start(e: DomainVillagerTickEvent, id: UUID) {
        val site = e.jobSite ?: return

        val list = recipes.get(e.profession, e.level)
        if (list.isEmpty()) { Trace.log { "work: no recipe for ${e.profession} L${e.level}" }; return }
        val link = links.resolve(site, e.tick)
        if (link == null) { Trace.log { "work: no link at ${site.vec3I}" }; return }

        val have = containers.contents(link.input).associate { it.material to it.count }
        val recipe = when (val pick = RecipePicker.pick(list, have)) {
            is Pick.Chosen -> pick.recipe
            Pick.None -> { Trace.log { "work: nothing craftable from ${link.input.vec3I}" }; return }
            is Pick.Ambiguous -> {
                Trace.log { "work: ambiguous ${pick.options.map { it.output.material }}, place a pattern item in ${link.input.vec3I}" }
                return
            }
        }

        val taken = ArrayList<ItemContainer>()
        for (need in recipe.inputs) {
            val got = containers.take(link.input, { it.material == need.material }, need.count)
            taken += got
            if (got.sumOf { it.count } < need.count) {
                Trace.log { "work: take failed ${need.material}, rollback" }
                containers.put(link.input, taken)
                return
            }
        }

        val ticks = workTicks(e.level)
        Trace.log { "work: start ${recipe.output.material} tier=${recipe.tier} ticks=$ticks" }
        jobs[id] = Job(recipe, site, e.tick + ticks)
        animation.play(id, site, ticks.toInt())
    }

    private fun finish(id: UUID, job: Job, now: Long) {
        val link = links.resolve(job.site, now)
        if (link == null) { Trace.log { "work: finish waits, no link at ${job.site.vec3I}" }; return }

        val out = job.recipe.output
        val leftover = containers.put(link.output, listOf(out))
        if (leftover.isNotEmpty()) {
            Trace.log { "work: output full ${link.output.vec3I}, retry" }
            val placed = out.count - leftover.sumOf { it.count }
            if (placed > 0) containers.take(link.output, { it.material == out.material }, placed)
            return
        }
        Trace.log { "work: done ${out.material} out=${link.output.vec3I}" }
        jobs.remove(id)
    }

    private fun workTicks(level: Int) = (240L - level * 40L).coerceAtLeast(40L)

    companion object {
        const val WORK_START = 2000L
        const val WORK_END = 9000L
        const val MAX_DIST = 3.0

        fun canWork(e: DomainVillagerTickEvent) =
            e.jobSite != null &&
                    e.profession != RecipeContainer.TradeProfession.NONE &&
                    !e.sleeping &&
                    e.worldTime in WORK_START..WORK_END &&
                    e.distanceToJobSite <= MAX_DIST

        fun reason(e: DomainVillagerTickEvent): String = when {
            e.worldTime !in WORK_START..WORK_END -> "world time ${e.worldTime} not in $WORK_START..$WORK_END"
            e.profession == RecipeContainer.TradeProfession.NONE -> "profession ${e.profession}"
            e.sleeping -> "sleeping"
            e.jobSite == null -> "no JOB_SITE memory"
            e.distanceToJobSite > MAX_DIST -> "too far: %.1f blocks".format(e.distanceToJobSite)
            else -> "OK, can work"
        }
    }
}
package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerMode
import net.craftoriya.redstoneIndustry.villagers.link.ContainerRef


data class Resolution(val input: ContainerRef, val output: ContainerRef)

interface ContainerRule {
    fun resolve(jobSite: LocationIContainer, refs: List<ContainerRef>): Resolution?
}

class SmartRule : ContainerRule {
    private val outputOrder = compareByDescending<ContainerRef> { it.location.vec3I.y }
        .thenByDescending { it.hopperBelow }
        .then(positionOrder)

    private val inputOrder = compareBy<ContainerRef> { it.location.vec3I.y }
        .thenBy { it.hopperBelow }
        .then(positionOrder)

    override fun resolve(jobSite: LocationIContainer, refs: List<ContainerRef>): Resolution? {
        val active = refs.filter { it.mode != ContainerMode.OFF }
        if (active.isEmpty()) return null

        val autos = active.filter { it.mode == ContainerMode.AUTO }

        val output = active.filter { it.mode == ContainerMode.OUT || it.mode == ContainerMode.IO }.minWithOrNull(outputOrder)
            ?: autos.minWithOrNull(outputOrder)
            ?: return null

        val input = active.filter { (it.mode == ContainerMode.IN || it.mode == ContainerMode.IO) && it.location != output.location }
            .minWithOrNull(inputOrder)
            ?: autos.filter { it.location != output.location }.minWithOrNull(inputOrder)
            ?: output.takeIf { it.mode == ContainerMode.AUTO || it.mode == ContainerMode.IO }
            ?: return null

        return Resolution(input, output)
    }

    private companion object {
        val positionOrder: Comparator<ContainerRef> = compareBy<ContainerRef>({ it.location.vec3I.x }, { it.location.vec3I.z })
    }
}
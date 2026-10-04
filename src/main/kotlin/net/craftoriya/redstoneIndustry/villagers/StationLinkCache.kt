package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.redstoneIndustry.common.Trace
import net.craftoriya.redstoneIndustry.villagers.binding.StationData


class StationLinkCache(
    private val store: BindingStore,
    private val finder: ContainerFinder,
    private val rule: ContainerRule
) {
    private val negativeUntil = HashMap<LocationIContainer, Long>()

    fun resolve(site: LocationIContainer, now: Long): ResolvedLink? {
        negativeUntil[site]?.let {
            if (now < it && it - now <= NEGATIVE_TTL) return null else negativeUntil.remove(site)
        }

        val cached = store.station(site)
        val i = cached?.input
        val o = cached?.output
        if (i != null && o != null &&
            finder.alive(i) && finder.alive(o) &&
            finder.inRange(site, i) && finder.inRange(site, o)
        ) {
            Trace.log { "cache hit ${site.vec3I} in=${i.vec3I} out=${o.vec3I}" }
            return ResolvedLink(i, o)
        }

        val refs = finder.findContainers(site)
        val res = rule.resolve(site, refs)
        if (res == null) {
            Trace.log { "resolve failed ${site.vec3I}, candidates=${refs.size}" }
            if (negativeUntil.size > 512) negativeUntil.values.removeIf { it <= now }
            negativeUntil[site] = now + NEGATIVE_TTL
            return null
        }
        Trace.log { "resolved ${site.vec3I} in=${res.input.location.vec3I} out=${res.output.location.vec3I} candidates=${refs.size}" }
        val fresh = store.station(site) ?: StationData(null, null)
        store.setStation(site, fresh.copy(input = res.input.location, output = res.output.location))
        return ResolvedLink(res.input.location, res.output.location)
    }

    fun invalidate(site: LocationIContainer) {
        negativeUntil.remove(site)
        val s = store.station(site)
        Trace.log { "invalidate ${site.vec3I} hadData=${s != null}" }
        if (s != null && (s.input != null || s.output != null)) store.setStation(site, s.copy(input = null, output = null))
    }

    fun forget(site: LocationIContainer) { negativeUntil.remove(site) }

    fun invalidateAround(container: LocationIContainer) {
        val stations = LinkedHashSet<LocationIContainer>()
        store.container(container)?.stations?.let { stations.addAll(it) }
        stations.addAll(finder.findWorkstations(container))
        Trace.log { "invalidateAround ${container.vec3I} stations=${stations.map { it.vec3I }}" }
        stations.forEach { invalidate(it) }
    }

    private companion object { const val NEGATIVE_TTL = 200L }

    data class ResolvedLink(val input: LocationIContainer, val output: LocationIContainer)
}
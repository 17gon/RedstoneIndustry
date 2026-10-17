package net.craftoriya.redstoneIndustry.villagers.binding

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IBlockDataPort

class BindingStore(private val data: IBlockDataPort) {

    fun station(at: LocationIContainer): StationData? = runCatching {
        val p = data.get(at, STATION)?.split(";", limit = 3) ?: return null
        fun loc(s: String) = dec(s)?.let { LocationIContainer(at.world, it) }
        StationData(loc(p[0]), loc(p[1]), p.getOrElse(2) { "" }.split("|").mapNotNull { loc(it) })
    }.getOrNull()

    fun setStation(at: LocationIContainer, d: StationData) {
        data.set(at, STATION, "${enc(d.input?.vec3I)};${enc(d.output?.vec3I)};${d.bound.joinToString("|") { enc(it.vec3I) }}")
    }

    fun removeStation(at: LocationIContainer) = data.remove(at, STATION)

    fun container(at: LocationIContainer): ContainerData? = runCatching {
        val raw = data.get(at, CONTAINER) ?: return null
        val parts = raw.split(";", limit = 2)
        val stations = parts.getOrElse(1) { "" }.split("|").mapNotNull { dec(it) }.map {
            LocationIContainer(
                at.world,
                it
            )
        }
        ContainerData(ContainerMode.valueOf(parts[0]), stations)
    }.getOrNull()

    fun setContainer(at: LocationIContainer, d: ContainerData) {
        data.set(at, CONTAINER, "${d.mode};${d.stations.joinToString("|") { enc(it.vec3I) }}")
    }

    fun removeContainer(at: LocationIContainer) = data.remove(at, CONTAINER)

    fun demolish(at: LocationIContainer): Boolean = data.get(at, DEMOLISH) == "1"

    fun setDemolish(at: LocationIContainer, on: Boolean) {
        if (on) data.set(at, DEMOLISH, "1") else data.remove(at, DEMOLISH)
    }

    private fun enc(v: Vec3I?) = if (v == null) "-" else "${v.x},${v.y},${v.z}"

    private fun dec(s: String): Vec3I? {
        if (s == "-" || s.isEmpty()) return null
        val p = s.split(",")
        return Vec3I(p[0].toInt(), p[1].toInt(), p[2].toInt())
    }

    private companion object {
        const val STATION = "s"
        const val CONTAINER = "c"
        const val DEMOLISH = "d"
    }
}
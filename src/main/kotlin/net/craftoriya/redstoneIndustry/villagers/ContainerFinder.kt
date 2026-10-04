package net.craftoriya.redstoneIndustry.villagers

import net.craftoriya.adaptersLib.model.BlockContainer
import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.port.IWorldQueryPort
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.redstoneIndustry.villagers.binding.BlockKinds
import net.craftoriya.redstoneIndustry.villagers.binding.ContainerMode
import net.craftoriya.redstoneIndustry.villagers.link.ContainerRef
import kotlin.math.abs
import kotlin.math.max

class ContainerFinder(
    private val world: IWorldQueryPort,
    private val store: BindingStore,
    private val kinds: BlockKinds,
    val range: Int
) {
    /** Cube distance. */
    fun inRange(a: LocationIContainer, b: LocationIContainer): Boolean {
        if (a.world != b.world) return false
        val p = a.vec3I
        val q = b.vec3I
        return max(abs(p.x - q.x), max(abs(p.y - q.y), abs(p.z - q.z))) <= range
    }

    fun alive(at: LocationIContainer): Boolean {
        val b = world.blockAt(at) ?: return true
        return b.material in kinds.containers
    }

    fun findContainers(center: LocationIContainer): List<ContainerRef> {
        val station = store.station(center)
        if (station != null && station.bound.isNotEmpty()) {
            val refs = ArrayList<ContainerRef>()
            val alive = ArrayList<LocationIContainer>()
            for (loc in station.bound) {
                val b = world.blockAt(loc)
                if (b == null) { alive += loc; continue }
                if (b.material !in kinds.containers) continue
                alive += loc
                if (inRange(center, loc)) refs += ref(b)
            }
            if (alive.size != station.bound.size) store.setStation(center, station.copy(bound = alive))
            if (alive.isNotEmpty()) return refs
        }

        val seen = HashSet<LocationIContainer>()
        val out = ArrayList<ContainerRef>()
        for (b in world.findBlocks(center, range, kinds.containers)) {
            if (seen.add(b.anchor)) out += ref(b)
        }
        return out
    }

    fun findWorkstations(center: LocationIContainer): List<LocationIContainer> =
        world.findBlocks(center, range, kinds.workstations).map { it.location }

    private fun ref(b: BlockContainer): ContainerRef {
        val n = b.anchor
        val v = n.vec3I
        val below = world.blockAt(LocationIContainer(n.world, Vec3I(v.x, v.y - 1, v.z)))
        return ContainerRef(n, store.container(n)?.mode ?: ContainerMode.AUTO, below?.material == "HOPPER")
    }
}
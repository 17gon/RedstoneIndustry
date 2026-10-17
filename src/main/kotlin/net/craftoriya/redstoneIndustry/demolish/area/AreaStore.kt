package net.craftoriya.redstoneIndustry.demolish.area

import net.craftoriya.adaptersLib.model.math.LocationIContainer
import net.craftoriya.adaptersLib.model.math.Vec3I
import net.craftoriya.adaptersLib.port.world.IBlockDataPort
import java.util.UUID

class AreaStore(private val data: IBlockDataPort, private val maxVolume: Int) {

    private val copies = HashMap<LocationIContainer, ZoneData>()
    private val sitesOf = HashMap<UUID, MutableList<LocationIContainer>>()

    fun zoneOf(station: LocationIContainer): ZoneData? =
        copies[station] ?: read(station)?.let { adopt(station, it); copies[station] }

    fun latest(id: UUID): ZoneData? = sitesOf[id]?.firstOrNull()?.let { copies[it] }

    fun sites(id: UUID): List<LocationIContainer> = sitesOf[id]?.toList() ?: emptyList()

    fun resolve(station: LocationIContainer): ZoneData? {
        val own = zoneOf(station)
        if (own?.demolish != null) return own
        val best = containing(station).filter { it.demolish != null }.maxWithOrNull(ZoneData.ORDER) ?: return own
        store(station, best)
        return best
    }

    fun join(station: LocationIContainer): ZoneData? {
        if (zoneOf(station) != null) return null
        val best = containing(station).filter { it.demolish != null }.maxWithOrNull(ZoneData.ORDER) ?: return null
        store(station, best)
        return best
    }

    fun set(station: LocationIContainer, kind: AreaKind, a: Vec3I, b: Vec3I): AreaBox {
        val box = AreaBox.of(a, b)
        require(box.volume <= maxVolume) { "Area too big: ${box.volume} > $maxVolume" }
        val base = zoneOf(station)
        val next = base?.copy(
            version = base.version + 1,
            demolish = if (kind == AreaKind.DEMOLISH) box else base.demolish,
            safe = if (kind == AreaKind.SAFE) base.safe + box else base.safe
        )
            ?: ZoneData(
                UUID.randomUUID(), 1,
                if (kind == AreaKind.DEMOLISH) box else null,
                if (kind == AreaKind.SAFE) listOf(box) else emptyList()
            )
        publish(station, next)
        return box
    }

    fun clear(station: LocationIContainer, kind: AreaKind? = null) {
        if (kind == null) {
            forget(station)
            data.remove(station, KEY)
            return
        }
        val base = zoneOf(station) ?: return
        publish(
            station, base.copy(
                version = base.version + 1,
                demolish = if (kind == AreaKind.DEMOLISH) null else base.demolish,
                safe = if (kind == AreaKind.SAFE) emptyList() else base.safe
            )
        )
    }

    private fun containing(station: LocationIContainer): List<ZoneData> {
        val here = station.vec3I
        return sitesOf.values.mapNotNull { sites ->
            val first = sites.firstOrNull()?.takeIf { it.world == station.world } ?: return@mapNotNull null
            copies[first]
        }.filter { it.isSafe(here) }
    }

    private fun publish(station: LocationIContainer, zone: ZoneData) {
        store(station, zone)
        for (site in sites(zone.id)) if (site != station) store(site, zone)
    }

    private fun adopt(station: LocationIContainer, zone: ZoneData) {
        val known = latest(zone.id)
        if (known != null && known.version > zone.version) store(station, known) else remember(station, zone)
    }

    private fun store(station: LocationIContainer, zone: ZoneData) {
        remember(station, zone)
        val parts = ArrayList<String>()
        zone.demolish?.let { parts += "D:${enc(it)}" }
        zone.safe.forEach { parts += "S:${enc(it)}" }
        data.set(station, KEY, "${zone.id}|${zone.version}|${parts.joinToString(";")}")
    }

    private fun remember(station: LocationIContainer, zone: ZoneData) {
        val old = copies[station]
        if (old != null && old.id != zone.id) unlist(station, old.id)
        copies[station] = zone
        val list = sitesOf.getOrPut(zone.id) { ArrayList() }
        if (station !in list) list += station
    }

    private fun forget(station: LocationIContainer) {
        val old = copies.remove(station) ?: return
        unlist(station, old.id)
    }

    private fun unlist(station: LocationIContainer, id: UUID) {
        val list = sitesOf[id] ?: return
        list.remove(station)
        if (list.isEmpty()) sitesOf.remove(id)
    }

    private fun read(station: LocationIContainer): ZoneData? = runCatching {
        val raw = data.get(station, KEY) ?: return null
        val (id, version, body) = raw.split("|", limit = 3)
        var demolish: AreaBox? = null
        val safe = ArrayList<AreaBox>()
        for (part in body.split(";").filter { it.isNotBlank() }) {
            val (k, v) = part.split(":", limit = 2)
            val n = v.split(",").map { it.toInt() }
            val box = AreaBox(Vec3I(n[0], n[1], n[2]), Vec3I(n[3], n[4], n[5]))
            if (k == "D") demolish = box else safe += box
        }
        ZoneData(UUID.fromString(id), version.toLong(), demolish, safe)
    }.getOrNull()

    private fun enc(box: AreaBox) = "${box.min.x},${box.min.y},${box.min.z},${box.max.x},${box.max.y},${box.max.z}"

    private companion object {
        const val KEY = "z"
    }
}
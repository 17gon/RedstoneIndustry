package net.craftoriya.redstoneIndustry.demolish

import net.craftoriya.adaptersLib.model.LocationIContainer
import net.craftoriya.adaptersLib.model.Vec3I
import net.craftoriya.adaptersLib.port.IBlockDataPort
import java.util.UUID

class AreaStore(private val data: IBlockDataPort, private val maxVolume: Int) {

    private val copies = HashMap<LocationIContainer, ZoneData>()

    fun zoneOf(station: LocationIContainer): ZoneData? {
        val own = copies[station] ?: read(station)?.also { copies[station] = it } ?: return null
        val newest = latest(own.id)
        if (newest != null && newest.version > own.version) {
            store(station, newest)
            return newest
        }
        return own
    }

    fun latest(id: UUID): ZoneData? = copies.values.filter { it.id == id }.maxByOrNull { it.version }

    fun sites(id: UUID): List<LocationIContainer> = copies.filterValues { it.id == id }.keys.toList()

    fun resolve(station: LocationIContainer): ZoneData? {
        val own = zoneOf(station)
        val best = (listOfNotNull(own) + containing(station)).maxWithOrNull(ZoneData.ORDER)
        if (best != null && best.id != own?.id) store(station, best)
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
            copies.remove(station)
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
        return copies.filterKeys { it.world == station.world }.values
            .groupBy { it.id }.values
            .mapNotNull { g -> g.maxByOrNull { it.version } }
            .filter { it.isSafe(here) }
    }

    private fun publish(station: LocationIContainer, zone: ZoneData) {
        store(station, zone)
        for (sites in sites(zone.id)) store(sites, zone)
    }

    private fun store(station: LocationIContainer, zone: ZoneData) {
        copies[station] = zone
        val parts = ArrayList<String>()
        zone.demolish?.let { parts += "D:${enc(it)}" }
        zone.safe.forEach { parts += "S:${enc(it)}" }
        data.set(station, KEY, "${zone.id}|${zone.version}|${parts.joinToString(";")}")
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
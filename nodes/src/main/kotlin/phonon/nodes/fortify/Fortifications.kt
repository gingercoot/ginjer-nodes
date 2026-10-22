package phonon.nodes.fortify

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import phonon.nodes.war.Raid
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumMap
import java.util.UUID

data class FortLevel(
    val captureMultiplier: Double,
    val cost: Map<Material, Int>,
    val upkeep: Map<Material, Int>,
)

data class Fort(
    val territoryId: Int,
    val owner: UUID,
    var level: Int,
)

class FortTreasuryHolder(val town: Town) : InventoryHolder {
    private val inv: Inventory = Bukkit.createInventory(this, 27, "Fortification Treasury")

    override fun getInventory(): Inventory = inv
}

object Fortifications {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/fortifications.json")

    val forts: HashMap<Int, Fort> = hashMapOf()
    val treasury: HashMap<UUID, EnumMap<Material, Int>> = hashMapOf()

    val defaultLevels: List<FortLevel> = listOf(
        FortLevel(1.25, mapOf(Material.IRON_BLOCK to 8), mapOf(Material.IRON_INGOT to 4)),
        FortLevel(1.5, mapOf(Material.GOLD_BLOCK to 8), mapOf(Material.GOLD_INGOT to 4)),
        FortLevel(2.0, mapOf(Material.DIAMOND_BLOCK to 4), mapOf(Material.DIAMOND to 2)),
    )

    fun maxLevel(): Int = Config.fortifyLevels.size

    fun level(territory: Territory): Int {
        val fort = forts[territory.id.toInt()] ?: return 0
        val town = territory.town
        if (town == null || town.uuid != fort.owner || territory.occupier != null) {
            forts.remove(fort.territoryId)
            save()
            return 0
        }
        return fort.level.coerceIn(0, maxLevel())
    }

    fun captureMultiplier(territory: Territory): Double {
        if (!Config.fortifyEnabled) return 1.0
        val lvl = level(territory)
        if (lvl <= 0) return 1.0
        return Config.fortifyLevels[lvl - 1].captureMultiplier.coerceAtLeast(1.0)
    }

    fun nextLevel(territory: Territory): FortLevel? = Config.fortifyLevels.getOrNull(level(territory))

    fun upgrade(town: Town, territory: Territory): String? {
        if (!Config.fortifyEnabled) return "Fortifications are disabled"
        if (territory.town !== town) return "Your town does not own this territory"
        if (territory.occupier != null) return "Cannot fortify an occupied territory"
        if (Raid.isPrepOrLive(territory)) return "Cannot fortify during a raid"
        if (territory.chunks.any { Nodes.getTerritoryChunkFromCoord(it)?.attacker != null }) {
            return "Cannot fortify while the territory is under attack"
        }

        val current = level(territory)
        val next = Config.fortifyLevels.getOrNull(current) ?: return "Already at max fortification (level $current)"
        if (!hasItems(town, next.cost)) {
            return "Not enough in the fortification treasury (/t fortify deposit). " +
                "Level ${current + 1} costs ${formatItems(next.cost)}"
        }
        takeItems(town, next.cost)

        forts[territory.id.toInt()] = Fort(territory.id.toInt(), town.uuid, current + 1)
        save()
        return null
    }

    fun townForts(town: Town): List<Pair<Territory, Int>> = forts.values.toList()
        .filter { it.owner == town.uuid }
        .mapNotNull { fort ->
            val territory = Nodes.getTerritoryFromId(TerritoryId(fort.territoryId)) ?: return@mapNotNull null
            val lvl = level(territory)
            if (lvl > 0) territory to lvl else null
        }
        .sortedBy { it.first.id.toInt() }

    fun chargeUpkeep(town: Town) {
        if (!Config.fortifyEnabled) return
        val dropped = ArrayList<String>()
        for ((territory, lvl) in townForts(town).sortedByDescending { it.second }) {
            val upkeep = Config.fortifyLevels[lvl - 1].upkeep
            if (hasItems(town, upkeep)) {
                takeItems(town, upkeep)
                continue
            }
            val fort = forts[territory.id.toInt()] ?: continue
            fort.level = lvl - 1
            if (fort.level <= 0) forts.remove(fort.territoryId)
            dropped.add("${territory.id.toInt()} (now level ${lvl - 1})")
        }
        if (dropped.isNotEmpty()) {
            save()
            val msg = "${ChatColor.RED}[Fortify] Couldn't pay upkeep, fortifications dropped: ${ChatColor.WHITE}${dropped.joinToString(", ")}"
            for (player in town.playersOnline) {
                Message.print(player, msg)
            }
        }
    }

    fun treasuryOf(town: Town): Map<Material, Int> = treasury[town.uuid] ?: emptyMap()

    fun acceptedMaterials(): Set<Material> = Config.fortifyLevels.flatMap { it.cost.keys + it.upkeep.keys }.toSet()

    fun hasItems(town: Town, items: Map<Material, Int>): Boolean {
        val stored = treasuryOf(town)
        return items.all { (mat, amount) -> (stored[mat] ?: 0) >= amount }
    }

    private fun takeItems(town: Town, items: Map<Material, Int>) {
        val stored = treasury[town.uuid] ?: return
        for ((mat, amount) in items) {
            val left = (stored[mat] ?: 0) - amount
            if (left > 0) stored[mat] = left else stored.remove(mat)
        }
        save()
    }

    fun openTreasury(player: Player, town: Town) {
        player.openInventory(FortTreasuryHolder(town).inventory)
    }

    fun depositFrom(player: Player, holder: FortTreasuryHolder) {
        val accepted = acceptedMaterials()
        val stored = treasury.getOrPut(holder.town.uuid) { EnumMap(Material::class.java) }
        val deposited = EnumMap<Material, Int>(Material::class.java)
        val rejected = ArrayList<ItemStack>()

        for (item in holder.inventory.contents) {
            if (item == null || item.type.isAir) continue
            if (item.type in accepted && item.isSimilar(ItemStack(item.type))) {
                stored[item.type] = (stored[item.type] ?: 0) + item.amount
                deposited[item.type] = (deposited[item.type] ?: 0) + item.amount
            } else {
                rejected.add(item)
            }
        }
        holder.inventory.clear()

        for (item in rejected) {
            for (rest in player.inventory.addItem(item).values) {
                player.world.dropItemNaturally(player.location, rest)
            }
        }

        if (deposited.isNotEmpty()) {
            save()
            Message.print(player, "${ChatColor.GREEN}[Fortify] Deposited ${formatItems(deposited)} into ${holder.town.name}'s treasury")
        }
        if (rejected.isNotEmpty()) {
            Message.error(player, "[Fortify] Returned items the treasury doesn't use (only plain ${accepted.joinToString(", ") { it.name }})")
        }
    }

    fun flushOpenTreasuries() {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = player.openInventory.topInventory.holder as? FortTreasuryHolder ?: continue
            depositFrom(player, holder)
            player.closeInventory()
        }
    }

    fun formatItems(items: Map<Material, Int>): String = if (items.isEmpty()) "nothing" else items.entries.joinToString(", ") { "${it.value}x ${it.key.name}" }

    fun load() {
        forts.clear()
        treasury.clear()
        if (!Files.exists(path)) return
        try {
            val root = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            root.getAsJsonArray("forts")?.forEach { el ->
                val o = el.asJsonObject
                val fort = Fort(o.get("territory").asInt, UUID.fromString(o.get("owner").asString), o.get("level").asInt)
                if (fort.level > 0) forts[fort.territoryId] = fort
            }
            root.getAsJsonObject("treasury")?.entrySet()?.forEach { (townId, items) ->
                val map = EnumMap<Material, Int>(Material::class.java)
                for ((matName, amount) in items.asJsonObject.entrySet()) {
                    val mat = Material.matchMaterial(matName) ?: continue
                    map[mat] = amount.asInt
                }
                treasury[UUID.fromString(townId)] = map
            }
            Nodes.logger?.info("[Fortify] Loaded ${forts.size} fortification(s)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Fortify] load failed: ${e.message}")
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = JsonArray()
            for (fort in forts.values) {
                val o = JsonObject()
                o.addProperty("territory", fort.territoryId)
                o.addProperty("owner", fort.owner.toString())
                o.addProperty("level", fort.level)
                arr.add(o)
            }
            root.add("forts", arr)
            val treasuryJson = JsonObject()
            for ((townId, items) in treasury) {
                if (items.isEmpty()) continue
                val o = JsonObject()
                for ((mat, amount) in items) o.addProperty(mat.name, amount)
                treasuryJson.add(townId.toString(), o)
            }
            root.add("treasury", treasuryJson)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Fortify] save failed: ${e.message}")
        }
    }
}

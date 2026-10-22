package phonon.nodes.vassal

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.ChatColor
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningTowers
import phonon.nodes.objects.Town
import phonon.nodes.util.DiscordRelay
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

data class VassalLink(
    val vassal: UUID,
    val overlord: UUID,
    val since: Long,
)

data class VassalOffer(
    val overlord: UUID,
    val target: UUID,
    val expiresAt: Long,
)

object Vassals {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/vassals.json")

    val links: LinkedHashMap<UUID, VassalLink> = linkedMapOf()
    private val offers: MutableList<VassalOffer> = mutableListOf()

    fun townByUuid(uuid: UUID): Town? = Nodes.towns.values.firstOrNull { it.uuid == uuid }

    fun overlordOf(town: Town): Town? {
        val link = links[town.uuid] ?: return null
        val overlord = townByUuid(link.overlord)
        if (overlord == null) {
            links.remove(town.uuid)
            save()
        }
        return overlord
    }

    fun vassalsOf(town: Town): List<Town> = links.values
        .filter { it.overlord == town.uuid }
        .mapNotNull { townByUuid(it.vassal) }

    fun isVassal(town: Town): Boolean = overlordOf(town) != null

    fun tributeRate(town: Town): Double {
        if (!Config.vassalsEnabled || !isVassal(town)) return 0.0
        return Config.vassalTributeRate.coerceIn(0.0, 1.0)
    }

    fun revoltReadyAt(town: Town): Long = (links[town.uuid]?.since ?: 0L) + Config.vassalRevoltCooldownMs

    fun isBound(a: Town, b: Town): Boolean = boundTo(a, b) || boundTo(b, a)

    private fun boundTo(vassal: Town, other: Town): Boolean {
        val overlord = overlordOf(vassal) ?: return false
        if (overlord === other) return true
        val nation = overlord.nation
        return nation != null && other.nation === nation
    }

    private fun validate(overlord: Town, target: Town): String? {
        if (!Config.vassalsEnabled) return "Vassals are disabled"
        if (overlord === target) return "You cannot vassalize your own town"
        if (isVassal(overlord)) return "A vassal cannot take vassals"
        if (isVassal(target)) return "${target.name} is already a vassal"
        if (vassalsOf(target).isNotEmpty()) return "${target.name} has its own vassals"
        if (target.nation != null) return "${target.name} must leave its nation first"
        return null
    }

    fun offer(overlord: Town, target: Town): String? {
        validate(overlord, target)?.let { return it }
        val now = System.currentTimeMillis()
        offers.removeIf { it.expiresAt < now || (it.overlord == overlord.uuid && it.target == target.uuid) }
        offers.add(VassalOffer(overlord.uuid, target.uuid, now + Config.vassalOfferTimeoutMs))

        val timeout = MiningTowers.formatDuration(Config.vassalOfferTimeoutMs / 1000)
        for (player in target.playersOnline) {
            Message.print(
                player,
                "${ChatColor.GOLD}[Vassal] ${overlord.name} offers to make your town their vassal " +
                    "(tribute ${percent(Config.vassalTributeRate)} of income). " +
                    "Leader: /vassal accept ${overlord.name} (expires in $timeout)",
            )
        }
        return null
    }

    fun pendingOffersFor(target: Town): List<Town> {
        val now = System.currentTimeMillis()
        offers.removeIf { it.expiresAt < now }
        return offers.filter { it.target == target.uuid }.mapNotNull { townByUuid(it.overlord) }
    }

    fun accept(target: Town, overlord: Town): String? {
        val now = System.currentTimeMillis()
        offers.removeIf { it.expiresAt < now }
        val offer = offers.firstOrNull { it.overlord == overlord.uuid && it.target == target.uuid }
            ?: return "No vassal offer from ${overlord.name}"
        validate(overlord, target)?.let { return it }
        offers.remove(offer)

        if (target.enemies.contains(overlord) || overlord.enemies.contains(target)) {
            Nodes.removeEnemy(overlord, target)
        }
        if (!(target.allies.contains(overlord) && overlord.allies.contains(target))) {
            val result = Nodes.addAlly(overlord, target)
            if (result.isFailure) {
                return "Could not form the alliance: ${result.exceptionOrNull()?.message}"
            }
        }

        links[target.uuid] = VassalLink(target.uuid, overlord.uuid, now)
        save()

        Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Vassal] ${target.name} is now a vassal of ${overlord.name}!")
        DiscordRelay.war("**[Vassal]** ${target.name} is now a vassal of ${overlord.name}")
        return null
    }

    fun revolt(vassal: Town): String? {
        val overlord = overlordOf(vassal) ?: return "Your town is not a vassal"
        val now = System.currentTimeMillis()
        val readyAt = revoltReadyAt(vassal)
        if (now < readyAt) {
            return "You can revolt in ${MiningTowers.formatDuration((readyAt - now) / 1000)}"
        }

        links.remove(vassal.uuid)
        save()
        Nodes.removeAlly(vassal, overlord)
        val war = Nodes.addEnemy(vassal, overlord)

        Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}[Vassal] ${vassal.name} has revolted against ${overlord.name}!")
        DiscordRelay.war("**[Vassal]** ${vassal.name} has revolted against ${overlord.name}!")
        if (war.isFailure) {
            Nodes.logger?.warning("[Vassal] revolt war failed: ${war.exceptionOrNull()?.message}")
        }
        return null
    }

    fun release(vassal: Town): String? {
        val overlord = overlordOf(vassal) ?: return "${vassal.name} is not a vassal"
        links.remove(vassal.uuid)
        save()
        Nodes.removeAlly(vassal, overlord)

        Message.broadcast("${ChatColor.GOLD}[Vassal] ${overlord.name} released ${vassal.name} from vassalage")
        DiscordRelay.war("**[Vassal]** ${overlord.name} released ${vassal.name}")
        return null
    }

    fun percent(rate: Double): String = "${Math.round(rate.coerceIn(0.0, 1.0) * 100)}%"

    fun load() {
        links.clear()
        offers.clear()
        if (!Files.exists(path)) return
        try {
            val root = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            root.getAsJsonArray("links")?.forEach { el ->
                val o = el.asJsonObject
                val link = VassalLink(
                    UUID.fromString(o.get("vassal").asString),
                    UUID.fromString(o.get("overlord").asString),
                    o.get("since").asLong,
                )
                if (townByUuid(link.vassal) != null && townByUuid(link.overlord) != null) {
                    links[link.vassal] = link
                }
            }
            Nodes.logger?.info("[Vassals] Loaded ${links.size} vassal(s)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Vassals] load failed: ${e.message}")
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = JsonArray()
            for (link in links.values) {
                val o = JsonObject()
                o.addProperty("vassal", link.vassal.toString())
                o.addProperty("overlord", link.overlord.toString())
                o.addProperty("since", link.since)
                arr.add(o)
            }
            root.add("links", arr)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Vassals] save failed: ${e.message}")
        }
    }
}

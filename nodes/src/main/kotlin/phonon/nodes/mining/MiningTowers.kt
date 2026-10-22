/**
 * Mining towers: furnace blocks that grant fixed loot on break, then restore,
 * with per-player cooldown per tower.
 */

package phonon.nodes.mining

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TowerReward(
    val material: Material,
    val amount: Int,
)

data class MiningTower(
    val id: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val cooldownSeconds: Long,
    val rewards: List<TowerReward>,
    /** player uuid -> epoch ms when they can mine again */
    val playerCooldownUntil: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap(),
) {
    fun location(): Location? {
        val w = Bukkit.getWorld(world) ?: return null
        return Location(w, x.toDouble(), y.toDouble(), z.toDouble())
    }

    fun matches(block: Block): Boolean = block.world.name == world && block.x == x && block.y == y && block.z == z

    fun ensureFurnace() {
        val loc = location() ?: return
        val b = loc.block
        if (b.type != Material.FURNACE && b.type != Material.BLAST_FURNACE && b.type != Material.SMOKER) {
            b.type = Material.FURNACE
        }
    }
}

object MiningTowers {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    val path: Path = Paths.get("plugins/nodes/mining-towers.json")

    val towers: ConcurrentHashMap<String, MiningTower> = ConcurrentHashMap()

    fun getByBlock(block: Block): MiningTower? = towers.values.firstOrNull { it.matches(block) }

    fun isTowerBlock(block: Block): Boolean = getByBlock(block) != null

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 && s > 0 -> "${m}m ${s}s"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    fun create(
        id: String,
        block: Block,
        cooldownSeconds: Long,
        rewards: List<TowerReward>,
    ): Result<MiningTower> {
        if (towers.containsKey(id)) {
            return Result.failure(Exception("Tower id \"$id\" already exists"))
        }
        if (rewards.isEmpty()) {
            return Result.failure(Exception("Need at least one reward"))
        }
        block.type = Material.FURNACE
        val tower = MiningTower(
            id = id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            cooldownSeconds = cooldownSeconds,
            rewards = rewards,
        )
        towers[id] = tower
        save()
        return Result.success(tower)
    }

    fun remove(id: String): Boolean {
        val t = towers.remove(id) ?: return false
        t.location()?.block?.type = Material.AIR
        save()
        return true
    }

    /**
     * Handle player breaking a tower furnace.
     * @return true if this was a tower and event should be cancelled (furnace restored)
     */
    fun tryMine(player: Player, block: Block): Boolean {
        val tower = getByBlock(block) ?: return false

        val now = System.currentTimeMillis()
        val until = tower.playerCooldownUntil[player.uniqueId] ?: 0L
        if (now < until) {
            val leftSec = ((until - now) / 1000).coerceAtLeast(1)
            Message.error(
                player,
                "${ChatColor.RED}Mining tower on cooldown: ${formatDuration(leftSec)} left",
            )
            Bukkit.getScheduler().runTask(Nodes.plugin!!, Runnable { tower.ensureFurnace() })
            return true
        }

        for (r in tower.rewards) {
            val stack = ItemStack(r.material, r.amount)
            val leftover = player.inventory.addItem(stack)
            for (item in leftover.values) {
                player.world.dropItemNaturally(player.location, item)
            }
        }

        tower.playerCooldownUntil[player.uniqueId] = now + tower.cooldownSeconds * 1000L
        save()

        Message.print(
            player,
            "${ChatColor.GREEN}Mining tower \"${tower.id}\": loot received. Cooldown ${formatDuration(tower.cooldownSeconds)}",
        )

        Bukkit.getScheduler().runTask(Nodes.plugin!!, Runnable { tower.ensureFurnace() })
        return true
    }

    fun load() {
        towers.clear()
        if (!Files.exists(path)) return
        try {
            val json = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            val arr = json.getAsJsonArray("towers") ?: return
            for (el in arr) {
                val o = el.asJsonObject
                val id = o.get("id").asString
                val rewards = ArrayList<TowerReward>()
                o.getAsJsonArray("rewards")?.forEach { re ->
                    val r = re.asJsonObject
                    val mat = Material.matchMaterial(r.get("material").asString) ?: return@forEach
                    rewards.add(TowerReward(mat, r.get("amount").asInt))
                }
                val tower = MiningTower(
                    id = id,
                    world = o.get("world").asString,
                    x = o.get("x").asInt,
                    y = o.get("y").asInt,
                    z = o.get("z").asInt,
                    cooldownSeconds = o.get("cooldownSeconds").asLong,
                    rewards = rewards,
                )
                o.getAsJsonObject("playerCooldownUntil")?.entrySet()?.forEach { (k, v) ->
                    tower.playerCooldownUntil[UUID.fromString(k)] = v.asLong
                }
                towers[id] = tower
                tower.ensureFurnace()
            }
            Nodes.logger?.info("[MiningTowers] Loaded ${towers.size} tower(s)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[MiningTowers] load failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = JsonArray()
            for (t in towers.values) {
                val o = JsonObject()
                o.addProperty("id", t.id)
                o.addProperty("world", t.world)
                o.addProperty("x", t.x)
                o.addProperty("y", t.y)
                o.addProperty("z", t.z)
                o.addProperty("cooldownSeconds", t.cooldownSeconds)
                val rew = JsonArray()
                for (r in t.rewards) {
                    val ro = JsonObject()
                    ro.addProperty("material", r.material.name)
                    ro.addProperty("amount", r.amount)
                    rew.add(ro)
                }
                o.add("rewards", rew)
                val cds = JsonObject()
                t.playerCooldownUntil.forEach { (k, v) -> cds.addProperty(k.toString(), v) }
                o.add("playerCooldownUntil", cds)
                arr.add(o)
            }
            root.add("towers", arr)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[MiningTowers] save failed: ${e.message}")
        }
    }

    fun shutdown() {
        save()
    }
}

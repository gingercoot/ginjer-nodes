package phonon.nodes.supply

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningTowers
import phonon.nodes.refinery.Refineries
import phonon.nodes.util.DiscordRelay
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

data class DropPoint(
    val id: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    fun block(): Block? = Bukkit.getWorld(world)?.getBlockAt(x, y, z)

    fun matches(block: Block): Boolean = block.world.name == world && block.x == x && block.y == y && block.z == z

    fun coords(): String = "$x, $y, $z"
}

data class DropLoot(
    val id: String,
    val weight: Int,
    val itemBase64: String,
) {
    fun item(): ItemStack? = Refineries.itemFromBase64(itemBase64)
}

class ActiveDrop(
    val point: DropPoint,
    val landsAt: Long,
    val expiresAt: Long,
) {
    var landed: Boolean = false
    var originalBlock: BlockData? = null
    var display: BlockDisplay? = null
}

object SupplyDrops {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/supplydrops.json")

    val points: LinkedHashMap<String, DropPoint> = linkedMapOf()
    val loot: LinkedHashMap<String, DropLoot> = linkedMapOf()

    var nextDropAt: Long = 0L
        private set
    var active: ActiveDrop? = null
        private set

    private val claims: HashMap<UUID, Long> = hashMapOf()

    private var bossBar: BossBar? = null
    private var tickTask: BukkitTask? = null

    fun load() {
        points.clear()
        loot.clear()
        nextDropAt = 0L
        if (Files.exists(path)) {
            try {
                val root = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
                root.getAsJsonArray("points")?.forEach { el ->
                    val o = el.asJsonObject
                    val p = DropPoint(
                        o.get("id").asString,
                        o.get("world").asString,
                        o.get("x").asInt,
                        o.get("y").asInt,
                        o.get("z").asInt,
                    )
                    points[p.id] = p
                }
                root.getAsJsonArray("loot")?.forEach { el ->
                    val o = el.asJsonObject
                    val l = DropLoot(o.get("id").asString, o.get("weight").asInt, o.get("item").asString)
                    loot[l.id] = l
                }
                nextDropAt = root.get("nextDropAt")?.asLong ?: 0L
            } catch (e: Exception) {
                Nodes.logger?.severe("[SupplyDrops] load failed: ${e.message}")
            }
        }
        if (nextDropAt <= 0L) {
            scheduleNext(System.currentTimeMillis())
        }
        startTask()
        Nodes.logger?.info("[SupplyDrops] Loaded ${points.size} point(s), ${loot.size} loot entries")
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        clearActive()
        save()
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val pointsJson = JsonArray()
            for (p in points.values) {
                val o = JsonObject()
                o.addProperty("id", p.id)
                o.addProperty("world", p.world)
                o.addProperty("x", p.x)
                o.addProperty("y", p.y)
                o.addProperty("z", p.z)
                pointsJson.add(o)
            }
            val lootJson = JsonArray()
            for (l in loot.values) {
                val o = JsonObject()
                o.addProperty("id", l.id)
                o.addProperty("weight", l.weight)
                o.addProperty("item", l.itemBase64)
                lootJson.add(o)
            }
            root.add("points", pointsJson)
            root.add("loot", lootJson)
            root.addProperty("nextDropAt", nextDropAt)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[SupplyDrops] save failed: ${e.message}")
        }
    }

    fun addPoint(id: String, block: Block): String? {
        if (points.containsKey(id)) return "Point \"$id\" already exists"
        if (MiningTowers.isTowerBlock(block) || Refineries.getByBlock(block) != null) {
            return "That block is already a mining tower or refinery"
        }
        points[id] = DropPoint(id, block.world.name, block.x, block.y, block.z)
        save()
        return null
    }

    fun removePoint(id: String): Boolean {
        if (active?.point?.id == id) clearActive()
        val removed = points.remove(id) != null
        if (removed) save()
        return removed
    }

    fun addLoot(id: String, weight: Int, item: ItemStack): String? {
        if (loot.containsKey(id)) return "Loot \"$id\" already exists"
        if (weight < 1) return "Weight must be at least 1"
        val b64 = Refineries.itemToBase64(item) ?: return "Could not save that item"
        loot[id] = DropLoot(id, weight, b64)
        save()
        return null
    }

    fun removeLoot(id: String): Boolean {
        val removed = loot.remove(id) != null
        if (removed) save()
        return removed
    }

    fun forceSpawn(pointId: String?): String? {
        if (active != null) return "A supply drop is already active"
        if (loot.isEmpty()) return "No loot set (/supplydrop loot add)"
        val point = if (pointId != null) {
            points[pointId] ?: return "Unknown point \"$pointId\""
        } else {
            randomPoint() ?: return "No drop points set (/supplydrop addpoint)"
        }
        start(point, System.currentTimeMillis())
        return null
    }

    fun cancelActive(): Boolean {
        if (active == null) return false
        clearActive()
        Message.broadcast("${ChatColor.GOLD}[Supply Drop] The supply drop was called off")
        return true
    }

    fun isDropBlock(block: Block): Boolean {
        val drop = active ?: return false
        return drop.landed && drop.point.matches(block)
    }

    fun tryStartClaim(player: Player) {
        val drop = active ?: return
        if (!drop.landed) return
        if (claims.containsKey(player.uniqueId)) {
            Message.announcement(player, "${ChatColor.YELLOW}Already claiming...")
            return
        }
        claims[player.uniqueId] = System.currentTimeMillis()
        Message.print(
            player,
            "${ChatColor.GOLD}[Supply Drop] Claiming... stay inside the ring for ${Config.supplyDropsChannelMs / 1000}s",
        )
        val claimerTown = Nodes.getResident(player)?.town?.name
        val who = if (claimerTown != null) "${player.name} ($claimerTown)" else player.name
        for (other in Bukkit.getOnlinePlayers()) {
            if (other !== player &&
                other.world.name == drop.point.world &&
                other.location.distanceSquared(player.location) < 64.0 * 64.0
            ) {
                Message.announcement(other, "${ChatColor.RED}$who is claiming the supply drop!")
            }
        }
    }

    fun interruptClaim(player: Player, reason: String) {
        if (claims.remove(player.uniqueId) != null) {
            Message.error(player, "[Supply Drop] Claim interrupted: $reason")
        }
    }

    fun dropClaim(uuid: UUID) {
        claims.remove(uuid)
    }

    private fun startTask() {
        if (tickTask != null) return
        val plugin = Nodes.plugin ?: return
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 5L, 5L)
    }

    private fun scheduleNext(from: Long) {
        val jitter = Config.supplyDropsJitterMs
        val offset = if (jitter > 0) ThreadLocalRandom.current().nextLong(-jitter, jitter + 1) else 0L
        nextDropAt = from + (Config.supplyDropsIntervalMs + offset).coerceAtLeast(60_000L)
        save()
    }

    private fun randomPoint(): DropPoint? {
        val usable = points.values.filter { Bukkit.getWorld(it.world) != null }
        if (usable.isEmpty()) return null
        return usable[ThreadLocalRandom.current().nextInt(usable.size)]
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val drop = active

        if (drop == null) {
            if (!Config.supplyDropsEnabled || now < nextDropAt) return
            val point = randomPoint()
            if (point == null ||
                loot.isEmpty() ||
                Bukkit.getOnlinePlayers().size < Config.supplyDropsMinPlayersOnline
            ) {
                scheduleNext(now)
                return
            }
            start(point, now)
            return
        }

        if (!drop.landed && now >= drop.landsAt) {
            land(drop)
        }
        if (drop.landed && now >= drop.expiresAt) {
            Message.broadcast("${ChatColor.GRAY}[Supply Drop] Nobody claimed the supply drop at ${drop.point.coords()}")
            clearActive()
            return
        }

        if (!drop.landed) updateFalling(drop, now)
        showParticles(drop)
        updateBossBar(drop, now)
        if (drop.landed) tickClaims(drop, now)
    }

    private fun start(point: DropPoint, now: Long) {
        val landsAt = now + Config.supplyDropsWarningMs
        active = ActiveDrop(point, landsAt, landsAt + Config.supplyDropsDespawnMs)
        scheduleNext(now)

        val wait = MiningTowers.formatDuration(Config.supplyDropsWarningMs / 1000)
        Message.broadcast(
            "${ChatColor.GOLD}${ChatColor.BOLD}[Supply Drop] ${ChatColor.YELLOW}Incoming at " +
                "${ChatColor.WHITE}${point.coords()}${ChatColor.YELLOW} in $wait!",
        )
        if (Config.supplyDropsDiscord) {
            DiscordRelay.war("**[Supply Drop]** Incoming at (${point.coords()}) in $wait")
        }
        if (Config.supplyDropsWarningMs <= 0L) {
            land(active!!)
        }
    }

    private fun land(drop: ActiveDrop) {
        val block = drop.point.block() ?: run {
            clearActive()
            return
        }
        drop.display?.remove()
        drop.display = null
        drop.originalBlock = block.blockData.clone()
        block.type = Material.CHEST
        drop.landed = true

        val loc = block.location.add(0.5, 1.0, 0.5)
        block.world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f)
        block.world.spawnParticle(Particle.EXPLOSION, loc, 3, 0.5, 0.5, 0.5)

        Message.broadcast(
            "${ChatColor.GOLD}${ChatColor.BOLD}[Supply Drop] ${ChatColor.YELLOW}Landed at " +
                "${ChatColor.WHITE}${drop.point.coords()}${ChatColor.YELLOW}! Right-click it to claim",
        )
    }

    private fun tickClaims(drop: ActiveDrop, now: Long) {
        if (claims.isEmpty()) return
        val world = Bukkit.getWorld(drop.point.world) ?: return
        val cx = drop.point.x + 0.5
        val cz = drop.point.z + 0.5
        val radiusSq = Config.supplyDropsChannelRadius * Config.supplyDropsChannelRadius

        for ((uuid, startedAt) in claims.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || player.isDead) {
                claims.remove(uuid)
                continue
            }
            val loc = player.location
            val dx = loc.x - cx
            val dz = loc.z - cz
            if (loc.world != world || dx * dx + dz * dz > radiusSq || kotlin.math.abs(loc.y - drop.point.y) > 3.0) {
                interruptClaim(player, "you left the ring")
                continue
            }
            val elapsed = now - startedAt
            if (elapsed >= Config.supplyDropsChannelMs) {
                complete(drop, player)
                return
            }
            val left = ((Config.supplyDropsChannelMs - elapsed) / 1000).coerceAtLeast(1)
            Message.announcement(player, "${ChatColor.GOLD}Claiming supply drop... ${left}s")
        }
    }

    private fun complete(drop: ActiveDrop, player: Player) {
        val items = rollLoot()
        for (item in items) {
            val leftover = player.inventory.addItem(item)
            for (rest in leftover.values) {
                player.world.dropItemNaturally(player.location, rest)
            }
        }

        val town = Nodes.getResident(player)?.town?.name
        val who = if (town != null) "${player.name} ($town)" else player.name
        Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Supply Drop] ${ChatColor.YELLOW}$who claimed the supply drop!")
        if (Config.supplyDropsDiscord) {
            DiscordRelay.war("**[Supply Drop]** $who claimed the supply drop at (${drop.point.coords()})")
        }
        clearActive()
    }

    private fun rollLoot(): List<ItemStack> {
        val entries = loot.values.toList()
        val total = entries.sumOf { it.weight }
        if (total <= 0) return emptyList()
        val rng = ThreadLocalRandom.current()
        val result = ArrayList<ItemStack>()
        repeat(Config.supplyDropsItemsPerDrop) {
            var roll = rng.nextInt(total)
            for (e in entries) {
                roll -= e.weight
                if (roll < 0) {
                    e.item()?.let { result.add(it) }
                    break
                }
            }
        }
        return result
    }

    private fun clearActive() {
        val drop = active
        active = null
        claims.clear()
        drop?.display?.remove()
        bossBar?.removeAll()
        bossBar = null
        if (drop != null && drop.landed) {
            val block = drop.point.block()
            val original = drop.originalBlock
            if (block != null && block.type == Material.CHEST) {
                if (original != null) block.blockData = original else block.type = Material.AIR
            }
        }
    }

    private fun updateFalling(drop: ActiveDrop, now: Long) {
        val world = Bukkit.getWorld(drop.point.world) ?: return
        if (!world.isChunkLoaded(drop.point.x shr 4, drop.point.z shr 4)) return
        val total = Config.supplyDropsWarningMs.coerceAtLeast(1L).toDouble()
        val fraction = ((drop.landsAt - now) / total).coerceIn(0.0, 1.0)
        val loc = Location(
            world,
            drop.point.x.toDouble(),
            drop.point.y + Config.supplyDropsFallHeight * fraction,
            drop.point.z.toDouble(),
        )
        val display = drop.display
        if (display == null || !display.isValid) {
            val spawned = world.spawn(loc, BlockDisplay::class.java)
            spawned.block = Material.CHEST.createBlockData()
            spawned.isPersistent = false
            spawned.isGlowing = true
            spawned.teleportDuration = 5
            drop.display = spawned
        } else {
            display.teleport(loc)
        }
        world.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, -0.2, 0.5), 3, 0.3, 0.1, 0.3, 0.0)
    }

    private fun showParticles(drop: ActiveDrop) {
        val world = Bukkit.getWorld(drop.point.world) ?: return
        if (!world.isChunkLoaded(drop.point.x shr 4, drop.point.z shr 4)) return

        val r = Config.supplyDropsChannelRadius
        val cx = drop.point.x + 0.5
        val cz = drop.point.z + 0.5
        val y = drop.point.y + 0.1
        val color = if (claims.isEmpty()) org.bukkit.Color.ORANGE else org.bukkit.Color.RED
        val dust = Particle.DustOptions(color, 1.3f)
        val count = (r * 4.0 * Math.PI).toInt().coerceIn(16, 160)
        for (i in 0 until count) {
            val a = 2.0 * Math.PI * i / count
            world.spawnParticle(Particle.DUST, cx + r * cos(a), y, cz + r * sin(a), 1, 0.0, 0.0, 0.0, 0.0, dust)
        }

        if (drop.landed) {
            val base = Location(world, cx, drop.point.y + 1.0, cz)
            for (i in 0 until 30 step 3) {
                world.spawnParticle(Particle.END_ROD, base.clone().add(0.0, i.toDouble(), 0.0), 1, 0.1, 0.5, 0.1, 0.0)
            }
        }
    }

    private fun updateBossBar(drop: ActiveDrop, now: Long) {
        val bar = bossBar ?: Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID).also { bossBar = it }
        if (!drop.landed) {
            val left = ((drop.landsAt - now) / 1000).coerceAtLeast(0)
            bar.setTitle("${ChatColor.GOLD}Supply drop at ${drop.point.coords()} ${ChatColor.WHITE}lands in ${MiningTowers.formatDuration(left)}")
            bar.color = BarColor.YELLOW
            val total = Config.supplyDropsWarningMs.coerceAtLeast(1L)
            bar.progress = ((drop.landsAt - now).toDouble() / total).coerceIn(0.0, 1.0)
        } else {
            val left = ((drop.expiresAt - now) / 1000).coerceAtLeast(0)
            bar.setTitle("${ChatColor.GOLD}Supply drop at ${drop.point.coords()} ${ChatColor.WHITE}unclaimed - ${MiningTowers.formatDuration(left)} left")
            bar.color = BarColor.RED
            val total = Config.supplyDropsDespawnMs.coerceAtLeast(1L)
            bar.progress = ((drop.expiresAt - now).toDouble() / total).coerceIn(0.0, 1.0)
        }
        for (p in Bukkit.getOnlinePlayers()) {
            if (!bar.players.contains(p)) bar.addPlayer(p)
        }
    }

    fun statusLines(): List<String> {
        val now = System.currentTimeMillis()
        val drop = active
        if (drop != null) {
            return if (drop.landed) {
                listOf("Supply drop at ${drop.point.coords()} - unclaimed, ${MiningTowers.formatDuration((drop.expiresAt - now) / 1000)} left")
            } else {
                listOf("Supply drop incoming at ${drop.point.coords()} in ${MiningTowers.formatDuration((drop.landsAt - now) / 1000)}")
            }
        }
        if (!Config.supplyDropsEnabled) return listOf("Supply drops are disabled")
        return listOf("Next supply drop in about ${MiningTowers.formatDuration(((nextDropAt - now) / 1000).coerceAtLeast(0))}")
    }
}

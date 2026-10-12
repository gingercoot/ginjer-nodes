/**
 * Mining boosts for Nodes hidden ore — tier-based.
 *
 * Tier T (T >= 1):
 *   rateMul   = 1.0 + T * 0.1   // 1.1, 1.2, 1.3, …
 *   amountMul = 1.0 + T * 0.5   // 1.5, 2.0, 2.5, …
 *
 * Player vs global: max tier wins (do not stack).
 *
 * Commands:
 *   /nda miningboost global <duration> [tier]     // no tier → +1
 *   /nda miningboost player <name> <duration> [tier]
 *   /nda miningboost clear global | player <name>
 */

package phonon.nodes.mining

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

data class BoostState(
    var tier: Int = 0,
    var endsAt: Long = 0L,
) {
    fun isActive(now: Long = System.currentTimeMillis()): Boolean = tier > 0 && endsAt > now

    fun rateMul(): Double = if (tier <= 0) 1.0 else 1.0 + tier * MiningBoost.RATE_STEP

    fun amountMul(): Double = if (tier <= 0) 1.0 else 1.0 + tier * MiningBoost.AMOUNT_STEP
}

object MiningBoost {
    /** Tier 1 → +0.1 rate each tier (1.1, 1.2, …). */
    const val RATE_STEP = 0.1

    /** Tier 1 → +0.5 amount each tier (1.5, 2.0, …). */
    const val AMOUNT_STEP = 0.5

    /** Max tier of boost */
    const val MAX_TIER = 12

    private val gson = GsonBuilder().setPrettyPrinting().create()
    val path: Path = Paths.get("plugins/nodes/mining-boosts.json")

    var global: BoostState = BoostState()
    val players: ConcurrentHashMap<UUID, BoostState> = ConcurrentHashMap()

    private val bossBars: ConcurrentHashMap<UUID, BossBar> = ConcurrentHashMap()
    private var tickTask: BukkitTask? = null

    fun effective(player: Player): Pair<Double, Double> {
        val tier = effectiveTier(player)
        if (tier <= 0) return 1.0 to 1.0
        return (1.0 + tier * RATE_STEP) to (1.0 + tier * AMOUNT_STEP)
    }

    fun effectiveTier(player: Player): Int {
        val now = System.currentTimeMillis()
        val p = players[player.uniqueId]
        val pTier = if (p != null && p.isActive(now)) p.tier else 0
        val gTier = if (global.isActive(now)) global.tier else 0
        return max(pTier, gTier)
    }

    fun hasAnyBoost(player: Player): Boolean = effectiveTier(player) > 0

    fun applyToDrops(
        player: Player,
        baseDrops: List<org.bukkit.inventory.ItemStack>,
        resample: () -> List<org.bukkit.inventory.ItemStack>,
    ): List<org.bukkit.inventory.ItemStack> {
        val (rateMul, amountMul) = effective(player)
        val random = java.util.concurrent.ThreadLocalRandom.current()
        val out = ArrayList<org.bukkit.inventory.ItemStack>()

        when {
            rateMul <= 0.0 -> { }

            rateMul < 1.0 -> {
                if (baseDrops.isNotEmpty() && random.nextDouble() < rateMul) {
                    out.addAll(baseDrops)
                }
            }

            else -> {
                out.addAll(baseDrops)
                val extraExact = rateMul - 1.0
                val whole = floor(extraExact).toInt()
                val frac = extraExact - whole
                repeat(whole) { out.addAll(resample()) }
                if (frac > 0.0 && random.nextDouble() < frac) {
                    out.addAll(resample())
                }
            }
        }

        if (amountMul != 1.0 && amountMul > 0.0) {
            for (stack in out) {
                val newAmt = max(1, round(stack.amount * amountMul).toInt())
                stack.amount = newAmt.coerceAtMost(stack.maxStackSize)
            }
        }
        return out
    }

    private fun stackedEndsAt(currentEndsAt: Long, durationMs: Long, now: Long = System.currentTimeMillis()): Long {
        val base = if (currentEndsAt > now) currentEndsAt else now
        return base + durationMs
    }

    fun setGlobal(tier: Int, durationMs: Long, announcer: String?) {
        val t = tier.coerceIn(1, MAX_TIER)
        val now = System.currentTimeMillis()
        val ends = stackedEndsAt(global.endsAt, durationMs, now)
        global = BoostState(t, ends)
        save()
        ensureTask()
        refreshAllBossBars()
        broadcastSet("GLOBAL", global, announcer)
    }

    /** No tier arg: current tier + 1 (0 → 1, 1 → 2, …). */
    fun bumpGlobal(durationMs: Long, announcer: String?) {
        val now = System.currentTimeMillis()
        val current = if (global.isActive(now)) global.tier else 0
        val next = (current + 1).coerceAtMost(MAX_TIER)
        setGlobal(next, durationMs, announcer)
    }

    fun setPlayer(
        uuid: UUID,
        name: String,
        tier: Int,
        durationMs: Long,
        announcer: String?,
    ) {
        val t = tier.coerceIn(1, MAX_TIER)
        val now = System.currentTimeMillis()
        val prevEnds = players[uuid]?.endsAt ?: 0L
        val ends = stackedEndsAt(prevEnds, durationMs, now)
        players[uuid] = BoostState(t, ends)
        save()
        ensureTask()
        Bukkit.getPlayer(uuid)?.let { refreshBossBar(it) }
        broadcastSet(name, players[uuid]!!, announcer)
    }

    fun bumpPlayer(uuid: UUID, name: String, durationMs: Long, announcer: String?) {
        val now = System.currentTimeMillis()
        val st = players[uuid]
        val current = if (st != null && st.isActive(now)) st.tier else 0
        val next = (current + 1).coerceAtMost(MAX_TIER)
        setPlayer(uuid, name, next, durationMs, announcer)
    }

    private fun broadcastSet(label: String, st: BoostState, announcer: String?) {
        val who = announcer ?: "Server"
        Message.broadcast(
            "${ChatColor.GOLD}${ChatColor.BOLD}[Mining] $who enabled " +
                "tier ${st.tier} (multiplier ${st.amountMul()}x) for $label " +
                "${ChatColor.GOLD}for ${formatLeft(st.endsAt)}",
        )
    }

    fun clearGlobal() {
        global = BoostState()
        save()
        refreshAllBossBars()
        Message.broadcast("${ChatColor.GRAY}[Mining] Global mining boost cleared")
    }

    fun clearPlayer(uuid: UUID) {
        players.remove(uuid)
        save()
        bossBars.remove(uuid)?.removeAll()
        Bukkit.getPlayer(uuid)?.let { refreshBossBar(it) }
    }

    fun formatLeft(endsAt: Long): String {
        val left = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1000
        val h = left / 3600
        val m = (left % 3600) / 60
        val s = left % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun parseDurationMs(input: String): Long? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it * 60_000L }
        var total = 0L
        var i = 0
        while (i < s.length) {
            if (!s[i].isDigit()) return null
            var j = i
            while (j < s.length && s[j].isDigit()) j++
            val num = s.substring(i, j).toLongOrNull() ?: return null
            if (j >= s.length) return null
            when (s[j]) {
                'd' -> total += num * 86_400_000L
                'h' -> total += num * 3_600_000L
                'm' -> total += num * 60_000L
                's' -> total += num * 1_000L
                else -> return null
            }
            i = j + 1
        }
        return if (total > 0) total else null
    }

    fun refreshBossBar(player: Player) {
        val tier = effectiveTier(player)
        val now = System.currentTimeMillis()
        val p = players[player.uniqueId]
        val ends = max(
            if (p != null && p.isActive(now)) p.endsAt else 0L,
            if (global.isActive(now)) global.endsAt else 0L,
        )

        if (tier <= 0) {
            bossBars.remove(player.uniqueId)?.let { bar ->
                bar.removePlayer(player)
                bar.isVisible = false
            }
            return
        }

        val bar = bossBars.getOrPut(player.uniqueId) {
            Bukkit.createBossBar("Mining Boost", BarColor.GREEN, BarStyle.SOLID)
        }
        if (!bar.players.contains(player)) {
            bar.addPlayer(player)
        }
        bar.isVisible = true
        val amountMul = 1.0 + tier * AMOUNT_STEP // 1.5, 2.0, 2.5, …
        bar.setTitle(
            "${ChatColor.GREEN}Mining Boost ${ChatColor.WHITE}tier: $tier " +
                "${ChatColor.GRAY}multiplier ${amountMul}x " +
                "${ChatColor.WHITE}${formatLeft(ends)}",
        )
        val left = (ends - now).toDouble().coerceAtLeast(0.0)
        bar.progress = (left / 3_600_000.0).coerceIn(0.0, 1.0)
    }

    fun refreshAllBossBars() {
        for (p in Bukkit.getOnlinePlayers()) {
            refreshBossBar(p)
        }
    }

    fun onJoin(player: Player) {
        refreshBossBar(player)
    }

    fun onQuit(player: Player) {
        bossBars.remove(player.uniqueId)?.removePlayer(player)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        var changed = false
        if (global.endsAt > 0 && now >= global.endsAt) {
            global = BoostState()
            Message.broadcast("${ChatColor.GRAY}[Mining] Global mining boost expired")
            changed = true
        }
        val expired = players.entries.filter { !it.value.isActive(now) }.map { it.key }
        for (id in expired) {
            players.remove(id)
            bossBars.remove(id)?.removeAll()
            changed = true
        }
        if (changed) {
            save()
        }
        refreshAllBossBars()
    }

    fun ensureTask() {
        val plugin = Nodes.plugin ?: return
        if (tickTask == null || tickTask?.isCancelled == true) {
            tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
        }
    }

    fun load() {
        if (!Files.exists(path)) {
            ensureTask()
            return
        }
        try {
            val json = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            json.getAsJsonObject("global")?.let { o ->
                global = parseState(o)
            }
            players.clear()
            json.getAsJsonObject("players")?.entrySet()?.forEach { (k, v) ->
                players[UUID.fromString(k)] = parseState(v.asJsonObject)
            }
            Nodes.logger?.info("[MiningBoost] Loaded (global active=${global.isActive()})")
        } catch (e: Exception) {
            Nodes.logger?.severe("[MiningBoost] load failed: ${e.message}")
        }
        ensureTask()
        val plugin = Nodes.plugin
        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, Runnable { refreshAllBossBars() })
        }
    }

    private fun parseState(o: JsonObject): BoostState {
        if (o.has("tier")) {
            return BoostState(
                tier = o.get("tier").asInt,
                endsAt = o.get("endsAt")?.asLong ?: 0L,
            )
        }
        val amount = o.get("amountMul")?.asDouble ?: 1.0
        val ends = o.get("endsAt")?.asLong ?: 0L
        val tier = if (ends > System.currentTimeMillis() && amount > 1.0) {
            max(1, round((amount - 1.0) / AMOUNT_STEP).toInt())
        } else {
            0
        }
        return BoostState(tier, ends)
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val g = JsonObject()
            g.addProperty("tier", global.tier)
            g.addProperty("endsAt", global.endsAt)
            root.add("global", g)
            val ps = JsonObject()
            for ((id, st) in players) {
                val o = JsonObject()
                o.addProperty("tier", st.tier)
                o.addProperty("endsAt", st.endsAt)
                ps.add(id.toString(), o)
            }
            root.add("players", ps)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[MiningBoost] save failed: ${e.message}")
        }
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        for (bar in bossBars.values) bar.removeAll()
        bossBars.clear()
        save()
    }
}

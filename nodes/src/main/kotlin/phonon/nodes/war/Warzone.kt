/**
 * Warzone event system for Nodes.
 *
 * - Warzone = real town (no join) that owns event territories.
 * - Caps only work on territories of an *enabled* warzone town.
 * - Attackers must be in a town and at war with that warzone town.
 * - During the event: occupation only (no annex of the warzone town).
 * - Home core of warzone is treated like a normal territory (occupy only).
 * - Majority hold (> half of wz territories occupied by same town) accrues score time.
 * - On end: all warzone territories annex to the highest-scoring town.
 */

package phonon.nodes.war

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
import phonon.nodes.objects.TerritoryId
import phonon.nodes.objects.Town
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WarzoneState(
    val townName: String,
    val townUuid: UUID,
    var enabled: Boolean = false,
    var endsAt: Long = 0L,
    /** townName -> milliseconds held as majority controller */
    val scores: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
    var lastMajorityTown: String? = null,
    var lastScoreTickMs: Long = 0L,
)

object Warzone {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    val path: Path = Paths.get("plugins/nodes/warzones.json")

    /** town name (lowercase key optional) -> state */
    val zones: ConcurrentHashMap<String, WarzoneState> = ConcurrentHashMap()

    private var scoreTask: BukkitTask? = null
    private var endCheckTask: BukkitTask? = null
    private val playerBars: ConcurrentHashMap<UUID, org.bukkit.boss.BossBar> = ConcurrentHashMap()

    /** Score tick interval (ticks). */
    private const val SCORE_PERIOD_TICKS: Long = 20L

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    fun isWarzoneTown(town: Town?): Boolean {
        if (town == null) return false
        return zones.containsKey(town.name)
    }

    fun isWarzoneTownName(name: String): Boolean = zones.containsKey(name)

    fun getState(townName: String): WarzoneState? = zones[townName]

    fun isEnabledWarzoneTown(town: Town?): Boolean {
        if (town == null) return false
        val state = zones[town.name] ?: return false
        return state.enabled && System.currentTimeMillis() < state.endsAt
    }

    /**
     * Territory is attackable as warzone land: owned by an enabled warzone town.
     */
    fun isAttackableWarzoneTerritory(town: Town?): Boolean = isEnabledWarzoneTown(town)

    /**
     * Attacker may flag this warzone town: must be enemies with the warzone town.
     */
    fun canAttackWarzone(attacker: Town, warzoneTown: Town): Boolean {
        if (!isEnabledWarzoneTown(warzoneTown)) return false
        if (attacker === warzoneTown) return false
        return attacker.enemies.contains(warzoneTown)
    }

    /** Enabled warzone whose town owns this territory, or null. */
    fun getEnabledWarzoneForTerritory(territory: phonon.nodes.objects.Territory): WarzoneState? {
        val town = territory.town ?: return null
        val state = zones[town.name] ?: return null
        if (!state.enabled || System.currentTimeMillis() >= state.endsAt) return null
        return state
    }

    /**
     * Current majority holder name + their TOTAL score ms.
     * Also top scorer for status lines.
     */
    fun holdSummary(state: WarzoneState): String {
        val wzTown = Nodes.towns[state.townName]
        val current = if (wzTown != null) majorityHolder(wzTown)?.name else state.lastMajorityTown
        val currentMs = if (current != null) state.scores[current] ?: 0L else 0L

        val currentLine = if (current != null) {
            "Holding: $current (${formatHoldMs(currentMs)} total)"
        } else {
            "Holding: nobody"
        }

        // top total score
        var topName: String? = null
        var topMs = -1L
        for ((n, ms) in state.scores) {
            if (ms > topMs) {
                topMs = ms
                topName = n
            }
        }
        val leadLine = if (topName != null && topMs > 0) {
            " │ Lead: $topName ${formatHoldMs(topMs)}"
        } else {
            ""
        }
        return currentLine + leadLine
    }

    fun refreshBossBar(player: Player) {
        val loc = player.location
        val chunk = Nodes.getTerritoryChunkFromBlock(loc.blockX, loc.blockZ)
        if (chunk == null) {
            clearBossBar(player)
            return
        }
        val state = getEnabledWarzoneForTerritory(chunk.territory)
        if (state == null) {
            clearBossBar(player)
            return
        }

        val summary = holdSummary(state)
        val left = formatDurationLeft(state.endsAt)

        val bar = playerBars.getOrPut(player.uniqueId) {
            Bukkit.createBossBar("Warzone", BarColor.RED, BarStyle.SOLID)
        }
        if (!bar.players.contains(player)) {
            bar.addPlayer(player)
        }
        bar.isVisible = true
        bar.setTitle(
            "${ChatColor.RED}${state.townName} ${ChatColor.WHITE}$summary ${ChatColor.GRAY}| ends $left",
        )

        // progress = event time remaining
        val now = System.currentTimeMillis()
        // approximate: we don't store startedAt; use endsAt - now vs last known duration is hard
        // simple pulse: full if > 1h left else scale by 1h
        val leftMs = (state.endsAt - now).coerceAtLeast(0L).toDouble()
        bar.progress = (leftMs / 3_600_000.0).coerceIn(0.0, 1.0)
    }

    fun clearBossBar(player: Player) {
        playerBars.remove(player.uniqueId)?.let { bar ->
            bar.removePlayer(player)
            bar.isVisible = false
        }
    }

    fun refreshAllBossBars() {
        for (p in Bukkit.getOnlinePlayers()) {
            refreshBossBar(p)
        }
    }

    // -------------------------------------------------------------------------
    // Duration parse: 72h, 90m, 2h30m, plain minutes number
    // -------------------------------------------------------------------------

    fun parseDurationMs(input: String): Long? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null

        // pure number => minutes
        s.toLongOrNull()?.let { return it * 60_000L }

        var totalMs = 0L
        var i = 0
        while (i < s.length) {
            if (!s[i].isDigit()) return null
            var j = i
            while (j < s.length && s[j].isDigit()) j++
            val num = s.substring(i, j).toLongOrNull() ?: return null
            if (j >= s.length) return null
            when (s[j]) {
                'h' -> totalMs += num * 3_600_000L
                'm' -> totalMs += num * 60_000L
                's' -> totalMs += num * 1_000L
                'd' -> totalMs += num * 86_400_000L
                else -> return null
            }
            i = j + 1
        }
        return if (totalMs > 0) totalMs else null
    }

    fun formatDurationLeft(endsAt: Long): String {
        val left = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val totalSec = left / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    /** Format any duration in ms (hold scores, not only time-left). */
    fun formatHoldMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m ${sec}s"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    // -------------------------------------------------------------------------
    // Create / enable / disable
    // -------------------------------------------------------------------------

    /**
     * Create warzone town and assign territories. Home = first id.
     * Town is closed (open=false). No leader.
     */
    fun create(name: String, territoryIds: List<Int>): Result<Town> {
        if (Nodes.towns.containsKey(name)) {
            return Result.failure(Exception("Town \"$name\" already exists"))
        }
        if (territoryIds.isEmpty()) {
            return Result.failure(Exception("Need at least one territory id"))
        }

        val homeId = territoryIds[0]
        val home = Nodes.getTerritoryFromId(TerritoryId(homeId))
            ?: return Result.failure(Exception("Territory id $homeId does not exist"))

        if (home.town !== null) {
            return Result.failure(Exception("Territory $homeId already owned by ${home.town!!.name}"))
        }

        val created = Nodes.createTown(name, home, null)
        if (created.isFailure) {
            return Result.failure(created.exceptionOrNull() ?: Exception("createTown failed"))
        }
        val town = created.getOrThrow()
        town.isOpen = false

        for (id in territoryIds.drop(1)) {
            val terr = Nodes.getTerritoryFromId(TerritoryId(id))
            if (terr == null) {
                Nodes.logger?.warning("[Warzone] Territory $id missing, skipped")
                continue
            }
            if (terr.town !== null && terr.town !== town) {
                Nodes.logger?.warning("[Warzone] Territory $id owned by ${terr.town!!.name}, skipped")
                continue
            }
            val claim = Nodes.claimTerritory(town, terr)
            if (claim.isFailure) {
                Nodes.logger?.warning("[Warzone] claim $id failed: ${claim.exceptionOrNull()?.message}")
            }
        }

        zones[name] = WarzoneState(
            townName = name,
            townUuid = town.uuid,
            enabled = false,
            endsAt = 0L,
        )
        needsSave = true
        save()
        Nodes.needsSave = true

        return Result.success(town)
    }

    fun enable(name: String, durationMs: Long): Result<WarzoneState> {
        val town = Nodes.towns[name]
            ?: return Result.failure(Exception("Town \"$name\" does not exist"))
        if (town.territories.isEmpty()) {
            return Result.failure(Exception("Warzone town has no territories"))
        }

        val state = zones.getOrPut(name) {
            WarzoneState(townName = name, townUuid = town.uuid)
        }
        state.enabled = true
        state.endsAt = System.currentTimeMillis() + durationMs
        state.scores.clear()
        state.lastMajorityTown = null
        state.lastScoreTickMs = System.currentTimeMillis()
        town.isOpen = false

        needsSave = true
        save()
        ensureTasks()

        Message.broadcast(
            "${ChatColor.DARK_RED}${ChatColor.BOLD}[Warzone] $name is LIVE for ${formatDurationLeft(state.endsAt)}! " +
                "Declare /war $name then capture territories. Majority hold scores time.",
        )
        refreshAllBossBars()
        return Result.success(state)
    }

    fun disable(name: String, award: Boolean = true): Result<Unit> {
        val state = zones[name] ?: return Result.failure(Exception("Not a registered warzone: $name"))
        if (!state.enabled && state.endsAt == 0L) {
            return Result.failure(Exception("Warzone \"$name\" is not active"))
        }
        endEvent(name, award)
        return Result.success(Unit)
    }

    // -------------------------------------------------------------------------
    // Scoring + end
    // -------------------------------------------------------------------------

    /**
     * Majority: occupied count by town T > N/2
     * (4 nodes, occupy 3 → hold).
     */
    fun majorityHolder(warzoneTown: Town): Town? {
        val n = warzoneTown.territories.size
        if (n == 0) return null
        val need = n / 2 // strictly greater than need
        val counts = HashMap<Town, Int>()

        for (terrId in warzoneTown.territories) {
            val terr = Nodes.getTerritoryFromId(terrId) ?: continue
            val occ = terr.occupier ?: continue
            if (occ === warzoneTown) continue
            counts[occ] = (counts[occ] ?: 0) + 1
        }

        var best: Town? = null
        var bestCount = 0
        for ((town, c) in counts) {
            if (c > need && c > bestCount) {
                best = town
                bestCount = c
            }
        }
        return best
    }

    fun tickScores() {
        val now = System.currentTimeMillis()
        for ((name, state) in zones) {
            if (!state.enabled) continue
            if (now >= state.endsAt) {
                endEvent(name, award = true)
                continue
            }
            val town = Nodes.towns[name] ?: continue
            val holder = majorityHolder(town)
            val last = state.lastScoreTickMs
            if (last > 0L && holder != null) {
                val delta = now - last
                if (delta > 0) {
                    state.scores.merge(holder.name, delta) { a, b -> a + b }
                }
            }
            state.lastMajorityTown = holder?.name
            state.lastScoreTickMs = now
        }
        if (needsSave) {
            save()
        }
        refreshAllBossBars()
    }

    /**
     * Same idea as FlagWar.disable() occupation cleanup, scoped to one warzone town.
     * Always nulls every chunk in the warzone (not only FlagWar.occupiedChunks).
     */
    private fun clearWarzoneOccupationLikeWarDisable(warzoneTown: Town) {
        val wzCoords = HashSet<phonon.nodes.objects.Coord>()
        for (terrId in warzoneTown.territories.toList()) {
            val terr = Nodes.getTerritoryFromId(terrId) ?: continue

            // territory-level occupation (core capture)
            if (terr.occupier != null) {
                Nodes.releaseTerritory(terr)
            }
            terr.occupier = null

            for (coord in terr.chunks) {
                wzCoords.add(coord)
            }
        }

        // 1) Cancel attacks on warzone chunks (copy map — concurrent-safe)
        for ((coord, attack) in FlagWar.chunkToAttacker.toMap()) {
            if (coord !in wzCoords) continue

            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if (chunk != null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            // Attack no longer has .thread — use cancelled flag + cancelAttack
            attack.cancelled = true
            try {
                FlagWar.cancelAttack(attack)
            } catch (_: Exception) {
            }
        }

        // 2) Force-clear EVERY warzone chunk (minimap fix)
        for (coord in wzCoords) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord) ?: continue
            chunk.attacker = null
            chunk.occupier = null
            FlagWar.occupiedChunks.remove(coord)
        }

        // 3) Also strip any occupiedChunks entries that point at this warzone
        for (coord in FlagWar.occupiedChunks.toList()) {
            if (coord in wzCoords) {
                val chunk = Nodes.getTerritoryChunkFromCoord(coord)
                if (chunk != null) {
                    chunk.attacker = null
                    chunk.occupier = null
                }
                FlagWar.occupiedChunks.remove(coord)
            }
        }

        FlagWar.needsSave = true
    }

    /**
     * End warzone: pick winner by score, annex all wz territories to winner.
     */
    fun endEvent(name: String, award: Boolean) {
        val state = zones[name] ?: return
        state.enabled = false
        // clear bars for everyone (event over)
        for (p in Bukkit.getOnlinePlayers()) {
            clearBossBar(p)
        }
        val warzoneTown = Nodes.towns[name]
        // Clear caps so minimap / occupation state reset before award or idle
        if (warzoneTown != null) {
            clearWarzoneOccupationLikeWarDisable(warzoneTown)
        }

        var winner: Town? = null
        if (award && warzoneTown != null) {
            var bestScore = -1L
            var bestName: String? = null
            for ((tName, score) in state.scores) {
                if (score > bestScore) {
                    bestScore = score
                    bestName = tName
                }
            }
            if (bestName != null) {
                winner = Nodes.towns[bestName]
            }
        }

        if (winner != null && warzoneTown != null) {
            val ids = warzoneTown.territories.toList()
            for (terrId in ids) {
                val terr = Nodes.getTerritoryFromId(terrId) ?: continue

                // Force transfer — do NOT use annexTerritory (needs occupier == winner)
                warzoneTown.territories.remove(terrId)
                warzoneTown.annexed.remove(terrId)
                warzoneTown.captured.remove(terrId)

                winner.territories.add(terrId)
                winner.annexed.add(terrId)
                winner.captured.remove(terrId)

                terr.town = winner
                terr.occupier = null
            }

            warzoneTown.claimsMax = Nodes.calculateMaxClaims(warzoneTown)
            winner.claimsMax = Nodes.calculateMaxClaims(winner)
            warzoneTown.needsUpdate()
            winner.needsUpdate()

            if (warzoneTown.territories.isEmpty()) {
                try {
                    Nodes.destroyTown(warzoneTown)
                } catch (e: Exception) {
                    Nodes.logger?.warning("[Warzone] destroyTown failed: ${e.message}")
                }
                zones.remove(name)
            }

            val heldMs = state.scores[winner.name] ?: 0L
            Message.broadcast(
                "${ChatColor.GOLD}${ChatColor.BOLD}[Warzone] $name ended! " +
                    "Winner: ${winner.name} (held ${formatHoldMs(heldMs)}). " +
                    "All warzone territories transferred.",
            )
        } else {
            Message.broadcast("${ChatColor.GOLD}${ChatColor.BOLD}[Warzone] $name ended with no winner.")
            warzoneTown?.territories?.forEach { id ->
                Nodes.getTerritoryFromId(id)?.occupier = null
            }
        }

        state.endsAt = 0L
        state.lastMajorityTown = null
        needsSave = true
        save()
        Nodes.needsSave = true
        Nodes.renderMinimaps()
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    var needsSave: Boolean = false

    fun load() {
        zones.clear()
        if (!Files.exists(path)) return
        try {
            val json = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            val arr = json.getAsJsonArray("warzones") ?: return
            for (el in arr) {
                val o = el.asJsonObject
                val name = o.get("townName").asString
                val uuid = UUID.fromString(o.get("townUuid").asString)
                val enabled = o.get("enabled")?.asBoolean ?: false
                val endsAt = o.get("endsAt")?.asLong ?: 0L
                val state = WarzoneState(name, uuid, enabled, endsAt)
                o.getAsJsonObject("scores")?.entrySet()?.forEach { (k, v) ->
                    state.scores[k] = v.asLong
                }
                state.lastMajorityTown = o.get("lastMajorityTown")?.takeIf { !it.isJsonNull }?.asString
                state.lastScoreTickMs = o.get("lastScoreTickMs")?.asLong ?: System.currentTimeMillis()
                zones[name] = state
            }
            Nodes.logger?.info("[Warzone] Loaded ${zones.size} warzone(s)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Warzone] Failed to load warzones.json: ${e.message}")
            e.printStackTrace()
        }
        ensureTasks()
    }
    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = com.google.gson.JsonArray()
            for (state in zones.values) {
                val o = JsonObject()
                o.addProperty("townName", state.townName)
                o.addProperty("townUuid", state.townUuid.toString())
                o.addProperty("enabled", state.enabled)
                o.addProperty("endsAt", state.endsAt)
                o.addProperty("lastMajorityTown", state.lastMajorityTown)
                o.addProperty("lastScoreTickMs", state.lastScoreTickMs)
                val scores = JsonObject()
                state.scores.forEach { (k, v) -> scores.addProperty(k, v) }
                o.add("scores", scores)
                arr.add(o)
            }
            root.add("warzones", arr)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
            needsSave = false
        } catch (e: Exception) {
            Nodes.logger?.severe("[Warzone] Failed to save warzones.json: ${e.message}")
            e.printStackTrace()
        }
    }

    fun ensureTasks() {
        val plugin = Nodes.plugin ?: return
        if (scoreTask == null || scoreTask?.isCancelled == true) {
            scoreTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable { tickScores() },
                SCORE_PERIOD_TICKS,
                SCORE_PERIOD_TICKS,
            )
        }
    }

    fun shutdown() {
        scoreTask?.cancel()
        scoreTask = null
        endCheckTask?.cancel()
        endCheckTask = null
        for (bar in playerBars.values) {
            bar.removeAll()
        }
        playerBars.clear()
        save()
    }
}

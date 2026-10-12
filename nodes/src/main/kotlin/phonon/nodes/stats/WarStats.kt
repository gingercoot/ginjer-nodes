package phonon.nodes.stats

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Session K/D while /nda war enable OR /nda war skirmish is active.
 * On disable: war-kdr-* or skirmish-kdr-* under plugins/nodes/
 */
object WarStats {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private var active: Boolean = false
    private var sessionType: String = "war"
    private var sessionStartedAt: String = ""
    private val records = ConcurrentHashMap<UUID, KillDeathRecord>()

    fun isTracking(): Boolean = active

    fun sessionType(): String = sessionType

    fun startWarSession(fullWar: Boolean) {
        if (active) {
            endWarSessionAndSave(silent = true)
        }
        records.clear()
        active = true
        sessionType = if (fullWar) "war" else "skirmish"
        sessionStartedAt = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val label = if (fullWar) "War" else "Skirmish"
        Message.broadcast("${ChatColor.GRAY}[$label] Session KDR tracking started")
    }

    fun addKill(uuid: UUID) {
        if (!active) return
        records.getOrPut(uuid) { KillDeathRecord() }.kills += 1
    }

    fun addDeath(uuid: UUID) {
        if (!active) return
        records.getOrPut(uuid) { KillDeathRecord() }.deaths += 1
    }

    fun endWarSessionAndSave(silent: Boolean = false) {
        if (!active) return
        active = false
        saveFiles()
        records.clear()
        if (!silent) {
            val label = if (sessionType == "skirmish") "Skirmish" else "War"
            Message.broadcast("${ChatColor.GRAY}[$label] Session KDR saved to plugins/nodes/")
        }
    }

    fun snapshotSortedByKills(): List<Pair<String, KillDeathRecord>> = records.entries
        .map { (uuid, rec) ->
            val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().substring(0, 8)
            name to rec
        }
        .sortedByDescending { it.second.kills }

    private fun saveFiles() {
        try {
            val dir = Paths.get("plugins/nodes")
            Files.createDirectories(dir)
            val stamp = sessionStartedAt.ifEmpty {
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            }
            val prefix = if (sessionType == "skirmish") "skirmish-kdr" else "war-kdr"
            val jsonPath = dir.resolve("$prefix-$stamp.json")
            val txtPath = dir.resolve("$prefix-$stamp.txt")

            val root = JsonObject()
            root.addProperty("type", sessionType)
            root.addProperty("startedAt", sessionStartedAt)
            root.addProperty("endedAt", LocalDateTime.now().toString())
            val players = JsonObject()
            val lines = ArrayList<String>()
            val title = if (sessionType == "skirmish") "Skirmish" else "War"
            lines.add("$title KDR session $stamp")
            lines.add("========================")

            val sorted = records.entries.sortedByDescending { it.value.kills }
            for ((uuid, rec) in sorted) {
                val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                val o = JsonObject()
                o.addProperty("name", name)
                o.addProperty("kills", rec.kills)
                o.addProperty("deaths", rec.deaths)
                o.addProperty("kdr", rec.kdrString())
                players.add(uuid.toString(), o)
                lines.add("$name - ${rec.kills}K / ${rec.deaths}D (KDR ${rec.kdrString()})")
            }
            root.add("players", players)

            FileWriter(jsonPath.toFile()).use { it.write(gson.toJson(root)) }
            FileWriter(txtPath.toFile()).use { it.write(lines.joinToString("\n")) }

            Nodes.logger?.info("[WarStats] Saved ${jsonPath.fileName} and ${txtPath.fileName}")
        } catch (e: Exception) {
            Nodes.logger?.severe("[WarStats] save failed: ${e.message}")
            e.printStackTrace()
        }
    }
}

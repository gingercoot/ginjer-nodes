package phonon.nodes

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.bukkit.entity.Player
import phonon.nodes.objects.Nation
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object RallyCap {
    /** nation name (lowercase) -> cap (-1 unlimited) */
    private val caps = ConcurrentHashMap<String, Int>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = File(Nodes.plugin!!.dataFolder, "rallycaps.json")

    fun load() {
        caps.clear()
        if (!file.exists()) {
            save()
            return
        }
        try {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return
            for ((k, v) in root.entrySet()) {
                caps[k.lowercase()] = v.asInt
            }
        } catch (e: Exception) {
            Nodes.logger?.severe("[RallyCap] load failed: ${e.message}")
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            for ((k, v) in caps) root.addProperty(k, v)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(root))
        } catch (e: Exception) {
            Nodes.logger?.severe("[RallyCap] save failed: ${e.message}")
        }
    }

    fun set(nationName: String, cap: Int) {
        caps[nationName.lowercase()] = cap
        save()
    }

    fun get(nationName: String): Int = caps[nationName.lowercase()] ?: -1

    fun get(nation: Nation): Int = get(nation.name)

    fun canJoinNationTown(nation: Nation?, player: Player): Boolean {
        if (nation == null) return true
        val cap = get(nation)
        if (cap < 0) return true
        val online = nation.playersOnline.size
        // Already counted towards the cap
        if (nation.playersOnline.contains(player)) return true
        return online < cap
    }

    fun denyMessage(nation: Nation): String {
        val cap = get(nation)
        return "Nation ${nation.name} is at rally cap ($cap online). You cannot join right now."
    }
}

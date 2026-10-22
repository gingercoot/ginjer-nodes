package phonon.nodes.killshop

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class KillShopEntry(
    val id: String,
    var cost: Int,
    val itemBase64: String,
) {
    fun toItem(): ItemStack? = KillShop.itemFromBase64(itemBase64)?.clone()
}

object KillShop {
    var enabled: Boolean = false
        private set

    private val points = ConcurrentHashMap<UUID, Int>()
    private val entries = ConcurrentHashMap<String, KillShopEntry>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File
        get() = File(Nodes.plugin!!.dataFolder, "killshop.json")

    fun load() {
        points.clear()
        entries.clear()
        if (!file.exists()) {
            save()
            return
        }
        try {
            val root = gson.fromJson(file.readText(), JsonObject::class.java) ?: return
            enabled = root.get("enabled")?.asBoolean ?: false
            root.getAsJsonObject("points")?.entrySet()?.forEach { (k, v) ->
                try {
                    points[UUID.fromString(k)] = v.asInt
                } catch (_: Exception) {
                }
            }
            root.getAsJsonArray("entries")?.forEach { el ->
                val o = el.asJsonObject
                val id = o.get("id").asString
                entries[id.lowercase()] = KillShopEntry(
                    id = id,
                    cost = o.get("cost").asInt,
                    itemBase64 = o.get("itemBase64").asString,
                )
            }
            Nodes.logger?.info("[KillShop] Loaded ${entries.size} entries, enabled=$enabled")
        } catch (e: Exception) {
            Nodes.logger?.severe("[KillShop] load failed: ${e.message}")
            e.printStackTrace()
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            root.addProperty("enabled", enabled)
            val pts = JsonObject()
            points.forEach { (u, n) -> pts.addProperty(u.toString(), n) }
            root.add("points", pts)
            val arr = JsonArray()
            entries.values.forEach { e ->
                val o = JsonObject()
                o.addProperty("id", e.id)
                o.addProperty("cost", e.cost)
                o.addProperty("itemBase64", e.itemBase64)
                arr.add(o)
            }
            root.add("entries", arr)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(root))
        } catch (e: Exception) {
            Nodes.logger?.severe("[KillShop] save failed: ${e.message}")
        }
    }

    fun shutdown() {
        save()
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        save()
    }

    fun getPoints(uuid: UUID): Int = points[uuid] ?: 0

    fun addPoint(uuid: UUID, amount: Int = 1) {
        if (!enabled || amount == 0) return
        points[uuid] = getPoints(uuid) + amount
        save()
    }

    fun trySpend(uuid: UUID, cost: Int): Boolean {
        val cur = getPoints(uuid)
        if (cur < cost) return false
        points[uuid] = cur - cost
        save()
        return true
    }

    fun allEntries(): List<KillShopEntry> = entries.values.sortedBy { it.id.lowercase() }

    fun getEntry(id: String): KillShopEntry? = entries[id.lowercase()]

    fun addEntry(id: String, cost: Int, item: ItemStack): String? {
        val key = id.lowercase()
        if (entries.containsKey(key)) return "ID already exists"
        if (cost < 0) return "Cost must be >= 0"
        if (id.isBlank()) return "ID cannot be empty"
        val b64 = itemToBase64(item) ?: return "Could not serialize item"
        entries[key] = KillShopEntry(id = id, cost = cost, itemBase64 = b64)
        save()
        return null
    }

    fun removeEntry(id: String): Boolean {
        val ok = entries.remove(id.lowercase()) != null
        if (ok) save()
        return ok
    }

    fun setCost(id: String, cost: Int): Boolean {
        val e = entries[id.lowercase()] ?: return false
        if (cost < 0) return false
        e.cost = cost
        save()
        return true
    }

    fun onPlayerKill(killer: Player) {
        if (!enabled) return
        addPoint(killer.uniqueId, 1)
        Message.print(
            killer,
            "§a+1 killshop point §7(total: ${getPoints(killer.uniqueId)})",
        )
    }

    fun itemToBase64(item: ItemStack): String? = try {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { it.writeObject(item) }
        Base64.getEncoder().encodeToString(baos.toByteArray())
    } catch (_: Exception) {
        null
    }

    fun itemFromBase64(data: String): ItemStack? = try {
        val bytes = Base64.getDecoder().decode(data)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as ItemStack
        }
    } catch (_: Exception) {
        null
    }
}

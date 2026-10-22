/**
 * Fixed-interval refineries (dropper blocks).
 * Admins set outputs while holding items (supports custom/NBT items).
 * Every interval, outputs are added to a buffer; anyone can collect via GUI.
 * No holograms — timer/info is shown in the GUI book.
 */

package phonon.nodes.refinery

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import phonon.nodes.Nodes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One configured output line (template item + amount per tick). */
data class RefineryOutput(
    val itemBase64: String,
    val amount: Int,
) {
    fun toItemStack(): ItemStack? {
        val base = Refineries.itemFromBase64(itemBase64) ?: return null
        val stack = base.clone()
        stack.amount = amount.coerceIn(1, stack.maxStackSize)
        return stack
    }
}

data class Refinery(
    val id: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    var intervalMs: Long,
    val outputs: MutableList<RefineryOutput> = mutableListOf(),
    val buffer: MutableList<ItemStack> = mutableListOf(),
    var lastProduceAt: Long = System.currentTimeMillis(),
    /** Legacy field from hologram version; cleared on load */
    var hologramUuid: UUID? = null,
) {
    fun block(): Block? {
        val w = Bukkit.getWorld(world) ?: return null
        return w.getBlockAt(x, y, z)
    }

    fun matches(block: Block): Boolean = block.world.name == world && block.x == x && block.y == y && block.z == z

    fun ensureDropper() {
        val b = block() ?: return
        if (b.type != Material.DROPPER) {
            b.type = Material.DROPPER
        }
    }

    fun msUntilNext(now: Long = System.currentTimeMillis()): Long {
        val elapsed = now - lastProduceAt
        return (intervalMs - elapsed).coerceAtLeast(0L)
    }

    fun isReady(): Boolean = buffer.isNotEmpty()
}

object Refineries {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    val path: Path = Paths.get("plugins/nodes/refineries.json")

    val byId: ConcurrentHashMap<String, Refinery> = ConcurrentHashMap()
    private var tickTask: BukkitTask? = null

    fun getByBlock(block: Block): Refinery? = byId.values.firstOrNull { it.matches(block) }

    fun isRefineryBlock(block: Block): Boolean = getByBlock(block) != null

    fun create(id: String, block: Block, intervalMs: Long): Result<Refinery> {
        if (byId.containsKey(id)) {
            return Result.failure(Exception("Refinery id \"$id\" already exists"))
        }
        if (intervalMs < 1000L) {
            return Result.failure(Exception("Interval too short"))
        }
        block.type = Material.DROPPER
        val r = Refinery(
            id = id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            intervalMs = intervalMs,
            lastProduceAt = System.currentTimeMillis(),
        )
        byId[id] = r
        save()
        ensureTask()
        return Result.success(r)
    }

    fun remove(id: String): Boolean {
        val r = byId.remove(id) ?: return false
        clearLegacyHologram(r)
        r.block()?.type = Material.AIR
        save()
        return true
    }

    /**
     * Append held item as an output (custom NBT items supported).
     */
    fun setOutput(id: String, held: ItemStack, amount: Int): Result<Unit> {
        val r = byId[id] ?: return Result.failure(Exception("Unknown refinery \"$id\""))
        if (held.type == Material.AIR || amount < 1) {
            return Result.failure(Exception("Hold an item and use amount >= 1"))
        }
        val template = held.clone()
        template.amount = 1
        val b64 = itemToBase64(template)
            ?: return Result.failure(Exception("Could not serialize item"))
        r.outputs.add(RefineryOutput(b64, amount))
        save()
        return Result.success(Unit)
    }

    fun clearOutputs(id: String): Boolean {
        val r = byId[id] ?: return false
        r.outputs.clear()
        save()
        return true
    }

    fun clearBuffer(id: String): Boolean {
        val r = byId[id] ?: return false
        r.buffer.clear()
        save()
        return true
    }

    /** Produce one cycle into buffer if due. */
    fun tryProduce(r: Refinery, now: Long = System.currentTimeMillis()) {
        if (r.outputs.isEmpty()) return
        if (now - r.lastProduceAt < r.intervalMs) return

        // At most one cycle per due check (no multi-catch-up backlog)
        r.lastProduceAt = now
        for (out in r.outputs) {
            val stack = out.toItemStack() ?: continue
            r.buffer.add(stack)
        }
        while (r.buffer.size > 54) {
            r.buffer.removeAt(0)
        }
        save()
    }

    fun tickAll() {
        val now = System.currentTimeMillis()
        for (r in byId.values) {
            tryProduce(r, now)
        }
    }

    fun takeFromBuffer(r: Refinery, index: Int): ItemStack? {
        if (index < 0 || index >= r.buffer.size) return null
        val item = r.buffer.removeAt(index)
        save()
        return item
    }

    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
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

    // ----- item serialize (custom items / NBT) -----

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

    private fun clearLegacyHologram(r: Refinery) {
        r.hologramUuid?.let { id ->
            Bukkit.getWorld(r.world)?.getEntity(id)?.remove()
        }
        r.hologramUuid = null
        val w = Bukkit.getWorld(r.world) ?: return
        val loc = org.bukkit.Location(w, r.x + 0.5, r.y + 1.2, r.z + 0.5)
        w.getNearbyEntities(loc, 1.5, 1.5, 1.5).forEach { e ->
            if (e is ArmorStand && e.isMarker && !e.isVisible) {
                e.remove()
            }
        }
    }

    fun load() {
        byId.clear()
        if (!Files.exists(path)) {
            ensureTask()
            return
        }
        try {
            val json = JsonParser.parseReader(FileReader(path.toFile())).asJsonObject
            val arr = json.getAsJsonArray("refineries") ?: run {
                ensureTask()
                return
            }
            for (el in arr) {
                val o = el.asJsonObject
                val outputs = mutableListOf<RefineryOutput>()
                o.getAsJsonArray("outputs")?.forEach { e ->
                    val oo = e.asJsonObject
                    outputs.add(
                        RefineryOutput(
                            oo.get("itemBase64").asString,
                            oo.get("amount").asInt,
                        ),
                    )
                }
                val buffer = mutableListOf<ItemStack>()
                o.getAsJsonArray("buffer")?.forEach { e ->
                    itemFromBase64(e.asString)?.let { buffer.add(it) }
                }
                val r = Refinery(
                    id = o.get("id").asString,
                    world = o.get("world").asString,
                    x = o.get("x").asInt,
                    y = o.get("y").asInt,
                    z = o.get("z").asInt,
                    intervalMs = o.get("intervalMs").asLong,
                    outputs = outputs,
                    buffer = buffer,
                    lastProduceAt = o.get("lastProduceAt")?.asLong ?: System.currentTimeMillis(),
                    hologramUuid = o.get("hologramUuid")?.asString?.let {
                        try {
                            UUID.fromString(it)
                        } catch (_: Exception) {
                            null
                        }
                    },
                )
                byId[r.id] = r
                r.ensureDropper()
                clearLegacyHologram(r)
            }
            Nodes.logger?.info("[Refineries] Loaded ${byId.size} refinery(ies)")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Refineries] load failed: ${e.message}")
            e.printStackTrace()
        }
        ensureTask()
    }

    fun save() {
        try {
            Files.createDirectories(path.parent)
            val root = JsonObject()
            val arr = JsonArray()
            for (r in byId.values) {
                val o = JsonObject()
                o.addProperty("id", r.id)
                o.addProperty("world", r.world)
                o.addProperty("x", r.x)
                o.addProperty("y", r.y)
                o.addProperty("z", r.z)
                o.addProperty("intervalMs", r.intervalMs)
                o.addProperty("lastProduceAt", r.lastProduceAt)
                val outs = JsonArray()
                for (out in r.outputs) {
                    val oo = JsonObject()
                    oo.addProperty("itemBase64", out.itemBase64)
                    oo.addProperty("amount", out.amount)
                    outs.add(oo)
                }
                o.add("outputs", outs)
                val buf = JsonArray()
                for (item in r.buffer) {
                    itemToBase64(item)?.let { buf.add(it) }
                }
                o.add("buffer", buf)
                arr.add(o)
            }
            root.add("refineries", arr)
            FileWriter(path.toFile()).use { it.write(gson.toJson(root)) }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Refineries] save failed: ${e.message}")
        }
    }

    fun ensureTask() {
        val plugin = Nodes.plugin ?: return
        if (tickTask == null || tickTask?.isCancelled == true) {
            tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable { tickAll() },
                20L,
                20L,
            )
        }
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        save()
    }
}

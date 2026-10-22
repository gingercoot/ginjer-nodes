package phonon.nodes.plot

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.objects.BlockPos
import phonon.nodes.objects.Plot
import phonon.nodes.objects.PlotAction
import phonon.nodes.objects.PlotRule
import phonon.nodes.objects.PlotSelection
import phonon.nodes.objects.PlotSubjectType
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Town
import phonon.nodes.war.FlagWar
import phonon.nodes.war.Warzone
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Town plots: protected AABB regions with per-subject allow/deny rules.
 * Default deny everything (including owner) until rules are set.
 * Plot rules override town perms when a plot contains the block.
 */
object Plots {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File
        get() = File(Nodes.plugin!!.dataFolder, "plots.json")

    private val plots = ConcurrentHashMap<String, Plot>()

    private val selecting = ConcurrentHashMap<UUID, PlotSelection>()

    fun load() {
        plots.clear()
        selecting.clear()
        val f = file
        if (!f.exists()) {
            save()
            return
        }
        try {
            val root = gson.fromJson(f.readText(), JsonObject::class.java) ?: return
            val arr = root.getAsJsonArray("plots") ?: return
            for (el in arr) {
                val o = el.asJsonObject
                val name = o.get("name").asString
                val minO = o.getAsJsonObject("min")
                val maxO = o.getAsJsonObject("max")
                val plot = Plot(
                    name = name,
                    townName = o.get("townName").asString,
                    worldName = o.get("worldName").asString,
                    min = BlockPos(minO.get("x").asInt, minO.get("y").asInt, minO.get("z").asInt),
                    max = BlockPos(maxO.get("x").asInt, maxO.get("y").asInt, maxO.get("z").asInt),
                    ownerUuid = UUID.fromString(o.get("ownerUuid").asString),
                    ownerName = o.get("ownerName").asString,
                )
                val rulesArr = o.getAsJsonArray("rules") ?: JsonArray()
                for (r in rulesArr) {
                    val ro = r.asJsonObject
                    val subject = PlotSubjectType.fromString(ro.get("subject").asString) ?: continue
                    val action = PlotAction.fromString(ro.get("action").asString) ?: continue
                    plot.rules.add(
                        PlotRule(
                            subject = subject,
                            personUuid = ro.get("personUuid")?.asString?.let { UUID.fromString(it) },
                            personName = ro.get("personName")?.asString,
                            action = action,
                            allow = ro.get("allow").asBoolean,
                        ),
                    )
                }
                plots[name.lowercase()] = plot
            }
            Nodes.logger?.info("[Plots] Loaded ${plots.size} plots")
        } catch (e: Exception) {
            Nodes.logger?.severe("[Plots] Failed to load plots.json: ${e.message}")
            e.printStackTrace()
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            val arr = JsonArray()
            for (plot in plots.values) {
                val o = JsonObject()
                o.addProperty("name", plot.name)
                o.addProperty("townName", plot.townName)
                o.addProperty("worldName", plot.worldName)
                val minO = JsonObject()
                minO.addProperty("x", plot.min.x)
                minO.addProperty("y", plot.min.y)
                minO.addProperty("z", plot.min.z)
                o.add("min", minO)
                val maxO = JsonObject()
                maxO.addProperty("x", plot.max.x)
                maxO.addProperty("y", plot.max.y)
                maxO.addProperty("z", plot.max.z)
                o.add("max", maxO)
                o.addProperty("ownerUuid", plot.ownerUuid.toString())
                o.addProperty("ownerName", plot.ownerName)
                val rulesArr = JsonArray()
                for (rule in plot.rules) {
                    val ro = JsonObject()
                    ro.addProperty("subject", rule.subject.name)
                    if (rule.personUuid != null) ro.addProperty("personUuid", rule.personUuid.toString())
                    if (rule.personName != null) ro.addProperty("personName", rule.personName)
                    ro.addProperty("action", rule.action.name)
                    ro.addProperty("allow", rule.allow)
                    rulesArr.add(ro)
                }
                o.add("rules", rulesArr)
                arr.add(o)
            }
            root.add("plots", arr)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(root))
        } catch (e: Exception) {
            Nodes.logger?.severe("[Plots] Failed to save: ${e.message}")
        }
    }

    fun shutdown() {
        save()
        selecting.clear()
    }

    fun get(name: String): Plot? = plots[name.lowercase()]

    fun all(): Collection<Plot> = plots.values

    fun plotsInTown(townName: String): List<Plot> = plots.values.filter { it.townName.equals(townName, ignoreCase = true) }

    fun findAt(block: Block): Plot? = plots.values.firstOrNull { it.contains(block) }

    fun findAt(loc: Location): Plot? = plots.values.firstOrNull { it.contains(loc) }

    fun isSelecting(player: Player): Boolean = selecting.containsKey(player.uniqueId)

    fun toggleSelect(player: Player): Boolean = if (selecting.containsKey(player.uniqueId)) {
        selecting.remove(player.uniqueId)
        false
    } else {
        selecting[player.uniqueId] = PlotSelection()
        true
    }

    fun clearSelect(player: Player) {
        selecting.remove(player.uniqueId)
    }

    fun getSelection(player: Player): PlotSelection? = selecting[player.uniqueId]

    fun create(
        name: String,
        town: Town,
        worldName: String,
        min: BlockPos,
        max: BlockPos,
        owner: Player,
    ): String? {
        if (plots.containsKey(name.lowercase())) {
            return "A plot named '$name' already exists"
        }
        for (p in plots.values) {
            if (p.intersects(worldName, min, max)) {
                return "Selection intersects plot '${p.name}'"
            }
        }
        val plot = Plot(
            name = name,
            townName = town.name,
            worldName = worldName,
            min = min,
            max = max,
            ownerUuid = owner.uniqueId,
            ownerName = owner.name,
        )
        plots[name.lowercase()] = plot
        save()
        return null
    }

    fun delete(name: String): Boolean {
        val removed = plots.remove(name.lowercase()) != null
        if (removed) save()
        return removed
    }

    fun setOwner(plot: Plot, newOwner: Resident): Boolean {
        if (newOwner.town?.name?.equals(plot.townName, ignoreCase = true) != true) {
            return false
        }
        plot.ownerUuid = newOwner.uuid
        plot.ownerName = newOwner.name
        save()
        return true
    }

    /** Call when a resident leaves/kicked — delete plots they own. */
    fun onResidentLeave(uuid: UUID) {
        val toRemove = plots.values.filter { it.ownerUuid == uuid }.map { it.name.lowercase() }
        for (key in toRemove) {
            plots.remove(key)
        }
        if (toRemove.isNotEmpty()) save()
    }

    /**
     * @return null if no plot at location (caller uses town perms).
     *         true if plot allows action, false if plot denies.
     */
    fun allows(player: Player, block: Block, action: PlotAction): Boolean? {
        val plot = findAt(block) ?: return null
        return allows(player, plot, action)
    }

    fun allows(player: Player, plot: Plot, action: PlotAction): Boolean {
        // War / occupation: enemies who captured this land ignore plots
        if (shouldSkipPlotForWar(player, plot)) {
            return true
        }

        val resident = Nodes.getResident(player)
        val plotTown = Nodes.getTownFromName(plot.townName)

        // Any matching ALLOW for this action or ALL → allow
        // Matching DENY does not override a more specific allow; we only need "any allow"
        for (rule in plot.rules) {
            if (rule.action != action && rule.action != PlotAction.ALL) continue
            if (!rule.allow) continue
            if (subjectMatches(rule, player, resident, plot, plotTown)) {
                return true
            }
        }
        return false
    }

    private fun shouldSkipPlotForWar(player: Player, plot: Plot): Boolean {
        val resident = Nodes.getResident(player) ?: return false
        val playerTown = resident.town ?: return false

        val territory = Nodes.getTerritoryFromBlock(plot.min.x, plot.min.z)
            ?: return false

        if (Warzone.isAttackableWarzoneTerritory(territory.town)) {
            return true
        }

        if (phonon.nodes.war.Raid.isLiveTerritory(territory)) {
            return true
        }

        val occupier = territory.occupier
        if (occupier != null && playerTown === occupier) {
            return true
        }

        try {
            if (FlagWar.enabled) {
                val chunk = Nodes.getTerritoryChunkFromBlock(plot.min.x, plot.min.z)
                if (chunk != null) {
                    // Fallback: player's town is at war with the plot's town
                    val plotTown = territory.town
                    if (plotTown != null &&
                        playerTown !== plotTown &&
                        (playerTown.enemies.contains(plotTown) || plotTown.enemies.contains(playerTown))
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return false
    }

    private fun subjectMatches(
        rule: PlotRule,
        player: Player,
        resident: Resident?,
        plot: Plot,
        plotTown: Town?,
    ): Boolean = when (rule.subject) {
        PlotSubjectType.PERSON ->
            rule.personUuid == player.uniqueId

        PlotSubjectType.TOWN ->
            resident?.town?.name?.equals(plot.townName, ignoreCase = true) == true

        PlotSubjectType.OTHERTOWN -> {
            val t = resident?.town
            t != null && !t.name.equals(plot.townName, ignoreCase = true)
        }

        PlotSubjectType.NATION -> {
            val tn = plotTown?.nation
            tn != null && resident?.nation === tn
        }

        PlotSubjectType.ALLY -> {
            val t = resident?.town
            plotTown != null && t != null && plotTown.allies.contains(t)
        }

        PlotSubjectType.OUTSIDER ->
            resident?.town == null

        PlotSubjectType.OFFICERS -> {
            val t = resident?.town
            plotTown != null &&
                t != null &&
                t === plotTown &&
                (resident === plotTown.leader || plotTown.officers.contains(resident))
        }
    }

    /** Entire selection must lie in [town] territories. */
    fun selectionInTown(town: Town, worldName: String, min: BlockPos, max: BlockPos): Boolean {
        // Sample corners + center; for strictness walk xz step 16 or every block if small
        val world = Bukkit.getWorld(worldName) ?: return false
        val step = 1
        var x = min.x
        while (x <= max.x) {
            var z = min.z
            while (z <= max.z) {
                val terr = Nodes.getTerritoryFromBlock(x, z)
                if (terr?.town !== town) return false
                z += if (max.z - min.z > 64) 8 else step
            }
            x += if (max.x - min.x > 64) 8 else step
        }
        for (cx in listOf(min.x, max.x)) {
            for (cz in listOf(min.z, max.z)) {
                if (Nodes.getTerritoryFromBlock(cx, cz)?.town !== town) return false
            }
        }
        return true
    }
}

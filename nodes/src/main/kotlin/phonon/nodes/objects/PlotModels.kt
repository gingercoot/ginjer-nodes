package phonon.nodes.objects

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import java.util.UUID

/** Actions a plot can allow/deny. */
enum class PlotAction {
    BUILD,
    DESTROY,
    INTERACT,
    USE_ITEMS, // "item"
    VEHICLES,
    ALL,
    ;

    companion object {
        fun fromString(s: String): PlotAction? = when (s.lowercase()) {
            "build" -> BUILD
            "destroy" -> DESTROY
            "interact" -> INTERACT
            "item", "items", "use_items", "useitems" -> USE_ITEMS
            "vehicle", "vehicles" -> VEHICLES
            "all" -> ALL
            else -> null
        }

        fun parseList(s: String): List<PlotAction>? {
            if (s.equals("all", ignoreCase = true)) return listOf(ALL)
            val a = fromString(s) ?: return null
            return listOf(a)
        }
    }
}

/** Who a permission rule applies to. */
enum class PlotSubjectType {
    PERSON,
    TOWN,
    OTHERTOWN,
    NATION,
    ALLY,
    OUTSIDER,
    OFFICERS,
    ;

    companion object {
        fun fromString(s: String): PlotSubjectType? = when (s.lowercase()) {
            "person", "player" -> PERSON
            "town" -> TOWN
            "othertown", "other_town" -> OTHERTOWN
            "nation" -> NATION
            "ally", "allies" -> ALLY
            "outsider", "outsiders" -> OUTSIDER
            "officer", "officers" -> OFFICERS
            else -> null
        }
    }
}

data class PlotRule(
    val subject: PlotSubjectType,
    /** Required when subject == PERSON (player UUID). */
    val personUuid: UUID? = null,
    val personName: String? = null,
    val action: PlotAction,
    val allow: Boolean,
)

data class BlockPos(val x: Int, val y: Int, val z: Int)

data class Plot(
    val name: String,
    val townName: String,
    val worldName: String,
    val min: BlockPos,
    val max: BlockPos,
    var ownerUuid: UUID,
    var ownerName: String,
    val rules: MutableList<PlotRule> = mutableListOf(),
) {
    fun contains(world: World, x: Int, y: Int, z: Int): Boolean {
        if (world.name != worldName) return false
        return x in min.x..max.x && y in min.y..max.y && z in min.z..max.z
    }

    fun contains(block: Block): Boolean = contains(block.world, block.x, block.y, block.z)

    fun contains(loc: Location): Boolean {
        val w = loc.world ?: return false
        return contains(w, loc.blockX, loc.blockY, loc.blockZ)
    }

    fun intersects(otherWorld: String, omin: BlockPos, omax: BlockPos): Boolean {
        if (worldName != otherWorld) return false
        return min.x <= omax.x &&
            max.x >= omin.x &&
            min.y <= omax.y &&
            max.y >= omin.y &&
            min.z <= omax.z &&
            max.z >= omin.z
    }
}

/** Two-corner selection while player is in select mode. */
data class PlotSelection(
    var pos1: Location? = null,
    var pos2: Location? = null,
) {
    fun isComplete(): Boolean = pos1 != null && pos2 != null

    fun bounds(): Triple<String, BlockPos, BlockPos>? {
        val a = pos1 ?: return null
        val b = pos2 ?: return null
        if (a.world?.name != b.world?.name) return null
        val world = a.world!!.name
        val min = BlockPos(
            minOf(a.blockX, b.blockX),
            minOf(a.blockY, b.blockY),
            minOf(a.blockZ, b.blockZ),
        )
        val max = BlockPos(
            maxOf(a.blockX, b.blockX),
            maxOf(a.blockY, b.blockY),
            maxOf(a.blockZ, b.blockZ),
        )
        return Triple(world, min, max)
    }
}

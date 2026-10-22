/**
 * Town
 *
 */

package phonon.nodes.objects

import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.constants.PermissionsGroup
import phonon.nodes.constants.TownPermissions
import phonon.nodes.serdes.SaveState
import phonon.nodes.utils.Color
import phonon.nodes.utils.EnumArrayMap
import phonon.nodes.utils.createEnumArrayMap
import phonon.nodes.utils.string.stringArrayFromSet
import phonon.nodes.utils.string.stringMapFromMap
import phonon.nodes.vassal.Vassals
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@JvmInline
value class TownId(private val id: UUID) {
    override fun toString(): String = id.toString()
    fun toUUID(): UUID = id
}

private var townNametagIdCounter: Int = 0

public class Town(
    val uuid: UUID,
    var name: String,
    var home: TerritoryId, // main territory owned by town
    var leader: Resident?,
    var spawnpoint: Location,
) {
    // town numeric id, not saved, can change on reload
    // used by nametag scoreboard system (cannot use name because 16 char team limit)
    val townNametagId: Int

    val residents: HashSet<Resident> = hashSetOf()

    val officers: HashSet<Resident> = hashSetOf()

    // Includes annexed territories
    val territories: HashSet<TerritoryId> = hashSetOf(home)

    // Annexed territories (config decides if they count towards claims)
    val annexed: HashSet<TerritoryId> = hashSetOf()

    // territories captured by town (but not annexed)
    val captured: HashSet<TerritoryId> = hashSetOf()

    val outposts: HashMap<String, TownOutpost> = hashMapOf()

    var nation: Nation? = null

    val allies: HashSet<Town> = hashSetOf()
    val enemies: HashSet<Town> = hashSetOf()

    // Must be a Set to satisfy the Bukkit interface used in Chat.kt
    val playersOnline: MutableSet<Player> = mutableSetOf()

    var claimsUsed: Int = 0
    var claimsMax: Int = 0 // calculated total claim power
    var claimsBonus: Int = 0 // manual adjust add/subtract claim power
    var claimsPenalty: Int = 0 // subtracted from max power, decay over time
    var claimsPenaltyTime: Long = 0 // time progress until penalty reduced
    var claimsAnnexed: Int = 0 // claims penalty from territories that were annexed

    var isOverClaimsMax: Boolean = false

    val income: IncomeInventory = IncomeInventory()

    val permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>

    // Chests locked to leader, officers and trusted residents
    val protectedBlocks: HashSet<Block> = hashSetOf()

    var color: Color

    var nametagTown: String // nametag seen by players in town
    var nametagNation: String // nametag seen by players in same nation
    var nametagNeutral: String // nametag seen by players neutral to this town
    var nametagAlly: String // nametag seen by allies of this town
    var nametagEnemy: String // nametag seen by enemies of this town

    var isOpen: Boolean = false

    val applications: HashMap<Resident, BukkitTask> = hashMapOf()

    var moveHomeCooldown: Long = 0L

    private var saveState: TownSaveState

    @Suppress("PropertyName")
    private var _needsUpdate = false

    init {
        this.townNametagId = townNametagIdCounter
        townNametagIdCounter += 1

        this.permissions = createEnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>({ _ -> EnumSet.of(PermissionsGroup.TOWN) })

        if (leader != null) {
            this.residents.add(leader!!)

            if (this.leader!!.player()?.isOnline == true) {
                this.playersOnline.add(leader!!.player()!!)
            }
        }

        val random = ThreadLocalRandom.current()
        this.color = Color(
            random.nextInt(256),
            random.nextInt(256),
            random.nextInt(256),
        )

        this.nametagTown = "${ChatColor.GREEN}[${this.name}] "
        this.nametagNation = "${ChatColor.DARK_GREEN}[${this.name}] "
        this.nametagNeutral = "${ChatColor.GOLD}[${this.name}] "
        this.nametagAlly = "${ChatColor.DARK_AQUA}[${this.name}] "
        this.nametagEnemy = "${ChatColor.RED}[${this.name}] "

        this.saveState = TownSaveState(this)
    }

    public override fun hashCode(): Int = this.uuid.hashCode()

    public fun updateNametags() {
        this.nametagTown = "${ChatColor.GREEN}[${this.name}] "
        this.nametagNation = "${ChatColor.DARK_GREEN}[${this.name}] "
        this.nametagNeutral = "${ChatColor.GOLD}[${this.name}] "
        this.nametagAlly = "${ChatColor.DARK_AQUA}[${this.name}] "
        this.nametagEnemy = "${ChatColor.RED}[${this.name}] "
    }

    public fun printInfo(sender: CommandSender) {
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"
        val leader = this.leader?.name ?: "${ChatColor.GRAY}None"
        val officers = if (this.officers.size > 0) {
            this.officers.map { r -> r.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val residents = if (this.residents.size > 0) {
            this.residents.map { r -> r.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val allies = if (this.allies.size > 0) {
            this.allies.map { it -> it.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }
        val enemies = if (this.enemies.size > 0) {
            this.enemies.map { it -> it.name }.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }

        val claimsMaxColor = if (claimsPenalty > 0) {
            "${ChatColor.RED}"
        } else {
            ""
        }
        val claimsUsedColor = if (isOverClaimsMax) {
            "${ChatColor.DARK_RED}"
        } else {
            ""
        }

        Message.print(sender, "${ChatColor.BOLD}Town ${this.name}:")
        Message.print(sender, "- Home${ChatColor.WHITE}: Territory (id = ${this.home})")
        Message.print(sender, "- Territories${ChatColor.WHITE}: ${this.territories.size}")
        Message.print(sender, "- Claim Power${ChatColor.WHITE}: ${claimsUsedColor}${this.claimsUsed}/${claimsMaxColor}${this.claimsMax}")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
        Vassals.overlordOf(this)?.let { overlord ->
            Message.print(sender, "- Vassal of${ChatColor.WHITE}: ${overlord.name} (pays ${Vassals.percent(Config.vassalTributeRate)} tribute)")
        }
        val vassals = Vassals.vassalsOf(this)
        if (vassals.isNotEmpty()) {
            Message.print(sender, "- Vassals${ChatColor.WHITE}: ${vassals.joinToString(", ") { it.name }}")
        }
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Officers[${this.officers.size}]${ChatColor.WHITE}: $officers")
        Message.print(sender, "- Residents[${this.residents.size}]${ChatColor.WHITE}: $residents")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    public class TownSaveState(t: Town) : SaveState {
        public val uuid = t.uuid
        public val name = t.name
        public val leader = t.leader?.uuid
        public val home = t.home
        public val spawnpoint = doubleArrayOf(t.spawnpoint.x, t.spawnpoint.y, t.spawnpoint.z)
        public val color = intArrayOf(t.color.r, t.color.g, t.color.b)
        public val permissions = t.permissions.copyOf()
        public val residents = t.residents.map { x -> x.uuid }
        public val officers = t.officers.map { x -> x.uuid }
        public val claimsBonus = t.claimsBonus
        public val claimsAnnexed = t.claimsAnnexed
        public val claimsPenalty = t.claimsPenalty
        public val claimsPenaltyTime = t.claimsPenaltyTime
        public val territories = t.territories.toList()
        public val annexed = t.annexed.toList()
        public val captured = t.captured.toList()
        public val outposts: HashMap<String, TownOutpost> = HashMap(t.outposts)
        public val allies = t.allies.map { x -> x.name }
        public val enemies = t.enemies.map { x -> x.name }
        public val income = t.income.storage.clone()
        public val incomeEgg = t.income.storageSpawnEgg.clone()
        public val isOpen = t.isOpen
        public val protectedBlocks: HashSet<Block> = HashSet(t.protectedBlocks)
        public val moveHomeCooldown = t.moveHomeCooldown

        public override var jsonString: String? = null

        public override fun createJsonString(): String {
            val leaderUUID = if (this.leader != null) "\"${this.leader}\"" else null
            val officers = this.officers.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val residents = this.residents.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val territories = this.territories.joinToString(",", "[", "]")
            val annexed = this.annexed.joinToString(",", "[", "]")
            val captured = this.captured.joinToString(",", "[", "]")
            val allies = this.allies.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val enemies = this.enemies.asSequence().map { x -> "\"$x\"" }.joinToString(",", "[", "]")
            val income = stringMapFromMap<Material, Int>(
                this.income,
                { k -> "\"$k\"" },
                { v -> "$v" },
            )
            val incomeSpawnEgg = stringMapFromMap<EntityType, Int>(
                this.incomeEgg,
                { k -> "\"$k\"" },
                { v -> "$v" },
            )

            val col = this.color
            val spawn = "[${this.spawnpoint[0]},${this.spawnpoint[1]},${this.spawnpoint[2]}]"

            val permissions = permissionsToJsonString(this.permissions)

            val jsonStrong = (
                "{" +
                    "\"uuid\":\"${this.uuid}\"," +
                    "\"leader\":$leaderUUID," +
                    "\"home\":${this.home}," +
                    "\"spawn\":$spawn," +
                    "\"color\":[${col[0]},${col[1]},${col[2]}]," +
                    "\"perms\":$permissions," +
                    "\"residents\":$residents," +
                    "\"officers\":$officers," +
                    "\"claimsBonus\":${this.claimsBonus}," +
                    "\"claimsAnnexed\":${this.claimsAnnexed}," +
                    "\"claimsPenalty\":[${this.claimsPenalty},${this.claimsPenaltyTime}]," +
                    "\"territories\":$territories," +
                    "\"annexed\":$annexed," +
                    "\"captured\":$captured," +
                    "\"outpost\":${townOutpostsToJsonString(this.outposts)}," +
                    "\"allies\":$allies," +
                    "\"enemies\":$enemies," +
                    "\"income\":$income," +
                    "\"incomeEgg\":$incomeSpawnEgg," +
                    "\"open\":${this.isOpen}," +
                    "\"protect\":${blocksToJsonString(this.protectedBlocks)}," +
                    "\"homeCool\":${this.moveHomeCooldown}" +
                    "}"
                )

            return jsonStrong
        }
    }

    public fun needsUpdate() {
        this._needsUpdate = true
    }

    // Returns the memoized snapshot unless needsUpdate() was called
    public fun getSaveState(): TownSaveState {
        if (this._needsUpdate) {
            this.saveState = TownSaveState(this)
            this._needsUpdate = false
        }
        return this.saveState
    }
}

private fun permissionsToJsonString(permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>>): String {
    val str = StringBuilder()

    str.append("{")

    var index: Int = 0
    for (type in enumValues<TownPermissions>()) {
        val groups = permissions[type]
        str.append("\"${type}\":")
        str.append(stringArrayFromSet<PermissionsGroup>(groups, { g -> "${g.ordinal}" }))
        if (index < permissions.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("}")

    val s = str.toString()
    return s
}

private fun blocksToJsonString(blocks: HashSet<Block>): String {
    val str = StringBuilder()
    str.append("[")

    var index: Int = 0
    for (block in blocks) {
        str.append("[${block.x},${block.y},${block.z}]")
        if (index < blocks.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("]")

    val s = str.toString()
    return s
}

private fun townOutpostsToJsonString(outposts: HashMap<String, TownOutpost>): String {
    val str = StringBuilder()
    str.append("{")

    var index: Int = 0
    for ((name, outpost) in outposts) {
        str.append("\"${name}\":${outpost.toJsonString()}")
        if (index < outposts.size - 1) {
            str.append(",")
        }
        index += 1
    }

    str.append("}")

    val s = str.toString()
    return s
}

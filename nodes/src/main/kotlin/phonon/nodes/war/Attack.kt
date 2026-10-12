/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - progress is driven by FlagWar.attackTickTask (single global ticker)
 */

package phonon.nodes.war

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.boss.BossBar
import org.bukkit.entity.ArmorStand
import org.bukkit.persistence.PersistentDataType
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.nms.createArmorStandNamePacket
import phonon.nodes.nms.sendPacket
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Town
import phonon.nodes.objects.townNametagViewedByPlayer
import java.util.UUID

public class Attack(
    val attacker: UUID,
    val town: Town,
    val coord: Coord,
    val flagBase: Block,
    val flagBlock: Block,
    val flagTorch: Block,
    val skyBeaconColorBlocks: List<Block>,
    val skyBeaconWireframeBlocks: List<Block>,
    val progressBar: BossBar,
    val attackTime: Long,
    var progress: Long,
) {
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255

    /** Set true when finishing/cancelling so global ticker skips this attack. */
    @Volatile
    var cancelled: Boolean = false

    val armorstand: AttackArmorStand? =
        if (Config.flagHologramsEnabled) {
            AttackArmorStand(this, flagBase.world, flagBase.location.clone().add(0.5, 1.75, 0.5))
        } else {
            null
        }

    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.x
        val flagY = flagBase.y
        val flagZ = flagBase.z

        this.noBuildXMin = flagX - Config.flagNoBuildDistance
        this.noBuildXMax = flagX + Config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Config.flagNoBuildDistance
        this.noBuildYMin = flagY + Config.flagNoBuildYOffset

        val progressNormalized: Double = this.progress.toDouble() / this.attackTime.toDouble()
        this.progressBar.setProgress(progressNormalized)

        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase,
        )

        try {
            this.armorstand?.sendPackets()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val jsonStringBufferSize = this.jsonStringBase.capacity() + 20
        this.jsonString = StringBuilder(jsonStringBufferSize)
    }

    public fun cancel() {
        if (this.cancelled) return
        this.cancelled = true

        val attack = this
        Bukkit.getScheduler().runTask(
            Nodes.plugin!!,
            Runnable {
                FlagWar.cancelAttack(attack)
            },
        )
    }

    public fun toJson(): StringBuilder {
        this.jsonString.setLength(0)
        this.jsonString.append(this.jsonStringBase)
        this.jsonString.append("\"p\":${this.progress}")
        this.jsonString.append("}")
        return this.jsonString
    }
}

private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: Block,
): StringBuilder {
    val s = StringBuilder()
    s.append("{")
    s.append("\"id\":\"$attacker\",")
    s.append("\"c\":[${coord.x},${coord.z}],")
    s.append("\"b\":[${block.x},${block.y},${block.z}],")
    return s
}

public class AttackArmorStand(
    val attack: Attack,
    val world: World,
    val loc: Location,
    val maxViewDistance: Int = 3,
) {
    var townNameArmorstand = createArmorStand(world, loc)
    var progressArmorstand = createArmorStand(world, loc.clone().add(0.0, -0.25, 0.0))

    val minViewChunkX: Int
    val maxViewChunkX: Int
    val minViewChunkZ: Int
    val maxViewChunkZ: Int

    init {
        val chunk = this.loc.chunk
        val chunkX = chunk.x
        val chunkZ = chunk.z
        minViewChunkX = chunkX - this.maxViewDistance
        maxViewChunkX = chunkX + this.maxViewDistance
        minViewChunkZ = chunkZ - this.maxViewDistance
        maxViewChunkZ = chunkZ + this.maxViewDistance
    }

    public fun remove() {
        try {
            this.townNameArmorstand.remove()
        } catch (_: Exception) {
        }
        try {
            this.progressArmorstand.remove()
        } catch (_: Exception) {
        }
    }

    public fun isValid(): Boolean = this.townNameArmorstand.isValid && this.progressArmorstand.isValid

    public fun respawn() {
        this.remove()
        this.townNameArmorstand = createArmorStand(this.world, this.loc)
        this.progressArmorstand = createArmorStand(this.world, this.loc.clone().add(0.0, -0.25, 0.0))
    }

    public fun sendPackets() {
        if (!this.world.isChunkLoaded(this.loc.chunk)) {
            return
        }

        for (player in world.players) {
            val playerChunk = player.location.chunk
            val playerChunkX = playerChunk.x
            val playerChunkZ = playerChunk.z

            if (playerChunkX < minViewChunkX ||
                playerChunkX > maxViewChunkX ||
                playerChunkZ < minViewChunkZ ||
                playerChunkZ > maxViewChunkZ
            ) {
                continue
            }

            val label = townNametagViewedByPlayer(attack.town, player).dropLast(1)
            val townNamePacket = this.townNameArmorstand.createArmorStandNamePacket(label)
            player.sendPacket(townNamePacket)

            val remainingSeconds = (attack.attackTime - attack.progress) / 20
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            val colorCode = townNametagViewedByPlayer(attack.town, player).take(2)
            val formattedProgress = "$colorCode[$minutes:${seconds.toString().padStart(2, '0')}]"
            val progressPacket = this.progressArmorstand.createArmorStandNamePacket(formattedProgress)
            player.sendPacket(progressPacket)
        }
    }
}

internal val NODES_ARMORSTAND_KEY = NamespacedKey("nodes", "armorstand")

private fun createArmorStand(
    world: World,
    loc: Location,
): ArmorStand {
    val armorstand = world.spawn(loc, ArmorStand::class.java)
    armorstand.isSmall = true
    armorstand.setGravity(false)
    armorstand.persistentDataContainer.set(NODES_ARMORSTAND_KEY, PersistentDataType.INTEGER, 0)
    return armorstand
}

/**
 * 1.13+ Player town nametag
 *
 * NOTE: this conflicts with any other plugin doing nametag prefix/suffix (e.g. TAB)
 * Make sure all other plugins that affect prefix/suffix are disabled
 *
 * TODO: make sure name is not too long (may cause bukkit error)
 */

package phonon.nodes.objects

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.PlayerScoreboardManager

public fun townNametagViewedByPlayer(town: Town, viewer: Player): String {
    val otherTown = Nodes.getResident(viewer)?.town
    if (otherTown !== null) {
        if (town === otherTown) {
            return town.nametagTown
        } else if (town.nation !== null && town.nation === otherTown.nation) {
            return town.nametagNation
        } else if (town.allies.contains(otherTown)) {
            return town.nametagAlly
        } else if (town.enemies.contains(otherTown)) {
            return town.nametagEnemy
        }
    }

    return town.nametagNeutral
}

public object Nametag {

    private var task: BukkitTask? = null

    private var updateLock: Boolean = false

    /**
     * Initialization:
     * 1. set nametag refresh scheduler
     * 2. set packet listener to override entity spawn events
     *    for nametag armor stands (so they don't teleport to random locations)
     */
    public fun start(plugin: Plugin, period: Long) {
        if (this.task !== null) {
            return
        }

        val task = object : Runnable {
            public override fun run() {
                Nametag.pipelinedUpdateAllText()
            }
        }

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, period, period)
    }

    public fun stop() {
        val task = this.task
        if (task === null) {
            return
        }

        task.cancel()
        this.task = null
    }

    /** No-op, kept for API compatibility. */
    public fun create(player: Player): Nametag? = null

    /** No-op, kept for API compatibility. */
    public fun destroy(player: Player) {}

    /** No-op, kept for API compatibility. */
    public fun clear() {}

    /** No-op, kept for API compatibility. */
    public fun visibility(player: Player, visible: Boolean) {}

    public fun updateTextForPlayer(player: Player) {
        val scoreboard = PlayerScoreboardManager.getScoreboard(player.getUniqueId())

        for (team in scoreboard.getTeams()) {
            if (team.name === "player") {
                continue
            } else {
                team.unregister()
            }
        }

        for (town in Nodes.towns.values) {
            val townNametagId = "t${town.townNametagId}"
            val teamTown = scoreboard.registerNewTeam(townNametagId)
            teamTown.setPrefix(townNametagViewedByPlayer(town, player))
            teamTown.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS)
        }

        for (otherPlayer in Bukkit.getOnlinePlayers()) {
            val town = Nodes.getTownFromPlayer(otherPlayer)
            if (player === otherPlayer) {
                val team = scoreboard.getTeam("player") ?: scoreboard.registerNewTeam("player")
                if (town !== null) {
                    team.setPrefix(townNametagViewedByPlayer(town, player))
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS)
                } else {
                    team.setPrefix("")
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
                }
                if (!team.hasEntry(player.getName())) {
                    team.addEntry(player.getName())
                }
            } else {
                if (town !== null) {
                    val townNametagId = "t${town.townNametagId}"
                    val team = scoreboard.getTeam(townNametagId)
                    if (team !== null) {
                        team.addEntry(otherPlayer.getName())
                    }
                }
            }
        }

        player.setScoreboard(scoreboard)
    }

    public fun updateAllText() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        for (player in onlinePlayers) {
            updateTextForPlayer(player)
        }
    }

    /**
     * Update all player nametags using a pipeline:
     * - only update subset of online players each time
     */
    public fun pipelinedUpdateAllText() {
        if (Nametag.updateLock == true) {
            return
        }

        val onlinePlayers = Bukkit.getOnlinePlayers().toList()
        if (onlinePlayers.size <= 0) {
            return
        }

        Nametag.updateLock = true

        val updatesPerTick: Int = Math.max(1, Math.ceil(onlinePlayers.size.toDouble() / Config.nametagPipelineTicks.toDouble()).toInt())
        var index = 0
        var tickOffset = 0L

        while (index < onlinePlayers.size) {
            val idxStart = index
            val idxEnd = Math.min(index + updatesPerTick, onlinePlayers.size)
            Bukkit.getScheduler().runTaskLater(
                Nodes.plugin!!,
                object : Runnable {
                    override fun run() {
                        for (i in idxStart until idxEnd) {
                            val player = onlinePlayers[i]
                            if (player.isOnline()) {
                                Nametag.updateTextForPlayer(player)
                            }
                        }
                    }
                },
                tickOffset,
            )

            index += updatesPerTick
            tickOffset += 1L
        }

        Bukkit.getScheduler().runTaskLater(
            Nodes.plugin!!,
            object : Runnable {
                override fun run() {
                    Nametag.updateLock = false
                }
            },
            tickOffset,
        )
    }
}

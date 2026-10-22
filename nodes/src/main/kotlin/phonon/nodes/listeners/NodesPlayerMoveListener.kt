package phonon.nodes.listeners

import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.Nodes.getTownFromPlayer
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Resident
import phonon.nodes.objects.Territory
import phonon.nodes.objects.Town

public class NodesPlayerMoveListener : Listener {

    @EventHandler
    public fun onPlayerMove(event: PlayerMoveEvent) {
        val fromX = event.getFrom().getBlockX()
        val fromY = event.getFrom().getBlockY()
        val fromZ = event.getFrom().getBlockZ()
        val toX = event.getTo().getBlockX()
        val toY = event.getTo().getBlockY()
        val toZ = event.getTo().getBlockZ()
        if (fromX == toX && fromZ == toZ && fromY == toY) {
            return
        }

        val player = event.player
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return
        }

        if (resident?.teleportThread != null) {
            resident.teleportThread!!.cancel()
            resident.teleportThread = null
            Message.error(event.player, "You moved, teleport cancelled")

            if (resident.isTeleportingToOutpost == true) {
                Message.error(event.player, "Refunding outpost teleport items")

                val world = player.world
                val location = player.location
                val inventory = player.getInventory()

                for ((material, amount) in Config.outpostTeleportCost) {
                    val items = ItemStack(material, amount)
                    val leftover = inventory.addItem(items)

                    for (items in leftover.values) {
                        world.dropItem(location, items)
                    }
                }

                resident.isTeleportingToOutpost = false
            }

            if (resident.isTeleportingToNationTown == true) {
                Message.error(event.player, "Refunding teleport items")

                val world = player.world
                val location = player.location
                val inventory = player.getInventory()

                for ((material, amount) in Config.nationTownTeleportCost) {
                    val items = ItemStack(material, amount)
                    val leftover = inventory.addItem(items)

                    for (items in leftover.values) {
                        world.dropItem(location, items)
                    }
                }

                resident.isTeleportingToNationTown = false
            }
        }

        val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
        val toCoord = Coord.fromBlockCoords(toX, toZ)

        if (fromCoord != toCoord) {
            onPlayerMoveChunk(event.player, resident, fromCoord, toCoord)
        }
    }

    @EventHandler
    public fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val fromX = event.getFrom().getBlockX()
        val fromY = event.getFrom().getBlockY()
        val fromZ = event.getFrom().getBlockZ()
        val toX = event.getTo().getBlockX()
        val toY = event.getTo().getBlockY()
        val toZ = event.getTo().getBlockZ()
        if (fromX == toX && fromZ == toZ && fromY == toY) {
            return
        }

        val resident = Nodes.getResident(event.player)
        if (resident == null) {
            return
        }

        val fromCoord = Coord.fromBlockCoords(fromX, fromZ)
        val toCoord = Coord.fromBlockCoords(toX, toZ)

        if (fromCoord != toCoord) {
            onPlayerMoveChunk(event.player, resident, fromCoord, toCoord)
        }
    }

    public fun onPlayerMoveChunk(player: Player, resident: Resident, fromCoord: Coord, toCoord: Coord) {
        val fromTerritory = Nodes.getTerritoryFromCoord(fromCoord)
        val toTerritory = Nodes.getTerritoryFromCoord(toCoord)

        if (fromTerritory != null && toTerritory != null) {
            val toTown = toTerritory.town
            val fromTown = fromTerritory.town
            if (toTerritory.name != fromTerritory.name) {
                if (toTown != null) {
                    printTownMessage(player, resident, toTown, toTerritory)
                } else {
                    Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
                }
            } else if (fromTown !== null && toTown !== null) {
                if (toTown !== fromTown || fromTerritory.occupier !== toTerritory.occupier) {
                    printTownMessage(player, resident, toTown, toTerritory)
                }
            } else if (fromTown !== null && toTown === null) {
                if (toTerritory.name != fromTerritory.name) {
                    Message.announcement(player, "${ChatColor.GRAY}${toTerritory.name}")
                } else {
                    Message.announcement(player, "${ChatColor.GRAY}Wilderness")
                }
            } else if (toTown !== null) {
                printTownMessage(player, resident, toTown, toTerritory)
            }
        }

        resident.updateMinimap(toCoord)

        if (player.gameMode in listOf(GameMode.CREATIVE, GameMode.SPECTATOR)) return

        val playerTown = getTownFromPlayer(player)

        if (player.allowFlight && toTerritory?.town != playerTown) {
            player.allowFlight = false
            // Slow falling so they don't take fall damage
            player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false, false))
            Message.print(player, "You are no longer in your town, disabling flight")
        }
    }
}

private fun printTownMessage(player: Player, resident: Resident, toTown: Town, toTerritory: Territory) {
    val residentTown = resident.town
    val territoryOccupier = toTerritory.occupier

    val territoryName = if (toTerritory.name != "") {
        "${toTerritory.name} (${toTown.name})"
    } else {
        "${toTown.name}"
    }

    var territoryNameColor = ""
    var ownerStatus = ""

    if (residentTown !== null) {
        if (toTown === residentTown) {
            territoryNameColor = "${ChatColor.DARK_GREEN}"
        } else if (residentTown.enemies.contains(toTown)) {
            territoryNameColor = "${ChatColor.DARK_RED}"
        } else {
            territoryNameColor = "${ChatColor.DARK_AQUA}"
        }

        if (territoryOccupier !== null) {
            if (territoryOccupier === residentTown) {
                ownerStatus = " ${ChatColor.DARK_GREEN}(Captured)"
            } else if (toTown === residentTown) {
                ownerStatus = " ${ChatColor.DARK_RED}(Occupied)"
            } else {
                ownerStatus = " ${ChatColor.DARK_AQUA}(Occupied)"
            }
        }
    } else {
        territoryNameColor = "${ChatColor.DARK_AQUA}"
        if (territoryOccupier !== null) {
            ownerStatus = " (Occupied)"
        }
    }

    Message.announcement(player, "${territoryNameColor}${territoryName}$ownerStatus")
}

/**
 * WorldMap Renderer
 *
 * Manager to render territories from the world into
 * strings to display in chat or other objects.
 *
 * Renders world chunk coords into ascii characters.
 */

package phonon.nodes

import org.bukkit.ChatColor
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Resident
// All glyphs must render at the same width
private val SOLID = "\u2588" // full solid block
private val SHADE0 = "\u2592" // medium shade
private val SHADE1 = "\u2593" // dark shade
private val SHADE2 = "\u2588" // full solid block
private val HOME = SHADE2
private val CORE = "\u256B" // core chunk H
private val CONQUERED0 = "\u255E" // captured chunk flag symbol
private val CONQUERED1 = "\u255F" // other chunk flag symbol
private val PLAYER_TOKEN = "\u253C" // player token
private val PLAYER_IN_OCCUPIED_TOKEN = "\u256C" // player token in occupied chunk
private val PORT_TOKEN = "${ChatColor.BOLD}\u2693" // port anchor symbol, bold so it has the same width as other chars

private val EMPTY = "${ChatColor.BLACK}$SHADE0"

private val PLAYER_IN_EMPTY = "${ChatColor.WHITE}${PLAYER_TOKEN}"

private val CORE_UNCLAIMED = "${ChatColor.GRAY}$CORE"
private val CORE_TOWN = "${ChatColor.GREEN}$CORE"
private val CORE_NEUTRAL = "${ChatColor.YELLOW}$CORE"
private val CORE_ENEMY = "${ChatColor.RED}$CORE"
private val CORE_ALLY = "${ChatColor.AQUA}$CORE"
private val CORE_CAPTURED = "${ChatColor.GREEN}$CORE"
private val CORE_OCCUPIED = "${ChatColor.RED}$CORE"

private val HOME_TOWN = "${ChatColor.GREEN}$HOME"
private val HOME_NEUTRAL = "${ChatColor.YELLOW}$HOME"
private val HOME_ALLY = "${ChatColor.AQUA}$HOME"
private val HOME_ENEMY = "${ChatColor.RED}$HOME"

private val OCCUPIED_TOWN = "${ChatColor.GREEN}$CONQUERED0"
private val OCCUPIED_NEUTRAL = "${ChatColor.YELLOW}$CONQUERED0"
private val OCCUPIED_ALLY = "${ChatColor.AQUA}$CONQUERED0"
private val OCCUPIED_ENEMY = "${ChatColor.RED}$CONQUERED0"

private val CAPTURE_TOWN = "${ChatColor.GREEN}${ChatColor.BOLD}⚑"
private val CAPTURE_ALLY = "${ChatColor.AQUA}${ChatColor.BOLD}⚑"
private val CAPTURE_NEUTRAL = "${ChatColor.YELLOW}${ChatColor.BOLD}⚑"
private val CAPTURE_ENEMY = "${ChatColor.RED}${ChatColor.BOLD}⚑"

// territory colors: maps territory color id -> color
// territories have color assigned to them during
// map generation from nodes editor

private val COLOR_UNCLAIMED: Array<String> = arrayOf(
    "${ChatColor.GRAY}$SHADE0",
    "${ChatColor.GRAY}$SHADE1",
    "${ChatColor.DARK_GRAY}$SHADE0",
    "${ChatColor.DARK_GRAY}$SHADE1",
    "${ChatColor.GRAY}$SHADE2",
    "${ChatColor.DARK_GRAY}$SHADE2",
)

private val COLOR_TOWN: Array<String> = arrayOf(
    "${ChatColor.GREEN}$SHADE0",
    "${ChatColor.GREEN}$SHADE1",
    "${ChatColor.DARK_GREEN}$SHADE0",
    "${ChatColor.DARK_GREEN}$SHADE1",
    "${ChatColor.GREEN}$SHADE2",
    "${ChatColor.DARK_GREEN}$SHADE2",
)

private val COLOR_NEUTRAL: Array<String> = arrayOf(
    "${ChatColor.YELLOW}$SHADE0",
    "${ChatColor.YELLOW}$SHADE1",
    "${ChatColor.GOLD}$SHADE0",
    "${ChatColor.GOLD}$SHADE1",
    "${ChatColor.GOLD}$SHADE2",
    "${ChatColor.GOLD}$SHADE2",
)

private val COLOR_ALLY: Array<String> = arrayOf(
    "${ChatColor.AQUA}$SHADE0",
    "${ChatColor.AQUA}$SHADE1",
    "${ChatColor.DARK_AQUA}$SHADE0",
    "${ChatColor.DARK_AQUA}$SHADE1",
    "${ChatColor.AQUA}$SHADE2",
    "${ChatColor.DARK_AQUA}$SHADE2",
)

private val COLOR_ENEMY: Array<String> = arrayOf(
    "${ChatColor.RED}$SHADE0",
    "${ChatColor.RED}$SHADE1",
    "${ChatColor.DARK_RED}$SHADE0",
    "${ChatColor.DARK_RED}$SHADE1",
    "${ChatColor.DARK_RED}$SHADE2",
    "${ChatColor.DARK_RED}$SHADE2",
)

private val COLOR_OCCUPIED_TOWN: Array<String> = arrayOf(
    "${ChatColor.GREEN}$CONQUERED0",
    "${ChatColor.GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED0",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
    "${ChatColor.GREEN}$CONQUERED1",
    "${ChatColor.DARK_GREEN}$CONQUERED1",
)

private val COLOR_OCCUPIED_NEUTRAL: Array<String> = arrayOf(
    "${ChatColor.YELLOW}$CONQUERED0",
    "${ChatColor.YELLOW}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED0",
    "${ChatColor.GOLD}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED1",
    "${ChatColor.GOLD}$CONQUERED1",
)

private val COLOR_OCCUPIED_ALLY: Array<String> = arrayOf(
    "${ChatColor.AQUA}$CONQUERED0",
    "${ChatColor.AQUA}$CONQUERED1",
    "${ChatColor.DARK_AQUA}$CONQUERED0",
    "${ChatColor.DARK_AQUA}$CONQUERED1",
    "${ChatColor.AQUA}$CONQUERED1",
    "${ChatColor.DARK_AQUA}$CONQUERED1",
)

private val COLOR_OCCUPIED_ENEMY: Array<String> = arrayOf(
    "${ChatColor.RED}$CONQUERED0",
    "${ChatColor.RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED0",
    "${ChatColor.DARK_RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED1",
    "${ChatColor.DARK_RED}$CONQUERED1",
)

private fun getAlternativeColor(c: String): String {
    when (c) {
        "${ChatColor.GRAY}" -> return "${ChatColor.WHITE}"
        "${ChatColor.DARK_GRAY}" -> return "${ChatColor.GRAY}"

        "${ChatColor.GREEN}" -> return "${ChatColor.DARK_GREEN}"
        "${ChatColor.DARK_GREEN}" -> return "${ChatColor.GREEN}"

        "${ChatColor.YELLOW}" -> return "${ChatColor.GOLD}"
        "${ChatColor.GOLD}" -> return "${ChatColor.YELLOW}"

        "${ChatColor.AQUA}" -> return "${ChatColor.DARK_AQUA}"
        "${ChatColor.DARK_AQUA}" -> return "${ChatColor.AQUA}"

        "${ChatColor.RED}" -> return "${ChatColor.DARK_RED}"
        "${ChatColor.DARK_RED}" -> return "${ChatColor.RED}"

        else -> return "${ChatColor.WHITE}"
    }
}

public object WorldMap {

    // Renders one minimap row (constant z), in chunk coords
    public fun renderLine(resident: Resident, playerCoord: Coord, z: Int, xMin: Int, xMax: Int): String {
        var renderedString = ""

        for (x in xMin..xMax) {
            val coord = Coord(x, z)
            val port = Nodes.chunkToPort.get(listOf(x, z))
            val territoryChunk = Nodes.getTerritoryChunkFromCoord(coord)

            val token = if (territoryChunk == null) {
                if (coord == playerCoord) {
                    PLAYER_IN_EMPTY
                } else {
                    EMPTY
                }
            } else {
                var playerToken = PLAYER_TOKEN

                val territory = territoryChunk.territory
                val color = territory.color

                val town = territory.town
                val nation = town?.nation
                val residentTown = resident.town
                val residentNation = residentTown?.nation
                val chunkOccupier = Nodes.territoryChunks.get(coord)?.occupier
                val attacker = territoryChunk.attacker

                val coordToken = if (attacker != null && coord != territory.core) {
                    val attackerNation = attacker.nation
                    if (attacker === residentTown) {
                        CAPTURE_TOWN
                    } else if (residentTown?.enemies?.contains(attacker) == true) {
                        CAPTURE_ENEMY
                    } else if (residentTown?.allies?.contains(attacker) == true || (residentNation !== null && residentNation === attackerNation)) {
                        CAPTURE_ALLY
                    } else {
                        CAPTURE_NEUTRAL
                    }
                } else if (residentTown !== null && chunkOccupier !== null) {
                    playerToken = PLAYER_IN_OCCUPIED_TOKEN

                    val chunkOccupierNation = chunkOccupier.nation
                    if (chunkOccupier === residentTown) {
                        OCCUPIED_TOWN
                    } else if (residentTown.allies.contains(chunkOccupier) || (residentNation !== null && residentNation === chunkOccupierNation)) {
                        OCCUPIED_ALLY
                    } else if (residentTown.enemies.contains(chunkOccupier)) {
                        OCCUPIED_ENEMY
                    } else {
                        OCCUPIED_NEUTRAL
                    }
                } else {
                    if (town != null) {
                        if (coord == territory.core) {
                            if (town === residentTown) {
                                CORE_TOWN
                            } else if (residentTown?.enemies?.contains(town) == true) {
                                CORE_ENEMY
                            } else if (residentTown?.allies?.contains(town) == true || (residentNation !== null && residentNation === nation)) {
                                CORE_ALLY
                            } else {
                                CORE_NEUTRAL
                            }
                        } else {
                            val occupier = territory.occupier
                            if (occupier != null) {
                                val occupierNation = occupier.nation

                                if (occupier === residentTown) {
                                    COLOR_OCCUPIED_TOWN[color]
                                } else if (residentTown?.enemies?.contains(occupier) == true) {
                                    COLOR_OCCUPIED_ENEMY[color]
                                } else if (residentTown?.allies?.contains(occupier) == true || (residentNation !== null && residentNation === occupierNation)) {
                                    COLOR_OCCUPIED_ALLY[color]
                                } else {
                                    COLOR_OCCUPIED_NEUTRAL[color]
                                }
                            } else {
                                if (territory.id == town.home) {
                                    if (town === residentTown) {
                                        HOME_TOWN
                                    } else if (residentTown?.enemies?.contains(town) == true) {
                                        HOME_ENEMY
                                    } else if (residentTown?.allies?.contains(town) == true || (residentNation !== null && residentNation === nation)) {
                                        HOME_ALLY
                                    } else {
                                        HOME_NEUTRAL
                                    }
                                } else {
                                    if (town === residentTown) {
                                        COLOR_TOWN[color]
                                    } else if (residentTown?.enemies?.contains(town) == true) {
                                        COLOR_ENEMY[color]
                                    } else if (residentTown?.allies?.contains(town) == true || (residentNation !== null && residentNation === nation)) {
                                        COLOR_ALLY[color]
                                    } else {
                                        COLOR_NEUTRAL[color]
                                    }
                                }
                            }
                        }
                    } else {
                        if (coord == territory.core) {
                            if (residentTown !== null && territory.occupier === resident.town) {
                                CORE_CAPTURED
                            } else {
                                CORE_UNCLAIMED
                            }
                        } else {
                            COLOR_UNCLAIMED[color]
                        }
                    }
                }

                if (coord == playerCoord) {
                    val color = coordToken.substring(0 until (coordToken.length - 1))
                    if (attacker != null && coord != territory.core) {
                        "$color$playerToken"
                    } else {
                        "${getAlternativeColor(color)}$playerToken"
                    }
                } else if (port?.chunkX == x && port?.chunkZ == z) {
                    val color = coordToken.substring(0 until (coordToken.length - 1))
                    "${getAlternativeColor(color)}$PORT_TOKEN"
                } else {
                    coordToken
                }
            }

            renderedString += token
        }

        return renderedString
    }
}

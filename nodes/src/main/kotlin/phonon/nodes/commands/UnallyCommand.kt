/**
 * Commands for breaking alliance with other towns, nations
 */

package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.ErrorNotAllies
import phonon.nodes.objects.Nation
import phonon.nodes.objects.Town
import phonon.nodes.utils.string.filterTownOrNation
import phonon.nodes.vassal.Vassals

/**
 * @command /unally
 * Break alliances with towns or nations
 *
 * @subcommand /unally [town]
 * Break alliance with a town
 *
 * @subcommand /unally [nation]
 * Break alliance with a nation
 */
public class UnallyCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, commandLabel: String, args: Array<String>): Boolean {
        if (args.size == 0) {
            printHelp(sender)
            return true
        }

        if (!(sender is Player)) {
            return true
        }

        val player: Player = sender
        val resident = Nodes.getResident(player)
        if (resident == null) {
            return true
        }

        val town = resident.town
        if (town == null) {
            return true
        }

        val nation = town.nation
        if (nation !== null && town !== nation.capital) {
            Message.error(player, "Only the nation's capital town can offer/accept alliances")
            return true
        }

        if (resident !== town.leader && !town.officers.contains(resident)) {
            Message.error(player, "Only the leader and officers can offer/accept alliances")
            return true
        }

        parseTownOrNationName(player, args, town, nation)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size > 0) {
            return filterTownOrNation(args[0])
        }

        return listOf()
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Ally commands:")
        Message.print(sender, "/ally [town]${ChatColor.WHITE}: Offer/accept alliance with town")
        Message.print(sender, "/ally [nation]${ChatColor.WHITE}: Offer/accept alliance with nation")
        Message.print(sender, "/unally [town]${ChatColor.WHITE}: Break alliance with town")
        Message.print(sender, "/unally [nation]${ChatColor.WHITE}: Break alliance with nation")
        return
    }

    private fun parseTownOrNationName(player: Player, args: Array<String>, town: Town, townNation: Nation?) {
        val target = args[0]

        var otherNation = Nodes.nations.get(target)
        if (otherNation !== null) {
            breakAlliance(player, town, otherNation.capital, townNation, otherNation)
            return
        }

        // Else a town (resolved to its nation if it has one)
        val otherTown = Nodes.towns.get(target)
        if (otherTown !== null) {
            otherNation = otherTown.nation
            if (otherNation !== null) {
                breakAlliance(player, town, otherNation.capital, townNation, otherNation)
            } else {
                breakAlliance(player, town, otherTown, townNation, otherNation)
            }
            return
        }

        Message.error(player, "Town or nation \"${target}\" does not exist")
    }

    private fun breakAlliance(player: Player, town: Town, other: Town, townNation: Nation?, otherNation: Nation?) {
        if (town === other) {
            Message.error(player, "You cannot make a peace treaty with yourself.")
            return
        }

        if (Vassals.isBound(town, other)) {
            Message.error(player, "Vassals and overlords can't unally. Use /vassal release or /vassal revolt")
            return
        }

        val result = Nodes.removeAlly(town, other)
        if (result.isSuccess) {
            Nodes.addTruce(town, other)

            val thisSide = if (townNation !== null) {
                townNation.name
            } else {
                town.name
            }

            val otherSide = if (otherNation !== null) {
                otherNation.name
            } else {
                other.name
            }

            Message.print(player, "${ChatColor.DARK_RED}${ChatColor.BOLD}$thisSide has ended its alliance with $otherSide")
        } else {
            when (result.exceptionOrNull()) {
                ErrorNotAllies -> Message.error(player, "You are not allied with this town or nation")
            }
        }
    }
}

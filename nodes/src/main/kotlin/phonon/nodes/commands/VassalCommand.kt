package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.mining.MiningTowers
import phonon.nodes.objects.Town
import phonon.nodes.utils.string.filterTown
import phonon.nodes.vassal.Vassals

private const val PERM_ADMIN = "nodes.vassal.admin"

class VassalCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "info" -> printInfo(sender)
            "list" -> printList(sender)
            "offer" -> cmdOffer(sender, args)
            "accept" -> cmdAccept(sender, args)
            "revolt" -> cmdRevolt(sender)
            "release" -> cmdRelease(sender, args)
            else -> printHelp(sender)
        }
        return true
    }

    private fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(PERM_ADMIN)

    private fun printHelp(sender: CommandSender) {
        val tribute = Vassals.percent(Config.vassalTributeRate)
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Vassal commands:")
        Message.print(sender, "/vassal info${ChatColor.WHITE}: Your overlord or your vassals")
        Message.print(sender, "/vassal list${ChatColor.WHITE}: All vassals")
        Message.print(sender, "/vassal offer <town>${ChatColor.WHITE}: Offer to make a town your vassal (leader)")
        Message.print(sender, "/vassal accept <town>${ChatColor.WHITE}: Become that town's vassal; ends any war between you (leader)")
        Message.print(sender, "/vassal release <town>${ChatColor.WHITE}: Free one of your vassals (leader)")
        Message.print(sender, "/vassal revolt${ChatColor.WHITE}: Break free from your overlord and go to war (leader)")
        Message.print(sender, "${ChatColor.GRAY}Vassals pay $tribute of their income and are allied with their overlord.")
        if (isAdmin(sender)) {
            Message.print(sender, "${ChatColor.GRAY}Admin: /vassal release <vassal> works on any vassal")
        }
    }

    private fun leaderTown(sender: CommandSender): Town? {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return null
        }
        val resident = Nodes.getResident(sender)
        val town = resident?.town
        if (town == null) {
            Message.error(sender, "You are not in a town")
            return null
        }
        if (resident !== town.leader) {
            Message.error(sender, "Only the town leader can do that")
            return null
        }
        return town
    }

    private fun printInfo(sender: CommandSender) {
        val town = (sender as? Player)?.let { Nodes.getResident(it)?.town }
        if (town == null) {
            printHelp(sender)
            return
        }
        val overlord = Vassals.overlordOf(town)
        if (overlord != null) {
            Message.print(sender, "${ChatColor.GOLD}[Vassal] ${town.name} is a vassal of ${overlord.name}")
            Message.print(sender, "  Tribute: ${Vassals.percent(Config.vassalTributeRate)} of income")
            val left = Vassals.revoltReadyAt(town) - System.currentTimeMillis()
            if (left > 0) {
                Message.print(sender, "  Revolt possible in ${MiningTowers.formatDuration(left / 1000)}")
            } else {
                Message.print(sender, "  Revolt available: /vassal revolt")
            }
        }
        val vassals = Vassals.vassalsOf(town)
        if (vassals.isNotEmpty()) {
            Message.print(sender, "${ChatColor.GOLD}[Vassal] ${town.name}'s vassals: ${ChatColor.WHITE}${vassals.joinToString(", ") { it.name }}")
        }
        val offers = Vassals.pendingOffersFor(town)
        if (offers.isNotEmpty()) {
            Message.print(sender, "${ChatColor.YELLOW}Pending offers from: ${offers.joinToString(", ") { it.name }}")
        }
        if (overlord == null && vassals.isEmpty() && offers.isEmpty()) {
            Message.print(sender, "${ChatColor.GRAY}${town.name} has no overlord or vassals. /vassal help")
        }
    }

    private fun printList(sender: CommandSender) {
        if (Vassals.links.isEmpty()) {
            Message.print(sender, "No vassals")
            return
        }
        Message.print(sender, "${ChatColor.GOLD}Vassals:")
        for (link in Vassals.links.values.toList()) {
            val vassal = Vassals.townByUuid(link.vassal) ?: continue
            val overlord = Vassals.townByUuid(link.overlord) ?: continue
            Message.print(sender, "  ${vassal.name} ${ChatColor.GRAY}-> ${ChatColor.WHITE}${overlord.name}")
        }
    }

    private fun cmdOffer(sender: CommandSender, args: Array<out String>) {
        val town = leaderTown(sender) ?: return
        val target = args.getOrNull(1)?.let { Nodes.getTownFromName(it) }
        if (target == null) {
            Message.error(sender, "Usage: /vassal offer <town>")
            return
        }
        val err = Vassals.offer(town, target)
        if (err != null) {
            Message.error(sender, err)
            return
        }
        Message.print(sender, "${ChatColor.GREEN}Offered vassalage to ${target.name}")
    }

    private fun cmdAccept(sender: CommandSender, args: Array<out String>) {
        val town = leaderTown(sender) ?: return
        val overlord = args.getOrNull(1)?.let { Nodes.getTownFromName(it) }
        if (overlord == null) {
            Message.error(sender, "Usage: /vassal accept <town>")
            return
        }
        Vassals.accept(town, overlord)?.let { Message.error(sender, it) }
    }

    private fun cmdRevolt(sender: CommandSender) {
        val town = leaderTown(sender) ?: return
        Vassals.revolt(town)?.let { Message.error(sender, it) }
    }

    private fun cmdRelease(sender: CommandSender, args: Array<out String>) {
        val vassal = args.getOrNull(1)?.let { Nodes.getTownFromName(it) }
        if (vassal == null) {
            Message.error(sender, "Usage: /vassal release <town>")
            return
        }
        if (!isAdmin(sender)) {
            val town = leaderTown(sender) ?: return
            if (Vassals.overlordOf(vassal) !== town) {
                Message.error(sender, "${vassal.name} is not your vassal")
                return
            }
        }
        Vassals.release(vassal)?.let { Message.error(sender, it) }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return listOf("help", "info", "list", "offer", "accept", "release", "revolt")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2) {
            val town = (sender as? Player)?.let { Nodes.getResident(it)?.town }
            return when (args[0].lowercase()) {
                "offer" -> filterTown(args[1])
                "accept" -> town?.let { Vassals.pendingOffersFor(it).map { t -> t.name } } ?: emptyList()
                "release" -> if (isAdmin(sender)) {
                    Vassals.links.values.mapNotNull { Vassals.townByUuid(it.vassal)?.name }
                } else {
                    town?.let { Vassals.vassalsOf(it).map { t -> t.name } } ?: emptyList()
                }
                else -> emptyList()
            }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}

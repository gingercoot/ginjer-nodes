package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.killshop.KillShop
import phonon.nodes.killshop.KillShopGui

class KillShopCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.isEmpty()) {
            return openShop(sender)
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "points", "point", "bal", "balance" -> showPoints(sender)
            "enable" -> adminEnable(sender, true)
            "disable" -> adminEnable(sender, false)
            "add" -> adminAdd(sender, args)
            "remove", "delete" -> adminRemove(sender, args)
            "cost", "setcost" -> adminCost(sender, args)
            "list" -> adminList(sender)
            else -> printHelp(sender)
        }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Killshop commands:")
        Message.print(sender, "/killshop${ChatColor.WHITE}: Open the shop")
        Message.print(sender, "/killshop points${ChatColor.WHITE}: Show your points")
        if (sender.hasPermission("nodes.killshop.admin")) {
            Message.print(sender, "/killshop enable${ChatColor.WHITE}: Enable killshop (awards points)")
            Message.print(sender, "/killshop disable${ChatColor.WHITE}: Disable killshop")
            Message.print(sender, "/killshop add <cost> <id>${ChatColor.WHITE}: Add held item to shop")
            Message.print(sender, "/killshop remove <id>${ChatColor.WHITE}: Remove shop entry")
            Message.print(sender, "/killshop cost <id> <cost>${ChatColor.WHITE}: Change cost")
            Message.print(sender, "/killshop list${ChatColor.WHITE}: List entries")
        }
    }

    private fun openShop(sender: CommandSender): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only")
            return true
        }
        if (!KillShop.enabled && !sender.hasPermission("nodes.killshop.admin")) {
            Message.error(sender, "Killshop is disabled")
            return true
        }
        if (!sender.hasPermission("nodes.command.killshop") &&
            !sender.hasPermission("nodes.killshop.admin")
        ) {
            Message.error(sender, "No permission")
            return true
        }
        KillShopGui.open(sender)
        return true
    }

    private fun showPoints(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("Players only")
            return
        }
        Message.print(
            sender,
            "${ChatColor.GOLD}Killshop points: ${ChatColor.YELLOW}${KillShop.getPoints(sender.uniqueId)}",
        )
    }

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (!sender.hasPermission("nodes.killshop.admin")) {
            Message.error(sender, "No permission")
            return false
        }
        return true
    }

    private fun adminEnable(sender: CommandSender, on: Boolean) {
        if (!requireAdmin(sender)) return
        KillShop.setEnabled(on)
        if (on) {
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}[Killshop] ${ChatColor.GREEN}ENABLED")
        } else {
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}[Killshop] ${ChatColor.GRAY}disabled")
        }
    }

    private fun adminAdd(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (sender !is Player) {
            Message.error(sender, "Hold the item and run in-game")
            return
        }
        if (args.size < 3) {
            Message.error(sender, "Usage: /killshop add <cost> <id>")
            return
        }
        val cost = args[1].toIntOrNull()
        if (cost == null) {
            Message.error(sender, "Cost must be an integer")
            return
        }
        val id = args[2]
        val hand = sender.inventory.itemInMainHand
        if (hand.type.isAir) {
            Message.error(sender, "Hold an item in your main hand")
            return
        }
        val err = KillShop.addEntry(id, cost, hand.clone())
        if (err != null) {
            Message.error(sender, err)
            return
        }
        Message.print(sender, "${ChatColor.GREEN}Added killshop entry '$id' cost=$cost")
    }

    private fun adminRemove(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (args.size < 2) {
            Message.error(sender, "Usage: /killshop remove <id>")
            return
        }
        if (KillShop.removeEntry(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Removed '${args[1]}'")
        } else {
            Message.error(sender, "Entry not found")
        }
    }

    private fun adminCost(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) return
        if (args.size < 3) {
            Message.error(sender, "Usage: /killshop cost <id> <cost>")
            return
        }
        val cost = args[2].toIntOrNull()
        if (cost == null) {
            Message.error(sender, "Cost must be an integer")
            return
        }
        if (KillShop.setCost(args[1], cost)) {
            Message.print(sender, "${ChatColor.GREEN}Set '${args[1]}' cost to $cost")
        } else {
            Message.error(sender, "Entry not found or invalid cost")
        }
    }

    private fun adminList(sender: CommandSender) {
        if (!requireAdmin(sender)) return
        val list = KillShop.allEntries()
        if (list.isEmpty()) {
            Message.print(sender, "No killshop entries")
            return
        }
        Message.print(sender, "${ChatColor.GOLD}Killshop entries (enabled=${KillShop.enabled}):")
        for (e in list) {
            Message.print(sender, "  ${e.id} ${ChatColor.GRAY}- cost ${e.cost}")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val admin = sender.hasPermission("nodes.killshop.admin")
        if (args.size == 1) {
            val base = mutableListOf("help", "points")
            if (admin) {
                base.addAll(listOf("enable", "disable", "add", "remove", "cost", "list"))
            }
            return base.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (!admin) return emptyList()
        when (args[0].lowercase()) {
            "remove", "cost" -> {
                if (args.size == 2) {
                    return KillShop.allEntries()
                        .map { it.id }
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                }
            }
        }
        return emptyList()
    }
}

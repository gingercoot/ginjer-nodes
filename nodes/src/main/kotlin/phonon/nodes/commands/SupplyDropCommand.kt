package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.supply.SupplyDrops

private const val PERM_ADMIN = "nodes.supplydrop.admin"

class SupplyDropCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "status" -> printStatus(sender)
            "addpoint" -> if (requireAdmin(sender)) cmdAddPoint(sender, args)
            "removepoint", "delpoint" -> if (requireAdmin(sender)) cmdRemovePoint(sender, args)
            "points" -> if (requireAdmin(sender)) cmdPoints(sender)
            "loot" -> if (requireAdmin(sender)) cmdLoot(sender, args)
            "spawn" -> if (requireAdmin(sender)) cmdSpawn(sender, args)
            "cancel" -> if (requireAdmin(sender)) cmdCancel(sender)
            else -> printHelp(sender)
        }
        return true
    }

    private fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(PERM_ADMIN)

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (isAdmin(sender)) return true
        Message.error(sender, "You do not have permission to do that")
        return false
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Supply drop commands:")
        Message.print(sender, "/supplydrop status${ChatColor.WHITE}: Active drop or time until the next one")
        if (isAdmin(sender)) {
            Message.print(sender, "/supplydrop addpoint <id>${ChatColor.WHITE}: Add a drop point at your feet")
            Message.print(sender, "/supplydrop removepoint <id>${ChatColor.WHITE}: Remove a drop point")
            Message.print(sender, "/supplydrop points${ChatColor.WHITE}: List drop points")
            Message.print(sender, "/supplydrop loot add <id> <weight>${ChatColor.WHITE}: Add held item to the loot table")
            Message.print(sender, "/supplydrop loot remove <id>${ChatColor.WHITE}: Remove a loot entry")
            Message.print(sender, "/supplydrop loot list${ChatColor.WHITE}: List loot entries")
            Message.print(sender, "/supplydrop spawn [point]${ChatColor.WHITE}: Start a drop now")
            Message.print(sender, "/supplydrop cancel${ChatColor.WHITE}: Call off the active drop")
        }
    }

    private fun printStatus(sender: CommandSender) {
        for (line in SupplyDrops.statusLines()) {
            Message.print(sender, "${ChatColor.GOLD}[Supply Drop] ${ChatColor.WHITE}$line")
        }
    }

    private fun cmdAddPoint(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return
        }
        if (args.size < 2) {
            Message.error(sender, "Usage: /supplydrop addpoint <id>")
            return
        }
        val block = sender.location.block
        val err = SupplyDrops.addPoint(args[1], block)
        if (err != null) {
            Message.error(sender, err)
            return
        }
        Message.print(sender, "${ChatColor.GREEN}Added drop point \"${args[1]}\" at ${block.x}, ${block.y}, ${block.z}")
    }

    private fun cmdRemovePoint(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /supplydrop removepoint <id>")
            return
        }
        if (SupplyDrops.removePoint(args[1])) {
            Message.print(sender, "${ChatColor.GREEN}Removed drop point \"${args[1]}\"")
        } else {
            Message.error(sender, "Unknown drop point")
        }
    }

    private fun cmdPoints(sender: CommandSender) {
        if (SupplyDrops.points.isEmpty()) {
            Message.print(sender, "No drop points")
            return
        }
        Message.print(sender, "${ChatColor.GOLD}Drop points:")
        for (p in SupplyDrops.points.values) {
            Message.print(sender, "  ${p.id} ${ChatColor.GRAY}@ ${p.world} ${p.coords()}")
        }
    }

    private fun cmdLoot(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "add" -> {
                if (sender !is Player) {
                    Message.error(sender, "Hold the item and run in-game")
                    return
                }
                val weight = args.getOrNull(3)?.toIntOrNull()
                if (args.size < 4 || weight == null) {
                    Message.error(sender, "Usage: /supplydrop loot add <id> <weight>")
                    return
                }
                val hand = sender.inventory.itemInMainHand
                if (hand.type.isAir) {
                    Message.error(sender, "Hold an item in your main hand")
                    return
                }
                val err = SupplyDrops.addLoot(args[2], weight, hand.clone())
                if (err != null) {
                    Message.error(sender, err)
                    return
                }
                Message.print(sender, "${ChatColor.GREEN}Added loot \"${args[2]}\" (${hand.amount}x ${hand.type.name}, weight $weight)")
            }
            "remove", "delete" -> {
                val id = args.getOrNull(2)
                if (id == null) {
                    Message.error(sender, "Usage: /supplydrop loot remove <id>")
                    return
                }
                if (SupplyDrops.removeLoot(id)) {
                    Message.print(sender, "${ChatColor.GREEN}Removed loot \"$id\"")
                } else {
                    Message.error(sender, "Unknown loot entry")
                }
            }
            "list" -> {
                if (SupplyDrops.loot.isEmpty()) {
                    Message.print(sender, "No loot entries")
                    return
                }
                val total = SupplyDrops.loot.values.sumOf { it.weight }
                Message.print(sender, "${ChatColor.GOLD}Loot table:")
                for (l in SupplyDrops.loot.values) {
                    val item = l.item()
                    val name = item?.itemMeta?.displayName?.takeIf { it.isNotBlank() } ?: item?.type?.name ?: "?"
                    val chance = "%.1f".format(100.0 * l.weight / total)
                    Message.print(sender, "  ${l.id} ${ChatColor.GRAY}- ${item?.amount ?: 0}x $name, weight ${l.weight} ($chance%)")
                }
            }
            else -> Message.error(sender, "Usage: /supplydrop loot <add|remove|list>")
        }
    }

    private fun cmdSpawn(sender: CommandSender, args: Array<out String>) {
        val err = SupplyDrops.forceSpawn(args.getOrNull(1))
        if (err != null) Message.error(sender, err)
    }

    private fun cmdCancel(sender: CommandSender) {
        if (!SupplyDrops.cancelActive()) {
            Message.error(sender, "No active supply drop")
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val admin = isAdmin(sender)
        if (args.size == 1) {
            val subs = mutableListOf("help", "status")
            if (admin) subs.addAll(listOf("addpoint", "removepoint", "points", "loot", "spawn", "cancel"))
            return subs.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (!admin) return emptyList()
        val sub = args[0].lowercase()
        if (args.size == 2) {
            return when (sub) {
                "removepoint", "spawn" -> SupplyDrops.points.keys.toList()
                "loot" -> listOf("add", "remove", "list")
                else -> emptyList()
            }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && sub == "loot" && args[1].equals("remove", ignoreCase = true)) {
            return SupplyDrops.loot.keys.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }
}

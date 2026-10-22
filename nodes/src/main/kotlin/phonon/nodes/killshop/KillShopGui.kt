package phonon.nodes.killshop

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import phonon.nodes.Message

class KillShopGuiHolder(
    val viewer: Player,
) : InventoryHolder {
    private val inv: Inventory = Bukkit.createInventory(this, 54, "${ChatColor.DARK_RED}Killshop")

    override fun getInventory(): Inventory = inv

    fun fill() {
        inv.clear()
        val entries = KillShop.allEntries()
        var slot = 0
        for (entry in entries) {
            if (slot >= 45) break // leave bottom row free
            val item = entry.toItem() ?: ItemStack(Material.BARRIER)
            val meta: ItemMeta = item.itemMeta ?: Bukkit.getItemFactory().getItemMeta(item.type)!!
            val lore = meta.lore?.toMutableList() ?: mutableListOf()
            lore.add("")
            lore.add("${ChatColor.GOLD}Cost: ${ChatColor.YELLOW}${entry.cost} points")
            lore.add("${ChatColor.DARK_GRAY}id: ${entry.id}")
            lore.add("${ChatColor.GRAY}Click to buy")
            meta.lore = lore
            meta.setDisplayName(
                (meta.displayName ?: item.type.name) + "${ChatColor.BLACK}${ChatColor.MAGIC}#${entry.id}",
            )
            item.itemMeta = meta
            inv.setItem(slot, item)
            slot++
        }

        val info = ItemStack(Material.GOLD_NUGGET)
        val im = info.itemMeta!!
        im.setDisplayName("${ChatColor.GOLD}Your points: ${ChatColor.YELLOW}${KillShop.getPoints(viewer.uniqueId)}")
        im.lore = listOf(
            "${ChatColor.GRAY}Kill players while killshop is enabled",
            "${ChatColor.GRAY}to earn points.",
        )
        info.itemMeta = im
        inv.setItem(49, info)
    }
}

object KillShopGui {
    fun open(player: Player) {
        val holder = KillShopGuiHolder(player)
        holder.fill()
        player.openInventory(holder.inventory)
    }

    fun parseEntryId(stack: ItemStack?): String? {
        if (stack == null || !stack.hasItemMeta()) return null
        val name = stack.itemMeta?.displayName ?: return null
        val marker = "${ChatColor.BLACK}${ChatColor.MAGIC}#"
        val idx = name.lastIndexOf(marker)
        if (idx < 0) return null
        return name.substring(idx + marker.length)
    }
}

class KillShopGuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? KillShopGuiHolder ?: return
        event.isCancelled = true

        if (event.clickedInventory != holder.inventory) return
        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != holder.viewer.uniqueId) return

        val slot = event.rawSlot
        if (slot < 0 || slot >= 45) return

        val stack = event.currentItem ?: return
        val id = KillShopGui.parseEntryId(stack) ?: return
        val entry = KillShop.getEntry(id) ?: run {
            Message.error(player, "Item no longer in shop")
            return
        }

        val give = entry.toItem() ?: run {
            Message.error(player, "Invalid shop item")
            return
        }

        if (!KillShop.trySpend(player.uniqueId, entry.cost)) {
            Message.error(
                player,
                "Not enough points (need ${entry.cost}, have ${KillShop.getPoints(player.uniqueId)})",
            )
            return
        }

        val leftover = player.inventory.addItem(give)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        Message.print(player, "${ChatColor.GREEN}Bought ${entry.id} for ${entry.cost} points")
        holder.fill()
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is KillShopGuiHolder) {
            event.isCancelled = true
        }
    }
}

package phonon.nodes.refinery

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.scheduler.BukkitTask
import phonon.nodes.Message
import phonon.nodes.Nodes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RefineryGuiHolder(val refineryId: String) : InventoryHolder {
    var inv: Inventory? = null

    override fun getInventory(): Inventory = inv ?: error("Refinery GUI inventory not initialized")
}

object RefineryGui {
    const val BOOK_SLOT = 11
    const val CHEST_SLOT = 15

    /** player UUID -> refinery id (GUI open) */
    private val openViewers: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()
    private var refreshTask: BukkitTask? = null

    fun open(player: Player, r: Refinery) {
        val holder = RefineryGuiHolder(r.id)
        val inv = Bukkit.createInventory(
            holder,
            27,
            "${ChatColor.DARK_GREEN}Refinery ${r.id}",
        )
        holder.inv = inv

        inv.setItem(BOOK_SLOT, makeInfoBook(r))
        inv.setItem(CHEST_SLOT, makeClaimChest(r))

        openViewers[player.uniqueId] = r.id
        ensureRefreshTask()

        player.openInventory(inv)
    }

    fun onClosed(player: Player) {
        openViewers.remove(player.uniqueId)
        if (openViewers.isEmpty()) {
            refreshTask?.cancel()
            refreshTask = null
        }
    }

    /** Push fresh book/chest into all open refinery GUIs. */
    fun refreshOpenGuis() {
        if (openViewers.isEmpty()) return

        val offline = ArrayList<UUID>()
        for ((uuid, refineryId) in openViewers) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline) {
                offline.add(uuid)
                continue
            }
            val top = player.openInventory.topInventory
            val holder = top.holder as? RefineryGuiHolder
            if (holder == null || holder.refineryId != refineryId) {
                offline.add(uuid)
                continue
            }
            val r = Refineries.byId[refineryId] ?: continue
            top.setItem(BOOK_SLOT, makeInfoBook(r))
            top.setItem(CHEST_SLOT, makeClaimChest(r))
            player.updateInventory()
        }
        for (id in offline) {
            openViewers.remove(id)
        }
        if (openViewers.isEmpty()) {
            refreshTask?.cancel()
            refreshTask = null
        }
    }

    private fun ensureRefreshTask() {
        val plugin = Nodes.plugin ?: return
        if (refreshTask != null && refreshTask?.isCancelled == false) return
        // every 1 second
        refreshTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable { refreshOpenGuis() },
            20L,
            20L,
        )
    }

    fun makeInfoBook(r: Refinery): ItemStack {
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta
        meta.title = "Refinery ${r.id}"
        meta.author = "Nodes"

        val lines = ArrayList<String>()
        lines.add("${ChatColor.GOLD}${ChatColor.BOLD}Refinery ${r.id}")
        lines.add("")
        lines.add("${ChatColor.GRAY}Interval: ${ChatColor.WHITE}${Refineries.formatDuration(r.intervalMs)}")
        lines.add("")

        when {
            r.outputs.isEmpty() -> {
                lines.add("${ChatColor.RED}No outputs configured")
            }
            r.isReady() -> {
                lines.add("${ChatColor.GREEN}${ChatColor.BOLD}Status: READY")
                lines.add("${ChatColor.GRAY}Click the chest to collect")
            }
            else -> {
                lines.add("${ChatColor.AQUA}Status: Running")
                lines.add("${ChatColor.WHITE}Next output in:")
                lines.add("${ChatColor.YELLOW}${Refineries.formatDuration(r.msUntilNext())}")
            }
        }

        lines.add("")
        lines.add("${ChatColor.DARK_GRAY}Configured outputs:")
        if (r.outputs.isEmpty()) {
            lines.add("${ChatColor.DARK_GRAY}(none)")
        } else {
            for (out in r.outputs) {
                val item = out.toItemStack()
                val name = item?.itemMeta?.displayName?.takeIf { it.isNotBlank() }
                    ?: item?.type?.name?.lowercase()?.replace('_', ' ')
                    ?: "?"
                lines.add("${ChatColor.GRAY}- ${out.amount}x $name")
            }
        }

        meta.addPage(lines.joinToString("\n"))
        meta.setDisplayName("${ChatColor.AQUA}Timer / info")
        meta.lore = listOf(
            if (r.isReady()) {
                "${ChatColor.GREEN}READY — collect at the chest"
            } else {
                "${ChatColor.YELLOW}Next in ${Refineries.formatDuration(r.msUntilNext())}"
            },
        )
        book.itemMeta = meta
        return book
    }

    fun makeClaimChest(r: Refinery): ItemStack {
        val chest = ItemStack(Material.CHEST)
        val meta = chest.itemMeta
        meta?.setDisplayName("${ChatColor.GOLD}${ChatColor.BOLD}Collect output")
        val lore = ArrayList<String>()
        if (r.buffer.isEmpty()) {
            lore.add("${ChatColor.RED}Nothing ready yet")
            lore.add("${ChatColor.GRAY}Next in ${Refineries.formatDuration(r.msUntilNext())}")
        } else {
            lore.add("${ChatColor.GREEN}Click to take all items")
            lore.add("${ChatColor.DARK_GRAY}────────────────")
            for (stack in r.buffer) {
                val name = stack.itemMeta?.displayName?.takeIf { it.isNotBlank() }
                    ?: stack.type.name.lowercase().replace('_', ' ')
                lore.add("${ChatColor.YELLOW}${stack.amount}x ${ChatColor.WHITE}$name")
            }
        }
        meta?.lore = lore
        chest.itemMeta = meta
        return chest
    }
}

class RefineryGuiListener : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? RefineryGuiHolder ?: return
        event.isCancelled = true

        if (event.clickedInventory != event.view.topInventory) return

        val player = event.whoClicked as? Player ?: return
        val r = Refineries.byId[holder.refineryId] ?: run {
            player.closeInventory()
            return
        }

        if (event.slot == RefineryGui.BOOK_SLOT) {
            return
        }

        if (event.slot != RefineryGui.CHEST_SLOT) return

        if (r.buffer.isEmpty()) {
            Message.error(
                player,
                "Nothing ready. Next in ${Refineries.formatDuration(r.msUntilNext())}",
            )
            event.inventory.setItem(RefineryGui.BOOK_SLOT, RefineryGui.makeInfoBook(r))
            event.inventory.setItem(RefineryGui.CHEST_SLOT, RefineryGui.makeClaimChest(r))
            return
        }

        val taken = r.buffer.toList()
        r.buffer.clear()
        Refineries.save()

        for (item in taken) {
            val leftover = player.inventory.addItem(item)
            for (left in leftover.values) {
                player.world.dropItemNaturally(player.location, left)
            }
        }

        Message.print(player, "${ChatColor.GREEN}Collected refinery ${r.id} output")
        player.closeInventory()
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        if (event.inventory.holder is RefineryGuiHolder) {
            val player = event.player as? Player ?: return
            RefineryGui.onClosed(player)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is RefineryGuiHolder) {
            event.isCancelled = true
        }
    }
}

/**
 * An inventory gui window instance
 *
 * Draws items at (x,y) coordinate in inventory from top-left
 * (0, 0) ------->
 *   |                  Same as HTML DOM screen
 *   |      (+x,+y)
 *   v
 */

package phonon.nodes.gui

import org.bukkit.Bukkit
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

public class GuiWindow(val size: Int, val title: String) : InventoryHolder {

    public val cols: Int = 9
    public val rows: Int = size / 9

    @Suppress("PropertyName")
    public val _inventory: Inventory = Bukkit.createInventory(this, size, title)

    public val elements: Array<GuiElement?> = Array(size, { i -> null })

    @Suppress("PropertyName")
    private val _onCloseHandlers: ArrayList<() -> Unit> = ArrayList()

    @Suppress("PropertyName")
    private val _onItemDepositHandlers: ArrayList<(InventoryClickEvent) -> Unit> = ArrayList()

    public override fun getInventory(): Inventory = _inventory

    public fun onClose(callback: () -> Unit) {
        this._onCloseHandlers.add(callback)
    }

    public fun onItemDeposit(callback: (InventoryClickEvent) -> Unit) {
        this._onItemDepositHandlers.add(callback)
    }

    public fun runOnClick(event: InventoryClickEvent) {
        val view = event.getView()
        val inventory = event.getClickedInventory()

        if (inventory == this._inventory) {
            // Cancelled by default; element callbacks can override
            event.setCancelled(true)

            val index = event.getSlot()
            val elem = this.elements[index]
            if (elem != null) {
                elem.onClick(event)
            }
        }
    }

    public fun runOnItemDeposit(event: InventoryClickEvent) {
        event.setCancelled(true)

        for (callback in this._onItemDepositHandlers) {
            callback(event)
        }
    }

    public fun runOnDrag(event: InventoryDragEvent) {
        val inventory = event.getInventory()
        if (inventory == this._inventory) {
            event.setCancelled(true)
        }
    }

    public fun runOnClose(event: InventoryCloseEvent) {
        for (callback in this._onCloseHandlers) {
            callback()
        }
    }

    public fun draw(element: GuiElement, x: Int, y: Int, items: ItemStack) {
        val index = y * 9 + x
        if (index < this.size) {
            this.elements[index] = element
            this._inventory.setItem(index, items)
        }
    }
}

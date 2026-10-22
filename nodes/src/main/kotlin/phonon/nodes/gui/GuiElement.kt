/**
 * Interface for all gui objects
 */

package phonon.nodes.gui

import org.bukkit.event.inventory.InventoryClickEvent

public interface GuiElement {

    public fun render(screen: GuiWindow)

    public fun onClick(event: InventoryClickEvent) {}
}

/**
 * List of town protectable blocks for Chests permissions
 * and for trusted player permissions
 *
 * TODO: include shulker boxes?
 */

package phonon.nodes.constants

import org.bukkit.Material

val PROTECTED_BLOCKS: List<Material> = listOf(
    Material.CHEST,
    Material.TRAPPED_CHEST,
    Material.FURNACE,

    Material.BARREL,
    Material.BLAST_FURNACE,
)

package phonon.nodes.listeners

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockGrowEvent
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory

public class NodesBlockGrowListener : Listener {

    @EventHandler
    public fun onBlockGrow(event: BlockGrowEvent) {
        val block = event.block
        val blockY = block.location.blockY

        if (block.lightFromSky < Config.cropsMinSkyLight) {
            event.setCancelled(true)
            return
        }

        if (blockY < Config.cropsMinYHeight || blockY > Config.cropsMaxYHeight) {
            event.setCancelled(true)
            return
        }

        val chunk: Chunk = event.block.chunk
        val territory: Territory? = Nodes.getTerritoryFromChunk(chunk)

        if (!Config.allowCropsInWilderness && (territory === null || territory.town === null)) {
            event.setCancelled(true)
            return
        }

        if (territory !== null && territory.cropsCanGrow) {
            // Use the new state: for spreading plants (sugarcane, vines) event.block is still AIR
            val blockState = event.getNewState()
            var plant: Material = blockState.type

            if (!territory.crops.containsKey(plant)) {
                val altName = Config.cropAlternativeNames.get(plant)
                if (altName === null || !territory.crops.containsKey(altName)) {
                    event.setCancelled(true)
                    return
                }

                plant = altName
            }

            val randNum = Math.random()
            val growRate = territory.crops.get(plant) ?: 0.0
            if (randNum <= growRate) {
                return
            }
        }

        event.setCancelled(true)
    }
}

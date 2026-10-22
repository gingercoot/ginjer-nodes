package phonon.nodes.listeners

import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Territory
import phonon.nodes.utils.entity.spawnEggFromEntity
import java.util.concurrent.ThreadLocalRandom

public class NodesEntityBreedListener : Listener {

    // Cancelling breed event causes entities to keep breeding until timeout
    // Cannot stop breeding, instead delete spawned child with % chance
    // from territory resource nodes
    @EventHandler
    public fun onEntitySpawn(event: CreatureSpawnEvent) {
        val spawnReason = event.getSpawnReason()
        if (spawnReason != SpawnReason.BREEDING && spawnReason != SpawnReason.DISPENSE_EGG && spawnReason != SpawnReason.EGG) {
            return
        }

        val location = event.location
        val block = location.block
        val blockY = location.blockY

        if (block.lightFromSky < Config.breedingMinSkyLight) {
            event.setCancelled(true)
            return
        }

        if (blockY < Config.breedingMinYHeight || blockY > Config.breedingMaxYHeight) {
            event.setCancelled(true)
            return
        }

        val entity: Entity = event.getEntity()
        val territory: Territory? = Nodes.getTerritoryFromChunk(entity.location.chunk)

        if (!Config.allowBreedingInWilderness && (territory === null || territory.town === null)) {
            event.setCancelled(true)
            return
        }

        if (territory !== null && territory.animalsCanBreed) {
            val entityType = entity.type
            val breedRate = territory.animals.get(entityType)

            val randNum: Double = ThreadLocalRandom.current().nextDouble()
            if (breedRate !== null && randNum <= breedRate) {
                val territoryOccupier = territory.occupier
                if (territoryOccupier != null && Math.random() <= Config.taxAnimalRate) {
                    // Occupier gets the bred animal as a spawn egg
                    val spawnEgg = spawnEggFromEntity(entityType)
                    if (spawnEgg !== null) {
                        Nodes.addToIncome(territoryOccupier, spawnEgg, 1)
                    }

                    event.entity.remove()
                } else if (Config.overClaimsPenalty && territory?.town?.isOverClaimsMax == true) {
                    if (Math.random() < Config.overClaimsMaxPenalty) {
                        event.entity.remove()
                    }
                }

                return
            }
        }

        event.entity.remove()
    }
}

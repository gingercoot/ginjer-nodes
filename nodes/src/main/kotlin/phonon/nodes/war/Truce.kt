/**
 * Truce storage
 *
 * truce.json storage format:
 * Array of truce array objects: [town1, town2, count]
 * {
 *   "truce": [
 *     ["town1", "town2", 420],
 *     ["town3", "town4", 500]
 *   ]
 * }
 */

package phonon.nodes.war

import com.google.gson.JsonParser
import phonon.nodes.Config
import phonon.nodes.Nodes
import phonon.nodes.objects.Town
import phonon.nodes.objects.TownPair
import phonon.nodes.utils.saveStringToFile
import java.nio.file.Path

public data class TruceSaveState(
    val town1: String,
    val town2: String,
    val time: Long,
)

public fun trucesToJsonStr(truces: List<TruceSaveState>): String {
    val size = truces.size
    var i = 0

    val s = StringBuilder()
    s.append("{\"truce\":[")
    for (truce in truces) {
        s.append("[\"${truce.town1}\", \"${truce.town2}\", ${truce.time}]")

        if (i < size - 1) {
            s.append(",")
            i += 1
        }
    }
    s.append("]}")

    return s.toString()
}

public class TaskSaveTruce(
    val truces: List<TruceSaveState>,
    val pathTruce: Path,
) : Runnable {
    override fun run() {
        val jsonStr = trucesToJsonStr(truces)
        saveStringToFile(jsonStr, pathTruce)
    }
}

public object Truce {
    // truce map of (town1, town2) -> time ticked in current truce
    public val truces: HashMap<TownPair, Long> = hashMapOf()

    public val trucesByTown: HashMap<Town, HashSet<TownPair>> = hashMapOf()

    public var needsUpdate: Boolean = false

    public fun create(town1: Town, town2: Town, startTime: Long) {
        val towns = TownPair(town1, town2)
        Truce.truces.put(towns, startTime)

        val town1Truces = Truce.trucesByTown.get(town1)
        if (town1Truces !== null) {
            town1Truces.add(towns)
        } else {
            Truce.trucesByTown.put(town1, hashSetOf(towns))
        }

        val town2Truces = Truce.trucesByTown.get(town2)
        if (town2Truces !== null) {
            town2Truces.add(towns)
        } else {
            Truce.trucesByTown.put(town2, hashSetOf(towns))
        }

        Truce.needsUpdate = true
    }

    public fun remove(town1: Town, town2: Town) {
        val towns = TownPair(town1, town2)
        Truce.truces.remove(towns)

        val town1Truces = Truce.trucesByTown.get(town1)
        if (town1Truces !== null) {
            town1Truces.remove(towns)
        }

        val town2Truces = Truce.trucesByTown.get(town2)
        if (town2Truces !== null) {
            town2Truces.remove(towns)
        }

        Truce.needsUpdate = true
    }

    public fun contains(town1: Town, town2: Town): Boolean = Truce.truces.contains(TownPair(town1, town2))

    public fun get(town: Town): List<TownPair> {
        val townTruces = Truce.trucesByTown.get(town)
        if (townTruces !== null) {
            return townTruces.toList()
        }

        return listOf()
    }

    public fun saveTask(): TaskSaveTruce {
        val truceSnapshot = Truce.truces.map { (towns, time) -> TruceSaveState(towns.town1.name, towns.town2.name, time) }
        return TaskSaveTruce(truceSnapshot, Config.pathTruce)
    }

    public fun fromJsonString(jsonString: String) {
        Truce.truces.clear()
        Truce.trucesByTown.clear()

        val json = JsonParser().parse(jsonString).getAsJsonObject()

        val truceList = json.get("truce")?.getAsJsonArray()
        if (truceList !== null) {
            for (truceJson in truceList) {
                val truce = truceJson.getAsJsonArray()
                val town1Name: String = truce[0].getAsString()
                val town2Name: String = truce[1].getAsString()
                val startTime: Long = truce[2].getAsLong()

                val town1 = Nodes.getTownFromName(town1Name)
                if (town1 === null) {
                    continue
                }

                val town2 = Nodes.getTownFromName(town2Name)
                if (town2 === null) {
                    continue
                }

                Truce.create(town1, town2, startTime)
            }
        }
    }
}

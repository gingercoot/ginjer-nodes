/**
 * Handles random sampling from array of ore deposits
 *
 * Interface has two methods of sampling
 * - sample(y: Int?): List<ItemStack>        : return single item stack that passes sample roll (given y height)
 * - sampleAll(y: Int?): List<ItemStack>     : returns multiple items that pass a sample roll (given y height)
 *
 * Both return a List<ItemStack> to keep same interface
 * for the client
 *
 * Single sample selects resources as discrete probability distribution
 * based on relative rates.
 *
 * if SUM(rates) < 1.0, fill in empty region with null (no item drop)
 * else, normalize rates by sum of all rates
 *
 * Sampling uses Vose's Alias method:
 * https://www.keithschwarz.com/darts-dice-coins/
 * https://www.keithschwarz.com/interesting/code/?dir=alias-method
 *
 * To allow different ore patterns at different y-height levels, map each
 * possible y in game height [0, 255] to an ore probability table
 *    0  -> Probability Table
 *    1  -> Probability Table
 *   ...
 *   255 -> Probability Table
 */

package phonon.nodes.objects

import org.bukkit.inventory.ItemStack
import java.util.ArrayDeque
import java.util.Deque
import java.util.concurrent.ThreadLocalRandom

val Y_WORLD_MIN = 0
val Y_WORLD_MAX = 255

private val random = ThreadLocalRandom.current()

/** Alias-method sampler over a list of OreDeposit. */
private class ItemDistribution(inputItems: List<OreDeposit>) {

    val size: Int
    val probability: DoubleArray
    val items: Array<OreDeposit?> // main item list (null = None)
    val alias: Array<OreDeposit?> // alias of item when Pr roll fails

    init {
        val probabilityTemp: MutableList<Double> = mutableListOf()
        val itemsTemp: MutableList<OreDeposit?> = mutableListOf()
        var prSum: Double = 0.0

        inputItems.forEach { item ->
            itemsTemp.add(item)
            probabilityTemp.add(item.dropChance)
            prSum += item.dropChance
        }

        if (prSum < 1.0) {
            itemsTemp.add(null)
            probabilityTemp.add(1.0 - prSum)
            prSum = 1.0
        }

        val n = itemsTemp.size
        val prAvg: Double = 1.0 / n

        val aliasTemp: Array<OreDeposit?> = arrayOfNulls(n)
        val probabilities: DoubleArray = DoubleArray(n)

        val small: Deque<Int> = ArrayDeque<Int>()
        val large: Deque<Int> = ArrayDeque<Int>()

        for (i in 0 until n) {
            probabilityTemp[i] = probabilityTemp[i] / prSum
            if (probabilityTemp[i] >= prAvg) {
                large.add(i)
            } else {
                small.add(i)
            }
        }

        while (!small.isEmpty() && !large.isEmpty()) {
            val less = small.removeLast()
            val more = large.removeLast()

            probabilities[less] = probabilityTemp[less] * n
            aliasTemp[less] = itemsTemp[more]

            probabilityTemp[more] = probabilityTemp[more] + probabilityTemp[less] - prAvg

            if (probabilityTemp[more] >= prAvg) {
                large.add(more)
            } else {
                small.add(more)
            }
        }

        while (!small.isEmpty()) {
            probabilities[small.removeLast()] = 1.0
        }

        while (!large.isEmpty()) {
            probabilities[large.removeLast()] = 1.0
        }

        this.size = n
        this.probability = probabilities
        this.items = Array<OreDeposit?>(n, { i -> itemsTemp[i] })
        this.alias = aliasTemp
    }

    public fun sample(): List<ItemStack> {
        val roll = random.nextInt(this.size)
        val p = random.nextDouble()
        val oreDeposit = if (p <= this.probability[roll]) {
            this.items[roll]
        } else {
            this.alias[roll]
        }

        if (oreDeposit !== null) {
            val minAmount = oreDeposit.minAmount
            val maxAmount = oreDeposit.maxAmount
            val amount = minAmount + random.nextInt(maxAmount - minAmount + 1)
            return listOf(ItemStack(oreDeposit.material, amount))
        }

        return listOf()
    }

    public fun sampleAll(): List<ItemStack> {
        val roll = random.nextDouble()
        val drops: MutableList<ItemStack> = mutableListOf()

        items.forEach { type ->
            if (type != null) {
                if (roll < type.dropChance) {
                    val minAmount = type.minAmount
                    val maxAmount = type.maxAmount
                    val amount = minAmount + random.nextInt(maxAmount - minAmount + 1)
                    drops.add(ItemStack(type.material, amount))
                }
            }
        }

        return drops as List<ItemStack>
    }
}

/** Samples ore drops by y-level (each y maps to an ItemDistribution). */
class OreSampler(
    val ores: ArrayList<OreDeposit>,
) {
    private val itemsAtHeight: Array<ItemDistribution?> = arrayOfNulls(256)

    val containsOre: Boolean = ores.size > 0

    init {
        // 1. iterate ores and generate array of y-intervals and their ore deposits
        var yStart = 0
        var yEnd: Int = Y_WORLD_MAX
        while (yStart < Y_WORLD_MAX) {

            yEnd = ores.fold(yEnd, { y, oreDeposit ->
                if (oreDeposit.ymin > yStart) {
                    Math.min(y, oreDeposit.ymin - 1) // don't include next interval start
                } else if (oreDeposit.ymax >= yStart) {
                    Math.min(y, oreDeposit.ymax)
                } else {
                    y
                }
            })

            // 2. generate item distribution for height interval
            // map all y in [yStart, yEnd] -> ItemDistribution
            val validOres: MutableList<OreDeposit> = mutableListOf()
            ores.forEach { oreDeposit ->
                if (yStart >= oreDeposit.ymin && yStart <= oreDeposit.ymax) {
                    validOres.add(oreDeposit)
                }
            }

            val items = ItemDistribution(validOres)
            for (i in yStart..yEnd) {
                this.itemsAtHeight[i] = items
            }

            yStart = yEnd + 1
            yEnd = Y_WORLD_MAX
        }
    }

    public fun sample(y: Int): List<ItemStack> {
        if (y >= Y_WORLD_MIN && y <= Y_WORLD_MAX) {
            val sampler = this.itemsAtHeight[y]
            if (sampler !== null) {
                return sampler.sample()
            }
        }

        return listOf()
    }

    public fun sampleAll(y: Int): List<ItemStack> {
        if (y >= Y_WORLD_MIN && y <= Y_WORLD_MAX) {
            val sampler = this.itemsAtHeight[y]
            if (sampler !== null) {
                return sampler.sampleAll()
            }
        }

        return listOf()
    }
}

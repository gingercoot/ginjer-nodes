/**
 * Json save state with lazily created json string
 */

package phonon.nodes.serdes

interface SaveState {
    public var jsonString: String?

    public fun createJsonString(): String

    public fun toJsonString(): String {
        val jsonString = this.jsonString
        if (jsonString === null) {
            val json = this.createJsonString()
            this.jsonString = json
            return json
        } else {
            return jsonString
        }
    }
}

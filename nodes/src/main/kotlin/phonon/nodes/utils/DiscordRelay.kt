package phonon.nodes.util

import org.bukkit.Bukkit
import phonon.nodes.Config
import phonon.nodes.Nodes
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Fire-and-forget Discord channel webhooks.
 * Runs async so war/flag logic never blocks on HTTP.
 */
object DiscordRelay {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun flagWar(message: String) {
        if (!Config.discordRelayFlagWar) return
        send(Config.discordWebhookFlagWar, message)
    }

    fun war(message: String) {
        if (!Config.discordRelayWar) return
        send(Config.discordWebhookWar, message)
    }

    fun raid(message: String) {
        if (!Config.discordRelayRaids) return
        send(Config.discordWebhookRaids.ifBlank { Config.discordWebhookWar }, message)
    }

    fun warzone(message: String) {
        if (!Config.discordRelayWarzones) return
        send(Config.discordWebhookWarzones.ifBlank { Config.discordWebhookWar }, message)
    }

    private fun send(webhookUrl: String, message: String) {
        val url = webhookUrl.trim()
        if (url.isEmpty() || !url.startsWith("https://discord.com/api/webhooks/")) {
            return
        }
        val plugin = Nodes.plugin ?: return
        val body = buildJsonContent(message)

        Bukkit.getScheduler().runTaskAsynchronously(
            plugin,
            Runnable {
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build()

                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !in 200..299) {
                        Nodes.logger?.warning(
                            "[DiscordRelay] webhook HTTP ${response.statusCode()}: ${response.body().take(200)}",
                        )
                    }
                } catch (e: Exception) {
                    Nodes.logger?.warning("[DiscordRelay] failed: ${e.message}")
                }
            },
        )
    }

    /** Minimal Discord webhook JSON; escapes control chars in content. */
    private fun buildJsonContent(message: String): String {
        val escaped = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", " ")
        // Discord caps content at 2000 chars
        val clipped = if (escaped.length > 1900) escaped.substring(0, 1900) + "..." else escaped
        return """{"content":"$clipped"}"""
    }
}

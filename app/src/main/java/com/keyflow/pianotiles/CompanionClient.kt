package com.keyflow.pianotiles

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

class CompanionClient(base: String, private val token: String) {
    private val base = base.trimEnd('/')

    private fun connection(path: String): HttpURLConnection {
        val uri = URI(base)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) {
            "Enter a valid http:// or https:// companion address."
        }
        require(path.startsWith('/') && !path.startsWith("//")) { "Invalid media path" }
        return (URI(base + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    fun request(path: String, data: JSONObject? = null): JSONObject {
        val connection = connection(path)
        try {
            if (data != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(data.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val input = if (code >= 400) connection.errorStream else connection.inputStream
            requireNotNull(input) { "Companion returned HTTP $code" }
            val response = input.bufferedReader().use { JSONObject(it.readText()) }
            if (code >= 300) error(response.optString("error", "Companion returned HTTP $code"))
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun download(path: String, destination: File) {
        val connection = connection(path)
        val temporary = File(destination.path + ".part")
        try {
            check(connection.responseCode == 200) {
                "Unable to download track. Reconnect the companion and retry."
            }
            val expected = connection.contentLengthLong
            connection.inputStream.use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            check(temporary.length() > 0 && (expected < 0 || temporary.length() == expected)) {
                "Download incomplete. Please retry."
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }
}

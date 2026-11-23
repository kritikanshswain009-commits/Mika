package com.jarvis.phone

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Jarvis PC from the background (the service, the share button): picks the best route and adds the key. */
object Net {
    @Volatile private var cached: String? = null
    @Volatile private var cachedAt = 0L

    fun reset() { cached = null }

    private fun canConnect(host: String, port: Int, ms: Int): Boolean {
        return try {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(host, port), ms)
            sock.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun paired(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val key = p.getString("key", null)
        return !key.isNullOrEmpty() && (!p.getString("host", null).isNullOrEmpty() || !p.getString("tshost", null).isNullOrEmpty())
    }

    /** The route that last worked. On mobile data the PC can never be on "the same Wi-Fi", so testing that
     *  route first costs a full 1.2 s timeout on every call - which the user feels as the app being slow. */
    @Volatile private var lastGood = ""

    /** "https://192.168.x.x:4892" (same Wi-Fi) or "http://100.x.x.x:4891" (Tailscale), or null if the PC can't be reached. */
    fun base(ctx: Context): String? {
        val now = System.currentTimeMillis()
        val c = cached
        if (c != null && now - cachedAt < 60000) return c
        // Remember a failure for a few seconds too. Status, the feature list and the question itself are
        // asked within the same second, and each one used to repeat the whole timeout sequence.
        if (c == null && cachedAt > 0 && now - cachedAt < 8000) return null
        val p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val lan = p.getString("host", null)
        val lanPort = p.getInt("port", 4892)
        val ts = p.getString("tshost", null)
        val tsPort = p.getInt("hport", 4891)
        val lanUrl = if (lan.isNullOrEmpty()) null else "https://" + lan + ":" + lanPort
        val tsUrl = if (ts.isNullOrEmpty()) null else "http://" + ts + ":" + tsPort
        // Try whichever route worked last time first, then the other one.
        val order: List<String?> = if (lastGood.isNotEmpty() && lastGood == tsUrl) listOf(tsUrl, lanUrl) else listOf(lanUrl, tsUrl)
        var found: String? = null
        for (u in order) {
            if (u == null) continue
            val isLan = (u == lanUrl)
            val host = if (isLan) lan!! else ts!!
            val port = if (isLan) lanPort else tsPort
            if (canConnect(host, port, if (isLan) 1200 else 3000)) { found = u; break }
        }
        if (found != null) lastGood = found
        cached = found
        cachedAt = now
        return found
    }

    fun open(ctx: Context, path: String, method: String, readMs: Int): HttpURLConnection? {
        val p = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val key = p.getString("key", null)
        if (key.isNullOrEmpty()) return null
        val b = base(ctx) ?: return null
        val c = URL(b + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = readMs
        c.setRequestProperty("X-Key", key)
        return c
    }

    /** POSTs JSON and returns the PC's reply text, or null if the PC couldn't be reached or refused. */
    fun postJsonRead(ctx: Context, path: String, json: String): String? {
        return try {
            val c = open(ctx, path, "POST", 15000) ?: return null
            val bytes = json.toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            body
        } catch (e: Exception) {
            null
        }
    }

    /** POSTs a small JSON text. Returns true when the PC said OK. */
    fun postJson(ctx: Context, path: String, json: String): Boolean {
        return try {
            val c = open(ctx, path, "POST", 15000) ?: return false
            val bytes = json.toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            c.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }
}

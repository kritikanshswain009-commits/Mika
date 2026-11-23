package com.jarvis.phone

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Online Kami: your own address in the cloud that holds a short, plain-text picture of what Jarvis
 * knows, so the phone can still answer simple questions when the PC is asleep.
 *
 * The address and the passphrase are typed into the app once, on the System & Cloud screen, and kept in
 * this app's private storage. They are deliberately NOT written in this file and NOT in the GitHub repo:
 * that repo is public, so anything typed into a source file here could be read by anyone, forever.
 *
 * What it can do: answer a question from the snapshot the PC pushed. What it cannot do: open a file, send
 * a message, change your calendar, reach the PC. If the answer needs any of that, the cloud is told to say
 * so rather than guess, and the phone says "ask me again at home".
 */
object Cloud {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun address(ctx: Context): String = prefs(ctx).getString("url", "") ?: ""
    fun passphrase(ctx: Context): String = prefs(ctx).getString("key", "") ?: ""
    fun configured(ctx: Context): Boolean = address(ctx).isNotBlank() && passphrase(ctx).isNotBlank()

    /** Typed by hand, so tidy it: no quotes, no spaces, no trailing slash, https by default. */
    private fun tidyUrl(raw: String): String {
        var u = raw.trim().trim('"', '\'', ' ', '\n')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u
        while (u.endsWith("/")) u = u.substring(0, u.length - 1)
        return u
    }

    fun setConfig(ctx: Context, url: String, key: String) {
        val u = tidyUrl(url)
        val k = key.trim().trim('"', '\'')
        val e = prefs(ctx).edit()
        if (u.isNotEmpty()) e.putString("url", u)
        if (k.isNotEmpty()) e.putString("key", k)
        e.apply()
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** Remembered so a failing handover does not fire on every single question. */
    private fun rememberWhy(ctx: Context, why: String): String {
        prefs(ctx).edit().putString("autoErr", whyInWords(ctx, why))
            .putLong("autoAt", System.currentTimeMillis()).apply()
        return whyInWords(ctx, why)
    }

    /**
     * The PC answers machine problems with machine words - "empty", "auth", "not routed", "locked". Read
     * out to a person those look like the cloud is broken beyond help, when they mean one specific fixable
     * thing. Translate them, and leave anything already in words alone.
     */
    private fun whyInWords(ctx: Context, why: String): String = when (why.trim().lowercase()) {
        "empty" -> "the PC did not understand the request (it is running an older Jarvis - restart it)"
        "auth", "unauthorized" -> "the PC did not accept this phone's pairing key - pair again"
        "locked" -> "the PC locked this address after too many wrong keys; try again in five minutes"
        "not routed" -> "the PC has the door but nobody behind it - restart Jarvis"
        "not found", "" -> why
        else -> why
    }

    /**
     * Learn the cloud settings from the paired PC instead of typing them.
     *
     * The Worker address is about 44 characters and the passphrase is a random 32, and this is a phone
     * keyboard: that is why the phone said "no cloud brain" for days while the PC had the real thing in
     * .env the whole time. The PC hands both to a PAIRED phone only - the pairing key is checked before it
     * answers - over the same local HTTPS call the phone already makes for everything else, and neither
     * side writes the secret to a log. Returns "" when it worked, otherwise the reason in plain words.
     */
    fun autoSetup(ctx: Context): String {
        if (configured(ctx)) return ""
        if (!Net.paired(ctx)) return "your phone is not paired with the PC yet"
        // Network is never allowed from the screen's own thread: it would throw and look like a bug here.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            return "the phone is busy on screen - try again in a moment"
        val at = prefs(ctx).getLong("autoAt", 0L)
        // One minute, not five: when the PC has just been restarted with the fix, the phone should collect
        // its settings on the next question instead of repeating an hour-old complaint about "empty".
        if (at > 0 && System.currentTimeMillis() - at < 60 * 1000L)
            return whyInWords(ctx, prefs(ctx).getString("autoErr", "") ?: "")
        val raw = try { Net.postJsonRead(ctx, "/api/cloud_handover", "{}") } catch (e: Exception) { null }
        if (raw.isNullOrEmpty()) return rememberWhy(ctx, "the PC did not answer - is Jarvis running?")
        val o = try { JSONObject(raw) } catch (e: Exception) { return rememberWhy(ctx, "the PC answered with something unreadable") }
        if (!o.optBoolean("ok")) return rememberWhy(ctx, whyInWords(ctx, o.optString("error", "the PC has no cloud settings either")))
        val u = o.optString("url").trim()
        val k = o.optString("key").trim()
        if (u.isEmpty() || k.isEmpty()) return rememberWhy(ctx, "the PC sent empty settings")
        setConfig(ctx, u, k)
        prefs(ctx).edit().putString("autoErr", "").apply()
        return ""
    }

    /**
     * Throw away settings that the cloud itself rejected.
     *
     * The passphrase is long and random, and a Worker can be redeployed with a new secret. When that
     * happens the phone keeps asking with the old key, gets "Wrong key." forever, and says the cloud is
     * gone - while heal() does nothing, because as far as it is concerned the phone is already set up.
     * Clearing the address and passphrase puts it back into the "not configured" state, so the very next
     * question collects the current settings from the paired PC and the cloud comes back by itself.
     */
    private fun forgetStale(ctx: Context, why: String) {
        forget(ctx)                        // the existing full reset: no address, no passphrase
        prefs(ctx).edit().putString("autoErr", why).putLong("autoAt", 0L).apply()
        note(why)
    }

    /** Called before any question that might need the cloud, so the phone heals itself quietly. */
    fun heal(ctx: Context) {
        if (configured(ctx)) return
        try { autoSetup(ctx) } catch (e: Exception) { }
    }

    /** The line on the System & Cloud screen. It never shows the passphrase, not even a bit of it. */
    fun status(ctx: Context): String {
        if (address(ctx).isEmpty()) return if (Net.paired(ctx))
            "Not set up yet - and you are paired, so ask me a question and I will collect the settings from your PC by myself."
        else "Not set up. Pair with your PC and I fetch it from there; or put the cloud address and the passphrase in here."
        val at = prefs(ctx).getLong("lastOk", 0L)
        val ok = if (at > 0) " · last good answer " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)) else ""
        val err = prefs(ctx).getString("lastErr", "") ?: ""
        val shown = address(ctx).removePrefix("https://").removePrefix("http://")
        return shown + ok + if (err.isNotEmpty()) " · last trouble: " + err else ""
    }

    // ---------------------------------------------------------------- the calls
    private fun open(ctx: Context, path: String, method: String, readMs: Int): HttpURLConnection? {
        val base = address(ctx)
        if (base.isEmpty()) return null
        return try {
            val c = URL(base + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 6000
            c.readTimeout = readMs
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("user-agent", "JarvisPhone/1.0 (Android)")   // Cloudflare rejects bare clients
            val k = passphrase(ctx)
            if (k.isNotEmpty()) c.setRequestProperty("x-kami-key", k)
            c
        } catch (e: Exception) {
            note("the address could not be opened")
            null
        }
    }

    private fun note(msg: String) { lastErr = msg }

    /** Did the cloud refuse the passphrase itself (rather than having no answer)? */
    private fun rejectedKey(why: String): Boolean {
        val t = why.trim().lowercase()
        return t.contains("wrong key") || t.contains("unauthor") || t.contains("forbidden") ||
               t.contains("passphrase") || t == "403" || t == "401" || t.contains("wrong passphrase")
    }

    @Volatile private var lastErr = ""

    /** Why the last cloud call failed, in words; empty when it worked or was never tried. */
    fun lastWhy(): String = lastErr

    private fun body(c: HttpURLConnection): String? {
        return try {
            val s = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            if (s == null) { if (c.responseCode !in 200..299) note(plain(c.responseCode)); return null }
            s.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            note("it did not answer in time")
            null
        } finally {
            try { c.disconnect() } catch (e: Exception) { }
        }
    }

    /** Cloudflare says "Wrong key." and nothing else; the user needs a sentence, not a status code. */
    private fun plain(code: Int): String = when (code) {
        403 -> "the passphrase is wrong"
        404 -> "that address is not my cloud page"
        500, 503 -> "the cloud page is not set up right"
        else -> "the cloud said HTTP " + code
    }

    private fun post(ctx: Context, path: String, payload: JSONObject, waitMs: Int): JSONObject? {
        val c = open(ctx, path, "POST", waitMs) ?: return null
        return try {
            c.doOutput = true
            val bytes = payload.toString().toByteArray()
            c.outputStream.use { it.write(bytes) }
            body(c)?.let { JSONObject(it) }
        } catch (e: Exception) {
            note("no answer from the cloud")
            null
        }
    }

    private fun get(ctx: Context, path: String, waitMs: Int): JSONObject? {
        val c = open(ctx, path, "GET", waitMs) ?: return null
        return try { body(c)?.let { JSONObject(it) } } catch (e: Exception) { note("the cloud sent something unreadable"); null }
    }

    /**
     * Ask the cloud something. Returns the answer, or null if it could not be had - and null has to stay
     * quiet, because the caller is the thing the user is actually talking to.
     */
    fun ask(ctx: Context, q: String, waitMs: Int = 22000): String? {
        if (!configured(ctx)) return null
        val text = q.trim()
        if (text.isEmpty()) return null
        val r = post(ctx, "/ask", JSONObject().put("q", text), waitMs) ?: return null
        if (!r.optBoolean("ok", false)) {
            val why = r.optString("error", "it refused")
            note(why)
            if (rejectedKey(why)) forgetStale(ctx, "the cloud rejected the passphrase, so I will take the current one from your PC")
            return null
        }
        val a = r.optString("answer", "").trim()
        if (a.isEmpty()) { note("it came back empty"); return null }
        prefs(ctx).edit().putLong("lastOk", System.currentTimeMillis()).apply()
        lastErr = ""
        return a
    }

    /**
     * The cloud's voice: mp3 bytes back from /say, or null. Never throws, because the caller's fallback is
     * the phone's own voice - silence would be read as a broken app, and a flat voice is not.
     */
    fun say(ctx: Context, text: String, waitMs: Int = 12000): ByteArray? {
        if (!configured(ctx)) return null
        val t = text.trim()
        if (t.isEmpty()) return null
        val c = open(ctx, "/say", "POST", waitMs) ?: return null
        return try {
            c.doOutput = true
            val bytes = JSONObject().put("text", t.take(700)).toString().toByteArray()
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode != 200) {
                note(plain(c.responseCode))
                if (c.responseCode == 401 || c.responseCode == 403)
                    forgetStale(ctx, "the cloud rejected the passphrase, so I will take the current one from your PC")
                return null
            }
            val ct = (c.contentType ?: "").lowercase()
            if (!ct.contains("audio") && !ct.contains("octet")) { note("the cloud has no voice set up"); return null }
            val out = c.inputStream.use { it.readBytes() }
            if (out.size <= 2048) { note("the voice came back empty"); return null }
            prefs(ctx).edit().putLong("lastOk", System.currentTimeMillis()).apply()
            lastErr = ""
            out
        } catch (e: Exception) {
            note("the voice did not come back in time")
            null
        } finally {
            try { c.disconnect() } catch (e: Exception) { }
        }
    }

    /** The Test button, and the "am I set up right" check. Says what is missing in words. */
    fun test(ctx: Context): String {
        if (address(ctx).isEmpty()) return "No cloud address yet."
        val p = get(ctx, "/", 9000)
        if (p == null) return "Can't reach " + address(ctx) + " from this phone" +
                (if (lastErr.isNotEmpty()) (" - " + lastErr) else "") + "."
        if (!p.optBoolean("ok", false)) return "The cloud answered but says: " + p.optString("error", "something is wrong")
        val bits = ArrayList<String>()
        bits.add(if (p.optBoolean("storeReady")) "storage ok" else "NO STORAGE bound (add the KAMI_STORE binding)")
        bits.add(if (p.optBoolean("keyConfigured")) "passphrase set" else "NO PASSPHRASE on the cloud (add the KAMI_KEY secret)")
        bits.add("thinking with " + p.optString("brain", "nothing"))
        val probe = ask(ctx, "Reply with exactly: awake", 22000)
        return if (probe != null) {
            "Working. " + bits.joinToString(", ") + ". It answered in your own words, so the passphrase matches."
        } else {
            "Reachable, but it will not talk to us: " + (lastErr.ifEmpty { "the passphrase here does not match the cloud" }) +
                ". (" + bits.joinToString(", ") + ")"
        }
    }
}

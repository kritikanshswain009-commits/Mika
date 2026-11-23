package com.jarvis.phone

import android.content.Context
import android.content.Intent

/**
 * The door into Mika, the app that actually operates other apps on this phone.
 *
 * WHY. Android gives the power-button / assistant slot to exactly one app, and that is this one - so
 * holding the assistant key opens Kami, not Mika. Mika is the app that can drive WhatsApp, a browser or
 * a shopping app. Neither can have both roles, so Kami keeps the button and hands the sentence over.
 *
 * Mika exposes a receiver guarded by a signature-level permission, so this only works if both apps are
 * built with the same signing key. If they are not, send() is refused by Android and false is returned -
 * nothing breaks, the sentence just stays with Kami.
 *
 * Nothing here reads or changes anything in Mika: one broadcast, one sentence, and Mika runs it with its
 * own model, its own permissions and its own visible progress.
 */
object Mika {
    const val PKG = "com.wangzan101.mika"
    private const val ACTION = "com.wangzan101.mika.KAMI_COMMAND"
    private const val EXTRA_TEXT = "text"
    private const val PERMISSION = "com.wangzan101.mika.permission.KAMI_COMMAND"

    /** An action verb - the kind of thing Mika can perform and Kami cannot. */
    private val ACTIONY = Regex(
        "\\b(open|launch|install|uninstall|search|look up|order|book|buy|pay|download|upload|send|" +
        "message|text|dm|reply|scroll|tap|click|swipe|like|follow|subscribe|watch|play|browse|navigate|" +
        "fill|sign ?up|log ?in)\\b", RegexOption.IGNORE_CASE)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** True when this sounds like work for an app rather than a question or a phone command. */
    fun looksLikeAppTask(text: String): Boolean = ACTIONY.containsMatchIn(text)

    /**
     * "Mika, open WhatsApp and message Aaditya" / "ask mika to order that" -> "open WhatsApp and message
     * Aaditya". Plain wording only, because a missed prefix just means Kami answers as usual.
     */
    fun commandIn(text: String): String? {
        val t = text.trim()
        val m = Regex("^(?:hey\\s+)?mika[,: ]+", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("^ask\\s+mika\\s+(?:to\\s+)?", RegexOption.IGNORE_CASE).find(t)
            ?: return null
        val rest = t.substring(m.range.last + 1).trim()
        return rest.ifEmpty { null }
    }

    /**
     * Hand one sentence to Mika. Returns false when Mika is missing, or when Android refused the
     * broadcast (different signing key, or Mika never opened since the phone booted).
     */
    fun send(ctx: Context, text: String): Boolean {
        val sentence = text.trim()
        if (sentence.isEmpty() || !installed(ctx)) return false
        return try {
            val i = Intent(ACTION)
                .setPackage(PKG)                  // explicit: no other app can answer this broadcast
                .putExtra(EXTRA_TEXT, sentence)
            ctx.sendBroadcast(i)                  // Android checks Kami holds Mika's permission
            true
        } catch (e: Exception) {
            false
        }
    }
}

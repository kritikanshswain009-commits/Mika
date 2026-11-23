package com.jarvis.phone

import android.content.Context

/**
 * One place for "the phone could not do this itself".
 *
 * WHY THIS EXISTS. The phone has three doors into Jarvis - the chat screen, the voice overlay sheet, and the
 * old tools list - and each kept its own copy of what happens next. The PC ask was added to one door only, so
 * the overlay went on saying "That one needs your PC. Tap Ask Jarvis on PC" while the pill at the top of that
 * very screen said "PC linked". The PC was awake, reachable and never asked. Same sentence, three different
 * answers, and the person in the middle of it has no way to know which door they used.
 *
 * So every door calls this. Order: the PC's own brain, then the cloud brain, then say plainly what is missing.
 * Nobody is ever sent to another screen to repeat themselves.
 */
object Ask {

    class Out(val text: String, val source: String)

    /** The answer from somewhere else, or null when there is genuinely nowhere to ask. */
    fun beyond(ctx: Context, text: String, waitMs: Int = 20000): Out? {
        // Runs on a worker thread, so this is where the phone quietly collects its cloud settings from the
        // paired PC. Without it the feature stays off forever, because nobody can type a 32-character
        // passphrase on a phone keyboard without errors.
        Cloud.heal(ctx)
        // "Mika, open WhatsApp and message Aaditya" is addressed to the app-operator, not to the PC.
        Mika.commandIn(text)?.let { sentence ->
            if (Mika.send(ctx, sentence)) return Out("On it, sir - Mika is handling that.", "mika")
        }
        val pc = try { PcAsk.ask(ctx, text) } catch (e: Exception) { null }
        // A PC that is mid-conversation answers "I'm busy with something else right now". True, but useless
        // on its own, so the cloud gets its turn; if the cloud has nothing either, the PC's sentence is
        // still the most honest thing on offer, so it is kept as the last resort.
        val shrugged = pc != null && Regex("starting up|busy with something").containsMatchIn(pc)
        val cloud = if (pc != null && !shrugged) null
                else if (!Cloud.configured(ctx)) null
                else try { Cloud.ask(ctx, text, waitMs) } catch (e: Exception) { null }
        return when {
            pc != null && !shrugged -> Out(pc, "pc")
            cloud != null -> Out(cloud, "cloud")
            pc != null -> Out(pc, "pc")
            // Nothing else could do it. If it is app-shaped work and Mika is here, hand it over rather
            // than telling the user the phone cannot do it - Mika can.
            Mika.looksLikeAppTask(text) && Mika.send(ctx, text) ->
                Out("On it, sir - Mika is handling that.", "mika")
            else -> null
        }
    }

    /** Name what is missing instead of blaming the question. */
    fun missing(ctx: Context): String {
        val gone = ArrayList<String>()
        if (!Net.paired(ctx)) gone.add("your PC is not paired")
        else if (!PcAsk.reachable(ctx)) gone.add("your PC is asleep or out of reach")
        if (!Cloud.configured(ctx)) {
            // This sentence runs off the screen thread, so it can also be the moment the phone learns the
            // settings. Say what actually happened rather than repeating a fixed line.
            val why = try { Cloud.autoSetup(ctx) } catch (e: Exception) { "the PC would not answer" }
            if (why.isEmpty() && Cloud.configured(ctx)) gone.add("the cloud brain has just been set up from your PC, so ask again")
            else gone.add("the cloud brain is not set up yet" + (if (why.isEmpty()) "" else " - " + why))
        } else if (Cloud.lastWhy().isNotEmpty()) {
            // Set up, but the last try failed: say why instead of going quiet about the cloud.
            gone.add("the cloud brain did not answer - " + Cloud.lastWhy())
        }
        return "I can't do that from the phone alone" +
                (if (gone.isEmpty()) "." else ", and " + gone.joinToString(" nor ") + ".") +
                " On my own I do alarms, timers, the flashlight, battery, calls, texts, reminders, saved" +
                " items, guides and automations."
    }
}

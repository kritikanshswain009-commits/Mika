package com.wangzan101.mika.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The door the phone's assistant knocks on.
 *
 * Android gives the power-button/assistant slot to exactly one app, and on this phone that is the
 * assistant app (Kami), not Mika. Mika is the one that can operate other apps. Neither can have both,
 * so this receiver lets the app that DOES get the button hand its sentence to the app that CAN act -
 * no UI, no polling, one intent.
 *
 * It reuses the same start path the capsule and the trigger system already use: ACTION_START on the
 * task service with a goal, so a handed-over sentence runs exactly like one the user typed or spoke.
 *
 * Safety: the receiver requires a signature-level permission, so only an app signed with this app's
 * own build key can send it. Anything else on the phone cannot drive Mika's agent or spend the
 * user's model credits.
 */
class CommandDoor : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return

        val start = Intent(context, TaskService::class.java)
            .setAction(TaskService.ACTION_START)
            .putExtra(TaskService.EXTRA_GOAL, text)
        intent.getStringExtra(EXTRA_CONVERSATION)?.let {
            start.putExtra(TaskService.EXTRA_CONVERSATION, it)
        }
        try {
            context.startForegroundService(start)
            Log.i(TAG, "handed over: ${text.take(80)}")
        } catch (e: Exception) {
            // Android refuses a background service start when Mika has nothing running yet. Opening
            // Mika once fixes it for the rest of the session, so the log says that rather than
            // pretending the sentence was accepted.
            Log.w(TAG, "could not start the task service (${e.javaClass.simpleName}): ${e.message}. "
                    + "Open Mika once, then hold the assistant key again.")
        }
    }

    companion object {
        /** Action the assistant app broadcasts. */
        const val ACTION = "com.wangzan101.mika.KAMI_COMMAND"
        /** The sentence to run, exactly as if the user had said it to Mika. */
        const val EXTRA_TEXT = "text"
        /** Optional Mika conversation id, to continue an existing thread. */
        const val EXTRA_CONVERSATION = "conversation"
        private const val TAG = "MikaCommandDoor"
    }
}

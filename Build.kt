package com.jarvis.phone

/**
 * Which build is actually on the phone.
 *
 * WHY THIS EXISTS. Twice now the question was "I pushed the files, why is it still doing the old thing?" -
 * and the answer needed evidence, not guessing. Pushing to GitHub is not installing: Actions builds an APK,
 * and the phone keeps running whatever was last installed over it. So the build carries a stamp, and the
 * stamp is shown in two places you will look at anyway: the PC pill on the voice sheet, and the PC-link line
 * on the System & Cloud card. If the stamp is not there, the phone is running an older build, full stop.
 *
 * Change STAMP whenever you push something that should be visible, and say so in the commit.
 */
object Build {
    const val STAMP = "11-22a"
    const val NOTES = "hands app work to Mika, cloud heals itself, PC status keeps checking and remembers the route"

    /** Short enough for a pill. Mirrored in assist.js (APP_BUILD) on purpose: the two copies are carried by
     *  different parts of an upload - .kt files and web assets - so which one is missing says which half of
     *  the push actually landed. */
    val SHORT = "app " + STAMP
}

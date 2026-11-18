package io.github.kamakazedan.plexampweb.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The launcher's Settings shortcut. Android clears a shortcut's task before opening it,
 * which would close the player (and stop the music): this opens in a task of its own,
 * and puts Settings over the player instead.
 */
class SettingsShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

package io.github.kamakazedan.plexampweb.ui

import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.kamakazedan.plexampweb.BuildConfig
import io.github.kamakazedan.plexampweb.PlexampWebApp
import io.github.kamakazedan.plexampweb.R
import io.github.kamakazedan.plexampweb.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * The app's own settings (the player's are in the player): which player, its saved copy
 * and checking it for a new version. From the launcher icon's Settings shortcut.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: ActivitySettingsBinding
    private val app: PlexampWebApp get() = PlexampWebApp.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ui = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ui.toolbar.setNavigationOnClickListener { finish() }
        ui.otherPlayer.setOnClickListener { confirmForget() }
        ui.checkNow.setOnClickListener {
            app.gui.start()
            show()
        }
        ui.appVersion.text = getString(R.string.settings_app_version, BuildConfig.VERSION_NAME)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.gui.saving.collect { show() } }
        }
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun show() {
        val base = app.prefs.playerUrl
        ui.playerAddress.text = base ?: getString(R.string.settings_no_player)
        ui.otherPlayer.isVisible = base != null
        val saved = app.gui.current()
        ui.savedCopy.text = when {
            saved == null -> getString(R.string.settings_saved_none)
            saved.appVersion != null -> getString(R.string.settings_saved_version, saved.appVersion, dateOf(saved.savedAt))
            else -> getString(R.string.settings_saved_when, dateOf(saved.savedAt))
        }
        val checking = app.gui.saving.value != null
        ui.checkNow.isEnabled = base != null && !checking
        ui.checkNow.setText(if (checking) R.string.settings_checking else R.string.settings_check_now)
        val at = app.prefs.lastCheckAt
        ui.lastCheck.text = if (at == 0L) {
            getString(R.string.settings_never_checked)
        } else {
            getString(R.string.settings_last_check, agoOf(at), outcomeOf(app.prefs.lastCheckOutcome))
        }
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_forget_title)
            .setMessage(R.string.settings_forget_body)
            .setPositiveButton(R.string.settings_forget_ok) { _, _ ->
                app.forgetPlayer()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun outcomeOf(outcome: String?): String = getString(
        when (outcome) {
            "updated" -> R.string.outcome_updated
            "same" -> R.string.outcome_up_to_date
            else -> R.string.outcome_failed
        },
    )

    private fun dateOf(time: Long): String = DateUtils.formatDateTime(
        this,
        time,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    private fun agoOf(time: Long): String =
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** The existing system action, independent of the edition's own audio processing. */
internal fun phoneEqualizerIntent(context: Context): Intent =
    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)

@Composable internal fun rememberPhoneEqualizerAction(): (() -> Unit)? {
    val context = LocalContext.current
    val intent = remember(context) { phoneEqualizerIntent(context) }
    val available = remember(intent) { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    return if (available) ({ launcher.launch(intent) }) else null
}

/** The light edition keeps the phone's own controls, including an explicit unavailable state. */
@Composable internal fun PhoneEqualizerTile(modifier: Modifier) {
    val open = rememberPhoneEqualizerAction()
    BentoTile(modifier, onClick = open, tag = "settings_phone_equalizer") {
        SettingIcon(Icons.Rounded.Tune, false)
        BentoTitle(string(R.string.phone_equalizer))
        BentoHelp(string(if (open != null) R.string.phone_equalizer_open else R.string.eq_phone_none))
    }
}


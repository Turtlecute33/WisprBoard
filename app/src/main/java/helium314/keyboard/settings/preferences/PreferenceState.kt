// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.utils.prefs

@Composable
fun rememberBooleanPreferenceState(key: String, default: Boolean): MutableState<Boolean> {
    return rememberPreferenceState(key) { prefs -> prefs.getBoolean(key, default) }
}

@Composable
fun rememberStringPreferenceState(key: String, default: String): MutableState<String> {
    return rememberPreferenceState(key) { prefs -> prefs.getString(key, default) ?: default }
}

@Composable
fun rememberIntPreferenceState(key: String, default: Int): MutableState<Int> {
    return rememberPreferenceState(key) { prefs -> prefs.getInt(key, default) }
}

@Composable
fun rememberLongPreferenceState(key: String, default: Long): MutableState<Long> {
    return rememberPreferenceState(key) { prefs -> prefs.getLong(key, default) }
}

@Composable
fun rememberFloatPreferenceState(key: String, default: Float): MutableState<Float> {
    return rememberPreferenceState(key) { prefs -> prefs.getFloat(key, default) }
}

@Composable
private fun <T> rememberPreferenceState(
    key: String,
    readValue: (SharedPreferences) -> T
): MutableState<T> {
    val prefs = LocalContext.current.prefs()
    val state = remember(prefs, key) {
        mutableStateOf(readValue(prefs))
    }

    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, changedKey ->
            if (changedKey == key) {
                state.value = readValue(sharedPreferences)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    return state
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The app data dir in credential-encrypted (CE) storage, for data that must not be readable before
 * the first unlock (PRIV-002). The app default is device-protected (DE) storage, for direct boot.
 * Null if the default dirs are not DE (they are then CE already), or if the CE path is not known:
 * then use the default dirs.
 */
fun credentialProtectedDataDir(context: Context): File? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !context.isDeviceProtectedStorage) return null
    val deDir = context.applicationInfo.deviceProtectedDataDir ?: return null
    return credentialDataDirFor(deDir)?.let(::File)
}

/**
 * The API for a CE context is not public, so the CE path comes from the public DE path. AOSP puts
 * the two dirs at `<volume>/user_de/<user>/<package>` and `<volume>/user/<user>/<package>`.
 */
internal fun credentialDataDirFor(deviceProtectedDataDir: String): String? {
    val marker = "/user_de/"
    val index = deviceProtectedDataDir.lastIndexOf(marker)
    if (index < 0) return null
    return deviceProtectedDataDir.substring(0, index) + "/user/" +
        deviceProtectedDataDir.substring(index + marker.length)
}

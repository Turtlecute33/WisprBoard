// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.Build;

public class ClipboardManagerCompat {

    public static void clearPrimaryClip(ClipboardManager cm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                cm.clearPrimaryClip();
            } catch (Exception e) {
                // workaround for system-caused crash in https://github.com/HeliBorg/HeliBoard/issues/203
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        }
    }

    public static Long getClipTimestamp(ClipData cd) {
        return getClipTimestamp(cd.getDescription());
    }

    /**
     * Overload taking just the description, so callers can decide whether a clip is recent enough
     * to bother with before fetching the clip itself. `ClipboardManager.getPrimaryClip()` copies
     * the whole clip across a binder boundary; `getPrimaryClipDescription()` does not.
     */
    public static Long getClipTimestamp(ClipDescription cd) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cd != null) {
            final long timestamp = cd.getTimestamp();
            if (timestamp > 0) // timestamp is 0 if not set
                return timestamp;
        }
        return System.currentTimeMillis();
    }

    public static Boolean getClipSensitivity(final ClipDescription cd) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return cd != null && cd.getExtras() != null && cd.getExtras().getBoolean("android.content.extra.IS_SENSITIVE");
        }
        return null; // can't determine
    }
}

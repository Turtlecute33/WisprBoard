// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialStorageCompatTest {
    @Test
    fun internalStorageMapsToUserDir() {
        assertEquals("/data/user/0/com.example.app", credentialDataDirFor("/data/user_de/0/com.example.app"))
    }

    @Test
    fun secondaryUserKeepsUserId() {
        assertEquals("/data/user/10/com.example.app", credentialDataDirFor("/data/user_de/10/com.example.app"))
    }

    @Test
    fun adoptedVolumeKeepsVolumePrefix() {
        assertEquals(
            "/mnt/expand/0000-1111/user/0/com.example.app",
            credentialDataDirFor("/mnt/expand/0000-1111/user_de/0/com.example.app"),
        )
    }

    @Test
    fun unknownLayoutGivesNull() {
        assertNull(credentialDataDirFor("/data/data/com.example.app"))
    }
}

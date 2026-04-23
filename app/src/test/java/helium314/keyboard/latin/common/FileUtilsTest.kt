package helium314.keyboard.latin.common

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileUtilsTest {
    @Test
    fun dictionaryImportRequiresDictExtension() {
        assertTrue(FileUtils.hasDictionaryFileExtension("main.dict"))
        assertTrue(FileUtils.hasDictionaryFileExtension("MAIN.DICT"))
        assertFalse(FileUtils.hasDictionaryFileExtension("main.txt"))
        assertFalse(FileUtils.hasDictionaryFileExtension(null))
    }

    @Test
    fun oversizedCopyDeletesPartialFile() {
        val parentDir = Files.createTempDirectory("fileutils-test-").toFile()
        val outFile = File(parentDir, "oversized.dict")

        try {
            assertFailsWith<IOException> {
                FileUtils.copyStreamToNewFile(
                    ByteArrayInputStream(ByteArray(32)),
                    outFile,
                    8,
                )
            }
            assertFalse(outFile.exists())
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun boundedCopySucceedsWithinLimit() {
        val parentDir = Files.createTempDirectory("fileutils-test-").toFile()
        val outFile = File(parentDir, "valid.dict")
        val payload = byteArrayOf(1, 2, 3, 4)

        try {
            FileUtils.copyStreamToNewFile(
                ByteArrayInputStream(payload),
                outFile,
                payload.size.toLong(),
            )
            assertTrue(outFile.exists())
            assertTrue(outFile.readBytes().contentEquals(payload))
        } finally {
            parentDir.deleteRecursively()
        }
    }
}

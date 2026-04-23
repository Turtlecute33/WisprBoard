/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.common;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

import helium314.keyboard.latin.utils.ExecutorUtils;

/**
 * A simple class to help with removing directories recursively.
 */
public class FileUtils {
    public static final long MAX_DICTIONARY_IMPORT_BYTES = 64L * 1024L * 1024L;

    public static boolean deleteRecursively(final File path) {
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        return path.delete();
    }

    public static boolean deleteFilteredFiles(final File dir, final FilenameFilter fileNameFilter) {
        if (!dir.isDirectory()) {
            return false;
        }
        final File[] files = dir.listFiles(fileNameFilter);
        if (files == null) {
            return false;
        }
        boolean hasDeletedAllFiles = true;
        for (final File file : files) {
            if (!deleteRecursively(file)) {
                hasDeletedAllFiles = false;
            }
        }
        return hasDeletedAllFiles;
    }

    /**
     *  copy data to file on different thread to avoid NetworkOnMainThreadException
     *  still effectively blocking, as we only use small files which are mostly stored locally
     */
    public static void copyContentUriToNewFile(final Uri uri, final Context context, final File outfile) throws IOException {
        copyContentUriToNewFile(uri, context, outfile, -1);
    }

    public static void copyDictionaryContentUriToNewFile(final Uri uri, final Context context,
            final File outfile) throws IOException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("dictionary import requires a content uri");
        }
        final String displayName = getContentUriDisplayName(context, uri);
        final String fallbackName = uri.getLastPathSegment();
        if (!hasDictionaryFileExtension(displayName) && !hasDictionaryFileExtension(fallbackName)) {
            throw new IOException("dictionary import must use a .dict source");
        }
        final long declaredSize = getContentUriSize(context, uri);
        if (declaredSize == 0) {
            throw new IOException("dictionary import is empty");
        }
        if (declaredSize > MAX_DICTIONARY_IMPORT_BYTES) {
            throw new IOException("dictionary import is too large");
        }
        copyContentUriToNewFile(uri, context, outfile, MAX_DICTIONARY_IMPORT_BYTES);
    }

    public static boolean hasDictionaryFileExtension(final String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".dict");
    }

    public static void copyContentUriToNewFile(final Uri uri, final Context context, final File outfile,
            final long maxBytes) throws IOException {
        final boolean[] allOk = new boolean[] { true };
        final CountDownLatch wait = new CountDownLatch(1);
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute(() -> {
            try {
                copyStreamToNewFile(context.getContentResolver().openInputStream(uri), outfile, maxBytes);
            } catch (IOException e) {
                allOk[0] = false;
            } finally {
                wait.countDown();
            }
        });
        try {
            wait.await();
        } catch (InterruptedException e) {
            allOk[0] = false;
        }
        if (!allOk[0])
            throw new IOException("could not copy from uri");
    }

    public static void copyStreamToNewFile(final InputStream in, final File outfile) throws IOException {
        copyStreamToNewFile(in, outfile, -1);
    }

    public static void copyStreamToNewFile(final InputStream in, final File outfile,
            final long maxBytes) throws IOException {
        if (in == null) {
            throw new IOException("could not open input stream");
        }
        File parentFile = outfile.getParentFile();
        if (parentFile == null || (!parentFile.exists() && !parentFile.mkdirs())) {
            throw new IOException("could not create parent folder");
        }
        try (InputStream input = in; FileOutputStream out = new FileOutputStream(outfile)) {
            copyStreamToOtherStream(input, out, maxBytes);
        } catch (IOException e) {
            deleteRecursively(outfile);
            throw e;
        }
    }

    public static void copyStreamToOtherStream(final InputStream in, final OutputStream out) throws IOException {
        copyStreamToOtherStream(in, out, -1);
    }

    public static void copyStreamToOtherStream(final InputStream in, final OutputStream out,
            final long maxBytes) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        long totalBytesCopied = 0;
        while ((len = in.read(buf)) > 0) {
            totalBytesCopied += len;
            if (maxBytes >= 0 && totalBytesCopied > maxBytes) {
                throw new IOException("stream exceeded maximum size");
            }
            out.write(buf, 0, len);
        }
        out.flush();
    }

    private static String getContentUriDisplayName(final Context context, final Uri uri) {
        return getContentUriMetadataColumn(context, uri, OpenableColumns.DISPLAY_NAME);
    }

    private static long getContentUriSize(final Context context, final Uri uri) {
        final String value = getContentUriMetadataColumn(context, uri, OpenableColumns.SIZE);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String getContentUriMetadataColumn(final Context context, final Uri uri,
            final String columnName) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[] { columnName },
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            final int columnIndex = cursor.getColumnIndex(columnName);
            if (columnIndex < 0 || cursor.isNull(columnIndex)) {
                return null;
            }
            return cursor.getString(columnIndex);
        } catch (Exception ignored) {
            return null;
        }
    }

}

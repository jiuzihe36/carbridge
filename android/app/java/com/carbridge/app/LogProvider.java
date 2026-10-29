package com.carbridge.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读日志文件 Provider：一键分享时把会话日志当**附件**发给微信/QQ/邮件。
 * 不引 AndroidX，自己暴露 files 目录下的单个日志文件；拒绝路径穿越。
 */
public final class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.carbridge.app.logs";

    public static Uri uriFor(String fileName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(fileName).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        File base = getContext() != null ? getContext().getExternalFilesDir(null) : null;
        if (base == null && getContext() != null) {
            base = getContext().getFilesDir();
        }
        if (base == null) {
            throw new FileNotFoundException("no files dir");
        }
        File f = new File(base, name);
        if (!f.isFile()) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

package io.github.vertango.labelprinter;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Opens the Label Printer web app. When a PDF or picture is opened with this app
 * ("Open with" → Label Printer), the file is handed to the web app the same way as
 * the Share menu does, so it goes straight to the printer.
 */
public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null
                && !"https".equals(in.getData().getScheme())) {
            Intent send = asShare(in);
            if (send != null) setIntent(send);
        }
        super.onCreate(savedInstanceState);
    }

    /** Copies the opened file into this app's own storage and turns the request into a share. */
    private Intent asShare(Intent in) {
        try {
            Uri src = in.getData();
            String type = in.getType();
            if (type == null) type = getContentResolver().getType(src);
            if (type == null) type = "application/pdf";
            String name = displayName(src, type);

            File dir = new File(getCacheDir(), "opened");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            dir.mkdirs();
            File out = new File(dir, name);
            try (InputStream is = getContentResolver().openInputStream(src);
                 OutputStream os = new FileOutputStream(out)) {
                if (is == null) return null;
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            Uri copy = FileProvider.getUriForFile(this, getPackageName() + ".files", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(type);
            send.putExtra(Intent.EXTRA_STREAM, copy);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return send;
        } catch (Exception e) {
            return null; // fall back to just opening the app
        }
    }

    private String displayName(Uri uri, String type) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) { }
        } else if (uri.getLastPathSegment() != null) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) name = "label";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = type.equals("application/pdf") ? ".pdf" : type.equals("image/png") ? ".png" : type.startsWith("image/") ? ".jpg" : "";
        if (!ext.isEmpty() && !name.toLowerCase().endsWith(ext) && !(ext.equals(".jpg") && name.toLowerCase().endsWith(".jpeg"))) name += ext;
        return name;
    }
}

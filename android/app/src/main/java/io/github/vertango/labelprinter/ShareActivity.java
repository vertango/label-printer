package io.github.vertango.labelprinter;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Share → Label Printer, or Open with → Label Printer: prints straight away on the device,
 * without opening anything. (If Bluetooth isn't allowed yet, it opens the Label Printer page instead.)
 */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (!WatchService.canPrint(this)) { handOver(in); return; }
        List<Uri> uris = new ArrayList<>();
        String action = in.getAction();
        if (Intent.ACTION_VIEW.equals(action) && in.getData() != null) uris.add(in.getData());
        else if (Intent.ACTION_SEND.equals(action)) { Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) { List<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        int n = 0;
        for (Uri u : uris) {
            File f = copy(u);
            if (f == null) continue;
            ContextCompat.startForegroundService(this, new Intent(this, WatchService.class)
                    .setAction(WatchService.ACTION_PRINT_FILE).putExtra(WatchService.EXTRA_PATH, f.getAbsolutePath()));
            n++;
        }
        Toast.makeText(this, n == 0 ? "Nothing to print in that share." : "Printing " + (n == 1 ? "1 file" : n + " files") + "…", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void handOver(Intent in) {
        Intent i = new Intent(in);
        i.setClass(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
        finish();
    }

    private File copy(Uri src) {
        try {
            File dir = new File(getCacheDir(), "shared");
            dir.mkdirs();
            File[] old = dir.listFiles(); // keep the last few for Reprint
            if (old != null && old.length > 20) {
                java.util.Arrays.sort(old, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < old.length - 20; i++) old[i].delete();
            }
            String name = "file";
            if ("content".equals(src.getScheme())) {
                try (Cursor c = getContentResolver().query(src, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
                } catch (Exception ignored) { }
            } else if (src.getLastPathSegment() != null) name = src.getLastPathSegment();
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File out = new File(dir, System.currentTimeMillis() % 100000 + "-" + name);
            try (InputStream is = getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(out)) {
                if (is == null) return null;
                byte[] buf = new byte[65536]; int r;
                while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            }
            return out;
        } catch (Exception e) { return null; }
    }
}

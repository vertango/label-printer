package io.github.vertango.labelprinter;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Watches the Downloads folder and prints new shipping label PDFs as soon as they arrive.
 * Only reads new PDFs in Downloads; nothing leaves the device except the label going to the printer.
 */
public class WatchService extends Service {
    public static final String ACTION_PAUSE = "io.github.vertango.labelprinter.PAUSE";
    public static final String ACTION_PRINT_FILE = "io.github.vertango.labelprinter.PRINT_FILE";
    public static final String EXTRA_PATH = "path";
    static final String CH_RUN = "watching", CH_EVENTS = "events";
    static final int NOTE_RUN = 1;
    static final Pattern LABEL_NAME = Pattern.compile(
            "(?i)(label|postage|shipping|royal.?mail|click.?(and|&|n)?.?drop|ebay|evri|hermes|parcel|dpd|yodel|amazon|shopify|despatch|dispatch)");

    private Prefs prefs;
    private HandlerThread thread;
    private Handler handler;
    private final ExecutorService printing = Executors.newSingleThreadExecutor();
    private FileObserver observer;
    private long startedAt;
    private final Map<String, Long> sizes = new HashMap<>();
    private final Set<String> handled = new HashSet<>();
    private boolean watching;
    private int nextEventId = 100;

    public static boolean canRun(Context c) {
        boolean bt = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return bt && canReadDownloads(c);
    }
    public static boolean canReadDownloads(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    public static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, WatchService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Watching Downloads", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_EVENTS, "Skipped files and problems", NotificationManager.IMPORTANCE_DEFAULT));
        }
        thread = new HandlerThread("downloads-watch");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            ServiceCompat.startForeground(this, NOTE_RUN, running("Watching Downloads for labels"),
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0);
        } catch (Exception e) {
            prefs.log("Couldn't start: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            prefs.enabled(false);
            prefs.log("Auto-print paused");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canRun(this)) {
            prefs.log("Auto-print needs Nearby devices and file access — open Label Auto-print");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PRINT_FILE.equals(action)) {
            String path = intent.getStringExtra(EXTRA_PATH);
            if (path != null) printing.execute(() -> {
                process(new File(path), true, 0);
                if (!prefs.enabled()) stopSelf();
            });
        }
        if (!watching && prefs.enabled()) startWatching();
        else if (!prefs.enabled() && !ACTION_PRINT_FILE.equals(action)) stopSelf();
        return START_STICKY;
    }

    private File downloads() { return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); }

    @SuppressWarnings("deprecation")
    private void startWatching() {
        watching = true;
        startedAt = System.currentTimeMillis();
        File dir = downloads();
        File[] now = dir.listFiles();
        if (now != null) for (File f : now) handled.add(key(f)); // don't print what's already there
        prefs.log("Watching " + dir.getName() + " for labels");
        observer = new FileObserver(dir.getPath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) { handler.postDelayed(WatchService.this::scan, 300); }
        };
        observer.startWatching();
        handler.post(this::poll);
    }

    private void poll() {
        if (!watching) return;
        // Check often while the screen is on (when downloads happen); hardly at all while it's off,
        // to save battery. New files also wake it straight away when Android reports them.
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        boolean awake = pm == null || pm.isInteractive();
        if (awake) scan();
        handler.postDelayed(this::poll, awake ? 1500 : 30000);
    }

    private static String key(File f) { return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified(); }

    /** New PDFs, once their size has stopped changing (the download has finished). */
    private void scan() {
        File[] files = downloads().listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || name.startsWith(".") || !name.toLowerCase().endsWith(".pdf")) continue;
            String k = key(f);
            if (handled.contains(k)) continue;
            if (f.lastModified() < startedAt - 5000) { handled.add(k); continue; }
            long len = f.length();
            Long before = sizes.get(k);
            if (len > 0 && before != null && before == len) {
                handled.add(k);
                sizes.remove(k);
                printing.execute(() -> process(f, false, 0));
            } else {
                sizes.put(k, len);
            }
        }
    }

    private void process(File f, boolean force, int attempt) {
        String name = f.getName();
        List<byte[]> jobs = new ArrayList<>();
        int pages = 0;
        try (PdfPages pdf = new PdfPages(f)) {
            pages = pdf.count();
            String rule = prefs.rule();
            boolean named = LABEL_NAME.matcher(name).find();
            for (int n = 0; n < pages; n++) {
                double[] sz = pdf.size(n);
                boolean labelSized = LabelRaster.looksLikeLabel(sz[0], sz[1]);
                boolean ok = force || Prefs.RULE_ALL.equals(rule) || labelSized || (Prefs.RULE_NAMED.equals(rule) && named);
                if (!ok) continue;
                byte[] bits = LabelPipeline.layout(pdf.page(n), prefs.options());
                jobs.add(Tspl.jadensJob(bits, prefs.density(), prefs.speed(), 1, false));
            }
        } catch (Exception e) {
            if (attempt < 3) { // maybe still being written
                handler.postDelayed(() -> printing.execute(() -> process(f, force, attempt + 1)), 2000);
                return;
            }
            prefs.log("Couldn't open " + name);
            event("Couldn't open " + name, "It may not be a normal PDF.", null);
            return;
        }
        if (jobs.isEmpty()) {
            prefs.log("Skipped " + name + " (doesn't look like a label)");
            event("Skipped " + name, "It doesn't look like a shipping label. Tap Print anyway to print it.", f);
            return;
        }
        update("Printing " + name + "…");
        try {
            PrinterLink.print(this, prefs, jobs);
            prefs.log("Printed " + jobs.size() + " label" + (jobs.size() == 1 ? "" : "s") + " from " + name);
            update("Printed " + name);
        } catch (Exception e) {
            prefs.log("Couldn't print " + name + ": " + e.getMessage());
            update("Watching Downloads for labels");
            event("Couldn't print " + name, e.getMessage(), f);
        }
    }

    private PendingIntent openSettings() {
        return PendingIntent.getActivity(this, 0, new Intent(this, AutoPrintActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent serviceIntent(int code, Intent i) {
        return Build.VERSION.SDK_INT >= 26
                ? PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
                : PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification running(String text) {
        Intent pause = new Intent(this, WatchService.class).setAction(ACTION_PAUSE);
        return new NotificationCompat.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle("Label auto-print is on")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openSettings())
                .addAction(0, "Pause", serviceIntent(1, pause))
                .build();
    }
    private void update(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTE_RUN, running(text));
    }
    private void event(String title, String text, File printAnyway) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        int id = nextEventId++;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_print)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openSettings());
        if (printAnyway != null) {
            Intent i = new Intent(this, WatchService.class).setAction(ACTION_PRINT_FILE).putExtra(EXTRA_PATH, printAnyway.getAbsolutePath());
            b.addAction(0, "Print anyway", serviceIntent(id, i));
        }
        nm.notify(id, b.build());
    }

    @Override public void onDestroy() {
        watching = false;
        if (observer != null) observer.stopWatching();
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        printing.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

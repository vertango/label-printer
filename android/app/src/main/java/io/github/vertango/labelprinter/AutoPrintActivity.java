package io.github.vertango.labelprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings for printing labels as soon as they're downloaded. */
@SuppressLint("MissingPermission")
public class AutoPrintActivity extends Activity {
    private Prefs prefs;
    private LinearLayout root;
    private Switch onOff;
    private TextView status, log;
    private Button btPerm, notePerm, filePerm, batteryPerm;
    private Spinner printer;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean filling;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setTitle("Label Auto-print");
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);

        heading("Print labels as soon as they're downloaded");
        text("When this is on, a new shipping label PDF that lands in your Downloads folder (for example after tapping Download on eBay) goes straight to the printer. A small notification shows while it's on, with a Pause button.");
        onOff = new Switch(this);
        onOff.setText("Auto-print downloaded labels");
        onOff.setTextSize(18);
        onOff.setPadding(0, dp(12), 0, dp(12));
        onOff.setOnCheckedChangeListener((v, on) -> { if (!filling) setEnabled(on); });
        root.addView(onOff);
        status = text("");

        heading("1. Allow");
        btPerm = button("Allow Nearby devices (to reach the printer)", v -> {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        });
        filePerm = button("Allow access to Downloads", v -> askFiles());
        text("Android calls this \"All files access\". Label Printer only looks at new PDFs in Downloads and never sends files anywhere.");
        notePerm = button("Allow notifications", v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        });
        batteryPerm = button("Keep it running (don't let the tablet put it to sleep)", v -> askBattery());

        heading("2. Printer");
        printer = new Spinner(this);
        root.addView(printer);
        printer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (i < devices.size()) prefs.printer(devices.get(i).getAddress(), devices.get(i).getName());
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        button("Test print", v -> testPrint());

        heading("3. Which PDFs to print");
        RadioGroup rg = new RadioGroup(this);
        String[][] rules = {
                {Prefs.RULE_LABELS, "Only label-sized pages (4x6)"},
                {Prefs.RULE_NAMED, "Label-sized pages, plus files named like labels (eBay, Royal Mail, Evri, postage, shipping…)"},
                {Prefs.RULE_ALL, "Every PDF I download"}};
        for (String[] r : rules) {
            RadioButton rb = new RadioButton(this);
            rb.setText(r[1]); rb.setTag(r[0]); rb.setId(View.generateViewId());
            rb.setPadding(0, dp(6), 0, dp(6));
            rg.addView(rb);
            if (r[0].equals(prefs.rule())) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> prefs.rule((String) g.findViewById(id).getTag()));
        root.addView(rg);
        text("Anything skipped shows a notification with a Print anyway button.");

        heading("4. Print settings");
        List<String> dens = new ArrayList<>();
        for (int i = 1; i <= 15; i++) dens.add("Darkness " + i + (i == 5 ? " (same as Jadens)" : ""));
        spinner(dens, prefs.density() - 1, i -> prefs.density(i + 1));
        List<String> xs = new ArrayList<>(), ys = new ArrayList<>();
        List<Float> vals = new ArrayList<>();
        for (int i = -12; i <= 12; i++) {
            float mm = i / 2f; vals.add(mm);
            String n = mm == (int) mm ? String.valueOf((int) Math.abs(mm)) : String.valueOf(Math.abs(mm));
            xs.add(mm == 0 ? "Left/right: centred" : n + " mm " + (mm < 0 ? "left" : "right"));
            ys.add(mm == 0 ? "Up/down: centred" : n + " mm " + (mm < 0 ? "up" : "down"));
        }
        spinner(xs, vals.indexOf(prefs.nudgeX()), i -> prefs.nudgeX(vals.get(i)));
        spinner(ys, vals.indexOf(prefs.nudgeY()), i -> prefs.nudgeY(vals.get(i)));
        text("These are separate from the Label Printer app's settings. Your tablet uses 2 mm left.");

        heading("Recent");
        button("Reprint the last label", v -> {
            String last = prefs.lastFile();
            if (last == null || !new java.io.File(last).exists()) { Toast.makeText(this, "Nothing to reprint yet.", Toast.LENGTH_SHORT).show(); return; }
            if (!WatchService.canRun(this)) { Toast.makeText(this, "Allow Nearby devices and Downloads access first.", Toast.LENGTH_LONG).show(); return; }
            ContextCompat.startForegroundService(this, new Intent(this, WatchService.class)
                    .setAction(WatchService.ACTION_PRINT_FILE).putExtra(WatchService.EXTRA_PATH, last));
            Toast.makeText(this, "Reprinting…", Toast.LENGTH_SHORT).show();
        });
        log = text("");
        button("Open Label Printer", v -> startActivity(new Intent(this, LauncherActivity.class)));
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        ui.postDelayed(tick, 2000);
    }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }
    private final Runnable tick = new Runnable() { @Override public void run() { refreshLog(); ui.postDelayed(this, 2000); } };

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        boolean denied = r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED;
        if (denied && p.length > 0 && !shouldShowRequestPermissionRationale(p[0])) {
            // Android won't ask again (it was refused before): open the app's own settings page.
            Toast.makeText(this, "Tap Permissions, then " + (code == 2 ? "Notifications" : "Nearby devices") + ", and choose Allow.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
        refresh();
    }

    private boolean hasBt() {
        return Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasNotes() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refresh() {
        tickIf(btPerm, hasBt(), "Nearby devices allowed");
        tickIf(filePerm, WatchService.canReadDownloads(this), "Downloads access allowed");
        tickIf(notePerm, hasNotes(), "Notifications allowed");
        tickIf(batteryPerm, hasBattery(), "Allowed to keep running");
        devices.clear();
        List<String> names = new ArrayList<>();
        if (hasBt()) for (BluetoothDevice d : PrinterLink.paired(this)) { devices.add(d); names.add(d.getName() + "  (" + d.getAddress() + ")"); }
        if (names.isEmpty()) names.add(hasBt() ? "No paired printers — pair it in Bluetooth settings" : "Allow Nearby devices first");
        printer.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        BluetoothDevice cur = hasBt() ? PrinterLink.pick(this, prefs) : null;
        if (cur != null) { int i = devices.indexOf(cur); if (i >= 0) printer.setSelection(i); prefs.printer(cur.getAddress(), cur.getName()); }
        filling = true;
        onOff.setChecked(prefs.enabled());
        filling = false;
        int today = prefs.todayCount();
        status.setText((prefs.enabled() ? "✅ On — waiting for new labels in Downloads." : "Off.")
                + "\nPrinted today: " + today + " label" + (today == 1 ? "" : "s"));
        refreshLog();
    }
    private void refreshLog() { String l = prefs.log(); log.setText(l.isEmpty() ? "Nothing yet." : l); }

    private void setEnabled(boolean on) {
        if (on && !WatchService.canRun(this)) {
            Toast.makeText(this, "Allow Nearby devices and Downloads access first (step 1).", Toast.LENGTH_LONG).show();
            filling = true; onOff.setChecked(false); filling = false;
            return;
        }
        prefs.enabled(on);
        if (on) { WatchService.start(this); prefs.log("Auto-print turned on"); }
        else { stopService(new Intent(this, WatchService.class)); prefs.log("Auto-print turned off"); }
        refresh();
    }

    private void askFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3);
        }
    }
    @SuppressLint("BatteryLife")
    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void testPrint() {
        if (!hasBt()) { Toast.makeText(this, "Allow Nearby devices first.", Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(this, "Sending a test label…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int W = LabelRaster.W, H = LabelRaster.H;
                Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.drawColor(Color.WHITE);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6);
                int m = 8 + Math.round(prefs.nudgeX() * LabelRaster.DPMM), t = 8 + Math.round(prefs.nudgeY() * LabelRaster.DPMM);
                c.drawRect(m + 16, t + 16, m + W - 32, t + H - 32, p);
                p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(72);
                c.drawText("Label Printer", m + 60, t + 200, p);
                p.setTypeface(Typeface.DEFAULT); p.setTextSize(52);
                c.drawText("Auto-print test", m + 60, t + 290, p);
                c.drawText(new SimpleDateFormat("d MMM HH:mm", Locale.UK).format(new Date()), m + 60, t + 370, p);
                c.drawText("Darkness " + prefs.density(), m + 60, t + 450, p);
                int[] px = new int[W * H];
                bmp.getPixels(px, 0, W, 0, 0, W, H);
                byte[] job = Tspl.jadensJob(LabelRaster.fromLabelPixels(px, 160), prefs.density(), prefs.speed(), 1, false);
                PrinterLink.print(this, prefs, Collections.singletonList(job));
                prefs.log("Test label printed");
                msg = "Test label sent.";
            } catch (Exception e) {
                msg = e.getMessage();
                prefs.log("Test print failed: " + msg);
            }
            String fm = msg;
            ui.post(() -> { Toast.makeText(this, fm, Toast.LENGTH_LONG).show(); refreshLog(); });
        }).start();
    }

    // ---- small UI helpers ----
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    private void heading(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(20); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6)); root.addView(t);
    }
    private TextView text(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(15); t.setPadding(0, dp(4), 0, dp(4)); root.addView(t); return t;
    }
    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        root.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }
    private void tickIf(Button b, boolean ok, String done) {
        if (ok) { b.setText("✅ " + done); b.setEnabled(false); }
    }
    interface OnPick { void pick(int i); }
    private void spinner(List<String> items, int sel, OnPick onPick) {
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        if (sel >= 0) sp.setSelection(sel);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { onPick.pick(i); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        root.addView(sp);
    }
}

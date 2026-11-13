package io.github.vertango.labelprinter;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Auto-print settings, kept on the device. */
public final class Prefs {
    public static final String RULE_LABELS = "labels", RULE_NAMED = "named", RULE_ALL = "all";
    private final SharedPreferences p;
    public Prefs(Context c) { p = c.getSharedPreferences("autoprint", Context.MODE_PRIVATE); }

    public boolean enabled() { return p.getBoolean("enabled", false); }
    public void enabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }
    public String printerAddress() { return p.getString("printer", null); }
    public String printerName() { return p.getString("printerName", null); }
    public void printer(String address, String name) { p.edit().putString("printer", address).putString("printerName", name).apply(); }
    public String rule() { return p.getString("rule", RULE_NAMED); }
    public void rule(String v) { p.edit().putString("rule", v).apply(); }
    public int density() { return p.getInt("density", 5); }
    public void density(int v) { p.edit().putInt("density", v).apply(); }
    public int speed() { return p.getInt("speed", 4); }
    public float nudgeX() { return p.getFloat("nudgeX", -2f); }
    public void nudgeX(float v) { p.edit().putFloat("nudgeX", v).apply(); }
    public float nudgeY() { return p.getFloat("nudgeY", 0f); }
    public void nudgeY(float v) { p.edit().putFloat("nudgeY", v).apply(); }

    public LabelPipeline.Options options() {
        LabelPipeline.Options o = new LabelPipeline.Options();
        o.nudgeXmm = nudgeX(); o.nudgeYmm = nudgeY();
        return o;
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(new Date()); }
    /** Labels printed today. */
    public int todayCount() { return today().equals(p.getString("countDay", "")) ? p.getInt("count", 0) : 0; }
    public synchronized void addPrinted(int n) { p.edit().putString("countDay", today()).putInt("count", todayCount() + n).apply(); }
    public String lastFile() { return p.getString("lastFile", null); }
    public void lastFile(String path) { p.edit().putString("lastFile", path).apply(); }

    public synchronized void log(String line) {
        String t = new SimpleDateFormat("HH:mm", Locale.UK).format(new Date());
        String all = t + "  " + line + "\n" + p.getString("log", "");
        String[] lines = all.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(12, lines.length); i++) b.append(lines[i]).append('\n');
        p.edit().putString("log", b.toString()).apply();
    }
    public String log() { return p.getString("log", ""); }
}

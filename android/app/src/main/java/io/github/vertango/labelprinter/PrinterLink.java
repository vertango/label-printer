package io.github.vertango.labelprinter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends printer commands over Bluetooth Classic (the serial "SPP" profile the Jadens uses).
 * Sending is paced like the Jadens app, and the printer is kept at most one label ahead,
 * so a busy printer isn't overrun (that's what caused striped labels).
 */
@SuppressLint("MissingPermission") // callers check Nearby devices permission first
public final class PrinterLink {
    static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    static final int CHUNK = 1024, SEND_BYTES_PER_SEC = 15000, BT_BYTES_PER_SEC = 10000;

    public static List<BluetoothDevice> paired(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothManager bm = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) return out;
        out.addAll(a.getBondedDevices());
        return out;
    }

    /** The saved printer, or the first paired Jadens-looking one. */
    public static BluetoothDevice pick(Context c, Prefs prefs) {
        String want = prefs.printerAddress();
        BluetoothDevice jd = null;
        for (BluetoothDevice d : paired(c)) {
            if (d.getAddress().equals(want)) return d;
            String n = d.getName();
            if (jd == null && n != null && n.toUpperCase().startsWith("JD-")) jd = d;
        }
        return jd;
    }

    /** Sends the labels. Tries for a while if another app (e.g. Chrome) is still holding the printer. */
    public static void print(Context c, Prefs prefs, List<byte[]> jobs) throws Exception {
        BluetoothDevice dev = pick(c, prefs);
        if (dev == null) throw new IOException("No printer chosen — open Label Auto-print and pick it");
        BluetoothSocket s = null;
        Exception last = null;
        for (int attempt = 0; attempt < 6 && s == null; attempt++) {
            if (attempt > 0) Thread.sleep(2500);
            s = tryConnect(dev, true);
            if (s == null) s = tryConnect(dev, false);
        }
        if (s == null) throw new IOException("Couldn't reach the printer. Is it on, and is another app using it?");
        try {
            OutputStream os = s.getOutputStream();
            long transmitEnd = 0;
            List<Long> finishes = new ArrayList<>();
            long readyAt = 0;
            double printMs = ((150 + 3) / (Math.max(1, Math.min(6, prefs.speed())) * 25.4)) * 1000 + 300;
            for (int i = 0; i < jobs.size(); i++) {
                if (i >= 2) { long wait = finishes.get(i - 2) - System.currentTimeMillis(); if (wait > 0) Thread.sleep(wait); }
                byte[] job = jobs.get(i);
                double gapMs = CHUNK * 1000.0 / SEND_BYTES_PER_SEC;
                for (int off = 0; off < job.length; off += CHUNK) {
                    long t = System.currentTimeMillis();
                    os.write(job, off, Math.min(CHUNK, job.length - off));
                    os.flush();
                    long wait = (long) (gapMs - (System.currentTimeMillis() - t));
                    if (wait > 1 && off + CHUNK < job.length) Thread.sleep(wait);
                }
                long now = System.currentTimeMillis();
                transmitEnd = Math.max(transmitEnd, now) + (long) (job.length * 1000.0 / BT_BYTES_PER_SEC);
                readyAt = (long) (Math.max(now, readyAt) + printMs);
                finishes.add(readyAt);
            }
            // Let Bluetooth finish delivering before letting go of the printer.
            long wait = transmitEnd + 1500 - System.currentTimeMillis();
            Thread.sleep(Math.max(1500, wait));
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static BluetoothSocket tryConnect(BluetoothDevice dev, boolean secure) {
        BluetoothSocket s = null;
        try {
            s = secure ? dev.createRfcommSocketToServiceRecord(SPP) : dev.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (Exception e) {
            if (s != null) try { s.close(); } catch (IOException ignored) { }
            return null;
        }
    }
}

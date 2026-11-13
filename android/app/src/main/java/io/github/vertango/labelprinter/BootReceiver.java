package io.github.vertango.labelprinter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turns auto-print back on after the tablet restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (new Prefs(c).enabled() && WatchService.canRun(c)) {
            try { WatchService.start(c); } catch (Exception ignored) { }
        }
    }
}

package com.hu.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the firewall back after a reboot (only if it was on and consent was given before). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        // The PC link stays off after a reboot (even if Home already brought it up) and gets a fresh PIN.
        if (PcLink.running()) PcLink.stop(c); else PcLink.forget(c);
        Firewall.ensure(c);
    }
}

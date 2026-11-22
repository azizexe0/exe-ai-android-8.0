package com.exeai.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms saved alarms and reminders after the device restarts (AlarmManager alarms do not survive reboot). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        DB db = new DB(context);
        try {
            Reminders.armAll(context, db);
        } finally {
            db.close();
        }
    }
}

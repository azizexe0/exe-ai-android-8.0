package com.exeai.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when an armed reminder is due: shows the notification and re-arms repeating ones. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(Reminders.EXTRA_ID, -1);
        if (id < 0) return;
        DB db = new DB(context);
        try {
            DB.Reminder r = db.reminderById(id);
            if (r == null) return;
            Reminders.notifyUser(context, r.text);
            if (r.repeatDaily) {
                long next = Reminders.nextDay(r.dueMillis);
                db.moveReminder(id, next);
                Reminders.arm(context, new DB.Reminder(id, r.text, next, true));
            } else {
                db.markReminderDone(id);
            }
        } finally {
            db.close();
        }
    }
}

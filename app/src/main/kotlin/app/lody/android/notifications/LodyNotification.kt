package app.lody.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.lody.android.R
import app.lody.android.home.HomeActivity

/** Shared by direct FCM delivery and the developer-mode trigger so both render the same way. */
internal fun postLodyNotification(context: Context, id: Int, title: String?, body: String?, route: String? = null, recipient: String? = null) {
    val intent = Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (route != null && recipient != null) intent.putExtra("route", route).putExtra("recipientUserId", recipient)
    val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = Notification.Builder(context, "lody")
        .setSmallIcon(R.drawable.lody_notification).setContentTitle(title).setContentText(body)
        .setContentIntent(pending).setAutoCancel(true).build()
    context.getSystemService(NotificationManager::class.java).notify(id, notification)
}

/** The app or the Lody channel is off, so nothing posted here can appear. */
internal fun lodyNotificationsBlocked(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java)
    return !manager.areNotificationsEnabled() || manager.getNotificationChannel("lody")?.importance == NotificationManager.IMPORTANCE_NONE
}

package app.lody.android.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import app.lody.android.LodyApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/** OneSignal owns its marked payloads; only direct FCM notifications are displayed here. */
class LodyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as LodyApplication
        app.applicationScope.launch { app.notifications.refresh() }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data.containsKey("custom")) return
        val app = application as LodyApplication
        app.applicationScope.launch {
            if (!app.notifications.canDisplayDirect()) return@launch
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
            val alert = message.notification ?: return@launch
            val recipient = message.data["recipientUserId"]
            if (recipient.isNullOrBlank() || recipient != app.account.state.value.account?.id) return@launch
            val route = message.data["route"]?.takeIf { PushRoute.fromPath(it) != null }
            val id = message.messageId?.hashCode() ?: message.data.hashCode()
            postLodyNotification(this@LodyMessagingService, id, alert.title, alert.body, route, recipient)
        }
    }
}

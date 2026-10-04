package nz.eloque.foss_wallet.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.eloque.foss_wallet.MainActivity
import nz.eloque.foss_wallet.R
import nz.eloque.foss_wallet.location.LocationReminderManager.Companion.REQUEST_ID_SEPARATOR
import nz.eloque.foss_wallet.model.LocalizedPassWithTags
import nz.eloque.foss_wallet.persistence.pass.PassRepository
import nz.eloque.foss_wallet.shortcut.ShortcutService.Companion.BASE_URI

private const val CHANNEL_ID = "location_reminders"

@AndroidEntryPoint
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var passRepository: PassRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val passIds =
            event.triggeringGeofences
                .orEmpty()
                .map { it.requestId.substringBeforeLast(REQUEST_ID_SEPARATOR) }
                .distinct()
        if (passIds.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                passIds.forEach { id ->
                    passRepository.findById(id)?.takeUnless { it.metadata.archived }?.let { showNotification(context, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(
        context: Context,
        passWithTags: LocalizedPassWithTags,
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        createChannel(context)

        val pass = passWithTags.pass
        val barcode = pass.barCodes.firstOrNull()
        val openPass =
            PendingIntent.getActivity(
                context,
                pass.id.hashCode(),
                Intent(Intent.ACTION_VIEW, "$BASE_URI/${pass.id}".toUri(), context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(pass.description)
                .setContentText(context.getString(R.string.location_reminder_text))
                .setContentIntent(openPass)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // Show the pass on the lock screen, not just a hidden placeholder.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        barcode?.toBitmap()?.let { bitmap ->
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
        }

        NotificationManagerCompat.from(context).notify(pass.id.hashCode(), builder.build())
    }

    private fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.location_reminders_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.location_reminders_channel)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}

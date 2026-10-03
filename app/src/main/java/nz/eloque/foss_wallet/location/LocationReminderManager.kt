package nz.eloque.foss_wallet.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import kotlinx.coroutines.flow.first
import nz.eloque.foss_wallet.persistence.SettingsStore
import nz.eloque.foss_wallet.persistence.pass.PassRepository

/**
 * Registers a geofence for every location of every non-archived pass,
 * so a pass can be surfaced when the user arrives somewhere it belongs.
 */
class LocationReminderManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val passRepository: PassRepository,
        private val settingsStore: SettingsStore,
    ) {
        private val geofencingClient by lazy { LocationServices.getGeofencingClient(context) }

        /** Replaces all registered geofences with the current set. Safe to call often. */
        suspend fun refresh() {
            if (!settingsStore.locationRemindersEnabled() || !hasLocationPermissions()) {
                remove()
                return
            }

            val geofences =
                passRepository
                    .all()
                    .first()
                    .filterNot { it.metadata.archived || !it.metadata.locationReminder }
                    .flatMap { passWithMetadata ->
                        passWithMetadata.pass.locations.mapIndexed { index, location ->
                            Geofence
                                .Builder()
                                .setRequestId("${passWithMetadata.pass.id}$REQUEST_ID_SEPARATOR$index")
                                .setCircularRegion(
                                    location.latitude,
                                    location.longitude,
                                    passWithMetadata.metadata.locationRadiusMeters.toFloat(),
                                ).setExpirationDuration(Geofence.NEVER_EXPIRE)
                                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                                .build()
                        }
                    }.take(MAX_GEOFENCES)

            // Clear the previous set first; adding over the top would keep stale geofences.
            geofencingClient.removeGeofences(pendingIntent()).addOnCompleteListener {
                if (geofences.isEmpty()) return@addOnCompleteListener
                val request =
                    GeofencingRequest
                        .Builder()
                        // Default is INITIAL_TRIGGER_ENTER, which would notify on every refresh while already inside a fence.
                        .setInitialTrigger(0)
                        .addGeofences(geofences)
                        .build()
                geofencingClient
                    .addGeofences(request, pendingIntent())
                    .addOnSuccessListener { Log.i(TAG, "Registered ${geofences.size} geofences") }
                    .addOnFailureListener { Log.w(TAG, "Failed to register geofences", it) }
            }
        }

        fun remove() {
            geofencingClient.removeGeofences(pendingIntent())
        }

        private fun hasLocationPermissions(): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val background =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    PackageManager.PERMISSION_GRANTED
                }
            return fine == PackageManager.PERMISSION_GRANTED && background == PackageManager.PERMISSION_GRANTED
        }

        private fun pendingIntent(): PendingIntent {
            val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
            // Geofencing requires a mutable PendingIntent on Android 12 and later.
            val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        }

        companion object {
            const val TAG = "LocationReminderManager"
            const val REQUEST_ID_SEPARATOR = "#"

            // Play services allows at most 100 geofences per app.
            const val MAX_GEOFENCES = 100
            val RADIUS_OPTIONS_METERS = listOf(100, 150, 250, 500, 1000)
            private const val REQUEST_CODE = 4242
        }
    }

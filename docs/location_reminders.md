# Location reminders

Status: **draft spec.** The Google Play services version is implemented on branch `worktree-geofence-passes`. The Google-free version is not implemented yet and is open for discussion.

## Goal

Show a pass when the phone is near the place the pass belongs to, for example a store card when you walk into the store. The notification shows the barcode, so you can scan it without opening the app first. It should appear on the lock screen.

Target: **work on all Android phones**, including phones without Google Play services. Google Play services is an optional accelerator, not a requirement.

## Scope

In scope:
- Passes that contain one or more `locations` in `pass.json` (already parsed by FossWallet).
- Notifying on **entering** a location's radius.
- Per-pass control: on/off and radius.
- Global opt-in switch in Settings, off by default.

Out of scope (for now):
- Leaving notifications, dwell-time triggers, time-based reminders (`relevantDate`).
- Beacons (`beacons` in `pass.json`) and NFC.
- Cloud sync of locations.

## User-facing behavior

### Settings
- **Location reminders** switch, off by default. Turning it on requests:
  1. Fine and coarse location.
  2. Background location (`ACCESS_BACKGROUND_LOCATION`), as a separate step, as Android requires.
  If a permission is denied, the switch stays off and a message explains why.
- Notification permission is already requested at launch.

### Per pass (pass view)
- A pass **without** a location: no reminder. The card says that location reminders are not available for this pass. No controls are shown.
- A pass **with** a location:
  - **Remind me near this location** switch, on by default.
  - **Radius** picker: 100, 150, 250, 500, 1000 m. Default 150 m.
- The per-pass settings only take effect when the global switch is on.

### Notification
- Title: pass description. Text: "You're near a location for this pass".
- Big-picture style with the barcode image, when the pass has a barcode.
- High priority, **public visibility**, so it shows on the lock screen.
- Tapping it opens the pass (`fosswallet://pass/<id>`).
- One notification per pass per entry, identified by pass id.

## Technical design (current implementation)

| Piece | Implementation |
|---|---|
| Geofence API | `GeofencingClient` from Google Play services `play-services-location` 21.4.0 |
| Geofence per | Each `locations` entry of each pass, ID `<passId>#<index>` |
| Trigger | `GEOFENCE_TRANSITION_ENTER` only |
| Initial trigger | Disabled (`setInitialTrigger(0)`), so entering while already inside does not re-notify on every refresh |
| Radius | Per pass, from `PassMetadata.locationRadiusMeters` (default 150) |
| Cap | 100 geofences per app. Extra locations are dropped, so the cap is enforced after filtering |
| Storage | `PassMetadata.locationReminder` (default on), `PassMetadata.locationRadiusMeters` (default 150). Room schema 28, auto-migrated from 27 |
| Refresh triggers | App start, pass add/update, delete, archive/unarchive, auto-archive, per-pass setting change, global switch change, device reboot (`BOOT_COMPLETED`) |
| Refresh method | Remove all geofences registered for our PendingIntent, then add the current set. Geofences are assumed to be cleared on reboot (to verify against Google's docs) |
| Receiver | `GeofenceBroadcastReceiver` (exported=false) |
| Boot | `BootReceiver` (exported=false) |
| Notification channel | `location_reminders`, high importance |

### Permissions added
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`
- `RECEIVE_BOOT_COMPLETED`
- `POST_NOTIFICATIONS` (already present)

### Known limitations
- Requires Google Play services on the device. Without it, the feature does nothing.
- Entering a fence while already inside it at registration time does not notify until the user leaves and comes back.
- Accuracy and battery use depend on Google's location stack.
- Radius minimum is 100 m in the UI. The choice is a judgment call; how reliable small radii are with GPS is not yet tested.

## Google Play services-free option (future)

Requirement: same behavior on any Android phone, with no Google Play services and no proprietary libraries. F-Droid compatible.

Three candidate approaches. None is implemented. Pick one after discussion.

### Option A: Android's built-in `LocationManager` proximity alerts

Use `LocationManager.addProximityAlert(latitude, longitude, radius, expiration, pendingIntent)`. It is part of the Android framework, so no Google dependency is needed. The system fires our existing PendingIntent when the device enters the radius, with an extra indicating entering or leaving.

What changes:
- Replace `GeofencingClient` with `LocationManager`. The receiver, notification, settings, and per-pass UI stay the same.
- Requires the same location permissions.

Open questions (to verify in the Android docs before building):
- Whether the method is deprecated on current Android versions, and whether it is still reliable there.
- Limits on the number of active proximity alerts.
- Which location provider the system uses. The GPS provider works without Google. The network provider usually depends on Google's location service, so on phones without it the quality may drop.
- Behavior after reboot. Alerts may need to be registered again, as with geofences.

Pros: no extra apps, no Google dependency, smallest code change.
Cons: depends on the phone's location providers. Quality varies between devices.

### Option B: microG

microG is an open-source reimplementation of Google Play services (GmsCore). If the user installs it, the current `GeofencingClient` code may work unchanged.

Open questions:
- How complete microG's geofencing and location support is. This needs testing on a real phone.
- Whether the app should depend on microG being installed, or whether it should fall back to Option A when microG is missing.
- It requires the user to install and set up microG, which is a barrier for many users.

Pros: no code change to the existing implementation, if microG supports it.
Cons: extra setup for the user. Not a solution for phones where microG is not installed.

### Option C: Polling

Use a periodic background job that reads the current location and calculates the distance to each pass location locally. No platform geofence API is needed.

What it needs:
- `WorkManager` periodic job. Android enforces a minimum interval of about 15 minutes.
- For faster checks, a foreground service with a persistent notification while reminders are enabled.
- Haversine distance check, and a stored "already notified" state per pass so it does not re-notify on every check.

Pros: works on any phone with GPS, no platform geofence dependency.
Cons: battery use, delays of up to 15 minutes in the background, and a permanent notification if fast checks are needed.

## Comparison

| | Play services (current) | A: LocationManager | B: microG | C: Polling |
|---|---|---|---|---|
| Needs Google Play services | Yes | No | No (needs microG) | No |
| Works on any phone | No | Yes | Only with microG | Yes |
| Detection latency | Fast | Fast-ish, varies | Depends on microG | Up to ~15 min, unless foreground service |
| Battery | Good | Varies by device | Varies | Worst, unless tuned |
| Code change from current | None | Replace geofence client | None if supported | New: scheduler, distance, state |
| Extra user setup | None | None | Install and configure microG | None |

## Open questions for discussion

1. Which option should be the default on phones without Google Play services: A, or C as a fallback?
2. Should the app detect whether Play services is present and pick the implementation automatically?
3. Is a permanent notification acceptable for the foreground-service fallback?
4. Is a minute or more of delay acceptable, or are near-instant arrival alerts required?
5. Radius: is 100 m a good minimum?
6. Should reminders stay enabled after reboot without the user opening the app?

## Testing

- Unit: radius and geofence ID handling, the 100-geofence cap, the per-pass filter (archived, reminder off, no location).
- Device: enter and leave a real location, with the screen off and the app closed. Test after reboot. Test with Play services absent (a degoogled phone or an emulator without Google APIs).
- Mock location: developer options mock location app, to test without travel.

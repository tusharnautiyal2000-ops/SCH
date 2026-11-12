# WhatsApp Scheduler (personal use, Android)

Schedules a WhatsApp message for a chosen date and time. At that time the app opens the
chat with your text pre-filled and taps Send using an Accessibility Service.

## Build
**Option A - Android Studio:** File > Open this folder, wait for Gradle sync, plug in your phone
(USB debugging on) and press Run. Or Build > Build APK(s) and install app-debug.apk.

**Option B - no install (GitHub):** create a private repo, upload this folder, open the
Actions tab > "Build APK" > Run workflow, then download the artifact
`WhatsAppScheduler-debug-apk` (contains app-debug.apk).

## First-time setup on the phone
1. Open the app and tap the three setup buttons (Accessibility, Display over apps, Battery).
2. If Accessibility says "Restricted setting": Settings > Apps > WhatsApp Scheduler > (three dots)
   > Allow restricted settings, then try again.
3. OnePlus: Settings > Apps > WhatsApp Scheduler > Battery usage > Allow background activity.
4. Allow notifications when asked.

## Sending to a WhatsApp group (experimental)
Tick "Send to a WhatsApp group instead" and type the group's EXACT name. At send time the app opens
WhatsApp's "Send to..." screen, finds the group (using the search box if needed), selects it and taps Send.
- The group name must be unique. If a contact has the same name as the group, it could pick the wrong one.
- This depends on WhatsApp's screen layout, so it is less reliable than 1-to-1 messages. Test with a group
  that only has you and one friend before using it for real.

## Repeating messages
Pick "Every hour" (or 30 min / 2 h / 6 h / day / week) before tapping Schedule. The list shows one repeating
entry (tap it to stop the series) plus a history row for each send.

For unattended use, keep a spare phone on a charger with: Lock screen = None, screen timeout of a few minutes
(or Developer options > Stay awake), and WhatsApp Scheduler set to Allow background activity.

## Updating the app
This build uses a fixed signing key (app/debug.keystore), so future versions install over the old one and keep
your settings. If you have the very first build installed, uninstall it once before installing this one.

## Debugging a failed group send
Tap a pending message > "Test now (send immediately)" to trigger it right away instead of waiting for the
clock - much faster for testing. While it runs, pull down the notification shade: a notification titled
"WhatsApp Scheduler - step" updates live with exactly what the app is doing (e.g. "Typing 'GroupName' into
search", "Found Send button - tapping"). If it fails, tell me the LAST step text you saw and I can fix that
exact step.

## If a group send still fails
After the failure, open the app and tap "Copy debug info", then paste it into the Claude chat. It contains a
list of what WhatsApp's screen looked like at the moment it got stuck, so the exact button can be targeted.

## Limits
- Phone must be unlocked (or have no lock) at send time for the automatic tap to work.
  If it can't, a notification appears: tap it, then tap Send yourself.
- WhatsApp updates can rename the Send button and break auto-tap.
- Messages closer than 45 s to each other are spaced apart automatically.

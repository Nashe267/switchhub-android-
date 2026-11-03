# Switch Hub for Android

The Android app for Switch Hub (Switch Graphics). It opens Switch Hub from your PC,
on the shop Wi-Fi or anywhere through Tailscale, and adds the phone things:

* finds your PC on the Wi-Fi by itself (no typing)
* remembers the PC's Tailscale address, so it also works on mobile data
* invoice / quote / statement PDFs open in your PDF viewer or go **straight to WhatsApp**
* take a photo of a supplier invoice from the To Pay upload
* WhatsApp reminders and phone links open the right app
* Android back button works

## Getting the APK
Every push to `main` builds the app on GitHub (Actions → "Build Android app") and publishes
`SwitchHub.apk` under **Releases**. On the phone: open the latest release, tap `SwitchHub.apk`,
then open it and tap **Install** (allow "Install unknown apps" for Chrome the first time).
New versions install over the old one; nothing needs setting up again.

## Needs
Switch Hub 3.6 or newer on the PC, with **Settings → Phone app** switched on and a PIN chosen.

`switchhub-release.jks` is the app's signing key. Keep this repository private and keep the
key: Android only accepts updates signed with the same key.

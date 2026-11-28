# Automatic pairing

Horus uses Android Wireless debugging for privileged actions, so it does not need the Shizuku app. Pairing is one fixed path:

1. **Accessibility first.** Tapping Auto Pairing checks that the Horus Accessibility service is on. If it is off, Horus opens Accessibility settings and stops there.
2. **Return.** When the service connects, Horus brings you back to the app and pairing continues.
3. **Developer options.** Horus opens Developer options and scrolls until it finds Wireless debugging. It turns it on if it is off.
4. **Pair.** Horus opens Pair device with pairing code, reads the code and port from the screen or the system notification, and pairs.
5. **Done.** On success Horus returns you to the app and connects.

Steps already satisfied are skipped. The order never changes. The Accessibility service reads the settings screens only to find these controls and the pairing code.

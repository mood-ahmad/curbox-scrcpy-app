# Curbox API

Curbox exposes an API that lets other apps control it, much like Shizuku does: your app binds to a
service, the user approves your app once, and after that you can run commands and read state. Access
is per app and revocable, so the user stays in control.

This document is the integration guide. A complete working client lives in the `:apitester` module
of this repo; read its `MainActivity.kt` alongside this doc.

---

## At a glance

- **Transport:** an exported AIDL bound service (`ICurboxApi`).
- **Identity:** the calling app's verified UID, resolved to its package. No tokens.
- **Permission:** the user approves your app from a dialog Curbox shows. They can remove access any
  time under **Reducers → Curbox API → Connected apps**, or turn the whole API off with one switch.
- **API version:** `2` (call `apiVersion()` to check).

---

## 1. Add the contract to your project

Copy [`ICurboxApi.aidl`](app/src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl) into your project,
keeping the exact path and package:

```
src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl
```

```aidl
package neth.iecal.curbox.api;

interface ICurboxApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
}
```

Enable AIDL in your module's `build.gradle.kts`:

```kotlin
android {
    buildFeatures { aidl = true }
}
```

## 2. Declare package visibility

On Android 11 (API 30) and above you must declare that you talk to Curbox, or you will not be able
to see or bind the service. Add this to your `AndroidManifest.xml`:

```xml
<queries>
    <intent>
        <action android:name="neth.iecal.curbox.api.BIND" />
    </intent>
    <intent>
        <action android:name="neth.iecal.curbox.api.REQUEST_PERMISSION" />
    </intent>
</queries>
```

---

## Intents and actions

| Purpose | Action |
| --- | --- |
| Bind the service | `neth.iecal.curbox.api.BIND` |
| Ask the user for permission | `neth.iecal.curbox.api.REQUEST_PERMISSION` |

> **Find Curbox by action, not by a hardcoded package.** Debug builds of Curbox use the application
> id `neth.iecal.curbox.debug`, release builds use `neth.iecal.curbox`. Resolve the package at
> runtime with `PackageManager.resolveService(Intent(BIND_ACTION), 0)` and reuse that package name
> for both binding and the permission request.

---

## 3. Bind the service

```kotlin
private var api: ICurboxApi? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        api = ICurboxApi.Stub.asInterface(service)
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        api = null
    }
}

fun connect() {
    val resolved = packageManager.resolveService(Intent("neth.iecal.curbox.api.BIND"), 0)
        ?: return // Curbox not installed
    val curboxPackage = resolved.serviceInfo.packageName
    val intent = Intent("neth.iecal.curbox.api.BIND").setPackage(curboxPackage)
    bindService(intent, connection, Context.BIND_AUTO_CREATE)
}
```

## 4. Request permission

A binder cannot show UI, so the permission request goes through an activity instead. Launch it
**for result** so Curbox can read your package reliably:

```kotlin
private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val allowed = result.resultCode == Activity.RESULT_OK
    }

fun requestPermission(curboxPackage: String) {
    val intent = Intent("neth.iecal.curbox.api.REQUEST_PERMISSION").setPackage(curboxPackage)
    permissionLauncher.launch(intent)
}
```

The user sees a dialog naming your app and finishes with Allow or Deny. You can check the current
state any time with `api.isGranted` (false when the user has not allowed you, has removed you, or
has turned the whole API off).

> Binder calls run on a pool thread and may block briefly while Curbox writes its settings. Call
> `execute`, `query` and `list` off your main thread.

---

## 5. Run actions: `execute(command, args)`

Returns one of `OK`, `DENIED`, `UNKNOWN_COMMAND`, or `FAILED`.

| Command | Args | Effect |
| --- | --- | --- |
| `START_FOCUS` | `target` (focus group id), `minutes` (int, default 25) | Starts a focus session for that group |
| `STOP_FOCUS` | none | Ends all running focus sessions |
| `SET_APP_BLOCKER_GROUP` | `target` (group id), `enable` (bool) | Turns an app blocker group on or off |
| `SET_KEYWORD_BLOCKER` | `enable` (bool) | Turns the keyword blocker on or off |
| `SET_KEYWORD_GROUP` | `target` (group id), `enable` (bool) | Turns a keyword group on or off |
| `SET_REEL_BLOCKER` | `enable` (bool) | Turns the reel blocker on or off |
| `SET_UI_HIDER` | `enable` (bool) | Turns the UI hider on or off |
| `SET_GRAYSCALE_GROUP` | `target` (group id), `enable` (bool) | Turns a grayscale group on or off |
| `SET_REEL_COUNTER` | `enable` (bool) | Turns the reel counter on or off |
| `SET_DND` | `enable` (bool) | Turns Do Not Disturb on or off |

Argument keys: `target`, `enable`, `minutes`.

```kotlin
val args = Bundle().apply {
    putString("target", focusGroupId)
    putInt("minutes", 5)
}
val status = api?.execute("START_FOCUS", args) // "OK"
```

## 6. Read state: `query(state)`

Returns a JSON object, or `null` when not allowed or the state is unknown.

| State | JSON fields |
| --- | --- |
| `FOCUS_ACTIVE` | `active` ("true"/"false"), `focus_group`, `focus_remaining` (minutes) |
| `SCREENTIME_TODAY` | `screentime` (minutes today) |
| `REELS_TODAY` | `reels` (count today) |

```kotlin
api?.query("SCREENTIME_TODAY") // {"screentime":"73"}
```

## 7. Discover things: `list(kind)`

Returns a JSON array (an object for `STATUS`), or `null` when not allowed or the kind is unknown.
Use the group lists to find the `id` you pass back as `target` in `execute`.

| Kind | Each item |
| --- | --- |
| `FOCUS_GROUPS` | `id`, `name`, `apps`, `websites`, `blockMode`, `exitable`, `autoTurnOnDnd` |
| `APP_BLOCKER_GROUPS` | `id`, `name`, `isActive`, `apps` |
| `KEYWORD_GROUPS` | `id`, `name`, `isActive`, `keywords` |
| `GRAYSCALE_GROUPS` | `id`, `name`, `isActive`, `apps` |
| `AUTO_DND_GROUPS` | `id`, `name`, `autoTurnOnDnd` |
| `UI_HIDER_SCRIPTS` | `id`, `label`, `packageName`, `isEnabled` |
| `STATUS` | object: `focusActive`, `focusGroup`, `focusRemainingMinutes`, `keywordBlocker`, `reelBlocker`, `reelCounter`, `uiHider` |

```kotlin
api?.list("FOCUS_GROUPS")
// [{"id":"a1b2...","name":"Study","apps":12,"websites":3,"blockMode":"BLOCK_SELECTED","exitable":true,"autoTurnOnDnd":false}]
```

---

## End to end example

```kotlin
// 1. resolve + bind (see connect() above)
// 2. if (api?.isGranted != true) requestPermission(curboxPackage)
// 3. once granted:
val groups = api?.list("FOCUS_GROUPS")            // discover a group id
val firstId = JSONArray(groups).getJSONObject(0).getString("id")

api?.execute("START_FOCUS", Bundle().apply {      // act on it
    putString("target", firstId)
    putInt("minutes", 25)
})

api?.query("FOCUS_ACTIVE")                         // confirm: {"active":"true",...}
```

---

## Notes

- **Effects vs writes.** Commands always write Curbox's settings, but a visible effect (focus mode
  actually blocking, DND flipping) only happens while Curbox's accessibility services are enabled.
- **Permission is per package.** If your app uses a shared UID, any package in that UID that the
  user allowed grants the whole UID.
- **Be ready for `DENIED`/`null`.** The user can remove your app or switch the API off at any moment.
- **Versioning.** Check `apiVersion()` if you depend on newer features. `list()` arrived in version
  2.

## Try it with the bundled tester

The `:apitester` module is a runnable app that drives every call in this doc, including a
"Dump everything" button that lists all groups and status at once.

```bash
./gradlew :app:installAndGrantAccessibilityFdroidDebug :apitester:installDebug
adb shell am start -n neth.iecal.curbox.apitester/.MainActivity
```


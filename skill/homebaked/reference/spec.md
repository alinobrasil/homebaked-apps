# Homebaked mini-app spec

What an app must be to run in the Homebaked Android app. Read all of it before writing one.

## 1. What you're building

A **mini-app** is a folder of plain static files that runs in a WebView on an Android phone,
full screen, like a native app. No build step, no bundler, no framework that needs compiling,
no server. The files you write are the files that run.

```
tip-calculator/       folder name = app id: lowercase letters, digits, hyphens
  index.html          required, at the folder root
  manifest.json       expected
  icon.png            optional (see section 4)
```

**Write one self-contained `index.html`**, with CSS and JS inline. Extra files (images, sounds)
are allowed and referenced by relative path, but one file keeps pushes and edits simple.
Keep the whole folder small: well under a few MB.

## 2. The promise: no ads, no tracking, offline

These are what the person is trusting Homebaked for. Never break them, even if it would be
convenient:

- **No analytics, trackers, ads, telemetry, "crash reporting", or third-party embeds.**
- **No external scripts, stylesheets or fonts.** No CDNs (`cdnjs`, `jsdelivr`, `unpkg`, Google
  Fonts…). Write the code yourself or inline it. Use system fonts:
  `font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`.
- **Offline by default.** An app reaches no website at all unless its manifest lists the host
  under `"network"`. The phone *enforces* this: undeclared requests are blocked, and scripts
  are never loaded from other sites even when declared.
- **Declare a network host only when the app's purpose needs live data** the person asked for
  (weather, a public transit feed). Prefer APIs that need no key and no
  account. Tell the person which site and why. The app gets a 🌐 badge. The API must send
  CORS headers (`Access-Control-Allow-Origin`); check before you rely on it.
- **Keep the person's data on the phone.** Never send what they type anywhere.

## 3. manifest.json

```json
{
  "name": "Tip Calculator",
  "category": "Utilities",
  "version": "1",
  "emoji": "🧮",
  "color": "#2F9E44"
}
```

| Field | Meaning |
|---|---|
| `name` | Label under the tile. Short: about 14 characters show. |
| `category` | Launcher section: `Games` (default) or `Utilities`, or any word to make a new section (`Learning`, `Family`). |
| `version` | Shown in the app's info sheet. Bump it with each change. |
| `emoji` | Tile picture when there's no `icon.png`. One emoji. |
| `color` | Tile background, `#RRGGBB`. Pick a saturated colour that suits the app. |
| `network` | Hosts the app may reach, e.g. `["api.open-meteo.com"]`; `"*.example.com"` covers subdomains. Omit for offline (the default and the norm). |
| `shared` | Shared storage buckets, e.g. `["rewards"]`. See section 7. |

It must be valid JSON. `homebaked.py push` refuses an invalid manifest.

## 4. Icon

Normally just set `emoji` and `color` in the manifest: the launcher draws the emoji on that
colour. That's the default; it looks good and needs no image tools.

An `icon.png` overrides them. Only make one when there's a reason to (the person asks for a
custom icon, or supplies an image). Rules: square, 320×320, fill the square edge to edge (the
launcher rounds the corners; don't add your own corners or transparency), bold shapes and
high contrast (it shows at about a third of the phone's width).

## 5. Runtime

| | |
|---|---|
| Engine | Android System WebView (current Chromium) |
| Android | 8.0 and up |
| URL | `https://<app-id>.homebaked.internal/index.html`: the app's own private origin |
| JavaScript, localStorage, IndexedDB | yes |
| Orientation | unlocked; the app must survive rotation |
| Service workers | no |

**System bars are already handled.** The status bar and gesture bar are outside the page, so
`100dvh`/`100vh` is the real usable height. Bottom-anchored controls are safe. Do **not**
add `env(safe-area-inset-*)` padding.

## 6. Hard rules

**Single page.** The Android back button closes the app; it doesn't walk history. No
`pushState` routing, no navigating to other pages. Swap views with JavaScript.

**Links to websites** open only after the person taps them and confirms leaving the app. Don't
redirect `location` to another site; it's blocked.

**Touch first.** Phone, portrait, thumbs. Tap targets at least 44px. Nothing that needs hover.
Use this viewport tag exactly:

```html
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
```

Set `touch-action: manipulation` on the body (removes the double-tap delay), or `none` on a
game canvas that handles its own gestures.

**Plain modern JavaScript** that runs directly in the browser. No JSX, TypeScript, imports that
need resolving, or npm packages.

**Show a small version label** somewhere unobtrusive (e.g. `v3` in a corner) and bump it with
every change, so the person can see an update arrived.

## 7. Storage

Each app's `localStorage` and IndexedDB are **private to it**: other apps can't see them, and
key names can't collide. They survive closing the app, rebooting, and updates. They're erased
when the app is deleted.

Wrap every read and write in try/catch; the app must start even if storage fails:

```js
var KEY = 'state';
function load() { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } }
function save(s) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) {} }
```

**Save state as it changes**, not only when leaving. A push to an open app reloads it, and
Android may kill a backgrounded app.

**Changing the shape of saved data later?** Read the old shape and convert it, or the person
loses their progress on the next update.

### Sharing data between apps

When two apps should share something (points earned in a spelling game that unlock levels in
a puzzle game), both list the same bucket name in `manifest.json` (`"shared": ["rewards"]`)
and use the bridge:

```js
var B = window.AndroidBridge;
var pts = B ? Number(B.sharedGet('rewards', 'points') || 0) : 0;   // missing → undefined, so use || or == null
if (B) B.sharedSet('rewards', 'points', String(pts + 5));        // values are strings; JSON.stringify objects
```

`sharedGet(bucket, key)`, `sharedSet(bucket, key, value)` (returns false over 512K
characters), `sharedRemove(bucket, key)`, `sharedKeys(bucket)` (JSON array string). An app can
only use buckets its manifest lists. Read the shared value fresh each time you need it, rather
than caching it, because the other app may have changed it. Agree on the key names and value
format between the apps, and write them down in a comment in both.

## 8. Native bridge

`window.AndroidBridge` exists only on the phone. **Always feature-detect it** so the app also
works in a desktop browser.

| Call | Does |
|---|---|
| `toast(message)` | Short Android toast |
| `speak(text, lang, rate)` | Text to speech with the phone's voice. `lang` like `"en-US"`, `"ko-KR"`, or `""` for the phone's language; `rate` 1 = normal. Interrupts the previous line. (WebView's own `speechSynthesis` is silent; use this.) |
| `stopSpeaking()` | Stop speaking |
| `ttsStatus(lang)` | JSON string: `ready`, `langSupported`, … |
| `listen(lang)` | Listen for one phrase, **on the phone only**. The result arrives once as `window.onSpeechResult(jsonString)`: `{"ok":true,"matches":[…]}` (empty if nothing heard) or `{"ok":false,"error":"unavailable"|"permission"|"language"|…}` |
| `stopListening()` | Stop listening |
| `asrStatus()` | JSON string: `available` (needs Android 12+ and an offline language pack), `permission` |
| `keepScreenOn(on)` | `true` stops the screen turning off while the app is open; `false` lets it sleep again. Use it for anything watched without touching (timers, recipes, scoreboards), only while it's needed. Without it the phone's screen timeout pauses the app, and timers miss their beeps. Homebaked 0.1.1+, so feature-detect `AndroidBridge.keepScreenOn`. |
| `sharedGet` / `sharedSet` / `sharedRemove` / `sharedKeys` | Section 7 |

Web APIs that work: Canvas, WebGL, Web Audio (after a tap), `navigator.vibrate`,
`requestAnimationFrame`, Pointer Events, `matchMedia`, Fullscreen. Not available: camera and
microphone through the web APIs (use `listen`), geolocation, notifications, WebRTC.

For voice features, check `asrStatus()` first and offer a typed fallback when it isn't
available. Speech recognition never uses the cloud; that's deliberate.

## 9. Things that break apps on phones

Real failures from building these:

- **Animation silently doesn't happen if you rebuild the DOM.** Recreating elements each frame
  gives the browser nothing to animate between. Keep moving elements as persistent nodes and
  move them with `transform: translate()`. The most common mistake by far.
- **Don't switch off essential motion with `prefers-reduced-motion`.** Fine for decoration,
  but if motion *is* the feature, honouring it ships a broken app.
- **Android can turn off CSS transitions entirely** (developer animation scale 0, or the
  accessibility "remove animations" switch). If motion is essential, drive it from
  `requestAnimationFrame`.
- **Use a fixed timestep for anything physical.** Phones run at 60, 90 or 120 Hz. Step the
  simulation from an accumulator at a fixed rate and clamp `dt`, or a game runs double speed
  on a 120 Hz phone and jumps ahead after returning from the background.
- **Measure, don't calculate, layout positions.** Use `getBoundingClientRect` instead of
  re-deriving CSS maths in JavaScript.
- **Phones are narrow.** Design for 360px wide; a row of seven items leaves ~51px each.
- **Symbol glyphs (♠, ★) render smaller than digits** at the same font size; size them up.
- **Audio needs a tap first.** Create or resume the `AudioContext` inside a touch handler.
- **An idle audio output goes to sleep**, cutting off the start of the next sound or all of it.
  For an app that beeps on a schedule, keep a near-silent oscillator running while it's active,
  and `resume()` the context before each sound.

## 10. Template

```html
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>My App</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100dvh; padding: 16px;
         font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         background: #14212e; color: #eee; touch-action: manipulation;
         user-select: none; -webkit-user-select: none; }
  button { min-height: 48px; padding: 12px 22px; font-size: 18px; border-radius: 10px;
           border: 0; background: #2f9e44; color: #fff; }
  .ver { position: fixed; right: 8px; bottom: 6px; font-size: 11px; opacity: .4; }
</style>
</head>
<body>
  <h1>My App</h1>
  <button id="go">Tap me</button>
  <div class="ver">v1</div>
<script>
var KEY = 'state';
function load() { try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; } }
function save(s) { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) {} }

var state = load();
document.getElementById('go').addEventListener('click', function () {
  state.count = (state.count || 0) + 1;
  save(state);
  if (window.AndroidBridge) AndroidBridge.toast('Count ' + state.count);
});
</script>
</body>
</html>
```

## 11. The home screen (`home`)

An app folder named `home` replaces the phone's built-in home screen. Everything above applies
(own origin, offline unless declared, storage private to it), plus:

- It gets **`window.HomeBridge`**, not `AndroidBridge`:

| Call | Does |
|---|---|
| `HomeBridge.apps()` | JSON string: `[{id, name, category, version, emoji, color, icon, online, network, shared}]` for every other app. `icon` is a same-origin URL (use it as an image source) or `""`; `color` is `#RRGGBB` or `""`. |
| `HomeBridge.open(id)` | Open an app |
| `HomeBridge.details(id)` | The phone's own info sheet for that app (what it can reach; delete) |
| `HomeBridge.toast(message)` | Short toast |
| `window.onAppsChanged()` | Define it: called when an app is added, updated or removed. Re-read `apps()` and redraw. |

- It can see other apps' names and icons, and nothing else of theirs.
- It sits under a native bar (Homebaked's name and status). Linking, unlinking and the switch
  back to the built-in home screen live there and can't be changed by the page.
- Show the `🌐` badge for apps with `online: true`, and wire long-press to `details(id)`, so
  people can always see what an app can reach and remove it.
- Feature-detect `HomeBridge` and show sample apps without it, so the page can be previewed in a
  desktop browser.
- Start from `reference/home-template.html`.

## 12. Checklist before pushing

- [ ] `index.html` at the folder root; works with no network
- [ ] No external scripts, styles, fonts, trackers; `homebaked.py push` shows no `WARNING`
      (or the host is deliberately declared and the person knows why)
- [ ] `manifest.json` valid, with `name`, `category`, `version`, `emoji`, `color`
- [ ] Looks right at 360×800 and after rotating; tap targets ≥ 44px; no hover-only controls
- [ ] Storage reads/writes in try/catch; state saved as it changes
- [ ] `AndroidBridge` feature-detected
- [ ] Moving elements are persistent nodes moved with `transform`
- [ ] Physics on a fixed timestep with clamped `dt`
- [ ] Version label bumped

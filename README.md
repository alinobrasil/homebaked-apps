# Homebaked

**Make your own phone apps by talking to Claude. No ads, no tracking, no app store.**

People bake at home so they know exactly what goes in. Homebaked does the same for
apps. You describe a simple app (a tip calculator, a chore chart, a flashcard game for your
kid) and Claude builds it and puts it on your Android phone. Nothing else is in it: no
ads, no trackers, nothing collecting your data in the background. It's yours.

### Why I made it: apps for my kids

Most apps in the app store are full of ads, often with overstimulating or harmful content,
and full of traps that get kids to tap their way into a paid subscription. I wanted apps my
kids could use without any of that, so I make them myself. With Homebaked, **you decide what
your kids get to use.** For example:

- **A language learning app**, with lessons, stars and the phone reading words out loud.
- **Classic games** like backgammon, solitaire and minesweeper, **with a cap on how many
  games a day**, and the option to **unlock 5 more games by finishing a language lesson**.

Whatever you want, you decide. Just talk to Claude, and it goes to your phone over Wi-Fi in an
instant.

**Using Homebaked:** [Requirements](#requirements) · [Setup](#setup) ·
[Making apps](#making-apps) · [Your data and privacy](#your-data-and-privacy) ·
[Good to know](#good-to-know) · [If something goes wrong](#if-something-goes-wrong) ·
[Removing it](#removing-it)

**How it's built:** [How it works](#how-it-works) ·
[What's in this repository](#whats-in-this-repository) ·
[Building it yourself](#building-it-yourself) · [For Claude](#for-claude-installing-the-skill)

---

## Requirements

**To use it:**

| You need | Details |
|---|---|
| An Android phone | Android 8 or newer. Voice input inside apps needs Android 12 or newer with offline speech for your language. There's no iPhone version (iOS doesn't allow this kind of app). |
| A computer | Mac, Windows or Linux |
| The **Claude desktop app** | With a Claude plan that includes Claude Code. You'll use its **Code** tab. Other ways of using Claude (claude.ai in a browser, the phone app) can't reach your phone, so they won't work. |
| Python 3.8 or newer | On the computer. Linux usually has it. On Mac and Windows, Claude offers to install it if it's missing; nothing else needs installing. |
| The same Wi-Fi | Phone and computer on the same home network. See [Good to know](#good-to-know). |

Setup takes about 10 minutes, once. To build the phone app yourself instead of downloading
it, see [Building it yourself](#building-it-yourself).

---

## Setup

### 1. Install Homebaked on your phone

1. On your phone, open **[the latest release](https://github.com/alinobrasil/homebaked-apps/releases/latest)**
   and tap **`Homebaked-<version>.apk`** (for example `Homebaked-0.2.0.apk`) to download it.
2. Open the downloaded file. Android asks whether your browser may install apps: tap
   **Settings**, turn on **Allow from this source**, go back, and tap **Install**.
3. If Google Play Protect warns that the app is unrecognized, tap **More details → Install
   anyway**. You'll see this for any app that doesn't come from the Play Store.
4. Open **Homebaked**. You'll see a **Scan QR code** button. Leave it there for now.

### 2. Add the Homebaked skill to Claude

1. On your computer, open the **Claude desktop app** and click the **Code** tab.
2. When it asks for a folder, make a new one for your apps, for example
   `Documents/My Apps`, and choose it.
3. Paste this message and send it:

   > Install the Homebaked skill from https://github.com/alinobrasil/homebaked-apps

4. Claude shows what it's about to do and asks for permission. Allow it. When it's done,
   it tells you the skill is installed.

### 3. Link your phone

1. In the same Code tab, type:

   > Link my phone to Homebaked.

2. Claude shows a **QR code**.
3. On your phone, in Homebaked, tap **Scan QR code**, allow the camera, and point it at the
   code. (Your phone's normal camera app works too.)
4. Tap **Link**. Claude replies that your phone is linked.

If your computer asks whether **Python** may accept incoming connections or use the network,
choose **Allow** (on Windows, tick **Private networks**). That's how your phone tells Claude
it's linked.

That's the setup done.

---

## Making apps

Open Homebaked on your phone, then ask Claude in the Code tab. For example:

> Make me a tip calculator and put it on my phone.

> Make a 10-minute workout timer with big buttons and a beep between exercises.

> Make a spelling game for my 7-year-old using these words: ...

Claude writes the app and sends it to your phone, and it shows up in Homebaked within a
few seconds. **Homebaked has to be open on your phone while Claude sends it.**

### Not sure what to make?

Ask Claude *"What could I make?"* — or try one of these:

- "A chore chart for the kids with stars they earn each day."
- "Flashcards for my Spanish vocabulary, with the phone saying each word out loud."
- "A breathing exercise: a circle that grows and shrinks, 4 seconds in, 6 out."
- "A memory matching game with animal emojis for a 4-year-old."
- "A water tracker: tap a glass each time I drink, resets at midnight."
- "A countdown to my holiday, with the days left in big numbers."

### Changing an app

Just say what to change:

> Make the buttons bigger and use a dark background.

If the app is open on your phone, it reloads by itself with the change.

### Other things you can ask

| Say | What happens |
|---|---|
| "What apps are on my phone?" | Claude lists them |
| "Delete the workout timer from my phone." | It's removed, along with its saved data |
| "Make points from my spelling game unlock extra levels in my puzzle game." | The two apps share a bit of storage (see below) |
| "Link another phone." | A new QR code, for a second phone |

On the phone, **long-press** an app to see what it can do, or to delete it.

### Your own home screen

The home screen itself can be changed the same way, by asking Claude:

> Make the icons on my home screen smaller and put my kids' apps at the top.

> Give my home screen a dark background and a search box.

Claude sends a new home screen and it appears right away. To go back to the original, tap
**Ready ⋮** at the top of Homebaked and choose **Use the built-in home screen**.

---

## Your data and privacy

**Apps are offline unless they need the internet.** Homebaked blocks every app from using the
internet unless the app declares why it needs it, such as a weather app that fetches
today's forecast. Apps that can go online have a 🌐 badge on their icon; long-press the icon to
see exactly which sites they can reach. This is enforced by Homebaked itself, so an app can't
quietly phone home even if its code tries to.

**Each app's data is private to that app.** One app can't read another app's saved data or
files. If you want two apps to share something (points, a score, a list), ask Claude. They'll
share one named storage area, and nothing else.

**Nothing goes to a cloud.** There are no accounts, no sign-in and no servers. Apps travel
from your computer to your phone over your own Wi-Fi. Homebaked doesn't back up to Google's
cloud. A direct transfer when you set up a new phone still copies everything over.

**One honest exception: Claude itself.** When you build an app with Claude, your conversation
and the app's code are sent to Anthropic, the company that makes Claude, because that's where
Claude runs. **What you type into your apps on the phone never goes there.** Your recipes,
budgets and kids' scores stay on your phone. If you want even the building step to stay
private, look at [Segra](https://www.segra.ai/), which runs AI models on your own hardware.

---

## Good to know

**Same Wi-Fi, and Homebaked open.** Claude can only reach your phone when both are on the
same network and Homebaked is open on the phone. It doesn't run in the background.

**Some networks block it.** Hotel, café, airport, school and office Wi-Fi often stop devices
from talking to each other ("client isolation"). So do some home **guest** networks and
some **mesh** systems, and a **VPN** on either device can get in the way. Home Wi-Fi is the place to use it.
If you have nothing else, your phone's **hotspot** works too: connect the computer to it.

**Your phone's address can change.** That's fine: Claude finds the phone again on its own.
If it can't, it'll ask you to open Homebaked; the address is also shown on the phone under
**Ready ⋮** at the top of the app.

---

## If something goes wrong

| What you see | Try this |
|---|---|
| Claude says it can't find your phone | Open Homebaked on the phone. Check both are on the same Wi-Fi (not a guest network). Turn off any VPN. |
| The QR scan says "expired" | Ask Claude for a new code. Each one works for 5 minutes. |
| Claude says "not linked" | The phone was unlinked or Homebaked was reinstalled. Ask Claude to link your phone again. |
| Linked, but Claude never confirmed it | Your computer's firewall blocked the reply. It still works; ask Claude to send an app. If it can't find the phone, allow Python through the firewall. |
| An app looks broken or blank | Tell Claude what you see. It can read the app and fix it. |
| A message says an app "tried to reach" a website | The app tried to go online without permission and was blocked. Tell Claude; it either removes that or declares it. |
| Voice input doesn't work | It needs Android 12+ and your language downloaded for offline speech: Settings → search "offline speech" or "speech recognition". |

---

## Removing it

- **Unlink a computer:** on the phone, tap **Ready ⋮** at the top of Homebaked, then
  **Unlink**.
- **Uninstall:** uninstall Homebaked like any app. All its apps and data go with it.
- **Remove the skill:** ask Claude "Remove the Homebaked skill."

---

## How it works

Three pieces, and no server anywhere in between:

```
  Your computer                                        Your phone
  ─────────────                                        ──────────
  Claude desktop app → Code tab                        Homebaked app
    │                                                    │
    │ the Homebaked skill tells Claude how to build      │ runs each app in its own sandbox
    │ apps (the spec) and how to send them               │ (web view: own storage, offline
    │                                                    │  unless declared)
    ▼                                                    │
  homebaked.py  ──── your Wi-Fi, signed requests ───▶  small server, only while the app
  (comes with the skill)                               is open on screen
```

1. **The skill** (`skill/homebaked/`) is a set of instructions for Claude plus a Python script.
   When you ask for an app, Claude reads the app spec, writes the app (normally one
   `index.html` with everything inside it, plus a small `manifest.json`), and runs
   `homebaked.py push` to send it.
2. **The script** (`homebaked.py`) links phones, sends apps, lists and deletes them. It uses only
   Python's standard library. It keeps the linked phones and their keys in `phones.json` in
   your user config folder (`~/.config/homebaked/`, or `%APPDATA%\Homebaked` on Windows),
   readable only by you.
3. **The phone app** (`android/`) receives apps, stores each in its own folder, and runs them.

### Linking (once per phone)

1. `homebaked.py pair` makes a **QR code** containing a one-time secret, the computer's public
   key, the computer's name and its address on your network. It's valid for 5 minutes.
2. You scan it in Homebaked and tap **Link**. The phone and the computer then each work out
   the same **secret key** using an X25519 key exchange. The key itself never appears in the
   QR code or on the network, so a photo of the QR code isn't enough to push to your phone.
3. The phone tells the computer it's linked (a short signed message over Wi-Fi). If your
   computer's firewall blocks that, the script finds the phone on the network instead.

### Sending an app

1. The script zips the app folder and sends it to the phone, **signed with the key**. The
   signature covers the app's name, the file and its contents, a timestamp and a one-time
   number, so the phone rejects anything from an unlinked computer, anything altered on the
   way, and any recorded push sent again.
2. The phone checks the signature, unpacks the app into its folder, and shows it. If that app is
   open on screen, it reloads by itself. Sending just one file (`push <app> index.html`) updates
   that file only, which is how small changes go.
3. The phone signs its answers too, and the script checks who it's talking to before sending
   anything, so a device pretending to be your phone gets nothing.

### Finding the phone

The phone's address on your Wi-Fi can change. While Homebaked is open it announces itself on
the local network (mDNS, `_homebaked._tcp`). If the last known address doesn't answer, the
script looks for that announcement and checks the phone's identity before using it. The phone
only accepts connections from your local network (and Tailscale), never from the internet,
and only while Homebaked is on screen.

### On the phone

- **Each app has its own private address** (`https://<app>.homebaked.internal/`) that exists
  only inside the phone. That's what keeps apps apart: an app can't read another's files or
  saved data.
- **Offline unless declared.** An app can only reach the websites listed under `"network"` in
  its `manifest.json`. Everything else is blocked by Homebaked (three separate checks: every
  request, a content security policy, and WebRTC switched off). Even declared websites can
  provide data, never code. Apps that go online get a 🌐 badge.
- **Sharing between apps** is opt-in: two apps that both list the same name under `"shared"`
  can read and write that one storage area, and nothing else of each other's.
- **The home screen** is built in, or it's an app named `home` that Claude can send like any
  other. That makes the layout something you change by asking, without reinstalling Homebaked.
  The bar at the top (status, linked computers, switch back to the built-in home screen) always
  stays Homebaked's own.
- **Phone features apps can use:** speech (the phone's voice), voice input (on the phone only,
  never the cloud), keeping the screen on (timers), short toast messages, and shared storage. The app asks for the camera only
  to scan QR codes and the microphone only if an app uses voice input.
- **No cloud:** no accounts, no analytics, no crash reporting; Android's cloud backup is
  switched off for Homebaked, and Google Safe Browsing and WebView metrics are off.

The full protocol, the security analysis and the design decisions are in
[RELEASE-PLAN.md](RELEASE-PLAN.md).

---

## What's in this repository

```
homebaked/
├── README.md                 this guide
├── RELEASE-PLAN.md           design decisions, protocol, security model, milestones, test plan
├── LICENSE                   MIT
├── apps/                     your own apps, if you build them in this folder (kept out of git)
├── skill/homebaked/          the Claude Code skill (copied to ~/.claude/skills/homebaked)
│   ├── SKILL.md              what Claude reads: when to use the skill and the steps to follow
│   ├── scripts/
│   │   ├── homebaked.py      link, push, list, delete, status, unlink (Python standard library only)
│   │   └── qr.py             QR code generator (writes PNG, SVG or text), standard library only
│   └── reference/
│       ├── spec.md           the rules every app must follow (runtime, storage, network, bridges)
│       ├── ideas.md          app ideas Claude can suggest
│       └── home-template.html starting point for a custom home screen
└── android/                  the phone app (Kotlin, Jetpack Compose)
    ├── gradle.properties     the product name, app id and link scheme, in one place
    └── app/src/main/java/app/shell/
        ├── MainActivity.kt   home screen: welcome steps, app grid or pushed home, status, link dialog
        ├── WebAppActivity.kt opens one app full screen, with the AndroidBridge
        ├── Sandbox.kt        the cage every app runs in: own origin, network blocking, link confirm
        ├── Home.kt           the pushable home screen's HomeBridge and icon serving
        ├── PushHub.kt        runs the server while the app is open; linked computers; LAN announce
        ├── UploadServer.kt   the signed HTTP endpoints (/hello, /apps, /upload, /delete, /link)
        ├── Linker.kt         reads the QR link, the key exchange, the "linked" message
        ├── X25519.kt         the key exchange math
        ├── Installer.kt      writes apps to disk, refusing any file path that escapes the app's folder
        ├── AppInfo.kt        reads manifest.json (name, category, emoji, colour, network, shared)
        ├── SharedStore.kt    shared storage areas between apps
        ├── Speaker.kt        text to speech
        ├── Ears.kt           on-device speech recognition
        ├── PushLog.kt        history of every install and delete
        └── Brand.kt          name-derived constants
```

Your apps are not in this repository. On the computer they live wherever you ran Claude (each
in its own folder); on the phone, inside Homebaked's own storage.

---

## Building it yourself

You only need this to change the phone app itself. Apps and home screens never need a rebuild.

**Requirements**

| Tool | Version |
|---|---|
| JDK | 17 or newer, up to 21 (tested with Temurin 21; Android Studio's bundled JDK 25 is too new for Gradle 8.9) |
| Android SDK | platform 35 (Android Studio installs it; set `sdk.dir` in `android/local.properties`) |
| Gradle | 8.9, through the included wrapper (`android/gradlew`); nothing to install |
| Android Gradle Plugin / Kotlin | 8.7.3 / 2.0.21, fetched by Gradle |
| Libraries | AndroidX Compose (BOM 2024.12.01), AndroidX WebKit 1.12.1, NanoHTTPD 2.3.1, ZXing Android Embedded 4.3.0 |
| Python | 3.8+ for the skill's script |

**Build and install**

```bash
cd android && ./gradlew assembleDebug     # test build: android/app/build/outputs/apk/debug/app-debug.apk
cd android && ./gradlew installDebug      # build and install on a phone connected over USB
cd android && ./gradlew assembleRelease   # release build, signed with your own key (below)
```

Or open the `android` folder in Android Studio and press Run.

**Release signing.** Every update must be signed with the same key, or phones refuse it and the
only way out is to uninstall (losing all apps and their data). Put the key's details in
`~/.gradle/gradle.properties`, never in the repository: `HOMEBAKED_STORE_FILE`,
`HOMEBAKED_STORE_PASSWORD`, `HOMEBAKED_KEY_ALIAS`, `HOMEBAKED_KEY_PASSWORD`.

**Checking a downloaded APK.** Official releases are signed with this certificate (SHA-256):
`b0:75:bb:77:15:dc:2b:f2:ed:dd:c0:ad:9e:14:50:8a:28:79:77:d9:5a:8c:4a:0a:5c:07:fe:1b:31:bb:7c:93`.
Check with `apksigner verify --print-certs Homebaked-<version>.apk`; each release also lists the
APK's own SHA-256.

**Renaming.** The name, app id and link scheme live in `android/gradle.properties`
(`BRAND_NAME`, `BRAND_APP_ID`, `BRAND_SCHEME`) and at the top of `scripts/homebaked.py`.

**Testing.** There's no automated test suite yet. Testing so far has been on the Android
emulator with the real script; the test plan is in [RELEASE-PLAN.md](RELEASE-PLAN.md#test-plan).

---

## For Claude: installing the skill

When someone asks you to install the Homebaked skill from this repository:

1. Get the repository: `git clone --depth 1 https://github.com/alinobrasil/homebaked-apps` into a
   temporary folder. If git isn't installed, download
   `https://github.com/alinobrasil/homebaked-apps/archive/refs/heads/main.zip` and unzip it.
2. Copy the folder `skill/homebaked` to the personal skills folder:
   `~/.claude/skills/homebaked` (on Windows, `%USERPROFILE%\.claude\skills\homebaked`).
   Replace an existing copy if there is one (that's how the skill is updated).
3. Check Python 3 runs (`python3 --version`, or `python`/`py` on Windows). If it doesn't,
   offer to install it and wait for a yes.
4. Tell the person it's installed, then offer to link their phone.

---

*Homebaked is free and open source under the [MIT License](LICENSE). The phone app has no analytics, no crash reporting and no
ads; the code is in this repository for anyone to check.*

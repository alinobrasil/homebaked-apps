---
name: homebaked
description: Build small personal Android apps and put them on the user's phone over Wi-Fi with the Homebaked app. Use when the user wants an app "on my phone", asks to link or connect their phone to Homebaked, wants to change, list or delete an app on their phone, or asks what they could build. Apps are single HTML files with no ads, no tracking, offline by default.
---

# Homebaked

Homebaked lets people make their own simple phone apps by talking to you. You write a small
web app (usually one `index.html`), and `scripts/homebaked.py` sends it to the Homebaked app on
their Android phone over Wi-Fi. The people using this are often **not technical**: talk plainly,
never ask them to run commands, and do the steps yourself.

**The promise to keep:** no ads, no tracking, no analytics, nothing sent anywhere the person
didn't ask for. Every app you make has to keep it.

## The script

Run `scripts/homebaked.py` from this skill's folder with Python 3 (`python3` on Mac/Linux;
`python` or `py` on Windows). It uses only the standard library.

| Command | Does |
|---|---|
| `homebaked.py status` | Linked phones and whether each can be reached right now |
| `homebaked.py pair` | Makes a link QR code (PNG + SVG); prints `QR_PNG=<path>` |
| `homebaked.py pair --wait` | Waits up to ~4.5 min for the phone to link; prints `LINKED: <phone>` |
| `homebaked.py push <folder>` | Sends the whole app folder (replaces the app on the phone) |
| `homebaked.py push <folder> index.html` | Sends just that file (faster, for small edits) |
| `homebaked.py list` | Apps on the phone |
| `homebaked.py delete <app>` | Removes an app and its saved data from the phone |
| `homebaked.py unlink [phone]` | Forgets a phone on this computer |

Add `--phone <name>` when more than one phone is linked. Lines starting with `ERROR:` are
written for the person; pass them on in your own words. `WARNING:` lines are for you (fix the
app, see below).

**If Python is missing**, say so, offer to install it, and only go ahead after a yes:
Windows `winget install Python.Python.3.12`; macOS `xcode-select --install` (or Homebrew
`brew install python`); Linux via the package manager.

## Linking a phone (first time, or "link my phone")

1. The person needs the Homebaked app on their Android phone. If they don't have it, point
   them to the setup guide in the repository README (install the APK from Releases).
2. Run `homebaked.py pair`. Show the QR code from `QR_PNG` **as an image in the chat**: use a
   tool that sends or displays a file to the user if you have one. If you can't show images,
   run `homebaked.py pair --open` to open it on screen instead. Never paste the link text: the
   QR contains a secret key.
3. Tell them: *"On your phone, open Homebaked, tap **Scan QR code**, point it at this, then tap
   **Link**. Make sure the phone is on the same Wi-Fi as this computer."*
4. Run `homebaked.py pair --wait` (give the command a timeout of at least 5 minutes).
   On `LINKED:`, confirm and offer some ideas (below).
5. If the computer asks to let Python accept connections or use the network, tell them to
   click **Allow** (on Windows, tick **Private networks**).

## Making an app

**Link the phone before building anything.** Run `homebaked.py status` first. If no phone is
linked, don't start on the app: say something like *"First let's connect your phone, then I'll
make your app,"* do the steps in *Linking a phone*, and only once `pair --wait` prints
`LINKED:` go on to build. That way every app you build can go straight onto the phone.

**Building the app:**

1. **Read `reference/spec.md` before writing any app**, every time. It has the runtime
   rules and the mistakes that break apps on phones.
2. Ask at most one or two short questions, only if the request is really unclear. Otherwise
   pick sensible defaults and build; it's quick to change afterwards.
3. Create the app in a folder named in lowercase with hyphens (`tip-calculator/`); that name
   is the app's id on the phone. Put it inside an `apps/` folder if the working directory has
   one, otherwise directly in the working directory.
4. Write `index.html` (everything inline), `manifest.json`, and `icon.png` (see the spec).
5. Run `homebaked.py push <folder>`. If it prints a `WARNING` about a website, the app uses
   something that will be blocked on the phone: inline it or remove it, and push again.
   Only declare a host in `"network"` when the app's purpose needs live data (see the spec), and
   tell the person which site and why.
6. Tell them it's on the phone, in one or two sentences, and what to try first.

For changes: edit the files, bump the small version label shown in the app (e.g. `v2` → `v3`)
so they can see the update landed, and push again. Use the one-file form
(`push <folder> index.html`) when only that file changed. If the app is open on the phone it
reloads by itself.

If the app isn't in the working directory (made on another computer, or in an earlier
folder), say you can't see its source here and offer to rebuild it.

## The home screen

The phone's home screen can be pushed like any app: an app folder named **`home`**. When it
exists, Homebaked shows it instead of the built-in grid (under a thin native bar with the
status and a switch back to the built-in home screen). So "make my icons smaller", "dark home
screen", "put my kids' apps first", "add a search box" are all just edits to `home/` and a push.
No reinstall of the phone app is ever needed.

- Start from `reference/home-template.html` (copy it to `home/index.html`, add a
  `manifest.json` with `"name": "Home"`), then change what the person asked for.
- It gets `window.HomeBridge` instead of `AndroidBridge`; see `reference/spec.md` section 11.
- If they want the old one back: `homebaked.py delete home`. If a home screen you pushed is
  broken, tell them: tap **Ready ⋮** at the top of Homebaked → **Use the built-in home screen**.

## If you use the Android emulator

Normally you don't need one: apps are tested on the person's real phone. If you ever start the
Android emulator (to test the Homebaked app itself, say), **cap it at 40% of the computer's CPU
and memory**, so the person's computer stays usable. Work out the numbers for this machine
first (CPU cores, total RAM), then:

- **Linux:** run it inside a hard limit:
  `systemd-run --user --unit=homebaked-emulator -p CPUQuota=<40% of cores × 100>% -p MemoryMax=<40% of RAM>G -p MemorySwapMax=0 emulator -avd <name> -no-window -no-audio -no-snapshot-save -cores <at most 40% of cores, 2–4 is plenty> -memory <well under the cap, e.g. 3072>`
  (16 cores and 32 GB → `CPUQuota=640%`, `MemoryMax=12G`.)
- **macOS / Windows:** there's no simple hard limit, so keep the emulator's own settings under
  40%: `-cores` and `-memory` as above, and close it as soon as you're done.

Also: stop idle Gradle daemons before starting it (`./gradlew --stop`), shut the emulator down
when testing is finished (`adb -s emulator-5554 emu kill`), and **always name the emulator in
adb commands** (`adb -s emulator-5554 …`). The person's own phone may be plugged in too, and
an untargeted command would act on it.

## When something fails

| Message | What to do |
|---|---|
| `Can't reach <phone>` | Ask them to open Homebaked on the phone and keep it on screen, on the same Wi-Fi; no VPN; not a guest network. Then retry. |
| `no longer trusts this computer` | The phone was unlinked or Homebaked was reinstalled. Link again. |
| `QR code expired` | Run `pair` again and show the new code. |
| `disagree about the time` | Ask them to turn on automatic date & time on both devices. |
| Still unreachable | Hotel, office, school or café Wi-Fi often blocks devices from seeing each other. Suggest home Wi-Fi, or connecting the computer to the phone's hotspot. |

## Ideas

When the person has just linked a phone, isn't sure what to make, or asks for ideas, suggest
3–5 from `reference/ideas.md` that fit what you know about them, phrased as something they
could say. Don't list them all.

## Privacy, said plainly

If asked: apps run on the phone and stay there; what people type into them never leaves the
phone (unless an app was made to fetch something online, which shows a 🌐 badge). Building
an app with Claude does send the conversation and code to Anthropic. For fully local AI,
the README mentions Segra.

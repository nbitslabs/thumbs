# Thumbs

Thumbs turns an Android phone into an authenticated [MCP](https://modelcontextprotocol.io) server, so AI agents on your own computers (Claude Code, Codex, Hermes, anything that speaks MCP over HTTP) can see and control the phone over Tailscale.

It runs entirely on the phone: an accessibility service does the seeing and touching, and a small HTTP server exposes it as MCP tools. No adb, USB cable or computer-side daemon is needed.

## How it works

- **Accessibility service.** Reads the UI tree, takes screenshots (`AccessibilityService.takeScreenshot`), dispatches gestures, performs global actions (back/home/recents/notifications…), and on Android 13+ acts as an input method for the focused field so typing is real IME input (unicode, emoji, key chords).
- **Notification listener** (optional). Lists, opens, acts on, replies to and dismisses notifications.
- **Foreground service.** Hosts the MCP server (Streamable HTTP, `POST /mcp`, JSON responses) on port 8765. Its notification shows when an agent is active and has a Stop button.

## Security model

- **API keys.** Every request needs `Authorization: Bearer thumbs_…`. Create one key per computer or agent in the app; each key is either *Full control* or *Read only* (screen, UI tree, device state, notifications). Keys are 256-bit random tokens shown once; only their SHA-256 is stored, and they are excluded from backups. Revoking a key takes effect immediately, and a key can be created to expire after an hour, a day or 30 days.
- **Network.** By default only Tailscale addresses (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`) and loopback (for `adb forward`) are accepted. Tailscale encrypts the traffic (WireGuard), which is why plain HTTP is used. "Any network" can be enabled in settings; a key is still required.
- **Browsers are rejected.** Requests carrying an `Origin` header are refused, which blocks DNS-rebinding attacks from web pages.
- **Brute force.** An address is locked out for 5 minutes after 10 failed authentications.
- **The Thumbs app is off-limits to agents.** Agents can't see it in the UI tree or screenshots (`FLAG_SECURE`), operate it, or launch it. This stops an agent from minting itself new keys or widening network access.
- **Approval on the phone (opt-in).** Settings → *Ask on this phone before agents act* can be set to *risky actions* or *every action*; it's off by default. A prompt then appears over the screen, describing the action ("Tap “Send” in WhatsApp", "Type and submit in Gmail: “…”"), and the agent waits up to 45 seconds for *Allow*, *Deny*, or *Allow everything from this key for 10 min*. Risky actions are recognised from the control's label (send, pay, delete, post, call, …, read by OCR in apps that hide their UI), a keyboard "send" action, notification replies, and raw intents. Icon-only buttons without a label can slip through, so use *every action* when that matters. Agents can't approve prompts themselves: control tools run one at a time, so no agent gesture can happen while a prompt waits, and the prompt is hidden from the UI tree and screenshots.
- **Live view links.** The browser live view can't send an API key, so it uses separate view-only links: random tokens that expire (1 minute to 24 hours), die when the key that created them is revoked or expires, and can all be ended at once in the app. They are subject to the same network policy and lockout as API keys. The page sends no referrer, isn't cached, and loads nothing external. The notification shows how many people are watching.
- **Audit.** The app shows a log of every tool call, with the key, arguments and result. Arguments that may hold typed text, URLs or replies are not recorded.
- **Agent instructions.** The MCP `instructions` tell agents never to enter PINs, passwords or 2FA codes, to ask the user to unlock a secure lock screen, and to confirm before sending, buying, deleting or changing security settings.

## Tools

| Area | Tools |
| --- | --- |
| Observe (read-only keys) | `get_device_state`, `screenshot`, `read_screen_text`, `get_ui_tree`, `find_elements`, `wait_for`, `wait_for_idle`, `list_apps`, `list_notifications` |
| Touch | `tap`, `long_press`, `double_tap`, `swipe`, `scroll`, `drag`, `pinch`, `element_action` |
| Text & keys | `type_text`, `press_key`, `set_clipboard`, `get_clipboard` |
| Apps | `open_app`, `open_url`, `start_intent` |
| Notifications | `open_notification`, `notification_action` (including inline replies), `dismiss_notification` |
| Power | `wake_screen` (`press_key lock_screen` locks) |
| Live view | `get_view_link` (read-only keys too) |

Conventions:

- **Coordinates.** All coordinates share one space: the screen scaled to fit vision-model input limits (e.g. 706×1568 for a 1080×2400 phone). Screenshots come back at exactly that size, so any point in a screenshot can be tapped directly. `get_device_state` reports the mapping.
- **Element refs.** `get_ui_tree` lists elements as `[e12] Button "OK" {clickable} @left,top,right,bottom`. Refs stay valid while the element is on screen, and most tools accept `ref`, `text` or `x`/`y` as the target.
- **Apps that hide their UI.** Some apps (WeChat, games, some Flutter/Unity apps) draw their own interface and expose nothing to accessibility. `get_ui_tree` says so, `read_screen_text` reads the screen with on-device OCR (ML Kit, Latin and Chinese, bundled in the app) and returns tappable bounds, `text` targets fall back to OCR, and `scroll` detects the end of a list by comparing screenshots.
- **Screen.** While an agent works, the screen is kept from dimming or sleeping until 2 minutes after its last step, and a sleeping screen is woken for actions and screenshots. Both can be turned off in settings.
- **Settling.** Control tools wait for the UI to settle, and accept `observe: "tree" | "screenshot" | "both"` to return the new state in the same call.
- **Directions.** For `scroll`, the direction is the content to reveal (`down` shows what's below), and it reports when the end has been reached. For `swipe`, the direction is finger motion, as a fast flick.

## Phone setup

1. Install Tailscale on the phone and connect it to your tailnet. In Android settings, set both Tailscale and Thumbs to **Unrestricted** battery usage; otherwise Doze cuts their network access while the phone is idle.
2. Install Thumbs: `adb install app-debug.apk`, or build it yourself (see Development).
3. Open Thumbs and work through the setup card:
   - **Accessibility service.** If you installed the APK through a browser or file manager, Android 13+ greys out the toggle. Go to App info → ⋮ → *Allow restricted settings* first. Installing with `adb` avoids this.
   - **Notification access** (optional), **unrestricted battery**, and the **status notification**.
4. Create an API key. The dialog shows the token once, together with ready-to-paste configuration.

## Connecting agents

Use the phone's Tailscale IP or MagicDNS name, which the app shows as the MCP endpoint.

Claude Code:

```sh
claude mcp add --transport http thumbs http://<phone>:8765/mcp --header "Authorization: Bearer thumbs_…"
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.thumbs]
url = "http://<phone>:8765/mcp"
http_headers = { "Authorization" = "Bearer thumbs_…" }
```

Generic JSON (Hermes and others):

```json
{ "mcpServers": { "thumbs": { "type": "http", "url": "http://<phone>:8765/mcp", "headers": { "Authorization": "Bearer thumbs_…" } } } }
```

For quick checks from a shell, use `scripts/mcp_call.py`, which only needs the Python standard library:

```sh
export THUMBS_URL=http://<phone>:8765/mcp THUMBS_TOKEN=thumbs_…
scripts/mcp_call.py list
scripts/mcp_call.py get_ui_tree
scripts/mcp_call.py tap '{"text": "Settings"}'
scripts/mcp_call.py screenshot --image /tmp/screen.jpg
```

## Live view

To watch an agent work, open a live view link in a browser on your tailnet. It shows the screen at about 3 frames per second, the foreground app and lock state, and a feed of every tool call (with the same argument redaction as the audit log).

- Agents call `get_view_link` (optionally with `minutes`) and hand you the URL.
- In the app, *Live view → Create link* makes a 1-hour link; *End all live views* revokes every link and closes open streams.

Frames come from the same accessibility screenshots agents use. Android allows about three a second, so it's a slideshow rather than video. Capture only runs while someone is watching, and agents' own screenshots are shared with viewers rather than captured twice. Up to 4 streams can be open at once. The Thumbs app itself and other secure screens appear black.

For smooth video on your own computer, [scrcpy](https://github.com/Genymobile/scrcpy) works over Tailscale if you enable Wireless debugging on the phone (`adb pair`, `adb connect <phone>:<port>`, `scrcpy`). Note that this grants full adb access, far beyond a Thumbs key, and needs re-pairing after reboots.

## Development

The dev shell (`nix develop`, or direnv with devenv) provides the JDK, Kotlin, Gradle and the Android SDK, including emulator system images.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Emulator workflow:

```sh
avdmanager create avd -n thumbs36 -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d pixel_7
emulator -avd thumbs36 -no-window -no-audio &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure enabled_accessibility_services com.nbitslabs.thumbs/.a11y.ThumbsAccessibilityService
adb shell cmd notification allow_listener com.nbitslabs.thumbs/com.nbitslabs.thumbs.device.NotificationListener
adb forward tcp:8765 tcp:8765   # loopback is always allowed
```

Then create a key in the app and point a client at `http://127.0.0.1:8765/mcp`.

## Known limitations

- **Secure lock screen.** Agents can't get past a PIN, pattern or password lock (by design). `wake_screen` turns the screen on and dismisses swipe-only lock screens.
- **FLAG_SECURE apps.** Screenshots of banking apps, password prompts and similar screens come back black. The UI tree usually still works.
- **Key events.** Keyboard keys (enter, tab, ctrl+…) are delivered to the focused text field. Arbitrary key events can't be injected into other views without root.
- **Clipboard reads.** Reading the clipboard only works while a text field is focused (Android restricts background clipboard access).
- **Games and canvas apps.** Apps that render without accessibility information can only be driven by screenshots, OCR and coordinates; icons without text can't be found by `text`.

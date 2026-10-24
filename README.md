# V.A.Y.U — Direct Android Controller

This fork turns V.A.Y.U into a phone-control bridge. The connected MCP client makes decisions; the Android app only executes explicit commands. There are **no embedded model API calls, provider keys, local AI task loops, or image-generation claims**.

Android 11+ • No root • Termux relay • Streamable HTTP MCP • Optional stdio adapter

## Phone-only setup (Samsung A30 / Android 11)

1. Install the new test APK from this branch's GitHub Actions build. Open **Actions → Build V.A.Y.U Controller APK → successful run → Artifacts → vayu-controller-android11-debug**. Download and unzip it, then install the APK. If an old app with a different signing certificate remains installed, uninstall it first. This is a debug-signed test build, not a production release.
2. In Termux, run:

   ```sh
   pkg update
   pkg install git nodejs-lts
   git clone -b codex/termux-controller https://github.com/entropyyt014-ops/V.A.Y.U-Agent.git
   cd V.A.Y.U-Agent
   npm ci --ignore-scripts
   bash scripts/termux-start.sh
   ```

   On a later restart, run the last command again from the repository. The script reuses a private token stored at `~/.config/vayu/token`. Do not post this token or screenshots of it.
3. Open V.A.Y.U. Tap **Enable accessibility**, select V.A.Y.U and enable its service. Return to the app. Enter `http://127.0.0.1:8787` and the token printed by Termux, then tap **Connect**. It should say **Connected — direct controls**.
4. Keep Termux running and the phone unlocked. Exempt Termux and V.A.Y.U from Samsung battery sleeping restrictions if background polling stops. You can run `termux-wake-lock` if available; this uses battery and does not prevent Android from killing processes.
5. Use **Disconnect phone control** to stop commands immediately. Disabling the accessibility service also stops control. Reconnection after a service restart occurs only if you previously enabled the relay.

## First local proof

Open another Termux session in the repository:

```sh
export VAYU_TOKEN="$(cat "$HOME/.config/vayu/token")"
curl -H "Authorization: Bearer $VAYU_TOKEN" http://127.0.0.1:8787/api/devices
curl -H "Authorization: Bearer $VAYU_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"vayu_look","arguments":{}}' http://127.0.0.1:8787/api/call
curl -H "Authorization: Bearer $VAYU_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"vayu_open_app","arguments":{"app":"com.android.chrome"}}' http://127.0.0.1:8787/api/call
```

The screenshot call returns base64 JPEG plus UI-tree text. Screenshots and touch coordinates are both native screen pixels. Protected windows may refuse capture. A reported action success means Android accepted the input; inspect the next screenshot to confirm the intended result.

## Connecting an MCP client

The relay exposes Streamable HTTP at `http://127.0.0.1:8787/mcp`, with `Authorization: Bearer <connection token>`. It uses the official MCP SDK in stateless mode, avoiding dropped session-header bugs.

A remote client cannot reach the phone's localhost address. It needs a separately configured HTTPS tunnel forwarding to port 8787, and support for this authenticated MCP endpoint. Tunnel installation, client authentication support, and connection to this ChatGPT session are **not verified by this code change**. Do not expose an unauthenticated endpoint or put the token in a URL. There is no OAuth server bundled here; clients requiring OAuth need an appropriate gateway. No phone-control tools become available in an existing chat just because the app is installed.

For a local stdio MCP client, use `node mcp-server/index.js` from this checkout, with `VAYU_TOKEN` and optionally `RELAY_URL` in the client environment. Root `npm ci` installs its SDK dependency.

## Tools and behavior

`vayu_devices`, `vayu_look`, `vayu_screenshot`, `vayu_ui_tree`, `vayu_status`, `vayu_tap`, `vayu_long_press`, `vayu_swipe`, `vayu_type_text`, `vayu_find_and_tap`, `vayu_type_in_field`, `vayu_press_back`, `vayu_press_home`, `vayu_press_recents`, `vayu_open_app`, `vayu_open_url`, `vayu_list_apps`.

Tools accept an optional `deviceId`. More than one online phone requires an explicit choice. One command is allowed at a time per phone. Timed-out queued commands are removed and never automatically replayed. If a response is lost after delivery, the input might already have run: observe before retrying. There is no autonomous `execute`, model recommendation, bulk sequence, or site-generation tool.

Google Flow, Instagram and YouTube remain normal apps/websites operated through screenshots and explicit inputs. Flow's mobile UI, character consistency, downloads, and end-to-end content creation have not been tested on the A30. Account login, device permissions and any publishing decisions remain under your control.

## Development

```sh
npm ci --ignore-scripts
npm test
# JDK 17 + Android SDK 34 required:
./gradlew assembleDebug lintDebug
```

The relay binds to loopback by default and requires a 32+ character connection token. No token is compiled into the APK. Data is transient in memory; restarting clears registrations and pending commands. Old relay copies and the AI demo were removed so root `npm start` always runs the same direct-control server.

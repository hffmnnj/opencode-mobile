# opencode-mobile

An Android wrapper for the OpenCode `serve` UI. Connect to your OpenCode instance from your phone without exposing a web interface to the public internet.

> **Disclaimer:** This is an independent project. I am not affiliated with Anomaly or the OpenCode team.

## What it does

This is a thin native Android shell around the OpenCode web interface. It connects to an `opencode serve` instance on your local network (or anywhere reachable) and renders the session UI in a WebView. A few extras the browser doesn't give you:

- Persistent server config. Save your `serve` URL, username, and password.
- Zoom control. Pinch-free text scaling from 50% to 200% for small screens.
- Auto `http://` prefix. Type `192.168.1.5:4096` and it just works.
- Back button navigation. Hardware back maps to WebView history.
- Clean full-screen chrome. No address bar or tabs.

## Prerequisites

- Android 8+ device
- OpenCode CLI installed on a host machine
- `opencode serve` running and reachable from your phone

## Setting up `opencode serve`

On your host machine:

```bash
# Binds to localhost only. Not reachable from phone.
opencode serve

# Bind to all interfaces so the phone can reach it.
opencode serve --host 0.0.0.0 --port 4096
```

If you run the app on the same device (e.g., via Termux), keep the default `localhost` binding and set the app URL to `http://localhost:4096`.

### CORS note

opencode-mobile is a native WebView, not a browser tab, so same-origin policy is relaxed. If you hit load issues, make sure `opencode serve` allows your origin:

```bash
opencode serve --host 0.0.0.0 --port 4096 --cors-origin "capacitor://localhost"
```

The app sends a standard mobile Chrome user-agent and loads the page without an iframe, so most CORS setups work out of the box.

## Connecting the app

1. Install the APK.
2. Launch OpenCode Mobile.
3. Tap the Settings button in the bottom-right corner.
4. Enter your `serve` address:
   - `http://192.168.1.5:4096` (auto-completes if you omit `http://`)
   - `http://100.x.x.x:4096` (Tailscale IP)
   - `http://localhost:4096` (if running `serve` locally on the phone)
5. Add username and password for basic auth if you want. See the Security section below.
6. Tap Save.

The app pings the server before loading the WebView. If the server is unreachable, it shows a retry/settings screen instead of a broken page.

## Security

### Username and password (basic auth)

`opencode serve` supports HTTP Basic Auth:

```bash
opencode serve --host 0.0.0.0 --port 4096 --user admin --password your-secret
```

In the app, open Settings and fill in the Username and Password fields. The app sends an `Authorization: Basic <credentials>` header on every request. This is fine for a home LAN, but do not rely on Basic Auth alone over the public internet. Credentials are sent base64-encoded with every request and are easy to intercept without TLS.

### Tailscale (recommended)

The best way to use this app from anywhere is [Tailscale](https://tailscale.com):

1. Install Tailscale on your host machine and your phone.
2. Enable MagicDNS so devices get stable hostnames.
3. Keep `opencode serve` bound to `127.0.0.1`. Do not expose it to `0.0.0.0`.
4. In the app, use the Tailscale IP: `http://100.x.x.x:4096`

Why Tailscale:

- End-to-end WireGuard encryption. No plaintext on your LAN.
- No open ports on your router.
- Works from cellular, coffee shops, anywhere.
- Combine with `--user` and `--password` for two layers of defense.

### Reverse proxy plus TLS (advanced)

If you need remote access without Tailscale, put a reverse proxy with valid TLS (Caddy, Nginx, Traefik) in front of `opencode serve` and use `https://` in the app. This is the only safe way to expose `serve` to the internet.

## Building from source

```bash
cd android
./gradlew assembleDebug
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Features

| Feature | How to use |
|---------|-----------|
| Change server | Settings -> edit URL -> Save |
| Zoom | Settings -> drag Zoom slider -> Save (applies instantly) |
| Basic auth | Settings -> fill Username and Password |
| Back navigation | Hardware back button (long-press to exit) |
| Retry on failure | Tap Retry on the error screen |

## Troubleshooting

**Server unreachable**

- Confirm `opencode serve` is running and bound to `0.0.0.0` (or your Tailscale IP).
- Check firewall or port blocking on the host.
- Verify phone and host are on the same network (or Tailscale is connected).

**Page loads but looks zoomed or desktop-sized**

- Open Settings and adjust the Zoom slider. The app defaults to 100% text zoom and injects a mobile viewport meta tag.

**Mixed content or connection errors**

- If your server is HTTP (not HTTPS), the app allows cleartext traffic automatically. Make sure the URL scheme is `http://`, not `https://`.

## License

Same as OpenCode. Check the upstream repository for license details.

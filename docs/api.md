# Webmote HTTP API

The remote page uses these calls, and scripts and home-automation tools can use them too. They are served on the
TV's address, port 8080 (or the next free port, as shown on the TV), and only to devices on the home network.

Requests and responses are JSON. The `Host` header must be the TV's IP address (or `localhost`), not a name.

## Pairing

Every call except pairing needs a key in the `X-Webmote-Key` header. Get one with the PIN shown on the TV:

```bash
curl -X POST -d '{"pin":"123456"}' http://192.168.1.20:8080/api/pair
# {"key":"3f9c…"}                          200: paired; keep the key, it doesn't expire
# {"error":"That PIN isn't right."}        403
# {"error":"Too many tries.","wait":30}    429: wait that many seconds
```

A page that isn't paired can ask the TV to show its PIN in a corner of the screen for a minute (until a device pairs):

```bash
curl -X POST -H 'Content-Type: application/json' -d '{}' http://192.168.1.20:8080/api/pair/show
# {"ok":true}
```

The key works until someone presses **New PIN** on the TV, or 20 newer devices pair. Treat it like a password.
A call with a missing or old key gets `401 {"error":"Pair this device again."}`.

## Calls

```bash
TV=http://192.168.1.20:8080; KEY=3f9c…
curl -H "X-Webmote-Key: $KEY" -X POST -d '{"key":"home"}' $TV/api/key
```

| Call | Body | Answer |
|---|---|---|
| `POST /api/key` | `{"key": "<name>"}` | `{"ok": true}`, or `false` for an unknown name or a key the TV refused |
| `POST /api/text` | `{"text": "..."}` replaces the text in the TV's focused text box | `{"ok": true}`, or `{"ok": false, "error": "No text box has focus on the TV."}` |
| `POST /api/text` | `{"enter": true}` presses the keyboard's Enter (search, go); with `"text"`, sets the text first. In an app with its own keyboard and no text box (Netflix), searches the app for `"text"` | as above |
| `GET /api/status` | | `{"tv": "Living Room TV", "app": "YouTube", "volume": 18, "max": 100, "muted": false}` |
| `GET /api/apps` | | `[{"label": "YouTube", "pkg": "com.google.android.youtube.tv"}, …]`, by name |
| `GET /api/icon/<pkg>.png` | | The app's banner as a PNG (at most 320 × 180), or 404 |
| `POST /api/launch` | `{"pkg": "<package>"}` | `{"ok": true}`; only apps from `/api/apps` can be opened |

Key names:

| Name | Does |
|---|---|
| `up`, `down`, `left`, `right`, `ok` | The D-pad |
| `back`, `home`, `recents` | Back, Home, recent apps |
| `vol_up`, `vol_down`, `mute` | Media volume; `mute` toggles |
| `play_pause` | Play/pause in the app that's playing |
| `settings` | Opens the TV's settings |
| `off` | Turns the screen off (standby). Webmote can't turn it back on |

## Limits

- One request per connection (`Connection: close`). Headers up to 8 KB, bodies up to 16 KB, 10-second timeout.
- No CORS: a web page on another site can't call the API. Call it from scripts, or from the page Webmote serves.

# Local DM control API

Enable the API in Settings > Local control API; enabling/disabling and port changes apply immediately without restart.
Alternatively, edit `api.enabled = true` in `dmmt-settings.ini`; hand edits apply when the DM window regains focus.
`api.port` defaults to `7071`. The app must be running, but its window need not have focus.
The server listens on all IPv4 interfaces, so a phone or other device on the same local network can call it.
Copied URLs (including map discovery URLs) use an active LAN IPv4 address instead of localhost. Private,
nonvirtual interfaces are preferred. If there is no LAN IPv4 address, URLs fall back to `127.0.0.1`.
On machines with multiple networks, use the IP on the network shared with the calling device; the server accepts
requests to any active local IPv4 interface. Copy URLs again if DHCP or changing networks changes the computer's IP.
Set **Copy address** (`api.copyAddress`) to `local` to copy `127.0.0.1` URLs for use on the same computer, or
`network` (default) for LAN URLs. The setting applies immediately to all URL-copy actions and map discovery URLs;
it changes only the URL address, not the listener's network accessibility.

There is **no authentication** and the app does not modify firewall rules. Enable the API only on trusted networks;
any device that can reach it can control the app. Do not forward the port to the internet. Existing firewall
restrictions or Wi-Fi client isolation may still block connections.
Cross-origin browser requests remain rejected and CORS is not enabled. Direct navigation to a copied URL in a
phone browser works; requests made by a separate web page remain subject to the browser-origin restrictions.

## External control setup

Use an HTTP-request action/plugin on your control device, or a local script that sends an HTTP request.
Right-click the desired DM control and copy its API URL into that action. Set the method to **GET**.
An HTTP request is preferable to an "open website" action, which opens a browser and may be rejected.

Examples (default port, illustrative LAN IP; use the actual IP from **Copy API URL**):

| Action | URL |
|--------|-----|
| Night | `http://192.168.1.100:7071/api/controls/lighting/night` |
| Toggle fog | `http://192.168.1.100:7071/api/controls/fog/enabled` |
| Toggle player freeze | `http://192.168.1.100:7071/api/controls/player/freeze` |
| Set effect colour to red | `http://192.168.1.100:7071/api/controls/effects/color?value=%23FF0000` |
| Set effect opacity to 0.5 | `http://192.168.1.100:7071/api/controls/effects/opacity?value=0.5` |
| Increase effect opacity by 0.1 | `http://192.168.1.100:7071/api/controls/effects/opacity?increment=0.1` |
| Decrease effect opacity by 0.1 | `http://192.168.1.100:7071/api/controls/effects/opacity?decrement=0.1` |

Requests run through the existing JavaFX controls. Setting effect colour updates both the picker and a selected effect,
just as selecting the colour manually would. Undo, project/global persistence and player freeze rules are unchanged.
Tool buttons arm tools as usual; they do not place an object without a canvas interaction.
Buttons that normally open a dialog still open that dialog and may need DM input. Further action commands are
rejected while a modal DM dialog or another API action is in progress.

## Control key images

`GET /api/controls/<path>/image` returns a control's key artwork as a raw `image/png` body. Right-click any
actionable DM control and choose **Copy key image URL** to copy that address; numeric controls additionally
offer **Copy increment key image URL** and **Copy decrement key image URL**, whose artwork carries a green plus
or a red minus badge. For example:

```text
http://192.168.1.100:7071/api/controls/audio/category/<uuid>/image
http://192.168.1.100:7071/api/controls/effects/opacity/image?operation=increment
```

Each image is a 144x144 PNG on a dark background, using the control's tool icon where
available. Dropdowns use their tab's icon (weather, effects, player view or levels) instead of a generic list
or dropdown arrow. Other value controls use fitting icons such as opacity, palette, font size, ruler and zoom;
the three audio volume sliders use volume, music-note and waves icons; remaining controls fall back to their
section's icon. The glyph is white, except for a music category or a sound
effect: those are drawn in the colour configured for that entry, so the artwork matches the audio overlay (a very
dark colour is lightened until the glyph is readable on the dark background).

`?operation=increment` and `?operation=decrement` are accepted only by sliders and numeric spinners; any other
control rejects `operation` with `400`. Unknown controls return `404`. Responses carry
`Cache-Control: private, max-age=60`. Nothing is written to disk.

Because `image` is the marker for this endpoint, it is reserved as the final segment of a control id; the
application refuses to register a control whose id ends in `.image`.

The artwork is rendered from the live control on every request, so it always reflects the current icon and
colour. Discovery reports a short `image` fingerprint per control that changes exactly when the rendered
artwork would, which lets a client cache key images and refetch only after an edit.

## Discovery and parameters

`GET /api/controls` lists actionable controls, their current values, numeric bounds and dropdown choices.
The `label` field is the short display name used by Stream Deck's **Title > Name**.
All fixed control names are defined in one ID-to-name file:
[`src/main/resources/dmmt/api/control-names.properties`](../src/main/resources/dmmt/api/control-names.properties).
For example, change `lighting.torch=Torch` to rename that control in the API without changing its tooltip,
sidebar settings label or endpoint. Rebuild and restart the application after editing this bundled resource.
Dynamic music categories and sound effects keep their live library names unless their exact
`audio.category.<id>` or `audio.effect.<id>` is added to the file. Unmapped controls fall back to their UI text,
then accessible text, then their id. These are application-source labels, not settings-file entries.

The separate `tooltip` field is the descriptive name used in Stream Deck's control picker.
[`src/main/resources/dmmt/api/control-tooltips.properties`](../src/main/resources/dmmt/api/control-tooltips.properties)
maps the same stable IDs to app hover help and API descriptions. For example, `audio.play`, `audio.musicPlay`
and `audio.effectsPause` can all have a short `label` of `Play/pause`, but distinct `tooltip` descriptions
for combined, music-only and effects-only playback. Rebuild and restart after editing.
Unmapped controls use their live tooltip, accessible text, then their short label. The field is included
in full discovery, batched discovery and command responses. The plugin relays this field to its property
inspector without dropping it. If it is unavailable, dropdowns show the stable control ID rather than the
short `label`; short names are reserved for key titles. Older clients can continue using `label`.

Pass `?ids=a.b,c.d` to describe only the listed controls, in the order given. This keeps polling cheap for a
control device that watches a handful of keys. Unknown ids are skipped instead of failing, so a deleted music
category only breaks its own key; empty ids and more than 128 ids are rejected with `400`.
Dropdown descriptors include the current `index` (`-1` if nothing is selected); `choices` is ordered by index.
Control paths use their stable section/name identifiers, for example `/api/controls/weather/type`.
Hidden sidebar controls remain callable. Disabled controls return an error rather than silently doing nothing.
The informational light hint has no action endpoint.

- **Buttons:** no query parameters; invoke the normal action.
- **Toggles:** no query parameters; toggle the current state. Buttons in mutually exclusive tool groups retain their
  normal UI behavior (selecting Day, Night or a tool selects that choice).
- **Colours:** `?value=%23RRGGBB` or `?value=%23RRGGBBAA`.
- **Dropdowns:** either `?index=0` (zero-based option order, first option is 0) or `?value=` followed by a URL-encoded
  choice from discovery or the copied URL. Use exactly one; invalid/out-of-range indices return `400` without
  changing the selection. For example, `/api/controls/weather/type?index=1` selects the second weather option.
  Right-click a dropdown and choose **Copy API index URL** to copy its current selection by index.
  Indices follow the current UI order, so update buttons if the available choices are reordered.
- **Sliders and numeric spinners:** exactly one of `value`, `increment` or `decrement`. Amounts must be finite;
  increments/decrements must be nonnegative. The resulting value is clamped to the control's UI bounds.
  Units are the underlying control units, not necessarily the displayed percentage: effect opacity uses `0.1..1`,
  and player zoom uses steps where `0` is calibrated size and `1` is twice that size.

Encode query values, especially `#` as `%23` and spaces as `%20`. An unencoded `#` starts a URL fragment and is
not sent to the server. Right-click copying handles encoding automatically and uses the control's current value
as an example. Numeric controls offer increment/decrement URL examples too.

Responses are JSON. Unknown endpoints/maps return `404`, invalid parameters return `400`, disabled/busy controls
return `409`, and unavailable/timed-out UI dispatch returns `503`. No API calls bypass normal application guards.
UI dispatch times out after 30 seconds. If a command has already opened a dialog, it may still finish after that
timeout; do not blindly retry a toggle when the outcome is uncertain.

## Weather

`GET /api/controls/weather/type?value=Thunderstorm` selects the new weather option. Thunderstorm is appended
at index `6`; existing indices (None, Rain, Snow, Mist, Dust motes, Embers) remain unchanged.
Discovery includes it in `weather.type` choices.

`GET /api/controls/weather/intensity?value=0.7` adjusts rain amount for a thunderstorm, never lightning frequency.
The independent `weather.lightningInterval` slider is discoverable (including its current value, bounds, disabled
state and key image) and supports the normal set/increment/decrement commands:

```text
/api/controls/weather/lightningInterval?value=7
/api/controls/weather/lightningInterval?increment=2
/api/controls/weather/lightningInterval?decrement=2
```

Values are seconds between strikes on average (2-120, default 7, with natural variation), not strikes per second; lower
means more frequent. It is enabled only when Thunderstorm is selected (otherwise `409`).
Changes use normal UI undo/redo, persist as `weather.lightningIntervalSeconds` and are shared across levels.
Global flash appearance and the initial interval are settings, not separate control endpoints.
Lightning pierces ambient darkness but not fog; it never reveals hidden map content.

## Maps and levels

`GET /api/controls/maps/previous` invokes the **Previous map** button beside Recent maps, with no parameters.
It opens the most recently opened existing map other than the current one; repeated presses switch between the
last two maps. Control discovery (`GET /api/controls`, including `?ids=maps.previous`) lists `maps.previous`
as a button with its live disabled state. It is disabled when no previous map is available (`409` on invocation).
`GET /api/controls/maps/previous/image` supplies its arrow key artwork; right-click the button to copy either
URL when URL options are enabled. Use **DM Control > Maps > Previous map** in the Stream Deck plugin.
The previous map is retained during the session even with a one-entry recent list; after restarting, only the
persisted recent history is available. This uses the normal asynchronous map switch described below, including
auto-save, unsaved-new-map rejection and player freeze.

`GET /api/maps` lists library maps with their persistent UUID, name, multilevel flag and switch URL.
Right-click a library map to copy the same switch URL:

```text
http://192.168.1.100:7071/api/maps/<uuid>/switch
http://192.168.1.100:7071/api/maps/<uuid>/switch?level=0
```

`level` is a **zero-based index**, ordered lowest level first. Omit it to open the level used last (or the lowest
level if none has been opened). A level parameter on an ordinary map, or an out-of-range index, is an error.
Renaming/moving a map preserves its UUID; copying creates a new UUID. Legacy maps get an ID persisted when first
indexed for API use. Reordering multilevel levels changes their indices, so update level-specific buttons accordingly.

Switch commands save the current map and use the normal asynchronous map loader. The response confirms that the
switch was **requested**, not that loading has completed. The DM window shows loading errors; `GET /api/state`
reports `busy`, the current map name, the open map's persistent UUID as `id` (empty when the map is not in the
library or has never been saved), current zero-based level (`-1` for ordinary maps), and player freeze state.
Match `id` against `/api/maps` rather than `map`: the displayed name is not unique and differs from the library
name for multilevel maps.
Save an unsaved new map before switching through the API; it returns `409` instead of discarding it or opening a
save prompt. File operations in progress also return `409`. Frozen players keep their snapshot until unfrozen.

## Map building tools

| Endpoint | Action |
|---|---|
| `/api/controls/building/drawDoor` | Arms Draw door; drag endpoints on the DM canvas. |
| `/api/controls/building/drawWindow` | Arms Draw window; drag endpoints on the DM canvas. |
| `/api/controls/building/roomLabel` | Arms Room label; click a room on the DM canvas and type its name. |

These use the same tool toggles as the DM controls, including cancellation with Esc or right-click.
They remain callable when their buttons are hidden in Settings. Door/window creation and room-label editing
use the existing map history and persistence; room labels never appear in player output.

## Audio controls

Audio is not part of the DM controls sidebar: it lives in the transport group at the bottom right of the status bar
and in the audio overlay that opens from there. Both are exposed under `/api/controls/audio/...` and are only
available when `audio.enabled` is on. Endpoints work whether the overlay is open or closed.
Play/pause endpoints only resume currently selected audio; they never reselect a stopped category or sound
effect. With nothing selected, they do nothing.

Imported recordings are listed in the library after copying, before analysis and playback preparation
finish. Pending, failed and cancelled preparations cannot play or enter music playlists; their audio
action controls are disabled until preparation succeeds. A category plays only its ready recordings.
Renaming, moving between categories and styling pending entries do not cancel preparation or change their
stable IDs. Use the audio library's preparation queue to inspect progress, retry failures or prioritise
recordings. Closing the library window does not stop that queue.

| Endpoint | Type | What it does |
|----------|------|--------------|
| `/api/controls/audio/play` | button | Pauses or resumes the music **and** all running sound effects. |
| `/api/controls/audio/previous` | button | Previous track of the category. |
| `/api/controls/audio/next` | button | Next track of the category. |
| `/api/controls/audio/overlay` | button | Opens or closes the audio overlay on the DM screen. |
| `/api/controls/audio/musicPlay` | button | The play/pause button in the middle of the music ring: pauses or resumes only the music (sound effects are untouched). |
| `/api/controls/audio/musicLoop` | toggle | Toggles repeating the current song (crossfading into itself over `audio.musicCrossfadeSeconds`) instead of automatically advancing to another song. Off at startup, session-only; manual previous/next still work. |
| `/api/controls/audio/stop` | button | Stops the music (sound effects keep playing). |
| `/api/controls/audio/musicVolume` | slider | Music volume, `0` to `1`. |
| `/api/controls/audio/effectsPause` | button | Pauses or resumes all running sound effects at once. |
| `/api/controls/audio/effectsStop` | button | Stops all running sound effects (fades out). |
| `/api/controls/audio/effectsVolume` | slider | Sound effect volume, `0` to `1`. |
| `/api/controls/audio/masterVolume` | slider | Master volume, `0` to `1`. |
| `/api/controls/audio/mute` | button | Mute: fades everything out, and back in on the next call. |
| `/api/controls/audio/library` | button | Opens the audio library window on the DM screen. |
| `/api/controls/audio/libraryWindow` | button | The Library button in the status bar: opens the same audio library window without opening the overlay. |

### One endpoint per category, music file and sound effect

In addition, **every music category and every sound effect has its own toggle**:

| Endpoint | Type | What it does |
|----------|------|--------------|
| `/api/controls/audio/category/<id>` | button | Plays that category, or stops it when it is already playing. |
| `/api/controls/audio/track/<id>` | button | Starts the music file's category at that song, then follows normal playlist ordering/shuffle. Repeated calls restart the song. |
| `/api/controls/audio/effect/<id>` | button | Starts or stops that sound effect loop. |

`<id>` is the library id of the category, music file or sound effect - the UUID that also appears in the library's
`library.json`, for example
`/api/controls/audio/category/8f1c6d94-2b77-4f0e-9a3b-6c5a1d2e7f10`. Ids never change, so an endpoint survives
**renaming** the entry and can never collide with another one; `GET /api/controls` lists every endpoint together
with the entry's current name, which is the easiest way to look an id up. Hiding an entry from the overlay does
**not** affect its endpoint - hidden entries keep working over the API. The endpoints are rebuilt whenever the
library changes, so `GET /api/controls` always lists the current set.

Call `POST /api/controls/audio/musicLoop` without parameters to toggle looping. Its boolean `value` is exposed
by `GET /api/controls` (also with `?ids=audio.musicLoop`), and stays synchronized even while the overlay is closed.
Music file endpoints use control IDs `audio.track.<id>` and also work for files in hidden categories.
Moving a file to another music category updates what its endpoint starts; converting it to a sound effect or
deleting it removes its music-file endpoint.

A stream deck page for a scene typically uses one `audio/category/<id>` button plus a few `audio/effect/<id>`
buttons to control its ambience.

## Stream Deck plugin

A ready-made Elgato Stream Deck plugin ships in [`streamdeck-plugin/`](../streamdeck-plugin/README.md). It talks
to this API from its own Node process, polls `GET /api/controls?ids=...` so that an active music category stays
highlighted, and draws each key from `GET /api/controls/<path>/image`. See that README for installation and setup.

Note for anything you build yourself in a browser: the API rejects requests that carry an `Origin` or `Referer`
header, so a web page cannot call it directly. Use a non-browser process.

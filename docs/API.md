# Local DM control API

Enable the API in Settings > Local control API; enabling/disabling and port changes apply immediately without restart.
Alternatively, edit `api.enabled = true` in `dmmt-settings.ini`; hand edits apply when the DM window regains focus.
`api.port` defaults to `7071`. The app must be running, but its window need not have focus.
The server binds only to `127.0.0.1`; do not proxy or forward it to a network. Other local programs can control it.
Browser-origin requests are rejected and CORS is not enabled.

## External control setup

Use an HTTP-request action/plugin on your control device, or a local script that sends an HTTP request.
Right-click the desired DM control and copy its API URL into that action. Set the method to **GET**.
An HTTP request is preferable to an "open website" action, which opens a browser and may be rejected.

Examples (default port):

| Action | URL |
|--------|-----|
| Night | `http://127.0.0.1:7071/api/controls/lighting/night` |
| Toggle fog | `http://127.0.0.1:7071/api/controls/fog/enabled` |
| Toggle player freeze | `http://127.0.0.1:7071/api/controls/player/freeze` |
| Set effect colour to red | `http://127.0.0.1:7071/api/controls/effects/color?value=%23FF0000` |
| Set effect opacity to 0.5 | `http://127.0.0.1:7071/api/controls/effects/opacity?value=0.5` |
| Increase effect opacity by 0.1 | `http://127.0.0.1:7071/api/controls/effects/opacity?increment=0.1` |
| Decrease effect opacity by 0.1 | `http://127.0.0.1:7071/api/controls/effects/opacity?decrement=0.1` |

Requests run through the existing JavaFX controls. Setting effect colour updates both the picker and a selected effect,
just as selecting the colour manually would. Undo, project/global persistence and player freeze rules are unchanged.
Tool buttons arm tools as usual; they do not place an object without a canvas interaction.
Buttons that normally open a dialog still open that dialog and may need DM input. Further action commands are
rejected while a modal DM dialog or another API action is in progress.

## Control key images

Right-click any actionable DM control and choose **Open key image** to open its artwork in the default browser.
Numeric controls additionally offer **Open increment key image** and **Open decrement key image**, with green
plus and red minus badges. Each is a 144x144 PNG on a dark background, using the control's tool icon where
available. Dropdowns use their tab's icon (weather, effects, player view or levels) instead of a generic list
or dropdown arrow. Other value controls use fitting icons such as opacity, palette, font size, ruler and zoom;
remaining controls fall back to their section's icon.

The browser opens a local HTML page containing the PNG, rather than opening an image viewer through the OS file
association. Right-click the image in the browser to copy or save it for your control device's key. No API listener,
network connection or window focus is required. Generated PNGs and pages are cached in
`.dmmt/control-key-images` under your home directory and never written into maps.

## Discovery and parameters

`GET /api/controls` lists actionable controls, their current values, numeric bounds and dropdown choices.
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

## Maps and levels

`GET /api/maps` lists library maps with their persistent UUID, name, multilevel flag and switch URL.
Right-click a library map to copy the same switch URL:

```text
http://127.0.0.1:7071/api/maps/<uuid>/switch
http://127.0.0.1:7071/api/maps/<uuid>/switch?level=0
```

`level` is a **zero-based index**, ordered lowest level first. Omit it to open the level used last (or the lowest
level if none has been opened). A level parameter on an ordinary map, or an out-of-range index, is an error.
Renaming/moving a map preserves its UUID; copying creates a new UUID. Legacy maps get an ID persisted when first
indexed for API use. Reordering multilevel levels changes their indices, so update level-specific buttons accordingly.

Switch commands save the current map and use the normal asynchronous map loader. The response confirms that the
switch was **requested**, not that loading has completed. The DM window shows loading errors; `GET /api/state`
reports `busy`, the current map name, current zero-based level (`-1` for ordinary maps), and player freeze state.
Save an unsaved new map before switching through the API; it returns `409` instead of discarding it or opening a
save prompt. File operations in progress also return `409`. Frozen players keep their snapshot until unfrozen.

# Dungeon Master Map Tool - Stream Deck plugin

Drives the DM view from an Elgato Stream Deck through the [local DM control API](../docs/API.md).
Keys stay highlighted while the thing they control is active, so the Stream Deck shows at a glance which
music category is playing, which sound effects loop and which map is open.

The plugin has **no npm dependencies**. It is plain CommonJS for the Node 20 runtime that the Stream Deck
software ships with.

## What it can do

Six generic actions cover every control, so there is no action to maintain per music category:

| Action | Purpose | Highlighted when |
|--------|---------|------------------|
| **Music Category** | Plays a music category, or stops it when it is already playing. | that category is playing |
| **Sound Effect** | Starts or stops one sound effect loop. | that effect is running |
| **DM Control** | Any button or toggle, for example `audio.play`, `lighting.night`, `player.freeze`. | the toggle is on |
| **Value Control** | Sliders and spinners: set, increment or decrement, for example the music volume. | the value is above its minimum |
| **Dropdown** | Selects one option of a dropdown, for example a weather type. | that option is selected |
| **Switch Map** | Opens a library map, optionally a specific level. | that map (and level) is open |

Mutual exclusivity is handled by the application: starting a category stops the previous one, and the
plugin's polling picks that up, so the previously highlighted key dims on its own.

A key that points at something that no longer exists (a deleted category, a renamed control) or that is
currently disabled is drawn dimmed, and unreachable keys get a red border, so a misconfigured page is obvious
without pressing anything.

## Install

1. Enable the API in the application: **Settings - Local DM control API**, then switch the listener on.
2. Quit the Stream Deck software.
3. Copy the folder `com.dmmt.dungeonmaster.sdPlugin` into the Stream Deck plugin folder:
   - Windows: `%APPDATA%\Elgato\StreamDeck\Plugins\`
   - macOS: `~/Library/Application Support/com.elgato.StreamDeck/Plugins/`
4. Start the Stream Deck software. The actions appear under **Dungeon Master Map Tool**.

To distribute a single installable file instead, run Elgato's `DistributionTool` on the same folder:

```text
DistributionTool -b -i com.dmmt.dungeonmaster.sdPlugin -o .
```

## Set up a key

1. Drag an action onto a key.
2. In the Property Inspector set **Host** and **Port** once; every action on every key shares them.
   They default to `127.0.0.1` and the port shown in the application's settings.
3. Pick the target. Music categories, sound effects, controls, dropdowns and maps are listed live, read from
   the running application - no ids to type.
4. Alternatively paste a URL copied in the application (right-click a control or a library map, then
   **Copy API URL**). The plugin fills in the rest of the fields from it.
5. The key artwork comes from the application automatically: once a control is selected, the plugin fetches
   `GET /api/controls/<path>/image`, so a music category key shows that category's own icon in its own colour.
   To override it, paste a key image URL copied with right-click **Copy key image URL** into the
   **Key image URL** field, or set your own image the usual Stream Deck way. Library maps are not controls and
   have no image of their own, so Switch Map keys use a generic map artwork shipped with the plugin, drawn in
   the same style and highlighted the same way; a pasted URL overrides it.

### Shared options

- **Title** decides what the plugin writes on the key: *No title* (the default) leaves the title you typed in
  Stream Deck alone, *Name* shows the target's own name - the music category, the sound effect, the control,
  the slider or the map - and *Value* shows the live value where that is meaningful (the selected dropdown
  option, a slider's percentage).
- **Show a checkmark when pressed** turns Stream Deck's green confirmation overlay off. It is a global option,
  shared by every key, and off means the key just updates its highlight.
- **DM Control** keys first ask for a **Section** (Audio, Fog, Lighting, ...) and only then list the controls of
  that section, instead of one dropdown with every control in the application.
- **Value Control** keys leave **Amount** empty to use the control's own step - for the volume sliders that is
  0.1, so one press is one tenth. Fill it in only to override that; the value is in the control's own unit, so
  `0.05` on a 0..1 volume slider is five percent, not five.

If the application runs on another machine, enable **Use network address** in its API settings and use the IP
it shows. The API only accepts requests from processes that are not browsers; the plugin's own Node process
qualifies, a Property Inspector web view would not, which is why all lookups are relayed through the plugin.

## Polling

Highlighting is driven by polling, because the API has no push channel.

- One poller per `host:port`, shared by every key, not one request per key.
- Each tick sends **at most one** `GET /api/controls?ids=...` with the union of the watched ids, plus at most
  one `GET /api/state` if a Switch Map key is visible. Map *names* come from `GET /api/maps`, but only when a
  Switch Map key is set to show its name, and at most once a minute.
- Nothing is sent at all when no key of this plugin is on a visible page or profile.
- Requests never overlap; a slow answer delays the next tick instead of stacking up.
- After an error the interval backs off through 1s, 2s, 5s, 10s, 20s and recovers immediately on success.
- Pressing a key applies the response right away and schedules one quick follow-up poll, so feedback is
  immediate rather than waiting for the next tick.
- Key artwork is fetched once per configuration and cached; failures are retried at most every 30 seconds.
  Discovery reports an artwork fingerprint, so **changing a music category's icon or colour in the application
  updates the key** on the next poll without any extra requests in the meantime.
- A key is redrawn only when its appearance actually changes.

The interval is configurable in the Property Inspector (**Refresh**, default 1000 ms, minimum 250 ms).
At the default, a full page of keys costs about one small HTTP request per second in total.

## Development

```powershell
cd streamdeck-plugin
node tools/selftest.js   # pure-logic checks: PNG codec, rendering, URL parsing, state evaluation, polling
node tools/make-icons.js # regenerates the PNG artwork under com.dmmt.dungeonmaster.sdPlugin/imgs
```

Layout:

| Path | Contents |
|------|----------|
| `manifest.json` | action definitions |
| `plugin.js` | Stream Deck event loop, key evaluation and press handling |
| `lib/ws.js` | minimal RFC 6455 client (Node 20 has no global `WebSocket`) |
| `lib/api.js` | HTTP client for the DM API, with keep-alive and URL parsing |
| `lib/poller.js` | shared, batched, backing-off polling |
| `lib/png.js` | dependency-free PNG decode/encode |
| `lib/render.js` | composites the active, inactive, disabled and error key states |
| `pi/` | Property Inspector |

Logs go to the Stream Deck log folder (`%APPDATA%\Elgato\StreamDeck\logs` on Windows).

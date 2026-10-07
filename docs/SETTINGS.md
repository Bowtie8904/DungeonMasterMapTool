# Settings reference (`dmmt-settings.ini`)

This document explains **every entry of the settings file**. Use your browser's or editor's search (`Ctrl+F`) with the
exact key (for example `fog.revealSeconds`) or with a word describing what you want to change (for example *fade*,
*zoom*, *tooltip*, *torch*, *cache*, *click*, *frame rate*). Every entry ends with a **Keywords** line that lists
common synonyms to make searching easier.

## Local control API

### `api.enabled`

**Default:** `false`. **Applies:** Live.

Enables the HTTP API for controlling the DM UI from external control devices or local programs without keyboard focus.
Only `127.0.0.1` is bound. Browser-origin requests are rejected; there is no network access or CORS support.
Switching this setting in Settings starts or stops the listener immediately, without a restart.
Right-click URL copying is available even when the server is disabled, for preparing buttons before enabling it.
Commands and examples are documented in [API.md](API.md).

**Keywords:** external controls, remote control, HTTP, localhost, automation.

### `api.port`

**Default:** `7071`. **Range:** `1024` to `65535`. **Applies:** Live.

TCP port for the local API. With the default, URLs begin with `http://127.0.0.1:7071`.
Choose an unused port if another application occupies it. Startup errors appear in the DM status bar; the app keeps
working normally without the API. Port changes switch the listener immediately while enabled; if the new port
cannot be bound, the old listener stays running and the status bar reports its address. Copy command URLs again
after successfully changing the port. Hand edits apply when the DM window regains focus.

**Keywords:** external controls, HTTP, TCP, localhost, port.

## Contents

1. [How the settings file works](#how-the-settings-file-works)
2. [Window and panels](#window-and-panels)
3. [Collapsible sidebar sections](#collapsible-sidebar-sections)
4. [Player screen](#player-screen)
5. [Fog and lighting](#fog-and-lighting)
6. [Frame rates](#frame-rates)
7. [Auto-save](#auto-save)
8. [Text defaults](#text-defaults)
9. [Import](#import)
10. [DM view navigation](#dm-view-navigation)
11. [Mouse and selection](#mouse-and-selection)
12. [Ping and laser pointer](#ping-and-laser-pointer)
13. [Editing, brush and effects](#editing-brush-and-effects)
14. [Lights](#lights)
15. [Time of day](#time-of-day)
16. [Weather](#weather)
17. [Performance mode](#performance-mode)
18. [Storage and caches](#storage-and-caches)
19. [User interface](#user-interface)
20. [Effect textures](#effect-textures)
21. [Other (unknown) keys](#other-unknown-keys)

---

## How the settings file works

- **Location:** `dmmt-settings.ini` next to the application jar. When the application is not started from a jar (for
  example from the IDE) the file is created in the working directory. Start the application with
  `-Ddmmt.settings=<path>` to use a different file. `dmmt-settings.default.ini` in the repository is an exact copy of a
  freshly created file.
- **Format:** one `key = value` per line. Lines starting with `#` (or `;`) are comments. The comment above each entry
  repeats its allowed range and whether a restart is needed.
- **Creation and new settings:** the file is created with every setting and its default on first start. When a newer
  version adds settings, the missing entries are appended with their defaults at startup; your values stay untouched.
- **Invalid values** (text where a number is expected, unknown colours, ...) are ignored and the built-in default is
  used. **Numbers outside the allowed range are clamped** to the nearest allowed value. Delete a line (or leave the
  value empty) to go back to the default.
- **Colours** are written as `#RRGGBB`; text colours also accept `#RRGGBBAA` (the last two digits are the opacity,
  `00` = transparent).
- **When changes apply** (shown as *Applies* below):
  - **Live** – edit and save the file while the application runs; the values are reloaded as soon as the main window
    regains focus (the status bar shows "Settings reloaded"). Some live values only affect things created afterwards
    (for example a new ping), this is mentioned in the description.
  - **Restart** – the value is used to build controls, windows or timers and is only read when the application starts.
    The file comment of these entries ends with *Restart required*.
  - **Managed by the app** – the application itself writes this value when you change the matching control (slider,
    toggle, menu, field). It is read at startup. Edit it by hand while the application is closed; a hand edit made
    while the application runs is kept in the file but only used after the next start (and is replaced as soon as
    you change the matching control).
- **First start / migration:** when no settings file exists yet, values stored by older versions of the application
  (Java preferences: last import folder, player screen and calibration, sidebar and section states, fog sharpness,
  light tint, auto-save and text defaults) are copied into the new file once.
- **Settings window:** every setting that has no control in the DM controls can also be changed in the application: click the
  cog button (DM controls header or status bar). Settings are grouped by category, can be found with the search field
  (name, key or description), explain themselves in tooltips, can be reset to their default individually, and are
  applied immediately (except entries marked *restart required*). Which DM controls tabs are shown is set there too. Related entries are grouped in collapsible blocks (collapsed by default) inside each category, for example *Player zoom*, *Map image cache*, one block per time of day, weather type, light preset or effect texture (with *Layer* and *Light emission* blocks inside). Numbers are edited with spinners. The search also finds the *Keywords* of this document.
- Keys the application does not know are kept in an *Other* section at the end of the file.

---

## Window and panels

### `ui.sidebarVisible`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Whether the sidebar on the right of the DM window (tools, fog, lighting, effects, text, building, player and performance
sections) is shown. Toggled with the sidebar button in the toolbar; the last state is remembered here.

*Keywords:* sidebar, side panel, hide panel, show panel, tool panel, layout

### `ui.controlsExpanded`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Whether the toolbar controls at the top of the DM window are expanded. Collapse them to get more map space; the state
is remembered here.

*Keywords:* toolbar, top bar, controls, collapse, expand, layout

### `ui.performanceMode`
**Default:** `false` · **Values:** `true`/`false` · **Applies:** managed by the app

Whether **performance mode** (the toggle at the bottom left of the DM window) is on. Performance mode trades visual
quality for speed on weak machines; what exactly it reduces is configured in the [Performance mode](#performance-mode)
section.

### `ui.effectAnimations`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Global switch for moving effect textures and weather on all maps (animations toggle in the Effects section). Independent of `ui.lightFlicker`.

### `ui.lightFlicker`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Global light flicker switch (circle-flash toggle in the Lighting section, next to Remove light). Off = no light flickers; on = each light flickers according to its own flicker setting. Independent of the per-map effect animations toggle; performance mode always disables flicker.

### `ui.recentMaps.max`
**Default:** `5` · **Range:** 1 to 30 · **Applies:** restart

How many maps the **Recent maps** list below the map library keeps. Older entries drop off the end.

*Keywords:* recent maps, history, last opened, recently used

### `ui.sections.hidden`
**Default:** empty · **Values:** comma separated tab ids: `tools`, `fog`, `lighting`, `weather`, `effects`, `text`, `building`, `player`, `performance` · **Applies:** live (also changed from the settings window)

Tabs of the DM controls overlay that are **completely hidden**. A DM who never uses effects or never changes the frame rate can remove those tabs to keep the overlay short. Hidden tabs keep all their settings and data; only their controls disappear from the overlay. Empty = every tab is shown. Easiest to change in **Settings** (cog button in the DM controls header or in the status bar) under *DM controls tabs*.

*Keywords:* hide tab, hide section, remove section, sidebar tabs, overlay, declutter, DM controls, show hide

### `ui.controls.hidden`
**Default:** empty · **Values:** comma separated `section.control` ids from the table below · **Applies:** live (also changed from the settings window)

Individual controls hidden from the DM controls overlay. In **Settings > DM controls tabs**, expand a tab's **controls** group and uncheck the controls you do not need. Labels, icons and value readouts belonging to a field/slider are hidden together; empty rows and unused separators take no layout space. Values, active tools, map data and keyboard shortcuts are unchanged. Both brush-size sliders have independent visibility choices but still share the same brush size, adjustable with Alt + mouse wheel. Whole-tab visibility (`ui.sections.hidden`) is independent, so restoring a tab preserves its individual choices. Empty means everything is shown; unknown ids are ignored. Hand edits are applied while the app is running.

For example: `ui.controls.hidden = lighting.candle,player.diagonal,fog.brushSize,effects.brushSize`

Each entry below combines the section prefix with a control suffix (for example, `lighting.candle`):

| Section prefix | Control suffixes |
|---|---|
| `tools` | `select`, `ping`, `laser`, `undo`, `redo` |
| `fog` | `enabled`, `revealBrush`, `hideBrush`, `revealRect`, `hideRect`, `revealAll`, `hideAll`, `revealRoom`, `brushSize`, `sharpness`, `fade`, `softness` |
| `lighting` | `torch`, `candle`, `campfire`, `magic`, `remove`, `flicker`, `revealPersistent`, `revealWhileLit`, `revealNone`, `day`, `dawn`, `dusk`, `night`, `on`, `off`, `ambient`, `tint`, `brightCore`, `hint` |
| `weather` | `type`, `intensity` |
| `effects` | `circle`, `rectangle`, `brush`, `pen`, `line`, `delete`, `clear`, `color`, `opacity`, `players`, `texture`, `border`, `light`, `animations`, `brushSize` |
| `text` | `add`, `layer`, `autoSize`, `players`, `delete`, `size`, `color`, `rotateLeft`, `rotateRight`, `background`, `noBackground`, `border`, `noBorder` |
| `building` | `drawWall`, `eraseWall`, `wallLayer`, `lock`, `snap`, `addImage` |
| `player` | `window`, `freeze`, `scaleTest`, `handout`, `grid`, `gridOpacity`, `screen`, `diagonal`, `tileSize`, `zoom` |
| `performance` | `target`, `animation`, `idle` |

*Keywords:* hide control, show control, individual controls, candle, screen diagonal, diameter, brush size slider, declutter, sidebar

### `ui.recentMaps`
**Default:** empty · **Values:** full map file paths separated by `|` · **Applies:** managed by the app

The recent maps history, most recent first. The app updates it whenever a map is opened; maps that no longer exist are removed automatically.
*Keywords:* performance mode, slow computer, laptop, low end, speed, lag, battery

### `window.dmWidth`
**Default:** `1500` · **Range:** 400 to 10000 · **Applies:** restart

Width in pixels of the main (DM) window when the application opens. The window can still be resized or maximised
afterwards. The player window always fills its whole screen and has no size setting.

*Keywords:* window size, width, startup size, main window, DM window, resolution

### `window.dmHeight`
**Default:** `920` · **Range:** 300 to 10000 · **Applies:** restart

Height in pixels of the main (DM) window when the application opens.

*Keywords:* window size, height, startup size, main window, DM window, resolution

---

## Collapsible sidebar sections

### `ui.section.<id>.expanded`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Remembers whether each collapsible sidebar section is expanded (`true`) or collapsed (`false`). Click a section header
to toggle it. A missing or invalid value shows the section expanded. One entry exists per section:

| Key | Sidebar section |
|-----|-----------------|
| `ui.section.tools.expanded` | Tools (select, fog brushes, rectangles, room fill) |
| `ui.section.fog.expanded` | Fog (fog sharpness, softness, fade animation) |
| `ui.section.lighting.expanded` | Lighting (time of day, ambient brightness, light tools, light tint, weather) |
| `ui.section.effects.expanded` | Effects (circle/box/freehand effects, pen, line, textures, colours) |
| `ui.section.text.expanded` | Text (text boxes, font size and colours) |
| `ui.section.building.expanded` | Building (walls, doors and windows) |
| `ui.section.player.expanded` | Player (player screen, calibration, player zoom) |
| `ui.section.performance.expanded` | Performance (frame rates) |

*Keywords:* collapse, expand, fold, accordion, sidebar section, panel state

---

## Player screen

### `player.screenIndex`
**Default:** `0` · **Range:** 0 to number of monitors − 1 · **Applies:** managed by the app

Index of the monitor that shows the player view (0 = first monitor in the screen selector). Chosen with the screen
selector in the Player section (each entry shows the resolution and position of the monitor). If the monitor no
longer exists (for example the TV is unplugged), the last available monitor is used; the stored index is only
replaced when you pick a screen yourself.

*Keywords:* monitor, display, second screen, TV, projector, table screen, player window

### `player.tileInches`
**Default:** `1.0` · **Range:** `player.tileInches.min` to `player.tileInches.max` · **Applies:** managed by the app

Physical size of one map grid tile on the player screen, in inches. With the correct screen diagonal, `1.0` makes
every tile exactly one inch wide, so miniatures fit the grid. Set in the Player section ("Tile size (in)", steps of
0.05). A stored value outside the field range is clamped at startup. The player zoom slider multiplies this scale.

*Keywords:* calibration, inch, 1 inch grid, miniature scale, tile size, physical size, scale

### `player.showGrid`
**Default:** `false` · **Range:** true / false · **Applies:** managed by the app

Show map-aligned grid lines over the player map images, below lighting, effects and fog. Controlled by the **Show grid**
icon on the second row of the Player view DM controls, beside the grid-opacity slider. Applies to all maps, including the frozen player view, and is
remembered across restarts. Does not change the DM view, calibration, or grid lines baked into map images.
The line opacity is controlled by `ui.gridOpacity`.

*Keywords:* grid, grid lines, map overlay, player screen, show grid, hide grid, battle map

### `player.screenDiagonalInches.<n>`
**Default:** none (estimated) · **Range:** `player.screenDiagonal.min` to `player.screenDiagonal.max` · **Applies:** managed by the app

Real diagonal of monitor number `<n>` in inches (for example `player.screenDiagonalInches.1 = 43`), needed for the
1-inch calibration. Without an entry the diagonal is estimated from the DPI reported by the operating system, which is
often wrong for TVs (the estimate is rounded to half inches and limited to the field range). Written when you change
"Screen diagonal (in)" in the Player section while that monitor is selected. Stored values below
`player.screenDiagonal.min` are ignored and the estimate is used. The numbering is the same as for
`player.screenIndex`. The default file only contains a commented example line `# player.screenDiagonalInches.0 =`.

*Keywords:* screen size, diagonal, TV size, monitor size, calibration, DPI, inches

### `player.viewportEdgeScroll.enabled`
**Default:** `true` · **Range:** true / false · **Applies:** live

Automatically pan the DM camera while dragging the player viewport title bar near an unobstructed map edge.
Hovering alone and other object drags never scroll. Controls and sidebars are excluded from the interaction area;
scrolling continues at capped speed beyond an edge, including outside the DM window, while the drag remains active.
Scrolling pauses over control overlays and resumes on return during the same drag. Release, cancellation,
focus loss, map/level changes and player-window closure stop it. Frozen player output remains unchanged; only the
staged viewport moves. The complete drag is one undo step, without changing zoom or rotation.
Available in the settings window's Player screen category.

*Keywords:* viewport, title bar, auto pan, edge scrolling, continuous drag, player camera, freeze

### `player.viewportEdgeScroll.zonePx`
**Default:** `40` · **Range:** 1 to 300 · **Applies:** live

Edge-zone width in logical pixels, measured from the unobstructed map interaction area. Speed increases linearly
from zero at the inner boundary to the maximum at the edge, staying at the maximum beyond it. On small interaction areas each zone is limited to
half the corresponding dimension.

*Keywords:* viewport, edge zone, margin, auto pan, drag, logical pixels

### `player.viewportEdgeScroll.maxSpeedPxPerSecond`
**Default:** `600` · **Range:** 1 to 3000 · **Applies:** live

Maximum DM camera scrolling speed in logical pixels per second. Combined diagonal speed has the same cap.
Elapsed time and DM zoom convert this to world movement, so speed is independent of frame rate and zoom.
Scrolling keeps interaction rendering active even when the cursor is stationary; normal idle throttling resumes afterwards.

*Keywords:* viewport, edge scrolling, auto pan speed, diagonal, frame rate, zoom

### `player.zoom.minStep`
**Default:** `-2` · **Range:** -6 to 0 · **Applies:** restart

Lowest value of the player zoom slider, as a power of two of the calibrated scale: `-2` means the players can zoom out
to 25 % (four times more map visible), `-3` to 12.5 %. Zooming out breaks the 1-inch calibration on purpose to show an
overview.

*Keywords:* player zoom, zoom out, overview, zoom slider, minimum zoom, player camera

### `player.zoom.maxStep`
**Default:** `2` · **Range:** 0 to 6 · **Applies:** restart

Highest value of the player zoom slider as a power of two: `2` means 400 % (zoomed in four times).

*Keywords:* player zoom, zoom in, close-up, zoom slider, maximum zoom, player camera

### `player.zoom.wheelFactor`
**Default:** `1.1` · **Range:** 1.01 to 4 · **Applies:** live

How much one mouse wheel notch changes the player zoom when you hold `Ctrl` over the DM view (`1.1` = 10 % per
notch). Larger values zoom faster.

*Keywords:* Ctrl wheel, mouse wheel, scroll, zoom speed, player zoom, zoom step

### `player.zoom.cursorPull`
**Default:** `0.4` · **Range:** 0 to 1 · **Applies:** live

When zooming the player view in with `Ctrl`+wheel and the cursor is inside the player viewport rectangle, the point
under the cursor stays in place and the view is additionally pulled towards the cursor by this share. `0` = no pull
(pure anchor zoom), `1` = the cursor point becomes the centre.

*Keywords:* zoom to cursor, centre, center, focus, player zoom, Ctrl wheel, anchor

### `player.zoom.min`
**Default:** `0.02` · **Range:** 0.001 to 1 · **Applies:** live

Absolute lower limit of the player camera zoom factor (screen pixels per map pixel) after calibration and the zoom
slider are applied. Only matters for very large maps with tiny grid cells.

*Keywords:* zoom limit, player camera, minimum zoom, huge map

### `player.zoom.max`
**Default:** `48` · **Range:** 1 to 1000 · **Applies:** live

Absolute upper limit of the player camera zoom factor. Only matters for maps with very small images or grid cells.

*Keywords:* zoom limit, player camera, maximum zoom, small map

### `player.tileInches.min`
**Default:** `0.25` · **Range:** 0.05 to 10 · **Applies:** restart

Smallest value accepted by the "Tile size (in)" field of the Player section.

*Keywords:* tile size limit, calibration, inch, field range

### `player.tileInches.max`
**Default:** `3` · **Range:** 0.1 to 20 · **Applies:** restart

Largest value accepted by the "Tile size (in)" field.

*Keywords:* tile size limit, calibration, inch, field range

### `player.screenDiagonal.min`
**Default:** `10` · **Range:** 1 to 100 · **Applies:** restart

Smallest value accepted by the "Screen diagonal (in)" field. Also the lower limit of the DPI-based estimate, and stored
diagonals below this value are ignored.

*Keywords:* screen size limit, diagonal, tablet, small screen, field range

### `player.screenDiagonal.max`
**Default:** `120` · **Range:** 10 to 1000 · **Applies:** restart

Largest value accepted by the "Screen diagonal (in)" field and upper limit of the DPI-based estimate. Raise it for
very large TVs or projectors.

*Keywords:* screen size limit, diagonal, projector, large TV, field range

### `player.screenDiagonal.fallback`
**Default:** `27` · **Range:** 1 to 1000 · **Applies:** live

Screen diagonal in inches used for calculations when the diagonal field has no value yet (for example while the
application starts).

*Keywords:* default diagonal, fallback, screen size, calibration

---

## Fog and lighting

### `fog.cellsPerGrid`
**Default:** `10` · **Range:** `fog.cellsPerGrid.min` to `fog.cellsPerGrid.max` · **Applies:** managed by the app

Fog of war resolution: how many fog cells make up one map grid cell along each axis. Higher values give sharper fog
edges and finer brushes but need more memory and CPU. Set with the fog sharpness slider in the Fog section (shown as
`10/t`); applies to every map. Values outside the slider range are clamped, invalid values use `10`.

*Keywords:* fog resolution, fog detail, fog sharpness, fog grid, fog of war quality

### `fog.softness`
**Default:** `0.3` · **Range:** 0 to `fog.softness.max` · **Applies:** managed by the app

Width of the soft fog edge in grid tiles. `0` gives hard, pixelated edges; larger values blend revealed areas
smoothly into the fog. The soft edge always lies inside the revealed area, so nothing behind a wall is revealed. Set
with the softness slider in the Fog section; values above `fog.softness.max` are clamped.

*Keywords:* soft edge, blur, feather, smooth fog, fog edge, gradient

### `fog.fadeAnimation`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Whether fog fades in and out when it is revealed or hidden (animation toggle button next to the softness slider in
the Fog section). When `false`, fog changes instantly. The fade itself is
configured with `fog.revealSeconds`, `fog.hideSeconds`, `fog.lightFadeSeconds`, `fog.fadeEasing` and
`fog.fadeMaxStepSeconds`.

*Keywords:* fog fade, fog animation, fade in, fade out, transition, instant fog

### `fog.cellsPerGrid.min`
**Default:** `5` · **Range:** 1 to 100 · **Applies:** restart

Lowest value of the fog sharpness slider. Performance mode draws the fog at this resolution (several fog cells merged
into one image pixel), so lowering it also makes performance mode fog coarser.

*Keywords:* fog resolution limit, fog sharpness, slider range, performance fog

### `fog.cellsPerGrid.max`
**Default:** `30` · **Range:** 1 to 200 · **Applies:** restart

Highest value of the fog sharpness slider. Very high values make fog painting and soft edges slow on big maps.

*Keywords:* fog resolution limit, fog sharpness, slider range, fog detail

### `fog.softness.max`
**Default:** `1` · **Range:** 0 to 5 · **Applies:** restart

Upper limit (in tiles) of the fog softness slider and of `fog.softness`.

*Keywords:* soft edge limit, blur limit, feather, slider range

### `fog.revealSeconds`
**Default:** `0.5` · **Range:** 0.01 to 30 · **Applies:** live

Seconds fog needs to **fade out completely** when you reveal an area with a fog tool (brush, rectangle, room fill,
reveal all). Partial changes (soft edges) take proportionally less time. Increase it for a slow, dramatic reveal.

*Keywords:* fog fade, reveal speed, fade out, uncover, disappear, dramatic reveal, transition time

### `fog.hideSeconds`
**Default:** `0.5` · **Range:** 0.01 to 30 · **Applies:** live

Seconds fog needs to **fade in completely** when you hide an area again (hide brush, hide rectangle, hide all).

*Keywords:* fog fade, hide speed, fade in, cover, re-fog, appear, transition time

### `fog.lightFadeSeconds`
**Default:** `0.16` · **Range:** 0.01 to 30 · **Applies:** live

Fade time in seconds (both directions) when the fog changes because of a **light** or a **door/window** – a light
being moved, switched on or off, or a door opened. It is short on purpose so the revealed area follows a moving torch
without lagging behind.

*Keywords:* light reveal, torch fade, moving light, door fade, line of sight fade, vision fade

### `fog.fadeMaxStepSeconds`
**Default:** `0.1` · **Range:** 0.01 to 2 · **Applies:** live

Longest time step a single frame may advance a fog fade. When the application stalls (loading a big image, saving), the
fade continues smoothly afterwards instead of jumping to the end. Larger values keep fades in sync with real time on
slow machines; smaller values make them smoother but slower when the frame rate is low.

*Keywords:* fog fade jump, stutter, frame step, smoothness, low frame rate

### `fog.fadeEasing`
**Default:** `linear` · **Values:** `linear` or `smooth` · **Applies:** live

Shape of the fog fade. `linear` changes the fog opacity at a constant speed. `smooth` starts and ends slowly
(smoothstep curve) and is faster in the middle; the total time stays the same.

*Keywords:* easing, ease in out, fade curve, smoothstep, fog animation style

### `fog.dmOpacity`
**Default:** `0.58` · **Range:** 0 to 1 · **Applies:** live

Opacity of fully fogged areas **in the DM view**, so the DM can still see the map beneath the fog. Players always see
fog fully opaque. `1` shows the DM exactly what the players see.

*Keywords:* DM fog transparency, see through fog, fog darkness, DM view fog, fog alpha

---

## Frame rates

### `render.targetFps`
**Default:** `60` · **Range:** 10 to `render.maxFps` (field), file values 1 to `render.maxFps` · **Applies:** managed by the app

Frame rate while you interact with the map (mouse, keyboard, laser pointer, panning) and while fog is fading. Applies to
the DM and the player view. Set with "Target FPS" in the Performance section of the sidebar.

*Keywords:* fps, frame rate, smoothness, refresh rate, framerate, interaction

### `render.animationFps`
**Default:** `30` · **Range:** 1 to `render.maxFps` (never above `render.targetFps`) · **Applies:** managed by the app

Frame rate while only animated effect textures, weather or light flicker move and nobody interacts ("Animation FPS"
in the Performance section). Light flicker never updates faster than this, even while you interact.

*Keywords:* fps, animation frame rate, texture animation, flicker speed, CPU usage

### `render.idleFps`
**Default:** `10` · **Range:** 1 to 60 (field), file values 1 to `render.maxFps` · **Applies:** managed by the app

Frame rate when nothing moves and there was no input for `input.recentInputMs` ("Idle FPS" in the Performance
section). Lower values save CPU, GPU and battery.

*Keywords:* idle fps, standby, power saving, battery, CPU usage, frame rate

### `render.maxFps`
**Default:** `240` · **Range:** 1 to 1000 · **Applies:** restart

Upper limit of all frame rate fields and settings above. Raise it for high refresh rate monitors (for example 360 Hz).

*Keywords:* maximum fps, frame rate limit, high refresh rate, 144 Hz, 240 Hz, cap

---

## Auto-save

### `autosave.enabled`
**Default:** `true` · **Values:** `true`/`false` · **Applies:** managed by the app

Whether maps that already exist on disk are saved automatically in the background. New, never saved maps are not
auto-saved. Toggled in the auto-save menu of the toolbar. Maps are also saved when the window loses focus.

*Keywords:* autosave, automatic save, backup, save interval

### `autosave.minutes`
**Default:** `2` · **Range:** whole minutes · **Applies:** managed by the app

Minutes between automatic saves. Chosen in the auto-save menu, whose choices come from `autosave.minuteOptions`. A
hand-written value that is not one of the choices still works, but no menu item is ticked. `0` or less saves at every
check (`autosave.checkSeconds`); an invalid value uses `2`.

*Keywords:* autosave interval, save every, minutes, backup frequency

### `autosave.minuteOptions`
**Default:** `1, 2, 5, 10` · **Format:** comma separated whole minutes, each 1 to 1440 · **Applies:** restart

The interval choices shown in the auto-save menu ("Every 1 minute", "Every 2 minutes", ...). Decimals are rounded,
duplicates removed.

*Keywords:* autosave menu, interval choices, save options, minutes list

### `autosave.checkSeconds`
**Default:** `10` · **Range:** 1 to 600 · **Applies:** restart

How often in seconds the auto-save timer checks whether a save is due. The actual save happens at the first check after
`autosave.minutes` have passed, so this is the maximum delay.

*Keywords:* autosave timer, check interval, save delay, polling

---

## Text defaults

### `text.fontSize`
**Default:** `32` · **Range:** `text.minFontSize` to `text.maxFontSize` · **Applies:** managed by the app

Font size used for new text boxes on maps that have no text yet. Each map remembers its own last used style; this is
the global fallback written whenever you change the text style. The value is clamped to the font size field range.

The four `text.*` style values are always written together whenever you change the font size or one of the text
colours in the Text section.

*Keywords:* font size, text size, label size, default text

### `text.textColor`
**Default:** `#FFFFFF` · **Format:** `#RRGGBB` or `#RRGGBBAA` · **Applies:** managed by the app

Default text colour of new text boxes. Besides hex values, CSS colour names (`white`, `gold`, ...) are accepted for
all `text.*Color` entries; an unreadable colour becomes transparent.

*Keywords:* font colour, text color, label colour, default text

### `text.backgroundColor`
**Default:** `#00000000` (transparent) · **Format:** `#RRGGBB` or `#RRGGBBAA` · **Applies:** managed by the app

Default background colour of new text boxes. The last two digits are the opacity (`00` = no background).

*Keywords:* text background, label background, box fill, default text

### `text.borderColor`
**Default:** `#00000000` (transparent) · **Format:** `#RRGGBB` or `#RRGGBBAA` · **Applies:** managed by the app

Default border colour of new text boxes (`00` opacity = no border).

*Keywords:* text border, outline, frame, label border, default text

### `text.rotation`
**Default:** `0` · **Values:** 0, 90, 180, 270 · **Applies:** managed by the app

Global rotation of all text boxes on the player view only (the DM view stays upright; box and text turn clockwise around the box centre). Set with the rotate buttons in the Text
section; it is independent of the map rotation and applies to every map.

*Keywords:* text rotation, rotate text, label rotation, text orientation

### `text.dmHiddenOpacity`
**Default:** `0.85` · **Range:** 0 to 1 · **Applies:** live

Text boxes hidden from the players are still drawn in the DM view, with their opacity multiplied by this factor. Kept
high so the text stays readable; `0` hides them for the DM too.

*Keywords:* hidden text, DM only, invisible to players, secret note

### `text.minFontSize`
**Default:** `6` · **Range:** 1 to 100 · **Applies:** restart

Smallest value of the font size field in the Text section.

*Keywords:* font size limit, smallest text, field range

### `text.maxFontSize`
**Default:** `400` · **Range:** 10 to 2000 · **Applies:** restart

Largest value of the font size field. Raise it for huge titles on big maps.

*Keywords:* font size limit, largest text, huge text, field range

### `text.autoMaxWidthCells`
**Default:** `12` · **Range:** 1 to 200 · **Applies:** live

When "auto size" is on, a text box grows with its text up to this width in grid cells and then wraps lines.

*Keywords:* text wrap, auto size, text box width, line length

### `text.minBoxSize`
**Default:** `40` · **Range:** 5 to 1000 · **Applies:** live

Smallest width and height of a text box in map pixels. A plain click (instead of a drag) with the text tool creates a
box of at least this size, and resizing never makes a box smaller.

*Keywords:* text box size, minimum size, click to create text, resize limit

---

## Import

### `import.lastDirectory`
**Default:** empty · **Applies:** managed by the app

Absolute path of the folder used last when importing a map image, a dd2vtt/uvtt file or a batch of maps. File and
folder dialogs open there next time. If the folder no longer exists, the dialogs open at the system default location.

*Keywords:* last folder, import folder, recent directory, file dialog, open location

### `import.dd2vtt.lightFlicker`
**Default:** `0.22` · **Range:** 0 to 1 · **Applies:** live (next import)

Flicker depth given to every light imported from a Dungeondraft/Universal VTT file (`.dd2vtt`, `.uvtt`). `0` imports
the lights with flicker switched off (steady light); it can still be switched on per light in its context menu.
Already imported maps are not changed.

*Keywords:* dd2vtt, uvtt, Dungeondraft, imported lights, flicker, universal VTT

### `import.dd2vtt.lightFlickerSpeed`
**Default:** `1.4` · **Range:** 0.05 to 20 · **Applies:** live (next import)

Flicker speed of lights imported from dd2vtt/uvtt files (`1` = normal speed). Not used when
`import.dd2vtt.lightFlicker` is `0`.

*Keywords:* dd2vtt, uvtt, Dungeondraft, imported lights, flicker speed

### `import.autoMergeMultiLevel`
**Default:** `true` · **Applies:** live (next import)

Whether a batch import (several files or *Import folder…*) automatically merges files that look like the levels of
one building (`haus_00 … haus_03`, `Inn 1`, `Inn 2`) into one multilevel map. With `false` every file becomes its own
ordinary map; you can still merge them later in the library.

*Keywords:* multilevel, merge, auto merge, batch import, levels, floors, import folder

### `import.duplicateBehavior`
**Default:** `ask` · **Options:** `ask`, `always`, `never` · **Applies:** live (next import)

What happens when an imported dd2vtt/uvtt file's original file name already matches a map (or multilevel level)
already in the library:
- `ask` (default): show the duplicate-maps dialog listing the matches, with per-file checkboxes (all ticked by
  default) plus Select all/Select none, and the choice to cancel the import, skip every duplicate, or import the
  ticked ones.
- `always`: import duplicates without asking.
- `never`: silently skip duplicates without asking.

Non-duplicate files in the same import are never affected and are always imported.

*Keywords:* duplicate, duplicate import, re-import, already imported, ask, always import, never import

---

## DM view navigation

### `dm.zoom.min`
**Default:** `0.03` · **Range:** 0.001 to 1 · **Applies:** live

Furthest the DM view can zoom out with the mouse wheel (`0.03` = map shown at 3 %). Lower it to see huge maps
completely.

*Keywords:* DM zoom, zoom out limit, mouse wheel, overview, huge map

### `dm.zoom.max`
**Default:** `6` · **Range:** 1 to 100 · **Applies:** live

Closest the DM view can zoom in (`6` = 600 %).

*Keywords:* DM zoom, zoom in limit, magnify, close-up, mouse wheel

### `dm.zoom.wheelFactor`
**Default:** `1.1` · **Range:** 1.01 to 4 · **Applies:** live

Zoom change per mouse wheel notch in the DM view. Zooming in multiplies by this factor and zooming out divides by it,
so in and out are symmetrical (`1.1` = 10 % per notch).

*Keywords:* zoom speed, mouse wheel, scroll zoom, zoom step, touchpad

---

## Mouse and selection

All distances in this section are **screen pixels**, so they feel the same at every zoom level.

### `input.lightPickRadiusPx`
**Default:** `24` · **Range:** 1 to 200 · **Applies:** live

How close (in pixels) a click must be to a light's centre to select, drag, remove or open the context menu of the
light. Also used for the hover cursor over lights. Raise it for touch screens.

*Keywords:* click tolerance, hit area, light selection, grab light, touch screen, precision

### `input.layerHandleRadiusPx`
**Default:** `16` · **Range:** 1 to 200 · **Applies:** live

Grab distance of the resize handle at the bottom-right corner of a selected image layer (unlocked map images).

*Keywords:* resize handle, image layer, grab corner, scale image, hit area

### `input.shapeHandleRadiusPx`
**Default:** `12` · **Range:** 1 to 200 · **Applies:** live

Grab distance of the resize handle of a selected effect shape (circle radius, box corner).

*Keywords:* resize handle, effect shape, spell area, grab handle, hit area

### `input.textHandleRadiusPx`
**Default:** `9` · **Range:** 1 to 200 · **Applies:** live

Grab distance of the eight resize handles of a selected text box.

*Keywords:* resize handle, text box, grab handle, hit area

### `input.wallPickRadiusPx`
**Default:** `10` · **Range:** 1 to 200 · **Applies:** live

Click tolerance for door and window lines (opening/closing doors with a click) and for the wall eraser.

*Keywords:* door click, window click, wall eraser, click tolerance, hit area, open door

### `input.shapePickRadiusPx`
**Default:** `6` · **Range:** 0 to 200 · **Applies:** live

Extra click tolerance around thin effects (freehand brush strokes, pen lines and straight lines) when selecting them.

*Keywords:* select line, pen selection, freehand selection, click tolerance, hit area

### `input.marqueeMinDragPx`
**Default:** `4` · **Range:** 0 to 200 · **Applies:** live

How far the mouse must move after pressing on an empty map spot before a selection rectangle (multi-select) is drawn.
Smaller drags count as a click that clears the selection.

*Keywords:* selection box, marquee, rubber band, multi-select, drag threshold

### `input.rightClickMaxMovePx`
**Default:** `5` · **Range:** 0 to 200 · **Applies:** live

A right-click that moves less than this many pixels cancels the active tool (like `Esc`) or opens a context menu; a
larger movement pans the view instead.

*Keywords:* right click, cancel tool, pan threshold, context menu, drag threshold

### `input.doorDebounceMs`
**Default:** `60` · **Range:** 0 to 2000 · **Applies:** live

A second click on the same door within this many milliseconds is ignored. Protects against worn mouse buttons that
send double clicks and would immediately close a door again. `0` disables the protection.

*Keywords:* double click, mouse bounce, door toggle, debounce, chatter

### `input.recentInputMs`
**Default:** `1000` · **Range:** 0 to 60000 · **Applies:** live

After the last mouse or keyboard input the application keeps rendering at `render.targetFps` for this many
milliseconds before dropping to `render.animationFps` or `render.idleFps`.

*Keywords:* idle delay, frame rate drop, input timeout, power saving

---

## Ping and laser pointer

### `ping.durationMs`
**Default:** `1200` · **Range:** 100 to 60000 · **Applies:** live (new pings)

How long a ping (the expanding ring shown to the players when you ping a spot) stays visible, in milliseconds.

*Keywords:* ping, marker, highlight spot, attention, ring animation, duration

### `ping.startRadiusPx`
**Default:** `10` · **Range:** 0 to 500 · **Applies:** live

Radius of the ping ring in screen pixels when the ping appears.

*Keywords:* ping size, ring size, marker size

### `ping.growPx`
**Default:** `50` · **Range:** 0 to 2000 · **Applies:** live

How many pixels the ping ring's radius grows while it fades out. The final radius is `ping.startRadiusPx + ping.growPx`.

*Keywords:* ping size, ring growth, ping animation

### `ping.color`
**Default:** `#FFE633` · **Format:** `#RRGGBB` · **Applies:** live

Colour of the ping ring and its centre dot.

*Keywords:* ping colour, ping color, marker colour, ring colour

### `laser.trailMs`
**Default:** `500` · **Range:** 0 to 10000 · **Applies:** live

How long the laser pointer trail stays visible behind the moving dot, in milliseconds. `0` shows only the dot.

*Keywords:* laser pointer, trail, tail, pointer, presentation

### `laser.dmDotPx`
**Default:** `6` · **Range:** 1 to 100 · **Applies:** live

Radius of the laser dot in the DM view in screen pixels.

*Keywords:* laser size, pointer size, DM laser

### `laser.playerDotInches`
**Default:** `0.15` · **Range:** 0 to 5 · **Applies:** live

Radius of the laser dot on the player screen in physical inches (uses the screen calibration), so it has the same
real-world size on every TV.

*Keywords:* laser size, pointer size, player laser, inch

### `laser.playerDotMinPx`
**Default:** `6` · **Range:** 1 to 200 · **Applies:** live

Smallest radius of the player laser dot in pixels, used when the inch-based size would be smaller.

*Keywords:* laser size, minimum pointer size, player laser

### `laser.color`
**Default:** `#FF1A1A` · **Format:** `#RRGGBB` · **Applies:** live

Colour of the laser dot, its glow and its trail. The small highlight in the centre is a lighter version of this
colour.

*Keywords:* laser colour, laser color, pointer colour, red dot

---

## Editing, brush and effects

### `history.maxSteps`
**Default:** `10` · **Range:** 1 to 10000 · **Applies:** live

Number of undo steps kept (`Ctrl+Z`). Older steps are dropped. Higher values use more memory, especially for fog edits
on large maps.

*Keywords:* undo, redo, history, Ctrl+Z, undo limit, memory

### `brush.defaultSizeTiles`
**Default:** `1.5` · **Range:** 0.1 to 200 · **Applies:** restart

Brush size in grid tiles when the application starts (fog brushes, Draw and Line). Both sidebar sliders and
**Alt + mouse wheel** over the map edit the same size; each wheel notch changes it by 0.1 tiles. The cursor preview
uses this exact size, without half-tile rounding. Circle and Box are drag-sized; Pen ignores brush size.

*Keywords:* brush size, default brush, fog brush, paint size

### `brush.minTiles`
**Default:** `0.2` · **Range:** 0.05 to 10 · **Applies:** restart

Smallest brush size in tiles, enforced by the sidebar sliders and Alt + mouse wheel.

*Keywords:* brush size limit, small brush, fine brush, slider range

### `brush.maxTiles`
**Default:** `8` · **Range:** 0.5 to 200 · **Applies:** restart

Largest brush size in tiles, enforced by the sidebar sliders and Alt + mouse wheel. Raise it to reveal large areas faster.

*Keywords:* brush size limit, big brush, large brush, slider range

### `effects.defaultColor`
**Default:** `#55AA33` · **Format:** `#RRGGBB` · **Applies:** restart

Colour of new effect shapes (circle, box, freehand, pen, line) at startup until you pick a texture or colour. Picking
a texture switches to the texture's default colour (`texture.<kind>.color`).

*Keywords:* effect colour, spell colour, AoE colour, area of effect, default color

### `effects.defaultOpacity`
**Default:** `0.4` · **Range:** 0.1 to 1 · **Applies:** restart

Opacity of new effect shapes at startup until you pick a texture or change the opacity slider.

*Keywords:* effect opacity, transparency, spell area, AoE alpha

### `effects.penWidthCells`
**Default:** `0.06` · **Range:** 0.005 to 2 · **Applies:** live (new pen lines)

Line width of the Pen tool in grid cells. The pen ignores the brush size.

*Keywords:* pen width, line thickness, drawing, annotate

### `effects.dmHiddenOpacity`
**Default:** `0.35` · **Range:** 0 to 1 · **Applies:** live

Effects hidden from the players are still drawn in the DM view, with their opacity multiplied by this factor so the DM
can tell them apart. `0` hides them for the DM too.

*Keywords:* hidden effect, DM only, invisible to players, secret area, trap

### `effects.softResolution`
**Default:** `0.5` · **Range:** 0.1 to 1 · **Applies:** live

Soft-edged effects (fire, smoke, mist, holy, ...) are composited on the CPU into an image at this fraction of the screen
resolution and smoothly scaled up. `0.5` computes a quarter of the pixels of `1`; the textures are soft noise, so the
difference is hard to see. Lower it if busy maps use a lot of CPU, raise it to `1` for the crispest flames.

*Keywords:* effect quality, fire, smoke, CPU usage, effect performance, blurry effects

---

## Lights

### `lighting.tint.max`
**Default:** `0.3` · **Range:** 0 to 1 · **Applies:** restart

Upper limit of the light tint slider; this strength itself is saved per map (not here), so different maps can use
different values. Values above this are clamped.

*Keywords:* light tint limit, colour strength, slider range

### `lighting.brightCore.max`
**Default:** `0.5` · **Range:** 0 to 1 · **Applies:** restart

Upper limit of the bright core slider; this strength itself is saved per map (not here), so different maps can use
different values. Values above this are clamped.

*Keywords:* bright core limit, hot spot strength, glow strength, slider range

### `lighting.brightCore.radius`
**Default:** `0.3` · **Range:** 0.05 to 1 · **Applies:** live

Radius of each light's bright core highlight, as a fraction of the light's own range. The bright core is a small
additive hot spot drawn right at each light's own position that brightens the map art underneath (rather than
covering it with a flat tint), mimicking the bright glow often baked into the centre of hand-painted map lights.

*Keywords:* bright core, hot spot, light glow, additive light, bright center, light highlight

### `lighting.dmDarknessFactor`
**Default:** `0.65` · **Range:** 0 to 1 · **Applies:** live

Share of the time-of-day darkness shown in the DM view. Players see the full darkness; the DM sees only this share so
the map stays readable. `1` shows the DM the same darkness as the players, `0` none.

*Keywords:* DM darkness, night view, readability, darkness overlay, DM visibility

### `lighting.lightMapScale`
**Default:** `4` · **Range:** 1 to 32 · **Applies:** live

The lighting (darkness and light circles) is computed at 1/N of the screen resolution and smoothed when scaled up.
`1` gives the sharpest shadow edges but is much slower; higher values are faster and softer.

*Keywords:* light quality, shadow resolution, lighting performance, light map, blur

### `lighting.lightMapMaxPixels`
**Default:** `20000` · **Range:** 1000 to 8000000 · **Applies:** live

Upper limit for the number of pixels in one light map. On large screens (for example a 4K player display) the light
map divisor from `lighting.lightMapScale` is raised until the light map fits this budget, so big canvases do not make
lighting much slower. Raise it for sharper lighting on big screens, lower it for more speed.

*Keywords:* light quality, lighting performance, 4K, large screen, pixel budget, light map

### `lighting.shadowRays`
**Default:** `96` · **Range:** 8 to 1024 · **Applies:** restart

Number of rays cast around each light for line of sight and wall shadows (plus extra rays towards every wall corner).
More rays make light circles rounder at long ranges but cost CPU with many lights.

*Keywords:* shadow quality, line of sight, light circle, raycast, vision, walls

### Light tool presets: `lightPreset.<id>.*`

The light tools of the Lighting section place lights with these values. `<id>` is `torch` (the *Add light* tool),
`candle`, `lantern`, `campfire` or `magic`. All values are **live**: change them and the next light placed uses them;
the tool tooltips show the configured range after a restart.

- **`rangeTiles`** – light range in grid tiles (0.5 to 1000).
- **`color`** – light colour `#RRGGBB`.
- **`flicker`** – flicker depth 0 to 1; `0` places a steady light without flicker, higher values give deeper dips.
- **`flickerSpeed`** – flicker speed 0 to 20 (`1` = normal speed); only used when `flicker` is above 0.

| Key | Default | Key | Default |
|-----|---------|-----|---------|
| `lightPreset.torch.rangeTiles` | `6` | `lightPreset.torch.color` | `#FFB35C` |
| `lightPreset.torch.flicker` | `0.22` | `lightPreset.torch.flickerSpeed` | `1.4` |
| `lightPreset.candle.rangeTiles` | `2` | `lightPreset.candle.color` | `#FFD98A` |
| `lightPreset.candle.flicker` | `0.12` | `lightPreset.candle.flickerSpeed` | `2.5` |
| `lightPreset.lantern.rangeTiles` | `8` | `lightPreset.lantern.color` | `#FFF4E0` |
| `lightPreset.lantern.flicker` | `0.22` | `lightPreset.lantern.flickerSpeed` | `1.4` |
| `lightPreset.campfire.rangeTiles` | `12` | `lightPreset.campfire.color` | `#FF8A3D` |
| `lightPreset.campfire.flicker` | `0.35` | `lightPreset.campfire.flickerSpeed` | `1.8` |
| `lightPreset.magic.rangeTiles` | `12` | `lightPreset.magic.color` | `#CFE4FF` |
| `lightPreset.magic.flicker` | `0` | `lightPreset.magic.flickerSpeed` | `0` |

*Keywords:* torch, candle, lantern, campfire, magic light, light tool, light preset, light radius, light range, light
colour, flicker, add light

### Light right-click menu: `lightMenu.*`

The submenus of a light's right-click menu have a **fixed set of entries**; you can change their values but not add or
remove entries. All values are **live**.

- **`lightMenu.range1`**, `lightMenu.range2`, `lightMenu.range3`, `lightMenu.range4`, `lightMenu.range5`, `lightMenu.range6`, `lightMenu.range7`, `lightMenu.range8`, `lightMenu.range9`, `lightMenu.range10` – the ten choices of the **Range** submenu, in tiles (0.1 to 10000). Defaults: 1, 2, 3, 4, 6, 8, 12, 16, 24, 100.
- **`lightMenu.color.warmTorch`**, **`lightMenu.color.candle`**, **`lightMenu.color.neutral`**, **`lightMenu.color.moonlight`**, **`lightMenu.color.arcane`**, **`lightMenu.color.fire`**, **`lightMenu.color.verdant`**, **`lightMenu.color.sunbeam`**, **`lightMenu.color.crimson`**, **`lightMenu.color.frost`**, **`lightMenu.color.rose`**, **`lightMenu.color.toxic`** – colours `#RRGGBB` of the **Color** submenu entries (defaults `#FFB35C`, `#FFD9A0`, `#FFF4E0`, `#A8C8FF`, `#C08CFF`, `#FF6A3D`, `#4DFF8C`, `#FFE34D`, `#FF3B3B`, `#6EF0FF`, `#FF6FCF`, `#C6FF4D`).
- **`lightMenu.flicker.candle.depth`** / `lightMenu.flicker.candle.speed`, **`lightMenu.flicker.torch.depth`** / `lightMenu.flicker.torch.speed`, **`lightMenu.flicker.strongTorch.depth`** / `lightMenu.flicker.strongTorch.speed`, **`lightMenu.flicker.slowPulse.depth`** / `lightMenu.flicker.slowPulse.speed` – depth (0.01 to 1) and speed (0.05 to 20, `1` = normal) of the **Flicker** submenu entries; the *Off* entry is fixed. Defaults: 0.12/2.5, 0.22/1.4, 0.35/1.8, 0.3/0.35.
- **`lightMenu.brightness.dim`**, **`lightMenu.brightness.normal`**, **`lightMenu.brightness.bright`** – brightness (0 to 1) of the **Brightness** submenu entries (defaults 0.4, 0.75, 1).

Older versions stored these as the lists `lightMenu.rangeTiles`, `lightMenu.colors`, `lightMenu.flicker` and
`lightMenu.brightness`; those keys are ignored and removed from the file at startup.

*Keywords:* light menu, context menu, light range menu, colour choices, moonlight, fire, arcane, flicker menu, candle flicker, torch flicker, pulse, light brightness, intensity, dim light, bright light

### `light.defaultRange`
**Default:** `300` · **Range:** 1 to 100000 · **Applies:** live

Range in map pixels used for a light whose saved data contains no range (hand-edited or very old project files).
Lights placed with a tool use their preset range instead.

*Keywords:* default light range, missing range, light radius, old projects

### `light.defaultFlicker`
**Default:** `0.18` · **Range:** 0 to 1 · **Applies:** live

Flicker depth used when a light without saved flicker values gets flicker (old projects, defaults of new data).

*Keywords:* default flicker, flicker depth, old projects

### `light.defaultFlickerSpeed`
**Default:** `1.5` · **Range:** 0.05 to 20 · **Applies:** live

Flicker speed used when a light without saved flicker values gets flicker.

*Keywords:* default flicker speed, old projects

---

## Time of day

The time-of-day buttons (Day, Dawn, Dusk, Night) darken everything that is not lit by a light and give the darkness a
colour. Each preset has four values, all **live**, range 0 to 1:

- **`darkness`** – opacity of the darkness over unlit areas for the players (`0` = no darkness, `1` = black). The DM
  sees `lighting.dmDarknessFactor` of it. The per-map ambient brightness slider scales this value.
- **`red`**, **`green`**, **`blue`** – colour of the darkness (0 = black, 1 = full colour channel). For example a dark
  blue night uses a small `blue` value and almost no `red`/`green`.

| Key | Default | Key | Default |
|-----|---------|-----|---------|
| `timeOfDay.day.darkness` | `0` | `timeOfDay.day.red` | `0` |
| `timeOfDay.day.green` | `0` | `timeOfDay.day.blue` | `0` |
| `timeOfDay.dawn.darkness` | `0.35` | `timeOfDay.dawn.red` | `0.22` |
| `timeOfDay.dawn.green` | `0.16` | `timeOfDay.dawn.blue` | `0.3` |
| `timeOfDay.dusk.darkness` | `0.55` | `timeOfDay.dusk.red` | `0.2` |
| `timeOfDay.dusk.green` | `0.09` | `timeOfDay.dusk.blue` | `0.12` |
| `timeOfDay.night.darkness` | `0.86` | `timeOfDay.night.red` | `0.01` |
| `timeOfDay.night.green` | `0.02` | `timeOfDay.night.blue` | `0.08` |

*Keywords:* day, dawn, dusk, night, sunrise, sunset, darkness, ambient light, ambient colour, mood, atmosphere

---

## Weather

### `weather.defaultIntensity`
**Default:** `0.4` · **Range:** 0.1 to 1 · **Applies:** live

Weather intensity of maps without saved weather and the value the intensity slider returns to when you double-click it.

*Keywords:* weather strength, intensity, rain amount, default weather

### Weather types: `weather.<type>.*`

`<type>` is `rain`, `snow`, `mist`, `dust` (dust motes) or `embers`. All values are **live**.

- **`particles`** – number of particles at full intensity on a 1920×1080 screen (0 to 10000; mist 0 to 500). The
  actual number scales with the intensity slider and the screen size; performance mode halves it. For **mist** the
  value is the number of large drifting fog banks and does not depend on intensity (intensity changes their opacity).
  `0` disables the weather type.
- **`color`** – particle colour `#RRGGBB`.
- **`opacity`** – highest particle opacity (0 to 1). Dust motes twinkle and embers flicker between a fraction of this
  and the full value; mist uses one third of it at the lowest intensity and the full value at intensity 1.

| Key | Default | Key | Default | Key | Default |
|-----|---------|-----|---------|-----|---------|
| `weather.rain.particles` | `600` | `weather.rain.color` | `#C8D7EB` | `weather.rain.opacity` | `0.42` |
| `weather.snow.particles` | `610` | `weather.snow.color` | `#F5F8FF` | `weather.snow.opacity` | `0.5` |
| `weather.mist.particles` | `70` | `weather.mist.color` | `#DBE3ED` | `weather.mist.opacity` | `0.12` |
| `weather.dust.particles` | `500` | `weather.dust.color` | `#FFF0CD` | `weather.dust.opacity` | `0.48` |
| `weather.embers.particles` | `295` | `weather.embers.color` | `#FF963C` | `weather.embers.opacity` | `0.75` |

*Keywords:* rain, snow, mist, fog bank, dust, embers, sparks, ash, particles, weather effect, storm, blizzard

---

## Performance mode

These values are used only while **performance mode** is on (`ui.performanceMode`, toggle at the bottom left). They
never change a project. All values are **live**.

### `performance.interactionFps`
**Default:** `20` · **Range:** 1 to 240 · **Applies:** live

Frame rate while interacting with the map in performance mode (never above `render.targetFps`).

*Keywords:* performance fps, interaction frame rate, slow computer

### `performance.idleFps`
**Default:** `5` · **Range:** 1 to 240 · **Applies:** live

Frame rate when nothing moves in performance mode (`render.idleFps` is used if it is lower).

*Keywords:* performance idle fps, power saving, battery

### `performance.animationFps`
**Default:** `10` · **Range:** 1 to 240 · **Applies:** live

Frame rate of moving effect textures and weather in performance mode; texture animation also advances in steps of
this rate. Light flicker is always off in performance mode.

*Keywords:* performance animation, texture animation speed, choppy animation

### `performance.lightMapScale`
**Default:** `8` · **Range:** 1 to 32 · **Applies:** live

Light map resolution divisor in performance mode (see `lighting.lightMapScale`). `8` computes a quarter of the light
pixels of the normal value `4`.

*Keywords:* performance lighting, light quality, shadow resolution

### `performance.maxFeatherPasses`
**Default:** `6` · **Range:** 1 to 24 · **Applies:** live

Most soft-edge (feather) passes of textured effects in performance mode (normally `texture.featherPasses`).

*Keywords:* soft edges, feather, texture quality, performance effects

### `performance.imageLevelBias`
**Default:** `2` · **Range:** 0 to 8 · **Applies:** live

Large map images are drawn from tiles this many detail levels coarser in performance mode; each level halves the
resolution. `0` keeps full image quality.

*Keywords:* image quality, map resolution, blurry map, texture memory, GPU

### `performance.fogFade`
**Default:** `false` · **Values:** `true`/`false` · **Applies:** live

Whether fog keeps fading in and out in performance mode (with the normal fade settings and the coarser performance
mode fog). Off by default because every fading frame redraws the fog image.

*Keywords:* fog fade performance, fog animation, slow computer

---

## Storage and caches

### `library.folder`
**Default:** `dmmap-projects` · **Applies:** restart

Folder of the map library (the map browser on the left). The default relative path `dmmap-projects` is next to the
application. A relative path is resolved against the application folder; an absolute path (for example a cloud-synced
folder like `D:\Dropbox\DnD maps`) is used as is. The folder is created if it does not exist. If the application
folder itself cannot be determined, `dmmap-projects` in your user folder is used.

*Keywords:* map library, projects folder, save location, storage, Dropbox, OneDrive, network drive, campaign folder

### `cache.imageTiles`
**Default:** `96` · **Range:** 8 to 4096 · **Applies:** live

Number of map image tiles kept in memory for large map images (each up to `cache.imageTileSize`² pixels, about 4 MB at
the default size). More tiles mean less reloading while panning, but more memory.

*Keywords:* memory, RAM, tile cache, large maps, panning, image cache

### `cache.imageTileLoadQueue`
**Default:** `48` · **Range:** 4 to 4096 · **Applies:** live

Most image tile loads waiting at once. When panning fast, older requests are dropped so the tiles you are looking at
load first.

*Keywords:* tile loading, queue, panning, large maps

### `cache.retentionDays`
**Default:** `60` · **Range:** 1 to 3650 · **Applies:** restart

Cached tile pyramids of large images on disk are deleted when they were not used for this many days. They are rebuilt
automatically when needed.

*Keywords:* disk cache, cleanup, disk space, cache age, temporary files

### `cache.imageTileSize`
**Default:** `1024` · **Range:** 256 to 4096 · **Applies:** live (next image load)

Edge length in pixels of the tiles of large map images. Changing it builds new image caches (the first load of each
large image takes longer once).

*Keywords:* tile size, image tiles, large maps, GPU texture

### `cache.overviewMaxSize`
**Default:** `4096` · **Range:** 512 to 8192 · **Applies:** live (next image load)

Images with at most this many pixels per side are drawn directly; larger ones are split into a tile pyramid. It is
also the size of the downscaled overview of large images. Some graphics cards cannot handle textures above 4096 or 8192
pixels. Changing it builds new image caches.

*Keywords:* texture size, maximum image size, GPU limit, large maps, pyramid, overview

### `cache.jpegQuality`
**Default:** `0.92` · **Range:** 0.1 to 1 · **Applies:** live (next image load)

JPEG quality of cached tiles of large images without transparency (images with transparency use PNG). Lower values
save disk space but show compression artefacts. Changing it builds new image caches.

*Keywords:* JPEG, compression, image quality, disk space, artefacts

### `cache.textureTiles`
**Default:** `128` · **Range:** 8 to 4096 · **Applies:** live

Number of generated effect texture tiles (one per texture and colour combination) kept in memory. Must be larger than
the number of texture/colour combinations visible at once, otherwise textures are regenerated every frame.

*Keywords:* texture cache, effect textures, memory, stutter

---

## User interface

### `ui.tooltipDelayMs`
**Default:** `300` · **Range:** 0 to 10000 · **Applies:** restart

Milliseconds the mouse must rest on a button or control before its tooltip appears.

*Keywords:* tooltip, hint, hover help, delay, popup

### `ui.tooltipDurationSeconds`
**Default:** `12` · **Range:** 1 to 600 · **Applies:** restart

Seconds a tooltip stays visible.

*Keywords:* tooltip, hint, hover help, duration, popup

### `ui.libraryAutoExpandMs`
**Default:** `700` · **Range:** 0 to 10000 · **Applies:** restart

Milliseconds a dragged map must hover over a closed folder of the map library before the folder opens.

*Keywords:* drag and drop, map library, folder, auto expand, spring-loaded folder

### `ui.wallColor`
**Default:** `#FF2A2A` · **Format:** `#RRGGBB` · **Applies:** live

Colour of walls in the DM view (walls are never shown to players).

*Keywords:* wall colour, wall color, building, line colour

### `ui.wallOpacity`
**Default:** `0.9` · **Range:** 0 to 1 · **Applies:** live

Opacity of walls in the DM view.

*Keywords:* wall transparency, wall alpha, building

### `ui.doorOpenColor`
**Default:** `#32CD32` (green) · **Format:** `#RRGGBB` · **Applies:** live

Colour of **open doors** in the DM view: the door line, the ring of the round door badge and (lightened) the door
icon inside the badge. Doors are never shown to players. Hovering a door draws a lighter glow in the same colour.

*Keywords:* door colour, door color, open door, door state, door badge, door icon, interactable, portal

### `ui.doorClosedColor`
**Default:** `#E0473C` (red) · **Format:** `#RRGGBB` · **Applies:** live

Colour of **closed doors** in the DM view (line, badge ring and icon). Pick a colour that differs clearly from
`ui.doorOpenColor` and `ui.wallColor` so you can tell the door state at a glance.

*Keywords:* door colour, door color, closed door, locked door, door state, door badge, door icon, interactable, portal

### `ui.windowOpenColor`
**Default:** `#00BFFF` (light blue) · **Format:** `#RRGGBB` · **Applies:** live

Colour of **open windows** in the DM view (line, badge ring and icon).

*Keywords:* window colour, window color, open window, window state, window badge, interactable, portal

### `ui.windowClosedColor`
**Default:** `#3B6FD8` (blue) · **Format:** `#RRGGBB` · **Applies:** live

Colour of **closed windows** in the DM view (line, badge ring and icon).

*Keywords:* window colour, window color, closed window, window state, window badge, interactable, portal

### `ui.gridOpacity`
**Default:** `0.08` · **Range:** 0 to 1 · **Applies:** live

Opacity of the player grid overlay and the DM background grid. Adjust with the 0-100% slider beside the grid toggle
on the second row of the Player view DM controls. The percentage is shown alongside the slider; changes apply
immediately and are remembered across maps and restarts. Does not affect grid lines baked into map images.

*Keywords:* grid, grid lines, grid visibility, square grid, battle map grid

---

## Effect textures

Textured effects (smoke, fire, water, ...) are procedural, tiling textures made of one to six animated layers. Their
defaults can be changed here. All texture values are **live**: edit the file, switch back to the application and the
textures update immediately. Colour, opacity and *emits light* are start values that are copied into an effect when
you pick the texture; existing effects keep their own colour and opacity.

### `texture.tileCells`
**Default:** `4` · **Range:** 0.25 to 64 · **Applies:** live

Size of one texture tile in grid cells. Smaller values give a finer, more repetitive pattern; larger values a coarser
one. Applies to all textures.

*Keywords:* texture scale, pattern size, texture size, tiling

### `texture.featherCells`
**Default:** `0.9` · **Range:** 0 to 5 · **Applies:** live

Width of the soft edge of textures with `softEdges = true`, in grid cells.

*Keywords:* soft edge, feather, blur edge, texture edge

### `texture.featherPasses`
**Default:** `8` · **Range:** 1 to 24 · **Applies:** live

Number of smoothing steps of the soft edge. More passes give a smoother edge but cost time when an effect is drawn.
Performance mode uses at most `performance.maxFeatherPasses`.

*Keywords:* soft edge quality, feather steps, smoothness

### Per texture: `texture.<kind>.<property>`

`<kind>` is one of: `smoke`, `fire`, `water`, `lava`, `acid`, `ice`, `lightning`, `arcane`, `darkness`, `mist`,
`blood`, `web`, `holy`, `grease`, `sand`, `wind`, `radiation`, `poison`, `swamp`, `rubble`, `thorns`, `force`,
`necrotic`, `portal`, `chasm`.

#### `texture.<kind>.color`
Default colour `#RRGGBB` given to a new effect when this texture is picked. The texture is colourised with the effect
colour, so any texture can be recoloured (orange water looks like lava). Invalid colours are ignored.

*Keywords:* texture colour, effect colour, spell colour

#### `texture.<kind>.opacity`
Default opacity (0.05 to 1) given to a new effect when this texture is picked.

*Keywords:* texture opacity, effect transparency, alpha

#### `texture.<kind>.softEdges`
`true` fades the texture out towards the shape edge (smoke, mist, fire); `false` keeps a crisp edge (water, ice, web).

*Keywords:* soft edge, hard edge, feather, blur

#### `texture.<kind>.emitsLight`
`true` makes new effects with this texture glow and light up their surroundings in the effect colour (fire, lava,
holy light). It is only the default applied when the texture is picked: the bulb toggle can switch light on or off
for any effect (also textures set to `false` and flat colour) at any time.

*Keywords:* glowing effect, light source, emissive, fire light, lava glow

#### `texture.<kind>.lightStrength`
Brightness (0 to 1) of the light an emitting effect gives off.

*Keywords:* glow strength, effect light brightness

#### `texture.<kind>.lightRange`
How far (0 to 20 grid cells) the light of an emitting effect reaches beyond the shape edge.

*Keywords:* glow range, effect light radius

#### `texture.<kind>.lightFlicker`
Flicker depth (0 = steady, 1 = deep dips) of the light of an emitting effect.

*Keywords:* glow flicker, fire flicker, lightning flash

#### `texture.<kind>.lightFlickerSpeed`
Flicker speed (0.05 to 20, `1` = normal) of the light of an emitting effect.

*Keywords:* glow flicker speed, flash speed

#### Animation layers: `texture.<kind>.layer<N>.<property>`
Each texture is drawn as one or more layers of the same tile, scrolled and scaled differently to create movement and
depth. `<N>` starts at 1. The default file lists the built-in layers of each texture (see the table below); you can add
more layers up to `layer6` by adding at least the `scale` line of the new layer (missing properties of a new layer
default to no movement, scale 1 and opacity 0.8). A new layer is only picked up if all lower numbers exist.

- **`speedX`**, **`speedY`** – scroll speed in texture tiles per second (negative = left/up). Example: fire rises with
  a negative `speedY`.
- **`scale`** – size of this layer's pattern (0.1 to 16, bigger = larger pattern).
- **`opacity`** – opacity of the layer (0 to 1).
- **`pulseDepth`** – how much the layer opacity pulses (0 = constant, 1 = fades out completely).
- **`pulseHz`** – pulses per second (≥ 0).

*Keywords:* texture animation, scroll speed, flow direction, drift, layer, pulse, shimmer, animated texture, wind
direction, river flow

#### Texture defaults

The table lists the default of every per-texture property and the number of built-in animation layers. The layer values
are listed in `dmmt-settings.default.ini`.

| Texture | color | opacity | softEdges | emitsLight | lightStrength | lightRange | lightFlicker | lightFlickerSpeed | layers |
|---|---|---|---|---|---|---|---|---|---|
| `smoke` | `#9A9A9A` | 0.75 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `fire` | `#FF4A1A` | 0.9 | true | true | 0.85 | 3 | 0.3 | 1.6 | 2 |
| `water` | `#2A7BE0` | 0.55 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `lava` | `#E01A08` | 1 | false | true | 0.7 | 2.5 | 0.12 | 0.5 | 2 |
| `acid` | `#6BD62A` | 0.85 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `ice` | `#9AD8FF` | 0.75 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `lightning` | `#8FB4FF` | 0.9 | true | true | 0.9 | 3 | 0.6 | 4 | 2 |
| `arcane` | `#B36BFF` | 0.85 | false | true | 0.6 | 2 | 0.1 | 0.8 | 2 |
| `darkness` | `#3B2160` | 1 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `mist` | `#D8E4EA` | 0.75 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `blood` | `#8A0A0A` | 1 | false | false | 0.5 | 2 | 0 | 0.8 | 1 |
| `web` | `#E8E8E8` | 1 | false | false | 0.5 | 2 | 0 | 0.8 | 1 |
| `holy` | `#FFD85A` | 0.85 | true | true | 0.9 | 3.5 | 0.05 | 0.8 | 2 |
| `grease` | `#1C1A16` | 0.85 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `sand` | `#D9B26A` | 0.75 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `wind` | `#DDEEEE` | 0.65 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `radiation` | `#7CFF3A` | 0.75 | true | true | 0.5 | 2 | 0.1 | 0.8 | 2 |
| `poison` | `#8FC12A` | 0.75 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `swamp` | `#4E5A2C` | 0.9 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `rubble` | `#8C867C` | 1 | false | false | 0.5 | 2 | 0 | 0.8 | 1 |
| `thorns` | `#4F7A2A` | 0.95 | false | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `force` | `#5AC8FF` | 0.85 | false | true | 0.6 | 2 | 0.1 | 0.8 | 2 |
| `necrotic` | `#5B2A86` | 0.9 | true | false | 0.5 | 2 | 0 | 0.8 | 2 |
| `portal` | `#8A4DFF` | 0.9 | true | true | 0.6 | 2 | 0.1 | 0.8 | 2 |
| `chasm` | `#7A6248` | 1 | false | false | 0.5 | 2 | 0 | 0.8 | 1 |

---

## Other (unknown) keys

Lines with keys the application does not know (typos, settings of other versions) are not deleted. They are moved to a
section `# ---- Other ----` at the end of the file. Check this section if a setting you edited seems to have no effect –
the key is probably misspelled.

*Keywords:* unknown setting, typo, misspelled key, not working, ignored setting

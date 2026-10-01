# Dungeon Master Map Tool

[![Build](https://github.com/Bowtie8904/DungeonMasterMapTool/actions/workflows/build.yml/badge.svg)](https://github.com/Bowtie8904/DungeonMasterMapTool/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/Bowtie8904/DungeonMasterMapTool?label=release)](https://github.com/Bowtie8904/DungeonMasterMapTool/releases/latest)
![Java 17](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![JavaFX 21](https://img.shields.io/badge/JavaFX-21-blue)
![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white)

A desktop application for tabletop game masters who run in-person sessions with a **TV or monitor lying flat on the table**. Import a battle map, control everything from your own screen (DM view) and show players a clean, live view (player view) on a second display, with fog of war, dynamic lighting, effects, handouts and more.

![DM view overview](docs/images/dm-view-overview.png)

## Table of contents

- [What it does](#what-it-does)
- [Who it is for](#who-it-is-for)
- [Download](#download)
- [Requirements](#requirements)
- [Build and run](#build-and-run)
- [Technology stack](#technology-stack)
- [User documentation](#user-documentation)
  1. [Window layout](#1-window-layout)
  2. [Map library (left sidebar)](#2-map-library-left-sidebar)
  3. [Creating and importing maps](#3-creating-and-importing-maps)
     - [Multilevel maps](#multilevel-maps-buildings-with-several-floors)
  4. [Navigating the map](#4-navigating-the-map)
  5. [Player view (second screen)](#5-player-view-second-screen)
  6. [Fog of war](#6-fog-of-war)
  7. [Lighting](#7-lighting)
  8. [Doors and windows](#8-doors-and-windows)
  9. [Effects (AOE shapes and textures)](#9-effects-aoe-shapes-and-textures)
  10. [Text boxes](#10-text-boxes)
  11. [Map building](#11-map-building)
  12. [Ping and laser pointer](#12-ping-and-laser-pointer)
  13. [Handouts](#13-handouts)
  14. [Saving, auto-save and undo/redo](#14-saving-auto-save-and-undoredo)
  15. [Performance](#15-performance)
  16. [Settings file](#16-settings-file)
  17. [Keyboard and mouse reference](#17-keyboard-and-mouse-reference)

---

## What it does

- Imports **dd2vtt / uvtt** universal-VTT map files (for example exported from Dungeon Alchemist) including walls, lights, doors and windows, or lets you build a map from your own images (png/jpg/webp).
- Shows the map on your **DM screen** with full controls and on a **borderless fullscreen player window** on a second monitor, calibrated so that one grid tile is about **1 inch** in real life (ideal for miniatures).
- **Fog of war** with brush, area and one-click room reveal tools.
- **Dynamic lighting** with line of sight against walls and closed doors, flicker, colours, presets and time-of-day ambience.
- **Effects** such as circles, boxes and freehand areas with 25+ animated textures (fire, smoke, water, webs, chasms, ...), text labels, pings and a laser pointer.
- **Handouts**: paste images from the clipboard and show them on the player screen.
- Organises maps in a **folder library** with thumbnails, search, drag & drop and auto-save.
- **Multilevel maps**: several floors of a building (e.g. Dungeon Alchemist level exports) shown as one map, with quick up/down level switching.
- Full **undo/redo**.

Everything you need is copied into the app's own project storage on import, so original files (for example on a USB drive) are not needed afterwards.

## Who it is for

Game masters of D&D, Pathfinder and similar tabletop RPGs who play **in person** with a horizontal display (TV, monitor or projector) as the battle map and want to keep control (fog, lights, secrets) on a separate screen. Also useful for anyone who wants a light, offline alternative to online virtual tabletops.

## Download

Get the [latest release](https://github.com/Bowtie8904/DungeonMasterMapTool/releases/latest):

- **Windows:** download `DungeonMasterMapTool-windows.zip`, unzip it and run `DungeonMasterMapTool.exe`. Java is bundled, nothing else to install.
- **Linux / macOS:** download `DungeonMasterMapTool-linux.jar` or `DungeonMasterMapTool-macos.jar` (Apple Silicon) and run it with `java -jar <file>` (Java 17 or newer required).

## Requirements

| What | Details |
|------|---------|
| Operating system | Windows (primary target), macOS or Linux with JavaFX support |
| Java | **JDK 17 or newer** (the build uses release 17) |
| Build tool | [Apache Maven](https://maven.apache.org/) 3.8+ (or use the `.mvn` wrapper folder if present) |
| Displays | One screen is enough; a **second screen** is needed for the player view |
| Memory | 2 GB free RAM recommended; large maps (>4096 px) use a disk cache |
| Map source (optional) | `.dd2vtt` / `.uvtt` files, e.g. from Dungeon Alchemist, or your own images |

## Build and run

```powershell
# run from the source tree
mvn javafx:run

# or build a runnable "fat" jar and start it
mvn package
java -jar target\DungeonMasterMapTool-1.0-SNAPSHOT-all.jar

# run the unit tests
mvn test
```

Main class: `dmmt.DungeonMasterMapToolLauncher`.

Data locations:

- **Map library:** the `dmmap-projects` folder in the working directory (created on first use, git-ignored).
- **Settings:** `dmmt-settings.ini` next to the jar (see [Settings file](#16-settings-file)); override with `-Ddmmt.settings=<path>`.
- **Image tile cache:** `%LOCALAPPDATA%\DungeonMasterMapTool\image-cache` (fallback `~/.dmmt/image-cache`); entries unused for 60 days are pruned.

## Technology stack

| Area | Technology |
|------|------------|
| Language / runtime | Java 17 |
| UI and rendering | JavaFX 21 (Canvas rendering, custom dark CSS theme) |
| Icons | Ikonli with Material Design Icons 2 |
| Rich text editing | RichTextFX |
| Geometry / line of sight | JTS Topology Suite |
| JSON (dd2vtt import, `.dmmap` save format) | Jackson |
| Boilerplate reduction | Lombok |
| Build | Maven (maven-shade-plugin for the fat jar, javafx-maven-plugin for `javafx:run`) |
| Tests | JUnit 5 |

Maps are saved in a versioned JSON format (`.dmmap`) that is identical for imported and custom maps, so every feature works on both.

---

## User documentation

> Tip: every button in the application has a tooltip that names its function and shortcut.

### 1. Window layout

- **Left:** the collapsible **map browser** (library and map actions).
- **Centre:** the **map canvas**. A floating **tool chip** at the top names the active tool ("Esc or right-click to exit").
- **Right:** the **DM controls** overlay, made of collapsible sections: *Tools, Fog of war, Lighting, Effects, Text, Map building, Player view, Performance*. Collapsed/expanded state is remembered. The whole panel can be collapsed and scrolls in small windows.
- **Bottom:** a slim status bar with messages (auto-save, import progress, errors) and the performance toggle and readout.

### 2. Map library (left sidebar)

![Map browser](docs/images/map-browser.png)

The library is the app-managed `dmmap-projects` folder shown as a tree, like a file browser.

- **Folders** group maps (campaign, cities, wilderness, ...). They can be nested, expanded and collapsed.
- Each map is shown by **name only**, with a **thumbnail** (hover for a larger preview). Thumbnails show only the map images (never fog, lights or effects) at the current rotation.
- The currently open map is highlighted, and its name is shown at the top.
- **Toolbar:** New map, Import dd2vtt, Import folder, Import multilevel map, Save, Auto-save toggle (with interval), New folder, Refresh, Rotate left, Rotate right.
- **Search bar:** case-insensitive live filter on map and folder names. A matching map is shown with its parent folders, a matching folder with its content. `Esc` or the clear button restores the tree.
- **Open a map:** double-click or press `Enter`. The current map is auto-saved first.
- **Recent maps:** the list below the tree shows the maps you opened last (most recent first, size set by `ui.recentMaps.max`). Double-click one to open it again.
- **Move:** drag & drop maps or folders onto a folder (or onto empty space for the library root). Hovering a collapsed folder while dragging expands it. Folders dropped onto a map go to that map's folder. With several entries selected, dragging any of them moves **all selected entries** (entries inside a selected folder travel with it); entries that cannot be moved (name taken, folder into itself) are skipped and listed afterwards.
- **Merge by drag & drop:** drop a map **onto another map** to build a multilevel map. The target turns green and shows what happens: map onto map → *Create a multilevel map*; map onto a multilevel map (or a multilevel map onto a map) → *Add to the multilevel map*; multilevel map onto a multilevel map → *Merge the levels into this map* (the map you drop onto keeps its name and shared settings, the dragged one gives up its levels and disappears). The level dialog opens first so you can set the order. Dragging **several selected maps** onto a map adds all of them in one go; if multilevel maps are involved, the target keeps its name and settings when it is a multilevel map, otherwise the first dragged multilevel map does.
- **Select several maps** with `Ctrl+click` / `Shift+click` (used to merge them into a multilevel map, or to drag them all at once).
- **Context menu on a map:** Open, Rename, Duplicate, Make multilevel map (or *Merge N maps into a multilevel map* when several maps are selected), Delete. **On a multilevel map:** Open, Manage levels, Dissolve into separate maps, Rename, Duplicate, Delete. **On a folder** (or empty space): New map here, Import map here, Import folder here, Import multilevel map here, New folder, Rename, Delete.
- **Keyboard:** `F2` rename, `Delete` delete (always with confirmation; folders state how many maps they contain), `Enter` open.
- **Duplicate** creates `Name (Copy)`, `Name (Copy 2)`, ...
- **Rename** renames the package folder and file on disk. Names cannot be empty, contain `<>:"/\|?*`, be reserved Windows names, or duplicate a name in the same folder.
- Leaving an unsaved new map asks **Save / Discard / Cancel**.

On disk each map is a package: `<folder>/<Map Name>/<Map Name>.dmmap` plus `assets`, `imports` and `thumbnail.png` (hidden in the tree). A multilevel map is a package `<folder>/<Map Name>/<Map Name>.dmlevels` with one ordinary map package per level below `levels/` (also hidden in the tree).

### 3. Creating and importing maps

![Import dialog](docs/images/import-location-dialog.png)

**Import a dd2vtt/uvtt map**

1. Click **Import dd2vtt** (or right-click a folder → *Import map here*).
2. Pick the file in the system file chooser. The last used directory is remembered.
3. A location dialog shows the library folder tree only. Choose or create a folder and confirm the map name (pre-filled from the file).
4. The map image, walls, lights, doors, windows and grid are converted and copied into the library. The original file is no longer used.

**Batch import**

- Select several files in the chooser, or use **Import folder…** to import every `.dd2vtt`/`.uvtt` found in a folder and its sub-folders.
- The location dialog asks only for the target folder. Maps are named after their files; duplicates get a suffix (`Name (2)`).
- Files in the same folder that look like the **levels of one building** are imported automatically as **one multilevel map**, ordered by level number:
  - names that differ only by a level number, optionally followed by a room name: `haus_00 … haus_03`, `Inn 1`, `Inn 2`, `tower_upper_02_barracks … tower_upper_10` (levels are named `Level 02 – barracks` etc.);
  - a file without a number whose name is the start of such a series (`tower` next to `tower_upper_…`) becomes the lowest level, and the multilevel map is named after it.
  - Series with a repeated number (`Market 1 day`, `Market 1 night`) and merely similar names (`Goblin Cave`, `Goblin Camp`) stay separate maps — merge them afterwards if needed.
- Import runs in the background with progress in the status bar (`Importing 3/12: name...`). The open map is not switched. Failed maps are removed again, the batch continues, and a final dialog lists failures.

**Create a custom map**

1. Click **New map**.
2. Drag & drop images (png/jpg/webp) onto the canvas, or use **Add image** in the *Map building* section.
3. Position and scale layers, then draw walls if you want line-of-sight lighting (see [Map building](#11-map-building)).
4. Click **Save** and choose a folder and name in the location dialog.
5. This tool has no capability of maintaining map object assets. It is meant to be used with map images created via some other tool. You will not be able to start with a blank canvas and create a full map in this tool.

#### Multilevel maps (buildings with several floors)

Dungeon Alchemist can export every floor of a multi-story building as its own dd2vtt file. A **multilevel map** keeps all those floors together: the library shows it as **one map** (with a layers badge; the tooltip shows the number of levels and a preview of the level opened last), and you switch floors while playing.

**Create one**

- **Import:** click **Import multilevel map** in the library toolbar (or right-click a folder → *Import multilevel map here*) and select all level files at once. The level dialog lists them **lowest level at the top**, sorted by file name and with suggested level names (e.g. `haus_00 … haus_03` → *Level 00 … Level 03*). Reorder, rename, remove or add levels, click **Next**, then pick the folder and name (pre-filled with the part all file names share).
- **Batch import:** files numbered like levels are grouped automatically (see *Batch import* above).
- **Merge existing maps:** drag a map onto another map, or select several maps in the library (`Ctrl+click`) and right-click → *Merge N maps into a multilevel map…* (or *Make multilevel map…* on a single map). The maps are **moved** into the new multilevel map and no longer appear on their own; their fog, lights, effects and cameras are kept. File names do not have to match — set the order in the dialog.

**Switch levels**

- For multilevel maps with two or more levels, a small switcher appears at the top left of the map: a dropdown to jump to any level (lowest at the top; hovering an entry shows a preview of that level), then **▼** one level down and **▲** one level up, the position (`2 / 4`) and a pencil button to manage the levels.
- `Page Up` / `Page Down` go one level up / down.
- The open level is saved before switching; only the open level is kept in memory.
- Saving remembers the open level; reopening the map returns to it. A map opened for the first time starts on the lowest level.

**What is per level and what is shared**

- **Per level:** fog of war, camera positions and the player view zoom, image layers, walls, doors/windows, lights, effects and text boxes.
- **Shared by all levels** (changing it on any level changes it for all): time of day, ambient brightness, weather, image layer lock, fog on/off, map rotation, DM view zoom, player zoom offset, text layer visibility and the last used text settings.

**Manage levels** (right-click the multilevel map → *Manage levels…*, or the pencil in the switcher)

- **Insert** levels anywhere: new entries are added below the selected level, from dd2vtt files, from existing library maps (moved in) or as an empty level.
- **Reorder** by dragging levels in the list (or `Alt+↑` / `Alt+↓`).
- **Right-click a level** to rename it (`F2` or double-click), delete it (`Delete`), or **move it out as a separate map**. Moving out asks for the map name, pre-filled with the level's original file/map name (or `<multilevel map> <level>`); the map is placed next to the multilevel map and keeps the shared settings it had. *Keep in the multilevel map* undoes this before you click **Apply**.
- **Apply** summarizes what happens (deleted levels, levels moved out) and asks for confirmation.
- A multilevel map left with **one level** becomes an ordinary map with the same name. Removing the **last** level asks for confirmation and deletes the whole multilevel map.
- **Dissolve** (right-click the multilevel map → *Dissolve into separate maps…*) turns every level into a separate map next to it; nothing is deleted.
- If the open level is removed, the nearest remaining level is opened (preferring the level below).
- Renaming, duplicating, moving and deleting the whole multilevel map work like for any other map in the library.

### 4. Navigating the map

- **Zoom:** mouse wheel, centred on the cursor.
- **Pan:** right-drag (or drag with a suitable tool).
- **Rotate the whole map** in 90° steps with the rotate buttons in the sidebar. Image layers, walls, fog, lights, effects, text boxes and both cameras rotate consistently and the rotation is saved.
- **Player viewport rectangle:** when the player window is open, the DM view shows a window-like rectangle (cyan border with a "Player view" title bar) representing what the players see. Drag it by its title bar with the Select tool.

Large images (> 4096 px) are rendered at full resolution through an on-disk tile pyramid built in the background the first time a map is opened.

### 5. Player view (second screen)

![Player view section](docs/images/player-view-section.png)

Controls are in the *Player view* section.

- **Player window** toggle: opens a borderless fullscreen window on the chosen monitor. Use the **screen selector** to pick the exact monitor.
- **1-inch calibration:** enter the physical **screen diagonal** of the player monitor (stored per screen) and the **tile size in inches** (default 1). The zoom is derived automatically so each grid tile measures that size. The **1 in test square** toggle shows a square on the player screen so you can verify it with a ruler.
- **Freeze:** snapshots the *entire* project (map, fog, lights, effects, camera) for the players. You can switch maps or edit freely while they keep seeing the old state. You can still move the player viewport; on **unfreeze** the player view jumps to the current map and the staged rectangle.
- The player view never shows DM helpers: wall guides, door state lines, badges, viewport handles, selection outlines or text-box editing.
- **Handout** button: see [Handouts](#13-handouts).

### 6. Fog of war

![Fog of war section](docs/images/fog-section.png)

The *Fog of war* section:

- **Fog on/off** toggle: hides or shows fog without losing what is revealed. Fog tools only work while fog is on.
- **Reveal brush / Hide brush:** paint fog away or back. The brush size slider covers 0.5–8 tiles and a circular preview follows the cursor.
- **Reveal area / Hide area:** drag a rectangle (dashed preview).
- **Reveal room:** one click floods the room under the cursor, bounded by walls and doors/windows (regardless of whether they are open). **Shift+click** hides the room instead. A hover preview outlines the region before you click, so you see immediately if it is not enclosed. The tool stays armed for several rooms in a row.
- **Reveal all / Hide all.**
- **Fog sharpness** slider: fog cells per tile (5–30, default 10). Applies to all projects; existing masks are resampled.

Rendering: the DM sees fog semi-transparent, so hidden content stays faintly readable. Players see fully opaque fog except revealed or lit regions. Fog covers effects, text boxes and the laser pointer's target area, but never erases map pixels.

### 7. Lighting

![Lighting section](docs/images/lighting-section.png)

The *Lighting* section controls lights and ambience.

**Lights**

- **Add light** and **Remove light** are one-shot tools: arm the tool, then click the map (place / remove the clicked light). The tool returns to Select afterwards. `Esc` cancels.
- **Presets** (place with one click): Candle (2 tiles), Torch (8), Lantern (12), Campfire (16), Magic light (12). Ranges are in tiles and scale with the grid.
- Drag lights with the Select tool. **Right-click** a light for its menu:
  - *Light on* (off lights cast nothing and reveal nothing),
  - **Fog reveal mode:** *Keep revealed* (the lit area stays revealed), *Only while lit* (default for DM-added lights), *Don't reveal* (default for imported lamps),
  - **Range** (1–12 tiles),
  - **Flicker:** Off / Candle / Torch / Strong torch / Slow pulse (imported lights default to Torch),
  - **Colour** preset,
  - **Blocked by walls**,
  - **Remove**.
- Light is blocked by walls and closed doors; open doors let light through.
- **Light tint** slider: strength of the light colour over lit areas (0–30 %, global).

**Time of day**

- Four buttons: **Day, Dawn, Dusk, Night**. Day means no darkness (dd2vtt maps have baked lighting). The DM view shows darkness at reduced strength so the map stays readable.
- **Ambient brightness** slider (−50 % to +100 %): adjusts the darkness of the current preset for this map only. Double-click resets; disabled at Day.

### 8. Doors and windows

Imported doors and windows are interactive objects.

- Each has a round **icon badge** in the DM view (open/closed glyph, red/green for doors, blue for windows) above the fog.
- With the Select tool, **click the badge or the door line** to open or close it. Hovering highlights it.
- State affects line of sight and lighting immediately, is undoable and is saved with the map.

### 9. Effects (AOE shapes and textures)

![Effects section](docs/images/effects-section.png)

Draw areas of effect that both DM and players see (below fog, above map and lighting).

**Tools**

- **Circle:** drag from the centre outward. **Box:** drag corner to corner. **Draw:** freehand stroke whose thickness is the brush size. **Pen:** thin freehand line in the selected colour. **Line:** straight line with the brush size as thickness.
- A live size label (in tiles, DM only) shows while dragging, e.g. radius `2.5` or `3 x 2`.

**Style**

- **Colour** and **opacity** (10–100 %) apply to new shapes and to the selected shape.
- **Texture** dropdown: Flat colour, Smoke, Fire, Water, Lava, Acid/slime, Ice/frost, Lightning, Arcane runes, Darkness/void, Mist/fog, Blood, Spider web, Holy light, Grease/oil, Sand/dust storm, Wind gusts, Radiation/aura, Poison/toxic gas, Swamp/mud/bog, Rubble/debris, Thorns/brambles, Force/shield, Necrotic/shadow rot, Entropy/portal and Chasm/broken earth. Textures are procedurally generated, animated and tinted with the effect colour; picking a texture loads its default colour and opacity, which you can change (orange water looks like lava, green like acid).
- **Border** toggle outlines a textured shape.
- **Light emission** (bulb) toggle: the effect glows and lights up dark maps (fire, lava, lightning, arcane, holy light, radiation, portal and force by default). It can be switched on or off for any effect afterwards, whatever the texture default.
- **Animations** toggle (per map): freezes all textures to a static frame to save performance.
- **Players see:** hides a shape from the player view (shown dashed and dimmed with a crossed-out eye for the DM). Right-click any shape with Select for a *Visible to players* menu item.
- **Brush size** slider for Draw, Line and the fog brush.

**Editing**

- Select tool: click to select, drag to move, drag the **bottom-right handle** to resize/stretch. `Delete` or the Delete button removes a shape; **Clear all** removes every shape.
- Everything is undoable and rotates with the map.

### 10. Text boxes

![Text section](docs/images/text-section.png)

- **Text** tool: click-drag to draw a box (a plain click creates an auto-sized one) and start typing. Click a box with the Text tool, or double-click it with Select, to edit. `Esc`, clicking outside or switching tools finishes editing.
- Text wraps to the box width, is centred and clipped at the edge. **Auto-size** boxes grow and shrink with the text (maximum width 12 cells); manually resizing turns auto-size off. The *Auto-size* toggle switches it per box.
- **Font size** and **text colour** apply to newly typed text or to the selected text (or all text when a box is selected but not edited).
- **Background** and **border** colours (transparent by default, opacity supported, "no fill" / "no border" buttons). Corners are rounded.
- Select tool: click to select, drag to move, use the 8 handles to resize, `Delete` removes.
- **Text layer** toggle shows/hides all text in DM and player views. Text boxes are drawn below fog.
- Last-used settings are remembered per map (with a global fallback).
- `Ctrl+C` / `Ctrl+V` copy and paste a box, including into another map.

### 11. Map building

![Map building section](docs/images/map-building-section.png)

- **Add image** (or drag & drop): adds an image layer, which starts unlocked so you can position it.
- **Move and resize** layers with drag handles. **Snap layers** snaps in half-tile steps. `Delete`/`Backspace` removes the selected layer. Per-layer rotation is not supported; use whole-map rotation.
- **Lock image layer** toggle: while locked, layers cannot be selected, moved, resized or deleted. It is locked by default for imported dd2vtt maps and unlocked for new custom maps, and is saved per map. A banner on the canvas warns while layers are unlocked and offers a **Lock** button.
- **Wall tools:** *Wall* draws segments (half-tile snap, hold `Shift` for free placement) and *Erase wall* removes them, so custom maps get line-of-sight lighting too.
- **Wall layer** toggle: shows or hides walls in red together with door/window lines and badges (DM only, session-only). Choosing a wall tool shows it again.
- **Multi-selection (Select tool):** drag a rectangle on empty space to select lights, text boxes, effects and unlocked image layers (the rectangle turns green while it would select something). `Ctrl+click` adds or removes items. Drag any member to move the group, `Delete` removes it, all as one undo step. Doors and windows are not part of groups.

### 12. Ping and laser pointer

- **Ping** (`P`, crosshair button in the Tools row): click the map to flash an animated marker that fades out, in both DM and player views.
- **Laser pointer:** hold the **middle mouse button** to show a red dot with a short fading trail at the cursor. It works with every tool without changing it, is drawn above fog, and is shown to players only when they are viewing the current map (not while frozen or showing a handout).

### 13. Handouts

![Handout window](docs/images/handout-window.png)

Show images such as NPC portraits or letters to your players.

1. Click **Handout** in the *Player view* section. A separate DM-side window opens (one instance).
2. Press **Ctrl+V** in that window or click **Paste**. Accepted: image data (browser "Copy image", Snipping Tool) and copied image files (png/jpg/webp/gif/bmp). Every paste **adds** an image.
3. All images appear together in an **auto-fitted grid** that maximises the covered area while keeping aspect ratios.
4. **Rotate left/right** in 90° steps so the handout faces the intended player around the table. Rotation applies to the whole arrangement.
5. Click an image to select it, then press `Delete` or use the right-click **Delete** or **Delete selected**. **Clear all** removes everything. The selection highlight is never shown to players.
6. Switch on **Show to players**. The player screen then shows only the handout on black. Switch it off and the player view returns to the live or frozen map. It is disabled without images or an open player window.

Closing the handout window stops showing it and discards the images. Handouts are session-only and never saved.

### 14. Saving, auto-save and undo/redo

- `Ctrl+S` or the **Save** button saves the project as `.dmmap`. It stores image layers, walls, doors/windows states, fog mask, lights, time of day and ambient brightness, effects, text boxes and cameras.
- **Auto-save** is on by default for maps that already exist on disk: every 2 minutes (1, 2, 5 or 10 selectable) when there are changes, plus when the window loses focus or on close. It runs in the background and is postponed during a drag. The status bar shows "Auto-saved HH:mm". New, never-saved maps are not auto-saved.
- **Undo `Ctrl+Z` / Redo `Ctrl+Y`** covers layer moves, rotation, lights (add, remove, move, settings), doors and windows, fog edits, effects, text boxes, walls, time of day and ambient brightness. Undo history is per session. Both views update immediately.
- Saved projects stay loadable even when the original import files are gone.

### 15. Performance

![Performance toggle](docs/images/performance-status-bar.png)

- The base layer (background, grid, images) is drawn on its own canvas, the light map is cached and only recomputed when needed, and the frame rate drops automatically when nothing moves (see the `render.*` settings).
- **Performance readout** in the status bar: actual/limit fps, average and worst render time, heap usage and the two slowest render parts (the tooltip lists all).
- **Performance mode** toggle (bottom-left): a temporary override for weaker machines. It lowers light-map resolution, slows texture animation, disables flicker, uses coarser tiles and merges fog cells, without touching your data or other settings. How much it reduces is configurable with the `performance.*` settings (see [docs/SETTINGS.md](docs/SETTINGS.md#performance-mode)).
- The **Animations** toggle in the Effects section disables texture animation per map.

### 16. Settings file

Global preferences are stored in `dmmt-settings.ini` next to the jar as plain `key = value` lines with `#` comments. The file is created with every setting and its default; settings added by a newer version are appended with their defaults at startup, and unknown keys are preserved. Invalid values fall back to defaults and numbers are clamped to their allowed range. Delete a line to restore its default.

**Every entry is documented in [docs/SETTINGS.md](docs/SETTINGS.md)** (default, range, when it applies, what it does, and search keywords). `dmmt-settings.default.ini` is a copy of a freshly created file.

Highlights:

| Key | Meaning |
|-----|---------|
| `player.screenIndex`, `player.tileInches`, `player.screenDiagonalInches.<n>`, `player.zoom.*` | Player monitor, 1-inch calibration and player zoom |
| `fog.cellsPerGrid`, `fog.softness`, `fog.revealSeconds`, `fog.hideSeconds`, `fog.fadeEasing` | Fog sharpness, soft edges and fade animation |
| `lighting.*`, `lightPreset.*`, `lightMenu.*`, `timeOfDay.*` | Lighting, light tool presets, light menu choices, time-of-day darkness |
| `render.*`, `performance.*` | Frame rates and performance mode |
| `input.*`, `dm.zoom.*` | Click tolerances and DM zoom |
| `weather.*`, `ping.*`, `laser.*` | Weather particles, ping and laser pointer look |
| `cache.*`, `library.folder` | Memory/disk caches and map library location |
| `texture.<kind>.*` | Per-texture colour, opacity, soft edges, light emission and animation layers |

Most values apply when the window regains focus after you saved the file; entries marked *Restart required* apply at the next start. Use `-Ddmmt.settings=<path>` to use a different file.
### 17. Keyboard and mouse reference

| Input | Action |
|-------|--------|
| `Esc` | Cancel the active tool / return to Select / clear selection |
| Right-click (no drag) | Same as `Esc` while a tool is active; opens context menus in Select |
| Right-drag | Pan |
| Mouse wheel | Zoom around the cursor |
| Middle mouse (hold) | Laser pointer |
| `P` | Ping tool |
| `Ctrl+S` | Save |
| `Page Up` / `Page Down` | One level up / down (multilevel maps) |
| `Ctrl+Z` / `Ctrl+Y` | Undo / Redo |
| `Ctrl+C` / `Ctrl+V` | Copy / paste text box (in the handout window: paste image) |
| `Delete` / `Backspace` | Delete selected layer, light, text box or effect |
| `Ctrl+click` | Add or remove an item from the selection |
| `Shift+click` (Reveal room) | Hide the room |
| `Shift` (wall tool) | Free wall placement without snap |
| `Enter` / `F2` / `Delete` (map browser) | Open / rename / delete |

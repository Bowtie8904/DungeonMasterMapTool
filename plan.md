# Dungeon Master Map Tool - Living Specification

## 1) Chosen Technology Stack

- **Language/runtime:** Java 17+ (compiled with release 17, JavaFX 21)
- **UI/rendering:** JavaFX + Canvas, custom dark CSS theme, Ikonli (Material Design Icons 2) for icons
- **Build/deps:** Maven
- **Codegen:** Lombok (DTOs/models/boilerplate reduction)
- **Project save format:** Versioned JSON (`.dmmap`)
- **DD2VTT import:** Jackson for JSON parsing
- **Math/geometry:** JTS (or lightweight in-house geometry where enough)

## 2) Product Goals

Desktop tool for tabletop dungeon masters that:

1. Imports universal-export **dd2vtt** maps (e.g., from Dungeon Alchemist).
2. Provides smooth DM-side map navigation (zoom, pan, camera controls).
3. Supports optional borderless fullscreen **player view** on a second display.
4. Supports fog of war, lighting, and overlay drawing workflows fast enough for live sessions on a mid-range laptop.
5. Supports creating custom maps from imported images while keeping full feature parity with imported dd2vtt maps.

## 3) Current Implementation Status

- [x] DD2VTT import and managed project copy flow
- [x] JavaFX bootstrap fixed and app runs with explicit launcher entrypoint
- [x] DM overlay and multi-monitor player selection
- [x] Freeze/staged player camera behavior with clean player render path
- [x] Save/open project handling into app-managed storage
- [x] Basic undo/redo support for map and editor actions
- [x] Whole-map rotation with corrected direction logic
- [x] Fog-of-war reveal/hide brush + area tools on a persisted grid bitmask (`FogMask`), with undo/redo
- [x] Dynamic lighting + line-of-sight engine (walls + closed doors), flicker, per-light fog reveal modes and time-of-day presets
- [x] AOE overlay drawing tools (circle/box/freehand) with color, opacity and player-visibility
- [x] Map switcher with full-snapshot freeze of the player view
- [x] Manual wall editing for custom-image maps (Wall / Erase Wall tools, half-tile snap, Shift = free, undoable)
- [x] 1-inch tile calibration for the player screen (screen diagonal + tile-inch settings, auto zoom, 1-inch test square)
- [x] Map browser sidebar with folder tree, drag & drop, rename/copy/delete, custom save/import location dialog (3.15)
- [x] Modern dark DM controls overlay: collapsible sections, icon buttons, tooltips, active-tool chip + cursors (3.16)
- [x] Performance pass (idle throttle: ~10 fps when no active lights and no input for 1s) (render throttling)

## 4) Core Functional Requirements

## 3.1 Import and Map Model

- Import dd2vtt map files.
- Create custom maps by drag-and-drop image import (png/jpg/webp) into a map canvas.
- Allow image layer transform editing (move, resize/scale) directly with drag handles. Per-layer rotation is intentionally not supported; use whole-map rotation.
- Parse and store:
  - base image path/data
  - grid size/scale
  - wall/occluder data for line of sight
  - dd2vtt lights
  - dd2vtt doors/windows and their interaction metadata
  - metadata required for rendering
- Convert imported content into internal project model and allow saving as `.dmmap`.
- Use the same internal model/save format for dd2vtt and custom-image maps so all features (fog, lights, overlays, player view, freeze) work identically.
- Imported dd2vtt semantics must remain functional after conversion (no loss of core gameplay behavior).
- Import must **copy all required source data/assets** from the dd2vtt location into app-managed project storage so the original file location (e.g., USB drive) is no longer required afterward.

## 3.2 DM View

- Zoom in/out around cursor focus.
- Pan/camera movement via drag and/or keybind.
- Render overlays (fog, lights, shapes) with editing controls.
- Show player viewport rectangle when player screen is active.
- Provide a map-rotation menu action to rotate the entire map in **90-degree steps** (0/90/180/270).
- Layout: **map browser sidebar** on the left (see 3.15), map canvas in the center, compact **DM controls overlay** on the right (see 3.16), slim status bar at the bottom.
- Remember the last dd2vtt import directory and reopen that location for the next import (source file) dialog.

## 3.3 Player View (Second Screen)

- Optional separate window on selected monitor.
- DM can choose the exact target monitor for player view from an in-app screen selector (supports 2+ monitor setups reliably).
- Borderless fullscreen mode.
- Adjustable world-to-screen scale so each tile is approximately **1 inch** physical size.
- Player window mirrors a movable camera rectangle controlled from DM view.
- Freeze mode: player view remains fixed while DM prepares another map/view.
- While frozen, DM can still move the player viewport rectangle; on unfreeze, player view immediately jumps to that staged rectangle.
- Player view must stay visually clean: DM-only helper/debug geometry (wall guides, door/window state lines, viewport handles) is never rendered there.

## 3.4 Map Switching

- Fast map/project switcher UI.
- If player view is frozen, switching/opening in DM view must not affect player output until unfreeze/swap action.
- Maps are switched from the **map browser** (3.15): double-clicking a map auto-saves the current map and opens the chosen one.
- **Freeze** snapshots the *entire* project for the player window (map, fog, lights, effects, camera) using a separate renderer/lighting engine, so players see the old map exactly as it was regardless of DM edits or map switches. Unfreezing makes the player view jump to the currently open map and its staged viewport.
- Frozen state is session-only (not saved in `.dmmap`).

## 3.5 Fog of War

- Fog toggle on/off without losing reveal state.
- Reveal/hide via:
  - brush (size configurable, 0.5-8 tiles, circular cursor preview)
  - rectangular area drag tool (dashed preview while dragging)
  - Reveal All / Hide All
- Fog tools only act while fog is enabled; the **Select** tool (default, `Esc`) is used for dragging lights, layers and the player viewport.
- Fog is rendered on its own transparent canvas above the map (never erase map pixels).
- DM fog rendering: semi-transparent (DM sees obscured content faintly).
- Player fog rendering: fully opaque except revealed/light-visible regions.
- Storage: world-space grid bitmask, cell size = grid cell / 10 (clamped 4-50 px); grows to cover map content; rotates with the map.

## 3.6 Dynamic Lighting

- Add/remove/move one or more light sources in DM view.
- Each light has:
  - range/radius
  - intensity/falloff preset
  - optional flicker (torch-style)
- Player view shows resulting illumination only (not editor token glyphs).
- LOS and occlusion use imported dd2vtt wall data plus closed doors (open doors let light through).
- Right-click a light in the DM view for its menu: fog reveal mode, range (1-12 tiles), flicker preset (Off/Candle/Torch/Strong torch/Slow pulse), color preset, "Blocked by walls", remove.
- Per-light fog reveal mode (decided):
  - **Keep revealed** (`PERSISTENT`): LOS area is written into the fog mask and stays revealed after the light moves.
  - **Only while lit** (`WHILE_LIT`, default for DM-added lights): LOS area is uncovered only while the light is there; not saved into the mask.
  - **Don't reveal** (`NONE`, default for imported dd2vtt map lamps): illuminates only.
- Each light can be switched on/off from its menu (`Light on`); an off light casts no light and reveals nothing (its token is drawn hollow). Undoable.
- Persistent reveals only accumulate while fog is enabled.
- Light moves, light setting changes and door toggles are undoable, including the fog they revealed.
- Rendering: quarter-resolution light map (cached LOS polygons per light, recomputed only on move/geometry change), skipped entirely at Day.

## 3.7 Time-of-Day Lighting

- 4 presets: Day, Dawn, Dusk, Night (selector in the DM overlay, undoable).
- Preset affects ambient darkness + tint globally; Day = no darkness (dd2vtt maps have baked lighting).
- DM view shows darkness at reduced strength so the DM can still read the map.

## 3.8 Tactical/AOE Overlays

- Draw circles/rectangles/free brush overlays (tools **Circle**, **Box**, **Draw** in the *Effects* section of the DM controls).
  - Circle: drag from center outward. Box: drag corner to corner. Draw: freehand, thickness = brush size slider.
- Adjustable color (color picker) and opacity (0.1-0.9); the current style applies to new shapes, and to the selected shape if there is one.
- Visible in both DM and player views; **Players see** unchecked hides a shape from the player view (DM sees it dashed/dimmed).
- Shapes are drawn above map and lighting but below fog, so fog still conceals them.
- With the Select tool: click a shape to select and drag to move; `Delete` or the Delete button removes it; Clear All removes every shape.
- All create/move/style/delete/clear actions are undoable; shapes rotate with the map and persist in the save file (`overlays`: `type`, `x/y`, `radius`, `width/height`, `points`, `strokeWidth`, `color`, `alpha`, `playerVisible`).

## 3.9 Map Ping Tool

- DM can place quick pings on the map to direct player attention.
- Ping appears immediately in DM and player views at world position.
- Ping should be visually obvious (short animation + fade out).
- Support at least one default ping style initially; extensible for variants later.

## 3.10 Doors, Windows, and dd2vtt Interactables

- Imported doors and windows must be represented as interactable map objects.
- DM can open/close doors and windows directly from DM view.
- Open/closed state affects line of sight and lighting occlusion in real time.
- Interactable states must render correctly in DM and player views.
- Open/closed state must be persisted in `.dmmap`.

## 3.11 Persistence

- `Ctrl+S` saves current project state.
- Save includes at minimum:
  - imported map reference/data
  - fog state
  - reveal mask
  - light definitions
  - imported/interactable object states (doors/windows)
  - time-of-day preset
  - overlays/shapes
  - ping-related settings/history if configured to persist
  - DM/player camera states
  - freeze mode state
- Include schema version for migrations.
- Saved projects must remain loadable even when original import paths are unavailable (offline/removed external media).

## 3.12 Undo/Redo History

- `Ctrl+Z` undoes the most recent DM action.
- Undo must cover at least:
  - moving map/image layers
  - whole-map rotation
  - adding/removing/moving lights
  - opening/closing doors and windows
  - fog-of-war reveal/hide actions (brush and rectangle tools)
- Undo must update both DM and player views immediately after action rollback.
- Undo state is session-local (not required to persist across app restarts in v1).
- Add `Ctrl+Y` redo support as part of the same command-history system.

## 3.13 Custom Map Builder

- Provide an editor mode for quick map assembly from one or more dropped images.
- Support snapping/scaling against grid so map tiles align predictably (**Snap layers** toggle: half-tile steps while moving/resizing).
- Persist image layer stack order and transforms in `.dmmap`.
- Allow adding walls manually for custom-image maps so LOS lighting remains usable even without dd2vtt wall data.

## 3.14 Whole-Map Rotation

- Support rotating the complete map scene in 90-degree intervals via menu controls.
- Rotation applies to all map-linked systems consistently:
  - base image/image layers
  - walls/LOS blocking geometry
  - fog mask and revealed areas
  - light positions and LOS output
  - overlays/shapes
  - DM and player camera framing
- Rotation state must be persisted and restored from `.dmmap`.

## 3.15 Map Browser (Left Sidebar)

- Collapsible sidebar on the left of the DM view containing the map library and the map-level actions: **New map, Import dd2vtt, Save, New folder, Refresh, Rotate left/right**, plus the name of the currently open map.
- Library = the app-managed `dmmap-projects` folder. It is shown as a **tree view** like a file browser:
  - Folders are real folders on disk and are used to group maps (campaign, cities, generic, wilderness, ...). Folders can be nested, expanded and collapsed; the tree scrolls for large libraries.
  - Each map is shown **only by its name** - no `.dmmap` extension, no asset files or asset folders.
  - The currently open map is highlighted.
- On disk every map is a **map package**: `<folder>/<Map Name>/<Map Name>.dmmap` plus its assets (`assets\...`, `imports\...`). A directory containing exactly one `.dmmap` is treated as a map package; other directories are folders. Loose `.dmmap` files (older saves) are also listed and converted to packages when moved/renamed/copied.
- Interactions:
  - **Double-click** (or Enter) opens a map in the DM view (current map is auto-saved first).
  - **Drag & drop** maps and folders onto a folder (or onto a map to use its folder) to move them; the move happens on disk. Dropping a folder into itself/its children is rejected. Hovering a collapsed folder while dragging expands it.
  - Right-click a **map**: Open, Rename, Duplicate (copy), Delete. Right-click a **folder** (or empty space = library root): New map here, Import map here, New folder, Rename, Delete. Keyboard: F2 rename, Delete delete, Enter open.
  - **Delete** always asks for confirmation (folders state how many maps they contain).
  - **Copy** creates a sibling named `<Name> (Copy)`, `<Name> (Copy 2)`, ... (a copy of a copy re-uses the base name).
  - **Rename** renames everything required on disk: the package folder and the `.dmmap` file (asset paths are relative, so they stay valid).
  - Names are validated (no empty names, no `<>:"/\|?*`, no reserved Windows names, no duplicates in the same folder; case-only renames work).
- File operations that touch the open map save it first and then re-point the app to the new location (deleting the open map switches to an empty new map).
- **Saving a new map** and **importing a dd2vtt** use a custom in-app **location dialog** (no system file browser): it shows only the folder tree of the library, allows creating folders, and asks for the map name (import pre-fills the dd2vtt name). The system file browser is only used to pick the external dd2vtt / image source files.
- Leaving an unsaved new map (New/open another map) asks Save / Discard / Cancel.

## 3.16 DM Controls Overlay & Visual Design

- All non-library DM controls live in a **compact overlay panel on the right** side of the DM view, organised in **collapsible sections** (collapsed state is remembered): Tools, Fog of war, Lighting, Effects, Map building, Player view. The whole panel can be collapsed to its header and scrolls if the window is small.
- **Dark, modern theme** (app-wide stylesheet `dmmt/ui/dark.css`): flat controls, rounded corners, subtle hover states, dark tooltips/menus/dialogs, one accent colour (amber).
- **Icon buttons** (Material Design Icons via Ikonli) instead of text wherever an icon is clear; **every button has a tooltip** describing what it does (and its shortcut if any).
- **Active tool visibility:**
  - Selected tool/state toggles are filled with the accent colour and glow.
  - A floating **tool chip** at the top of the canvas names the active tool (and "Esc to exit").
  - The **mouse cursor changes per action**: eraser for revealing fog / erasing walls, brush for painting fog, pen for freehand effects, pencil for walls, crosshair for area/shape tools, target for ping, open/closed hand for grabbing and panning, resize arrow on layer handles.
- Time of day is a 4-button segmented control (Day/Dawn/Dusk/Night icons). Effect "Players see", Snap layers, Fog on/off, Freeze and Player window are icon toggles.
- Map building section also offers **Add image** (file picker) in addition to drag & drop.
- Implementation: `dmmt.ui` package - `Icons` (icon buttons, tooltips, cached icon cursors), `CollapsibleSection`, `Dialogs` (dark text/confirm/save-changes/error dialogs), `MapLocationDialog`, `MapBrowser`; disk operations in `dmmt.service.MapLibraryService` (unit-tested). `assets`/`imports` folders next to loose `.dmmap` files are hidden from the tree.

## 4) Proposed `.dmmap` Structure (v1 Draft)

```json
{
  "schemaVersion": 1,
  "map": {
    "sourceType": "dd2vtt",
    "sourcePath": "imports/catacombs/catacombs.dd2vtt",
    "imagePath": "imports/catacombs/catacombs.webp",
    "grid": { "pixelsPerCell": 140, "cellSizeFeet": 5 },
    "rotationQuarterTurns": 0
  },
  "imageLayers": [
    {
      "id": "layer-1",
      "path": "assets/custom/floor-01.webp",
      "x": 0,
      "y": 0,
      "scaleX": 1.0,
      "scaleY": 1.0,
      "rotationDeg": 0.0,
      "zIndex": 0
    }
  ],
  "walls": [ /* normalized wall segments/polylines */ ],
  "interactables": [
    {
      "id": "door-1",
      "type": "door",
      "x1": 320,
      "y1": 220,
      "x2": 370,
      "y2": 220,
      "state": "closed",
      "blocksSightWhenClosed": true
    }
  ],
  "views": {
    "dmCamera": { "x": 0, "y": 0, "zoom": 1.0 },
    "playerCamera": { "x": 0, "y": 0, "zoom": 1.0 },
    "playerFrozen": false
  },
  "fog": {
    "enabled": true,
    "mask": {
      "originX": 0, "originY": 0, "cellSize": 15, "cols": 500, "rows": 500,
      "revealed": "<base64 of deflate-compressed BitSet bytes>"
    }
  },
  "lighting": {
    "timeOfDayPreset": "DUSK",
    "lights": [
      {
        "id": "light-1",
        "x": 100,
        "y": 200,
        "range": 450,
        "color": "#FFB35C",
        "intensity": 1.0,
        "castsShadows": true,
        "revealMode": "PERSISTENT",
        "flicker": { "enabled": true, "strength": 0.22, "speed": 1.4 }
      }
    ]
  },
  "pings": {
    "style": "default",
    "persistHistory": false
  },
  "overlays": [
    {
      "id": "ov-1",
      "type": "circle",
      "x": 100,
      "y": 100,
      "radius": 80,
      "color": "#55AA33",
      "alpha": 0.4
    }
  ]
}
```

## 5) Performance Targets (Mid-range Laptop)

- 60 FPS target when idle/panning on typical map sizes.
- Keep heavy recomputation incremental:
  - cached fog mask textures
  - cached LOS polygons per light unless moved/changed
  - dirty-rectangle redraw strategy where practical (decided: full-canvas redraw plus idle throttling instead of dirty rectangles, since lighting/fog are composited per frame)
- Non-blocking file IO for import/save to keep UI responsive. Implemented: import, open, save and map switching run on a background thread (`runInBackground`); saves write a deep-copied snapshot.

## 6) Architecture Outline

- `core-model`: map state, entities, serialization DTOs.
- `import-dd2vtt`: parser + normalization into internal model.
- `render-engine`: layers (base map, fog, lights, overlays, UI guides).
- `editor-tools`: brush/rect reveal, shape tools, light tools.
- `player-output`: second window lifecycle, monitor placement, fullscreen.
- `persistence`: `.dmmap` read/write, migration handlers.
- `history`: command stack for undo/redo transactions.

## 7) Implementation Roadmap

## Phase 1 - Skeleton + Import + Basic Viewing

1. Maven project setup (JavaFX app bootstrap).
2. dd2vtt import flow (including lights + doors/windows metadata).
3. Custom-map image drag-and-drop + resize baseline.
4. DM canvas with zoom/pan.
5. Whole-map 90-degree rotation menu + persistence baseline.
6. Basic map switcher.
7. Undo/redo command-stack baseline (`Ctrl+Z`/`Ctrl+Y`) wired for map transforms and door/window state changes.

## Phase 2 - Player Window + Camera Linking

1. Second-window management and monitor targeting.
2. Borderless fullscreen mode.
3. DM-visible draggable player viewport rectangle.
4. Freeze/unfreeze player output.

## Phase 3 - Fog of War System

1. Fog mask model + persistence.
2. Brush and rectangle reveal tools.
3. DM-vs-player fog rendering differences.
4. Toggle fog without reset.

## Phase 4 - Lighting + LOS

1. Light entities and manipulation.
2. LOS occlusion from walls + door/window state.
3. Flicker behavior.
4. Time-of-day ambient presets.
5. Interactive open/close controls for doors/windows.
6. Undo/redo integration for add/remove/move light operations.

## Phase 5 - Overlays + Save Polish

1. Circle/rect/brush overlays with color + alpha.
2. Ctrl+S project save/load polish.
3. Migration-safe schema versioning.
4. Manual wall editing for custom-image maps.
5. Ping tool (animated attention marker).
6. Undo/redo integration for fog brush/rectangle actions.
7. Performance optimization pass.

## Phase 6 - Modern DM UI + Map Library

1. Map library service (scan tree, create folder, move, rename, copy, delete, name validation) with unit tests.
2. Left map browser sidebar (tree, drag & drop, context menus, confirmations).
3. Custom save/import location dialog.
4. Right DM controls overlay with collapsible sections.
5. Dark theme stylesheet, icon buttons + tooltips, tool chip and per-tool cursors.

## 8) Open Decisions (Track Here)

- ~~Exact tile-to-inch calibration UX.~~ Decided: manual screen-diagonal entry + test square (v1.1).
- ~~Whether to store fog as stroke history, bitmap mask, or hybrid representation.~~ Decided: bitmap mask (v0.8).
- Minimum supported GPU/OpenGL profile for JavaFX on older laptops.

## 9) Change Log

- **v0.1:** Initial requirement mapping + selected stack locked.
- **v0.2:** Added custom map builder requirements (drag/drop images, resizing, shared `.dmmap` model parity, manual walls).
- **v0.3:** Added whole-map 90-degree rotation via menu with save/restore in `.dmmap`.
- **v0.4:** Added ping tool, full dd2vtt lights/doors/windows support, and DM door/window interaction with persisted open/closed state.
- **v0.5:** Import now requires copying dd2vtt/source assets into managed project storage so external source media is not needed after import.
- **v0.6:** Added Lombok as implementation dependency and defined testing strategy (unit/integration/regression/manual acceptance).
- **v0.7:** Added command-history undo/redo requirements (`Ctrl+Z`/`Ctrl+Y`) for map transforms, lights, door/window state, and fog-of-war edits.
- **v1.7 (current):** Implemented 3.15 and 3.16 (Phase 6 complete).
- **v1.6:** Added map browser sidebar (3.15), modern DM controls overlay and visual design (3.16), Phase 6. Map switcher dropdown and system file dialogs for open/save replaced by the library.
- **v1.5:** Async file IO; removed per-layer rotation and hand-drawn doors from scope; dirty-rect redraw replaced by idle throttling.
- **v1.4:** Full-plan audit. Fixed: player window now uses full monitor bounds (covers taskbar), light Brightness menu (intensity), layer Snap toggle, spec corrections (Java 17, freeze not saved). 
- **v1.3:** Wall editing tools (draw/erase segments; light LOS updates automatically via geometry signature).
- **v1.2:** Render loop throttles to ~10 fps when idle (no enabled lights, no input for 1 s).
- **v1.1:** Player zoom is derived automatically so a tile measures N inches (default 1): PPI = screen diagonal in DIP / entered diagonal (stored per screen); "1 in test square" toggle for verification. Exact calibration UX resolved: manual diagonal entry.
- **v1.0:** Map switcher dropdown; freeze now snapshots the whole project for the player view (separate renderer/lighting engine).
- **v0.9:** AOE effect shapes (circle/box/freehand) with color, opacity, player visibility, selection/move/delete and undo.
- **v0.8:** Fog moved to a persisted grid bitmask with Select/Reveal/Hide/area tools and brush size; dynamic lighting with wall/door LOS, flicker, colors, per-light fog reveal modes via right-click menu, and 4 time-of-day presets.

## 10) Testing Strategy

- **Unit tests (JUnit 5):**
  - dd2vtt parsing/normalization (walls, lights, doors/windows).
  - `.dmmap` serialization/deserialization and schema-version migration.
  - Geometry/LOS calculations, door/window occlusion state changes.
  - Fog mask operations (brush/rect reveal consistency).
  - command-history action inversion tests (undo/redo for move/rotate/light/door/fog operations).
- **Integration tests:**
  - Import dd2vtt -> save `.dmmap` -> reload -> verify state parity.
  - External-source removal scenario (import from removable path, then load from copied project assets only).
  - Whole-map rotation (0/90/180/270) preserving interactables, lights, fog, overlays.
  - Multi-step undo/redo sequences preserving deterministic final state.
- **Rendering/regression checks:**
  - Snapshot-style checks for key layer combinations (fog on/off, day/night, open/closed doors).
  - Performance smoke checks on representative map sizes (target stable interaction at 60 FPS class hardware).
- **Manual acceptance checklist per release:**
  - Two-window DM/player flow including freeze/unfreeze.
  - Ping visibility and timing behavior.
  - Custom map drag/drop + resize + save/reload fidelity.
  - Ctrl+S persistence across all active tool states.

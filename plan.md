# Dungeon Master Map Tool - Living Specification

## 1) Chosen Technology Stack

- **Language/runtime:** Java 25+ (compiled with release 25, JavaFX 25; shaded jar sets `Enable-Native-Access: ALL-UNNAMED`)
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
3. Supports optional borderless fullscreen **player view** on a second display (owned by the DM window, so it has no taskbar button of its own).
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
- [x] Full-resolution rendering of large map images (> 4096 px) via cached tile pyramid (see 5)
- [x] Performance pass (idle throttle: ~10 fps when no active lights and no input for 1s) (render throttling)
- [x] Configurable frame rates (Performance sidebar section + `render.targetFps` 60 / `render.animationFps` 30 / `render.idleFps` 10 in the settings file); the light map is cached and only rasterised when its inputs change; its divisor (`lighting.lightMapScale`) is raised automatically on large canvases so a light map never exceeds `lighting.lightMapMaxPixels` (default 20000, e.g. 1080p uses about 1/11 and a 4K screen about 1/21 instead of 1/4); when it is rasterised the rows are split into bands filled in parallel (lights keep their order per band, so the result is identical; no per-pixel `sqrt` inside the full-brightness core) and the final ARGB pass also runs in parallel; light flicker time advances at the animation fps only; lights whose range does not touch the screen are skipped; effect shapes completely outside the canvas are not drawn; soft textures (fire, smoke, ...) are composited on the CPU (`dmmt.render.SoftEffectRenderer`): a soft-edge alpha mask per shape is computed once in world space (cached until the shape or feather settings change, the continuous equivalent of the `texture.featherPasses` inset passes), and every frame the scrolling texture layers are sampled, multiplied by the mask in parallel and drawn with one image draw per effect, at no more than the texture's own resolution and `effects.softResolution` (default 0.5) of the screen; the image is kept and redrawn as-is until the animation clock (stepped at `render.animationFps`), the view or the shape changes, so frames above the animation rate cost almost nothing. This replaces the 2 layers × feather passes stroked pattern fills, which JavaFX rasterised on its render thread and which capped busy maps at ~17 fps; chasm and hard-edged textures keep the vector path
- [x] Separate base canvas (background, grid, map images) below the animated canvas in DM and player view; only redrawn on camera/layer/size changes or when image tiles finish loading
- [x] Performance metrics readout in the status bar next to the performance toggle: actual/limit fps, average and worst render time per frame, and heap in use/allocated (MB, e.g. `120/512 MB`), refreshed about once a second; the two slowest render parts (`dm effects`, `dm lighting`, `dm fog`, `player ...`, `light engine`, `base redraw`, ...) are appended and the tooltip lists every part (`dmmt.render.FrameProfiler`)
- [x] Performance mode toggle (bottom-left of the status bar, default off, persisted as `ui.performanceMode` in the settings): a temporary in-memory override (`dmmt.render.PerformanceMode`) that never modifies project data or other settings and reverts fully when switched off. While on (all values are settings, see 3.25): the light map is rasterised at 1/8 instead of 1/4 resolution (`performance.lightMapScale`), effect textures still animate but only at 10 fps (`performance.animationFps`) and light/emitter flicker is switched off, the render loop runs at most at 5 fps when nothing happens (`performance.idleFps`, or the idle FPS if lower) and at 20 fps while the user interacts (`performance.interactionFps`), map images use tiles two pyramid levels coarser (`performance.imageLevelBias`), and soft texture edges use at most 6 feather passes (`performance.maxFeatherPasses`). The fog image is drawn at the lowest fog sharpness (`fog.cellsPerGrid.min` cells per tile) by merging mask cells, without touching the saved fog mask or the fog sharpness setting; fog fading is off unless `performance.fogFade = true`. Visibility polygons are unchanged because they drive gameplay (reveal while lit)
- [x] One-click room reveal bounded by walls/doors/windows (3.17)
- [x] Handout window: paste a clipboard image, toggle showing it on the player screen, rotate in 90-degree steps (3.18)
- [x] Laser pointer on middle mouse button hold (3.19)
- [x] Map browser thumbnails + search bar for maps and folders (3.20)
- [x] Auto-save for maps that already exist on disk (3.21)
- [x] Light presets (candle, torch, lantern, ...) placeable with one click (3.22)
- [x] Right-click cancels the active tool like Esc (3.23)
- [x] Soft fog edges and fog fade animation (3.5)
- [x] Batch import of dd2vtt maps: multi-file selection and whole-folder import (3.26)
- [x] Handout upgrades: multiple pasted images shown together in an auto-fitted grid, per-image delete (3.27)
- [x] Copy/paste of lights, effect shapes and text boxes (Ctrl+C / Ctrl+V), also across maps (3.28)
- [x] Subtle ambient weather per map: rain, snow, mist, dust motes, embers (3.29)
- [x] Multilevel maps: several levels (e.g. building floors from Dungeon Alchemist) shown as one map with a level switcher (3.32)

## 4) Core Functional Requirements

## 3.1 Import and Map Model

- Import dd2vtt map files.
- Create custom maps by drag-and-drop image import (png/jpg/webp) into a map canvas.
- Allow image layer transform editing (move, resize/scale) directly with drag handles. Per-layer rotation is intentionally not supported; use whole-map rotation.
- With the Select tool, a selected image layer can be removed by pressing `Delete` (or `Backspace`); removal is undoable.
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
- Import must **copy all required assets** (the map image) from the dd2vtt into app-managed project storage so the original file location (e.g., USB drive) is no longer required afterward. The `.dd2vtt` file itself is **not** stored in the project; everything needed (walls, lights, portals, grid, image) is converted into the internal model and `.dmmap`, and nothing reads the dd2vtt again.

## 3.2 DM View

- Zoom in/out around cursor focus.
- **Alt + mouse wheel over the map canvas:** adjust the shared brush size by 0.1 grid tiles per notch for Reveal brush, Fog brush, Draw and Line, clamped to `brush.minTiles` / `brush.maxTiles`. Both sidebar sliders and the cursor preview update immediately without moving either camera, editing content or adding undo history. Ordinary wheel still zooms the DM view; Ctrl + wheel takes precedence even with Alt held. Unsupported tools consume Alt + wheel without zooming (Circle and Box stay drag-sized; Pen stays fixed-width). Ignore contextual changes during a fog stroke or effect draw. Sidebars, dialogs and text editors are excluded. A mouse-transparent, canvas-bounded DM-only `Brush: 2.5 tiles` label follows the cursor while adjusting and for 0.9 seconds afterwards; it disappears on canvas exit, drawing or tool change.
- Pan/camera movement via drag and/or keybind.
- Render overlays (fog, lights, shapes) with editing controls.
- Show player viewport rectangle when player screen is active. It looks like an application window: fully transparent interior with a cyan border and a thicker "Player view" title bar above the top edge. Only the title bar can be grabbed to drag the viewport (Select tool); it takes click priority over everything beneath it. The bar also shows the current player zoom (e.g. "125%", right-aligned) and, when the box is wide enough, a **Reset** button that sets the player zoom back to normal (100%, step 0); the button is dimmed while the zoom is already normal.
- Provide a map-rotation menu action to rotate the entire map in **90-degree steps** (0/90/180/270). An image layer's x/y/width/height is its on-screen footprint (swapped at 90/270); the renderer and thumbnails draw the unrotated image with swapped sides at 90/270 so non-square maps are not stretched.
- Layout: **map browser sidebar** on the left (see 3.15), map canvas in the center, compact **DM controls overlay** on the right (see 3.16), slim status bar at the bottom.
- Remember the last dd2vtt import directory and reopen that location for the next import (source file) dialog.

## 3.3 Player View (Second Screen)

- Optional separate window on selected monitor.
- DM can choose the exact target monitor for player view from an in-app screen selector (supports 2+ monitor setups reliably).
- Borderless fullscreen mode.
- **Show grid** icon-only toggle on the second row of the Player view DM controls section, with a grid-opacity slider and percentage beside it: draws the map-aligned grid over the player map images and below lighting/effects/fog. Off by default; stored globally as `player.showGrid` in the settings file, not in projects. Applies immediately, including while frozen, and leaves DM grid visibility unchanged. The opacity slider edits the existing global `ui.gridOpacity` setting (0-100%, default 8%), shared with the DM background grid. It cannot remove grid lines baked into a map image.
- Adjustable world-to-screen scale so each tile is approximately **1 inch** physical size.
- Player window mirrors a movable camera rectangle controlled from DM view.
- **Player zoom slider** (Player view section): per-map, saved in `.dmmap` (`views.playerZoomStep`). 0 = calibrated tile size; the zoom factor is `2^step` (range -2..+2, i.e. 25%-400%). Negative zooms out, positive zooms in. The DM-view player viewport box always reflects the resulting zoom. Double-click resets to 0. **Ctrl + mouse wheel** over the DM view changes the player zoom (same 10% per notch as the plain wheel does for the DM view zoom). If the cursor is inside the player viewport box, the zoom is anchored on the cursor (the box moves so the point under the cursor keeps its place within the box); when zooming in, the box centre is additionally pulled towards the cursor so the target ends up centred after a few notches.

- Freeze mode: player view remains fixed while DM prepares another map/view.- While frozen, DM can still move the player viewport rectangle; on unfreeze, player view immediately jumps to that staged rectangle.- Player view must stay visually clean: DM-only helper/debug geometry (wall guides, door/window state lines, viewport handles) is never rendered there.
- **Wall layer (DM only):** all walls (imported dd2vtt walls and manually drawn walls) are drawn as red lines. A **wall layer toggle** next to the wall tools in the Map building section shows/hides the wall lines together with the door/window lines and icon badges (shown by default at startup, session-only). While hidden, doors/windows cannot be clicked; choosing a wall tool shows the layer again. Lights and light tokens are not part of the wall layer and are always shown.

## 3.4 Map Switching

- Fast map/project switcher UI.
- If player view is frozen, switching/opening in DM view must not affect player output until unfreeze/swap action.
- Maps are switched from the **map browser** (3.15): double-clicking a map auto-saves the current map and opens the chosen one. A dimmed overlay with a spinner and "Loading <map>..." (or "Importing <file>..." for a single dd2vtt import, which opens the map afterwards; batch imports show none) covers the DM canvas while the switch runs (the spinner itself has no opaque box behind it: the dark theme rule for number spinners is scoped away from the progress indicator), so slow loads of large maps give visible feedback.
- **Freeze** snapshots the *entire* project for the player window (map, fog, lights, effects, camera) using a separate renderer/lighting engine, so players see the old map exactly as it was regardless of DM edits or map switches. Unfreezing makes the player view jump to the currently open map and its staged viewport.
- Frozen state is session-only (not saved in `.dmmap`).

## 3.5 Fog of War

- Fog toggle on/off without losing reveal state.
- Reveal/hide via:
  - brush (size configurable, default range 0.2-8 tiles, circular cursor preview; Alt + wheel adjusts by 0.1 tiles)
  - rectangular area drag tool (dashed preview while dragging)
  - Reveal All / Hide All
- Fog tools only act while fog is enabled; the **Select** tool (default, `Esc`) is used for dragging lights, layers and the player viewport.
- Fog is rendered on its own transparent canvas above the map (never erase map pixels).
- DM fog rendering: semi-transparent (DM sees obscured content faintly).
- Player fog rendering: fully opaque except revealed/light-visible regions.
- Storage: world-space grid bitmask, cell size = grid cell / N (clamped 2-50 px); grows to cover map content; rotates with the map.
- **Soft fog edges:** revealed cells fade from opaque at the real edge of the revealed area to clear over a configurable width (*Fog softness* slider in the Fog of war section, 0-100 % of a tile, default 30 %, `fog.softness` in the settings file; 0 = the previous hard edge). The gradient lies entirely **inside** the revealed area: fogged cells always stay 100 % opaque, so a soft edge never reveals anything beyond walls or the real reveal border (players cannot peek past walls). The ramp is computed from the distance to the nearest fogged cell (`dmmt.render.FogShading`), which also smooths the cell staircase along diagonal line-of-sight edges. Purely a rendering effect: the saved fog mask, reveal logic and line of sight are unchanged, the interior stays fully clear, and a fully revealed map has no dark border. Performance mode keeps hard edges. The fog image is updated incrementally: only the region around cells that changed since the previous frame (plus the soft-edge reach) is recomputed, faded and uploaded, so dragging a reveal-while-lit light stays fast on large maps.
- **Fog fade animation:** when fog changes (brush, rectangle, room reveal, reveal/hide all, undo/redo, moving lights that reveal) the affected cells fade in/out over 0.5 s instead of switching instantly. Toggle button in the Fog of war section (`fog.fadeAnimation`, default on). Switching maps, rotating, resampling and loading snap instantly; performance mode disables the fade. While a fade runs the render loop uses the target frame rate, otherwise the normal idle throttling applies. Fog changes caused by a light (live or persistent reveal, e.g. while dragging a light) fade much faster (0.12 s) so the lit area keeps up with the light. The DM and player views fade independently but identically.
- **Fog sharpness** slider (Fog of war section): N = fog cells per tile (5-30, default 10). Stored globally in the settings file (3.25) (applies to all projects); existing masks are resampled (nearest neighbour) to the new cell size when a project loads or the slider is released, and persistent light reveals are re-applied at the new resolution.

## 3.6 Dynamic Lighting

- Add/remove/move one or more light sources in DM view.
- **Add light** and **Remove light** are one-shot tools: arm the tool, then click the map to place a light at that spot, or click a specific light to remove it. After one successful click the tool returns to Select, so the next click doesn't place or remove another light. A Remove click that misses every light does nothing and the tool stays armed. Esc cancels.
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
- Imported dd2vtt lights default to the **Torch** flicker preset (dd2vtt carries no flicker data). Depth and speed come from `import.dd2vtt.lightFlicker` / `import.dd2vtt.lightFlickerSpeed`; a depth of `0` imports the lights with flicker switched off.
- Batch import merges level-like files into multilevel maps only when `import.autoMergeMultiLevel` is true (default); when false every file becomes its own ordinary map.
- Each light can be switched on/off from its menu (`Light on`); an off light casts no light and reveals nothing (its token is drawn hollow). Undoable.
- Persistent reveals only accumulate while fog is enabled.
- Light moves, light setting changes and door toggles are undoable, including the fog they revealed.
- Rendering: quarter-resolution light map (cached LOS polygons per light, recomputed only on move/geometry change), skipped entirely at Day.
- **Light tint** slider (Lighting section): strength of the light colour tint over lit areas (0-30%, default 8%). Stored globally in the settings file (3.25) (applies to all lights and projects); updates live while dragging.

## 3.7 Time-of-Day Lighting

- 4 presets: Day, Dawn, Dusk, Night (selector in the DM overlay, undoable).
- Preset affects ambient darkness + tint globally; Day = no darkness (dd2vtt maps have baked lighting).
- DM view shows darkness at reduced strength so the DM can still read the map.
- **Ambient brightness** slider (Lighting section, -50% to +100%, default 0%): adjusts the ambient darkness of the *current* preset for *this map* (effective darkness = preset darkness x (1 - brightness)). Stored per preset in the project (`lighting.ambientBrightness`, keyed by preset name, 0 entries omitted), so e.g. Night can be brightened on one map only. Updates live while dragging; one undo step per release; double-click resets. Disabled at Day (no darkness).

## 3.8 Tactical/AOE Overlays

- Draw circles/rectangles/free brush overlays (tools **Circle**, **Box**, **Draw** (brush icon), **Pen** in the *Effects* section of the DM controls).
  - Circle: drag from center outward. Box: drag corner to corner. Draw: freehand, thickness = brush size slider; a dashed brush-size outline follows the cursor (like the fog brush).
  - **Pen**: freehand thin line in the selected color (and opacity); ignores the brush size, has no texture, border or light (those controls are disabled while the Pen tool is active or a pen line is selected). Saved as overlay type pen (points, fixed strokeWidth).
  - While dragging, a live size label is shown at the shape's center in the DM view only, in grid tiles (circle: radius, e.g. `2.5`; box: `width x height`, e.g. `3 x 2`). One tile equals the configured tile size on the player screen (1 tile = 1 inch by default), so radius `1` is a 1-inch radius.
  - **Line**: drag to draw a perfectly straight line (two points) using the brush size as thickness and the selected color/opacity; no texture, border or light (controls disabled like Pen). Saved as overlay type `line` (`points` = start/end, `strokeWidth`). Resizing keeps the line width. The dashed brush-size outline follows the cursor.
- Adjustable color (color picker) and opacity (0.1-1.0); the current style applies to new shapes, and to the selected shape if there is one.
- Visible in both DM and player views; **Players see** unchecked hides a shape from the player view (DM sees it dashed and strongly dimmed (35%) with a crossed-out eye badge at its center). With the Select tool, **right-clicking any effect shape** (circle, box, freehand, pen, line) opens a menu with a *Visible to players* toggle (undoable, same as the Players see toggle).
- Shapes are drawn above map and lighting but below fog, so fog still conceals them.
- With the Select tool: click a shape to select and drag to move; a selected shape shows a resize handle at the bottom-right corner of its bounds - drag it to rescale/stretch the shape (box: width/height, freehand: points and brush thickness are scaled together so a blob stays a blob (thickness follows the average of the two axis factors); pen: points stretched, line width unchanged, circle: radius, uniform); undoable (Resize effect); `Delete` or the Delete button removes it; Clear All removes every shape.
- All create/move/style/delete/clear actions are undoable; shapes rotate with the map and persist in the save file (`overlays`: `type`, `x/y`, `radius`, `width/height`, `points`, `strokeWidth`, `color`, `alpha`, `playerVisible`, `texture`).
- **Animated textures:** a *Texture* dropdown in the Effects section sets the texture of new shapes and of the selected shape (undoable). Textures: Flat color, Smoke, Fire, Water, Lava, Acid/slime, Ice/frost, Lightning, Arcane runes, Darkness/void, Mist/fog, Blood, Spider web, Holy light, Grease/oil, Sand/dust storm, Wind gusts, Radiation/aura, Poison/toxic gas, Swamp/mud/bog, Rubble/debris, Thorns/brambles, Force/shield (hex grid), Necrotic/shadow rot, Entropy/void/portal (small spiral vortices), Chasm/broken earth (the shape is a hole: broken earth plates split by black cracks form a rim along the shape edge (about 1.6x `texture.featherCells` wide) that breaks up into a solid black void towards the middle; for freehand strokes the void follows the merged final outline of the stroke, not the brush path; default brown, 100% opacity, static). Blood is dense (little empty space between pools). They are procedurally generated, seamlessly tiling tiles drawn as scrolling and/or pulsing layers in both DM and player views, for circle, box and freehand shapes alike. Every texture is colourised with the effect color; picking a texture loads its default color (smoke grey, fire red, water blue, ...) and default opacity (denser textures such as lava, darkness, blood and web start at 100%), both of which can then be changed freely (e.g. orange water = lava-like, green = acid). Blood and Spider web are static; the spider web is a connected net of irregular little webs (uneven spokes and rings with missing threads) that continues across tile borders without gaps. Soft textures (smoke, fire, mist, darkness, holy light, sand, wind, lightning, radiation, poison, necrotic, portal) fade out towards the shape edge (feathered over up to 0.9 grid cells) instead of ending in a hard cut; solid textures (water, lava, blood, web, swamp, rubble, thorns, force, ...) keep a crisp edge; each texture's edge is switchable in the settings file via `texture.<kind>.softEdges` (true/false). The opacity slider (10-100%) is stored per shape and applies directly to textured shapes; *Players see* still applies. A per-shape **border** toggle (next to the texture dropdown, default off, saved as `border`) outlines a textured shape in its effect color (freehand strokes get one outline around their final merged area); flat shapes always keep their edge line. Only textures that actually move (`OverlayTextures.isMoving`) keep the render loop at full frame rate (wakes the idle throttle). Missing/unknown `texture` = flat color (older saves). Generated tiles are cached per texture+colour (LRU, 128 entries) so maps with many differently coloured textures never regenerate tiles while rendering. Implementation: `dmmt.render.OverlayTextures` (table of definitions, unit-tested).

- **Animations toggle** (play icon next to the texture/border toggles): global for all maps (persisted as `ui.effectAnimations`, default on). When off, textures render as a static frame in DM and player view and the render loop no longer wakes for them, which improves performance on weak machines.
- **Light emission:** effects that do not emit light are dimmed by darkness like the map (drawn below the lighting pass, lit only by light sources and other emitters); emitting effects are drawn above it at full brightness. Effects can emit light (bulb toggle next to the border toggle, saved per shape as `emitsLight`). Emitting shapes add a glow to the lighting map (only visible when the map is dark, e.g. night) in the effect's colour: full strength inside the shape, fading out over `lightRange` grid cells beyond its edge. Picking a texture sets the toggle to that texture's default (OverlayTextures.defaultEmitsLight). The bulb toggle is available for every effect shape (any texture, including flat color) regardless of that default, so light can be added to (or removed from) a shape later; it is only disabled for pen and line strokes. Flat color uses the built-in light strength 0.5, range 2 cells and no flicker. Defaults (settings): `texture.<kind>.emitsLight` (on for fire, lava, lightning, arcane, holy, radiation, portal, force), `.lightStrength` (0-1) and shape edge, in grid cells. Flicker: `texture.<kind>.lightFlicker` (depth 0-1; fire 0.3, lightning 0.6, lava 0.12, arcane/portal/radiation/force 0.1, holy 0.05, others steady) and `.lightFlickerSpeed`; it follows the map's light flicker toggle (not the animations toggle) and dims/shrinks the glow like light-source flicker. Shape light ignores walls (no shadows). Older saves default to off.
## 3.9 Map Ping Tool

- DM can place quick pings on the map to direct player attention.
- Ping appears immediately in DM and player views at world position.
- Ping should be visually obvious (short animation + fade out).
- Support at least one default ping style initially; extensible for variants later.

## 3.10 Doors, Windows, and dd2vtt Interactables

- Imported doors and windows must be represented as interactable map objects.
- DM can open/close doors and windows directly from DM view.
  - Every door/window shows a round **icon badge** (door/window glyph, open/closed variant, red/green resp. blue state colour; configurable via `ui.doorOpenColor`, `ui.doorClosedColor`, `ui.windowOpenColor`, `ui.windowClosedColor`) at the middle of its line in the DM view only, drawn above the fog so it is always visible.
  - In Select mode a **single click** on the badge (or within a few pixels of the door line) toggles it; a human double-click toggles twice (quick open/close); only presses on the same door within 60 ms (mechanical switch bounce) are ignored. Badge hits take priority over lights, lights over door lines.
  - Hovering a door highlights its badge and line.
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
  - time-of-day preset (and per-preset ambient brightness)
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
- **Lock image layer** toggle (lock icon, Map building section): while locked, image layers cannot be selected, moved, resized or deleted from the canvas; lights, doors/windows, effects, the player viewport and all other tools keep working normally. Default: **locked for imported dd2vtt maps**, **unlocked for new custom maps**. The state is saved per map in `.dmmap` (`map.imageLayersLocked`; missing in older saves = locked if the map came from a dd2vtt import). Adding an image (drag & drop / Add image) unlocks the layer so the new image can be positioned.
  - While the image layer is **unlocked**, a banner at the top of the DM canvas (below the tool chip) says so and offers a **Lock** button to lock it directly.- Persist image layer stack order and transforms in `.dmmap`.
- **Multi-selection (Select tool):** lights, text boxes, effects and (unlocked) image layers can be selected as a group and moved or deleted together.
  - Left-drag on empty space (nothing selectable under the press, and not on a resize handle) draws a selection rectangle. Lights count when their center is inside, text boxes/effects when the rectangle touches them, image layers only when fully enclosed. Anything selectable under the press (including an unlocked image layer) takes priority and is selected/dragged normally instead; with image layers locked, a press over an image still starts the rectangle. While dragging, the rectangle turns green and each item that would be selected gets a solid highlight plus a count; it stays cyan while nothing is inside.
  - `Ctrl+click` adds an item to the group (the current single selection joins it) or removes it if already in the group. `Ctrl+click` on empty space does nothing.
  - Dragging any member moves the whole group as one undoable action; `Delete`/`Backspace` deletes the whole group as one undoable action. A plain click outside the group, `Esc`, or switching tool clears it. Doors/windows are not part of groups.
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
- **App icon:** `src/main/resources/dmmt/icon.png` is set (pre-scaled 16–256 px) on the DM window and the player window, so it shows in the title bar and taskbar.
- **Icon buttons** (Material Design Icons via Ikonli) instead of text wherever an icon is clear; **every button has a tooltip** describing what it does (and its shortcut if any).
- **Active tool visibility:**
  - Selected tool/state toggles are filled with the accent colour and glow.
  - A floating **tool chip** at the top of the canvas names the active tool (and "Esc or right-click to exit").
  - The **mouse cursor changes per action**: eraser for revealing fog / erasing walls, brush for painting fog, pen for freehand effects, pencil for walls, crosshair for area/shape tools, target for ping, pointing hand (link cursor) when hovering interactable objects (lights, door/window badges and lines), open/closed hand for grabbing layers/effects and panning, resize arrow on layer handles.
- Time of day is a 4-button segmented control (Day/Dawn/Dusk/Night icons). Effect "Players see", Snap layers, Fog on/off, Freeze and Player window are icon toggles.
- Map building section also offers **Add image** (file picker) in addition to drag & drop.
- Implementation: `dmmt.ui` package - `Icons` (icon buttons, tooltips, cached icon cursors), `CollapsibleSection`, `Dialogs` (dark text/confirm/save-changes/error dialogs), `MapLocationDialog`, `MapBrowser`; disk operations in `dmmt.service.MapLibraryService` (unit-tested). `assets`/`imports` folders next to loose `.dmmap` files are hidden from the tree.

## 3.17 One-Click Room Reveal

- New fog tool **Reveal room** in the **Fog of war section** of the DM controls (icon toggle button next to the other fog tools, tooltip, own cursor, named in the tool chip). Only acts while fog is enabled, like the other fog tools.
- **Room reveal only happens while this tool is explicitly selected.** Clicks with Select or any other tool never reveal a room, and there is no shortcut or modifier that reveals a room without selecting the tool first.
- **Click** inside a room: every fog cell of that room becomes revealed. **Shift+click** hides the room instead.
- A room is found by a **flood fill on the fog grid** starting at the clicked cell. Boundaries are:
  - all walls (imported dd2vtt walls and manually drawn walls),
  - all doors and windows **whatever their open/closed state** (a room ends at its door, so one click never reveals the next room through an open door).
- Boundary segments are rasterised into the fog grid as blocking cells (conservative line rasterisation, 4-connected fill) so tiny gaps between wall segments do not let the fill leak.
- The fill is limited to the map content bounds (same area the fog mask covers), so an area that isn't enclosed never reveals more than the map.
- **Hover preview:** while the tool is armed, the region under the cursor is outlined/tinted in the DM view before clicking, so the DM sees at once if the region is not enclosed. The preview is cached per start cell and recomputed only when the cursor enters a different region or wall/door geometry changes.
- Stays armed after a click (several rooms can be revealed in a row); `Esc` returns to Select.
- Each click is one undo step (uses the existing fog history). No named/saved reveal regions.
- Works identically on rotated maps (fill runs on the rotated fog grid with rotated wall geometry).

## 3.18 Handout Mode

> Superseded in parts by 3.27: the handout holds several images (pasting adds instead of replacing) shown in an auto-fitted grid, and rotation/fit-to-screen apply to the whole arrangement (HandoutWindow.drawBoard, HandoutLayout). The single-image wording below describes the original behaviour.

- Show an image to the players on the player screen (e.g. a portrait of an NPC they meet), pasted from the **clipboard**.
- A **Handout** button in the Player view section of the DM controls opens a separate, non-modal **handout window** for the DM (dark theme, stays on the DM monitor, only one instance; pressing the button again focuses it). Ctrl+V is **not** a global shortcut in the DM view; pasting only happens inside the handout window.
- Handout window contents:
  - A large **preview area** showing the pasted image with its current rotation, and an empty-state hint "Press Ctrl+V to paste an image".
  - **Ctrl+V** inside the window (and a **Paste** button) loads the clipboard content. Accepted: image data (e.g. "Copy image" in a browser, Snipping Tool) and copied image files (png/jpg/webp/gif/bmp from Explorer; the first image file is used). Anything else shows a hint in the window and keeps the current image. Pasting a new image replaces the current one (if it is being shown, players see the new image immediately).
  - **Rotate left / Rotate right** buttons (90-degree steps: 0/90/180/270) so the image faces the player it is meant for when the TV lies flat on the table. Rotation applies live to the preview and, if shown, the player screen. The last rotation is remembered for the session and used for the next handout.
  - A **Show to players** toggle (off by default after paste). On = player screen shows the handout; off = player screen shows the map again. Disabled while there is no image or the player window is not open (tooltip explains why).
- **Closing the handout window** stops showing the handout and discards the image.
- While shown, the player window displays **only the handout**: the image is centred and scaled to fit (keeping aspect ratio, max ~90% of the screen) on a black background; fit-to-screen accounts for the rotation (width/height swap at 90/270). Map, fog, lights, effects, pings and laser pointer are not drawn on the player screen.
- The handout sits on top of whatever the player view is doing: hiding it returns the player screen to the live or frozen view exactly as before. Freeze/unfreeze, map switching and DM editing keep working while a handout is shown.
- If the player window is closed while a handout is shown, the toggle switches off.
- Handouts are session-only: not saved in `.dmmap`, not copied into the map package, not part of undo/redo.
- Implementation: `dmmt.ui.HandoutWindow` (window, clipboard paste, rotation, shared `drawRotated` used by preview and player screen); the player screen draws it in `renderPlayer` before any map rendering.

## 3.19 Laser Pointer

- **Hold the middle mouse button** on the DM canvas to show a laser pointer at the cursor position; release to stop. Works with every tool active and does not change the active tool.
- **Laser pointer tool** (laser icon next to Ping in the Tools row; hotkey `L` toggles it on and off): a normal tool that keeps the laser on permanently, the dot following the cursor over the map until the tool is dismissed with `Esc` or right-click (the global tool chip shows the hint). While it is active no other interaction is possible: clicks, Delete, copy/paste and context menus are ignored (right-drag still pans, the wheel still zooms). Selecting another tool, Ping or `P` ends it. Middle-mouse hold keeps working as described above.
- Rendered as a bright red dot with a short fading trail (~0.5 s) in both DM and player views. The dot size is fixed in screen pixels on the player screen (clearly visible, ~0.3 tile at 1-inch calibration) so it reads well regardless of zoom.
- Drawn above fog (it must be visible on unrevealed areas) and below DM-only UI.
- Only shown on the player screen when the player view shows the currently open map (not while frozen, not while a handout is shown). Always shown in the DM view.
- Not persisted and not undoable. While active it keeps the render loop at full frame rate (wakes the idle throttle).
- Middle mouse is currently unused, so there is no conflict with right-click panning or tools.

## 3.20 Map Browser Thumbnails & Search

- **Thumbnails:**
  - Every map row in the tree shows a small thumbnail (~48x32 px) left of the name; hovering a map shows a larger thumbnail (~256 px) in its tooltip.
  - The thumbnail shows **only the map image layers** at the map's current rotation. It is rendered from the image layers directly, not from the DM/player canvas, so **fog of war is never included** (otherwise a mostly unrevealed map would give a black thumbnail). Time-of-day darkness, lights, walls, doors/windows, effects, pings and the player viewport are not drawn either.
  - Stored as `thumbnail.png` inside the map package, next to the `.dmmap` (hidden from the tree like `assets`/`imports`). It is rewritten on every save (manual and auto-save) and on import. Moving/renaming/copying a package carries it along automatically.
  - Maps without a thumbnail (older saves, loose `.dmmap` files) get one generated lazily on a background thread when they first become visible in the tree; a placeholder icon is shown meanwhile. Loaded thumbnails are cached in memory.
- **Search bar** at the top of the map browser (search icon, placeholder "Search maps and folders", clear button):
  - Case-insensitive substring match on map names **and** folder names, filtered live while typing.
  - A matching **map** is shown together with its parent folders (expanded).
  - A matching **folder** is shown with its full contents.
  - Non-matching entries are hidden; if nothing matches, an "No maps or folders found" hint is shown.
  - `Esc` in the search field or the clear button clears the filter and restores the previous expand/collapse state.
  - All interactions (open, drag & drop, context menus, rename, delete) keep working on the filtered tree. The filter stays applied after refresh/file operations.
- No favourites.
- Implementation: `ThumbnailService` (Java2D render of image layers, written by `ProjectService.save` for packaged maps, lazy generation via `loadOrCreateThumbnail`), `MapTreeFilter` (pure filter, unit-tested), `MapBrowser` (search field, thumbnail cache + background loader).

## 3.21 Auto-Save

- Maps that **already have a file on disk** are saved automatically in the background. New maps that were never saved are **not** auto-saved (they keep the Save / Discard / Cancel prompt).
- Auto-save runs every **2 minutes** when the map has unsaved changes (dirty flag), and additionally when the DM window loses focus / on app close for dirty saved maps. No save happens if nothing changed.
- Uses the existing background save path (deep-copied snapshot, `runInBackground`), so the UI never blocks. An auto-save is postponed while a drag/brush stroke is in progress and skipped if a save is already running.
- Status bar shows "Auto-saved HH:mm"; errors are shown in the status bar (no modal dialog during play) and retried on the next interval.
- Auto-save on/off and the interval (1/2/5/10 minutes) are global settings (settings file, 3.25) (default: on, 2 min), toggled from the map browser next to Save.
- Undo/redo history is unaffected by auto-save.

## 3.22 Light Presets

- The Lighting section gets a row of **preset buttons** (icon + tooltip): each one arms a one-shot place tool like **Add light** (click the map to drop the light, then back to Select; `Esc` cancels). No configuration needed before placing.
- Built-in presets (range in tiles, flicker preset, colour):
  - **Candle** - 2 tiles, Candle flicker, warm yellow
  - **Torch** - 6 tiles, Torch flicker, warm orange (`#FFB35C`, same as today's Add light default)
  - **Campfire** - 8 tiles, Strong torch, orange (the former Lantern preset was removed to keep the row narrow; Campfire takes its place and radius)
  - **Magic light** - 12 tiles, no flicker, cold white/blue
- Placed lights use fog reveal mode **Only while lit** (`WHILE_LIT`, like DM-added lights today), are blocked by walls and are ordinary lights afterwards: movable, editable via right-click, removable, undoable, saved in `.dmmap` (no new save fields).
- The existing **Add light** button stays and places a Torch.
- Ranges are in tiles, so presets scale with the map's grid size.

## 3.23 Right-Click Cancels the Active Tool

- While any tool other than Select is active (fog brush/area/room reveal, Add/Remove light and light presets, effect shapes, walls, ping, ...), a **right-click on the DM canvas does exactly what `Esc` does**: disarm ping, return to the Select tool and cancel an in-progress stroke/shape drag (the partial action is discarded, not added to history).
- "Right-click" = right button pressed and released without moving more than a few pixels. A **right-drag still pans** the camera as today and keeps the active tool.
- With a tool active, a right-click on a light only cancels the tool; it does **not** open the light menu (a second right-click, now in Select, opens it). This keeps "right-click = cancel" predictable.
- While Select is active, right-click behaves as before (light menu on a light, pan otherwise).
- The tool chip shows "Esc or right-click to exit" globally; individual tool tooltips do not repeat it.

## 3.24 Text Boxes

- **Text** tool (*Text* section of the DM controls): click-drag on the map to draw a text box (a plain click creates a default-sized one) and start typing immediately. Click a box with the Text tool, or double-click it with Select, to edit its text. `Esc`, clicking outside the box, or switching tool finishes editing.
- Text wraps automatically to the width of the box; text that does not fit is clipped at the box edge.
- **Auto-size:** boxes created with a plain click start small and grow/shrink (width and height) to fit the text as it is typed or deleted; text wraps once the box reaches a maximum width of 12 grid cells. A drag-created box has a fixed size, manually resizing a box switches auto-size off, and the *Auto-size* toggle in the Text section switches it on or off for the selected box (saved as utoSize).
- Text is centered in the box, each line horizontally and the whole text block vertically (in the editor and in the rendered view).
- Rich text: **font size** and **text color** can be changed before typing (applies to newly typed text) and after typing (applies to the selected text; with a box selected but not being edited, to all of its text). Editing uses an in-place editor scaled with the map zoom, so what is typed is what is drawn.
- Box style: **background color** (neutral light gray `#EEEEEE` by default, with a dark `#1E1E1E` default text colour so new boxes are readable on any map), **border color** (transparent by default), rounded corners. Colors may carry opacity (custom color dialog); "no fill" / "no border" buttons reset to transparent.
- Select tool: click a box to select, drag to move, drag one of the 8 handles to resize, `Delete` removes it. Create/edit/move/resize/style/delete are undoable.
- **Per-box visibility (like effect shapes):** a *Players see* toggle in the Text section and a right-click menu (*Visible to players*) on a text box hide a single box from the player view; the DM still sees it dimmed with a crossed-out eye badge. Undoable, copied with the box, saved as `playerVisible` (default true).
- **Text layer** toggle hides/shows all text boxes in both DM and player views (saved with the map). Choosing the Text tool shows the layer again.
- Text boxes are drawn above map, lighting and effects but **below fog**, so fog hides them from players.
- **Last used settings** (font size, text color, background, border) are stored per map (`lastTextSettings`) and, as fallback, globally in the settings file (3.25). Selecting the Text tool loads the map's settings, or the global ones when the map has none.
- `Ctrl+C` copies the selection, `Ctrl+V` pastes it at the mouse position (or the view center) — also into a different map opened afterwards (see 3.28; text boxes are part of the copied items).
- **Global text rotation (player view only):** two rotate buttons (left/right, 90-degree steps) in the *Text* section turn all text boxes (box and text, around the box center) on the **player view only**, e.g. so they face players at a table; the DM view always shows them upright so they stay readable and editable. It is independent of the map rotation (map rotation only moves box positions, never their size or orientation), applies to every map and is persisted globally as `text.rotation` (0/90/180/270) in the settings file (`dmmt.render.TextBoxGeometry`).
- Text boxes persist in the save file (`textBoxes`, `textLayerVisible`, `lastTextSettings`).
## 3.25 Settings File

- All global preferences (sidebar/section state, player screen, tile inches, fog sharpness, light tint, auto-save, text defaults, last import folder) live in `dmmt-settings.ini` **next to the jar** (working directory when run from an IDE; override with `-Ddmmt.settings=<path>`). Nothing is stored in `java.util.prefs` any more; on first start existing values are imported once from the old preferences.
- Plain `key = value` lines with `#` comments; the file is created with every known setting and its default, kept in sync when the app changes a value (atomic write), and unknown keys are preserved. Invalid values fall back to the defaults.
- **Texture defaults** are configurable: `texture.<kind>.color`, `.opacity`, `.softEdges`, `.emitsLight`, `.lightStrength`, `.lightRange` and per animated layer `texture.<kind>.layerN.speedX/speedY/scale/opacity/pulseDepth/pulseHz`, plus global `texture.tileCells`, `texture.featherCells`, `texture.featherPasses`. Hand edits are picked up when the window regains focus (other settings on next start); per-effect values already stored in a map are unaffected. The repository keeps a reference copy of all built-in defaults in `dmmt-settings.default.ini` (generated by `AppSettings.renderDefaults()`; the user's own `dmmt-settings.ini` stays git-ignored). `DefaultSettingsFileTest` fails when it is stale; regenerate it with `mvn test -Dtest=DefaultSettingsFileTest -Dupdate.default.settings=true` after changing a default, setting or texture.
- **Tuning values** (v3.1): every behaviour/look constant a DM may want to change is a setting, defined once in `dmmt.service.Tuning` (key, default, range, comment, restart flag) and read by the code through `Tuning.<NAME>.get()`; no duplicate literals. Groups: window sizes, player zoom/calibration limits, DM zoom, mouse pick tolerances and debounce, ping and laser pointer, undo depth, brush/effect/text defaults and limits, light presets and the light context-menu choices, light defaults, time-of-day colours, light map/shadow quality, fog fade (reveal/hide/light fade times, max step, easing `linear|smooth`, DM fog opacity, sharpness/softness limits), weather (default intensity, particles, colour, opacity per type), performance-mode values (incl. optional fog fade), frame-rate cap, image/texture caches, map library folder, auto-save choices, tooltip timing, wall/grid look, door/window state colours (`ui.doorOpenColor`, `ui.doorClosedColor`, `ui.windowOpenColor`, `ui.windowClosedColor`).
- `AppSettings` loads `Tuning` whenever it (re)reads the file, so the defaults are active from startup and hand edits apply when the window regains focus (values marked *restart* shape controls or windows and need a restart). On startup any missing known key is written to the file with its default.
- Every entry is documented in `docs/SETTINGS.md` (searchable keywords per entry); `SettingsDocumentationTest` fails when a settings key is not documented.
- **Settings window (v3.8):** a cog button in the DM controls header and in the status bar opens a non-modal `Settings` window (`dmmt.ui.SettingsWindow`) with every setting that has no control in the DM controls (all `Tuning` values, the effect texture defaults, `ui.recentMaps.max`). Settings are grouped by category (list on the left), explained by a description and tooltip (default, key, range), edited with a type-specific control (checkbox, colour picker, choice list, validated text/number field with clamping), individually resettable, and **text-searchable** (all words must match category, key, name or description; results are grouped by category). Changes are written to the settings file and applied at once through `AppSettings.applyEdit` (entries marked *restart required* only apply after a restart). `AppSettings.editableSettings()` describes the entries; `Tuning.Setting` carries kind, min, max and options.
- **Grouping (v3.9):** categories are ordered so related ones are adjacent (editing/effects next to effect textures, fog, lights, time of day, weather together). `AppSettings.SettingInfo` carries an optional `group` path (nested with `/`, e.g. `Fire/Layer 1`, `Right-click menu/Colour choices`): a collapsible block, collapsed by default, for every subtopic with two or more settings (player zoom, calibration limits, pick distances, map image cache, tooltips, per time of day / weather type / light preset / effect texture, ...); there are no tabs. Numbers use spinners with the setting's range. Search also matches hard-coded keywords from `src/main/resources/dmmt/settings-keywords.properties` (copied from the *Keywords* lines of docs/SETTINGS.md, generic keys use `*`); keep it in sync when adding settings (`AppSettingsTest` requires keywords for every editable setting). The light right-click menu lists became fixed individual settings (`lightMenu.range1-10`, `lightMenu.color.<id>`, `lightMenu.flicker.<id>.depth/speed`, `lightMenu.brightness.<id>`) so no entries can be added that the code does not know; the old list keys are dropped at startup (`Tuning.lightMenu*()`).
- **Hidden DM controls tabs (v3.8):** the first settings category *DM controls tabs* lets the DM show or hide each tab (Tools, Fog of war, Lighting, Weather, Effects, Text, Map building, Player view, Performance) of the DM controls overlay. Stored as `ui.sections.hidden` (comma separated ids, empty = all shown), applied live and when the settings file is edited by hand; hidden tabs keep their data and settings.

## 3.26 Batch Import of Maps (implemented)

- The library's **Import** actions (toolbar button, folder context menu "Import map here…") open the system file chooser with **multi-selection** enabled (`.dd2vtt`, `.uvtt`). A new **Import folder…** action (toolbar button + folder context menu "Import folder here…") opens the system **folder chooser** and imports every suitable map found in that folder **and its sub-folders**. "Suitable" = `.dd2vtt` / `.uvtt` files (case-insensitive).
- One selected file keeps the existing flow (location dialog with name + folder, imported map is opened afterwards).
- Two or more files (or a folder) use the location dialog **without the name field** (folder only, "New folder" still available). Each map is named after its source file; a name that is already taken in the target folder (or repeated within the batch) gets a numeric suffix (`Name (2)`, `Name (3)`, ...). Invalid characters in file names are cleaned like any other map name.
- The batch runs on a background thread (one map after another) and shows progress in the status bar (`Importing 3/12: name...`). The currently open map is **not** switched or saved; the browser refreshes when the batch ends.
- A map that fails to import is removed again (no half-imported package) and does not stop the batch. When the batch ends the status bar shows `Imported N of M maps` and, if anything failed, a dialog lists the failed file names with the reason.
- An empty folder / selection with no suitable file shows a hint instead of the location dialog. The last used import directory (3.25) is remembered for both choosers.
- Implementation: `dmmt.service.BatchImportService` (folder scan, unique naming, sequential import with progress callback; unit-tested), `MapLocationDialog` gets a folder-only mode.

## 3.27 Handout Upgrades (multiple images, implemented)

- Extends 3.18: the handout window holds a **list of images** instead of one. Every paste (Ctrl+V / Paste button) **adds** an image (copied image files: all image files in the clipboard are added). Pasting never replaces existing images.
- The handout window preview and the player screen show all images **together in a grid**. The grid is computed by `dmmt.ui.HandoutLayout`: for N images it tries every column count (1..N), lays the images out row by row, fits each image into its cell keeping its aspect ratio (no distortion, a thin gap between cells) and picks the arrangement that covers the **largest total image area** of the available screen. Incomplete last rows are centred. With a single image it fills the screen as much as possible (no longer the ~90% margin of 3.18; a thin outer margin remains).
- The grid re-arranges immediately when images are added or removed, in the preview and (if shown) on the player screen.
- Rotation (90-degree steps) applies to the whole arrangement: the layout is computed in the rotated space (width/height swapped at 90/270) and then the board is rotated.
- **Mirror for opposite side:** a session-only icon toggle beside the rotation buttons in the handout's existing control row (off by default, shared `Icons.toggle` styling, opposing vertical arrows icon and explanatory tooltip) shows two copies of the currently displayed images on the player screen, facing opposite sides of the table. This is a 180-degree rotation, not a reflection of text. At 0/180 degrees the screen is split into top/bottom halves; at 90/270 it is split into left/right halves. The image arrangement is recomputed for one half's available space, keeping aspect ratios and margins, and repeated facing the opposite direction in the other half. Rotation, image changes and selected-only display apply to both copies immediately. The DM preview remains a single unrotated, selectable arrangement. This is not a saved application setting or project property.
- **Selecting and deleting:** left-click an image in the preview selects it (highlighted border); `Delete` removes the selected image; right-click an image opens a context menu with **Delete** (right-click also selects it). Clicking empty space clears the selection. A **Delete selected** / **Clear all** button is available in the controls. The selection highlight is DM-only (never drawn on the player screen).
- When the last image is removed the window returns to its empty state and "Show to players" turns off.
- Handouts remain session-only (3.18).
- **Selection and partial display (3.30):** click selects one image, Ctrl/Shift+click adds or removes images, Ctrl+A selects all, clicking empty space clears the selection. The right-click menu offers "Show only this image to players" (or "Show only the N selected images to players" when several are selected) and Delete (all selected); an eye-check toggle next to the "Show to players" toggle does the same for the selection and switches the handout off again when pressed a second time. The layout keeps every image readable: for up to 8 images every slicing arrangement (side by side or stacked at each level, in image order) is tried so the smallest image is as large as possible, e.g. a slim vertical image sits next to a stack of two wide ones; more images fall back to rows, and the DM preview is never rotated (rotation applies to the player view only, which the rotate tooltips say) so the DM can tell the images apart. Both need the player window to be open. Only the chosen images are laid out and shown on the player screen (the DM preview still lays out every image and marks the ones the players see with green frames; selected images have blue frames). The "Show to players" toggle shows all images again (turning it on always shows the whole handout; turning it off returns to the map); deleting images also removes them from the shown subset, and the handout returns to the map when the subset becomes empty.
- Implementation: `HandoutLayout` (pure, unit-tested: single image, rows/columns choice, wide vs. tall images, rotation, removal), `HandoutWindow` (image list, hit-testing, selection, context menu), `renderPlayer` draws all images via the shared layout.

## 3.28 Copy & Paste of Lights, Effects and Text (implemented)

- `Ctrl+C` copies the current selection: a single selected light, effect shape or text box, or a multi-selection (marquee / Ctrl+click) containing any mix of them. Image layers are not copied. (There are no copy/paste buttons.)
- `Ctrl+V` pastes all copied items as one undoable step ("Paste"), keeping their relative arrangement, with the group centred on the mouse cursor (or the view centre when the cursor is outside the canvas). Pasted items get new ids; a single pasted item is selected, several pasted items become the multi-selection.
- The clipboard lives in the app (not the system clipboard) and survives map switches, so items can be pasted into another map. Positions and sizes are copied as-is in world units (no rescaling between maps with different grid sizes). Pasting text boxes re-enables a hidden text layer.
- Pasted lights keep all their settings (range, colour, flicker, reveal mode, on/off); persistent reveals are recorded in the fog history so undo restores the fog.

## 3.29 Ambient Weather (implemented)

- *Weather* section in the DM controls: a type dropdown (None, Rain, Snow, Mist, Dust motes, Embers) and an intensity slider (10-100%, default 40%, double-click resets). Both are stored per map (`weather.type`, `weather.intensity`) and changes are undoable; older saves = no weather.
- Weather is **subtle by design**: thin low-opacity particles (rain streaks ~22%, snow ~50%, dust and embers fade in and out, mist is a few very soft gradient blobs with at most ~12% opacity each) that never hide map details.
- Shown in both DM and player views, above map, effects and lighting but **below text boxes and fog**. It is screen-space: particle positions are a pure function of index and time (`dmmt.render.WeatherEffects`), so DM and player canvases need no shared state.
- Follows the map's *Animations* toggle (off = static frame, render loop not woken) and Performance mode (half the particles, 5 fps animation). The frozen player view keeps the weather of the moment it was frozen.
- Implementation: `WeatherType`, `WeatherEffects` (unit-tested: counts, determinism, wrap, clamping), `DmProject.WeatherState`, `CanvasMapRenderer.drawWeather`.
## 3.30 UI Polish Pass (in progress)

- Source files stay UTF-8; tooltips use real characters (em dash, degree sign), never mojibake.
- Text tab has no copy/paste buttons (Ctrl+C / Ctrl+V remain, see 3.28).
- Any button that needs a selection (e.g. remove light, show to players, rotate, delete) is enabled only while such an element is selected; "Show to players" in the Text tab is lit only while a shown text box is selected.
- Remove light is no longer a click tool: it is a button that removes the selected lights (single or multi-selection, one undo step) and is disabled while no light is selected. Selection-dependent buttons are refreshed every DM frame (updateSelectionControls): remove light, delete effect, delete text, text auto-size and text show-to-players (the latter two are also cleared, so nothing stays lit once the text box is deselected).
- Tooltips: no light ranges (configurable), no "Esc / right-click to exit" (shown globally at the top), text rotation buttons say they affect the player view only, select tool tooltip is generic (several right-click elements).
- Separate **global** light flicker toggle (same play-circle icon as the Animations toggle, at the far right of the time-of-day row in the Lighting section; persisted as `ui.lightFlicker`, default on) independent of the **global** Animations toggle in the Effects section (persisted as `ui.effectAnimations`, default on, applies to all maps; it only controls effect textures and weather). Off = no light or light-emitting effect flickers; on = each light flickers according to its own flicker setting. Performance mode still disables flicker regardless of the toggle (`CanvasMapRenderer.flickerOn`).
- Player view box on the DM view shows the player zoom level in its top bar with a one-click Reset button (back to 100%); implemented in `CanvasMapRenderer.drawViewportRect` / `isOnViewportResetButton`.
- Laser pointer is a normal tool next to Ping (see 3.19; still works on middle-mouse hold). It stays on until dismissed with Esc / right-click; no other interaction while active.
- Text boxes hidden from players are drawn in the DM view at `text.dmHiddenOpacity` (default 0.85) so they stay readable.
- Loading spinner without the opaque box behind it; the semi-transparent dimming overlay stays (see 3.4).
- Handout dialog: right-click option "Show only this image to players", multi-select, and a button to show only the selected images (see 3.27).
- Default text boxes: neutral opaque background and dark text for visibility on any map.
- Lighting tab: the light flicker toggle sits at the end of the first row (after Remove light) to keep the time-of-day row narrow.
- Lighting tab: the time-of-day row has **Turn on** / **Turn off** buttons (after the day-time presets, behind a separator) that switch all selected lights on or off in one undo step; disabled while no light is selected.
- Lighting tab: a "Fog reveal" row with three buttons (Keep revealed / Only while lit / Don't reveal) that set the reveal mode of all selected lights in one undo step; disabled while no light is selected (also available per light in the right-click menu, see 3.6).

## 3.31 Recent Maps (implemented)

- Small **Recent maps** list at the bottom of the map browser sidebar, below the library tree: a history of the maps that were opened, most recent first, up to `ui.recentMaps.max` entries (default 5, 1 to 30). Shows at most 5 rows, then scrolls.
- **Double-click** (or Enter) opens a map from the list, same flow as the tree (current map is auto-saved first). The open map is highlighted; the tooltip shows the full path.
- A map is recorded whenever it becomes the open map (`MapBrowser.updateCurrentMap`). Persisted in the settings file as `ui.recentMaps` (paths separated by `|`); maps that no longer exist on disk are dropped. Logic lives in `RecentMaps` (unit tested).

## 3.32 Multilevel Maps (implemented)

Dungeon Alchemist can build multi-storey buildings and export every level as its own dd2vtt. A **multilevel map** groups such levels into one map of the library.

- **Library:** a multilevel map is shown as **one map** in the map browser (layers badge on the thumbnail, tooltip "Multilevel map · N levels"). Open, rename, duplicate, move (drag & drop), delete and search work on the whole multilevel map like on any other map. The recent maps list records the multilevel map (not a single level).
- **Storage (map package):** `<folder>/<Name>/<Name>.dmlevels` (manifest JSON) + `levels/<levelId>/level.dmmap` (+ `assets\...`, `thumbnail.png`) per level + `thumbnail.png` of the whole map. Each level is a normal `.dmmap` map package, so all map features work per level unchanged. Level folders are named by a stable random id, so reordering/renaming levels never moves files. A directory containing a `.dmlevels` file is always a multilevel map package (never a folder).
- **Manifest** (`MultiLevelManifest`): `schemaVersion`, `levels` (ordered **lowest level first**: `id`, `name`, `folder`), `currentLevelId` (last opened level, `null` = never opened) and `shared` (map-wide settings).
- **Level order:** index 0 is the lowest level. All lists (level dropdown, level dialog) show the **lowest level at the top**. "Up" = next higher level.
- **Opening:** opens the level stored in `currentLevelId`; a multilevel map that was never opened starts on the lowest level. Saving (manual, auto-save, switching level/map, app exit) stores the current level and updates `currentLevelId`.
- **Only one level is in memory** at a time (the open level, like a normal map). Switching level saves the current level (background thread, loading spinner "Loading <level>..."), then loads the other one; undo/redo history is cleared like on a map switch; the player view follows unless it is frozen (freeze keeps the old level on the player screen).
- **Per level:** fog of war (mask), DM and player camera, lights, walls, doors/windows, effects, text boxes, grid, image layers. A level that was never opened starts with its cameras centred on the level.
- **Shared by all levels** (`shared` in the manifest): time of day + ambient brightness per preset, weather (type + intensity), image layer lock, fog on/off, map rotation, player zoom, text layer visibility and last-used text settings. The open level holds the live values; on every save they are written to the manifest, and whenever a level is loaded they are applied to it (rotation: the level is rotated by the difference to the shared rotation). So changing any of them on one level applies to all levels. When a multilevel map is created, the shared settings are taken from the lowest level.
- **Level switcher overlay:** small floating panel at the top-left of the DM canvas (separate from the tool panel), only shown while a multilevel map with **2 or more levels** is open: *Level down* button, dropdown with all level names (lowest first, the current one selected), *Level up* button, the position (`2 / 4`) and a *Manage levels* button. Down/up are disabled on the lowest/highest level. Shortcuts: `Page Down` = level down, `Page Up` = level up. The window title and the browser's "open map" label show `Map name — Level name` / `Map name · Level name`.
- **Creating a multilevel map**
  - *Import multilevel map* (map browser toolbar button and folder context menu "Import multilevel map here…"): pick several `.dd2vtt`/`.uvtt` files (multi-select). The **level dialog** opens with the files in natural name order (numbers compared numerically, e.g. `_2` before `_10`); level names are the file names minus the part all files share (underscores read as spaces), or "Level N" if nothing is left. The levels can be reordered, renamed, removed and more can be added. Then the location dialog asks for the name (pre-filled with the shared part of the file names) and folder. Import runs in the background with progress in the status bar; if any file fails, nothing is created. The new map is opened afterwards (the current map is saved / asks Save-Discard-Cancel first like a normal import).
  - *Merge maps*: select several maps in the library tree (`Ctrl`/`Shift`+click) and choose "Merge N maps into a multilevel map…" in the context menu; a single selected map offers "Make multilevel map…" (it becomes a multilevel map with one level, more can be added). The level dialog shows the selected maps (natural name order), then the location dialog asks for name/folder (default: shared part of the names — with a suffix such as " (multilevel)" if that name is taken — and the folder of the first map). The original maps are **moved** into the multilevel map (they disappear as separate maps; fog, lights etc. are kept). Multilevel maps cannot be merged into another one. If the open map is merged, it is saved first and the new multilevel map is opened on that level. If a change fails, moved maps are put back and nothing is deleted (loose originals and removed levels are only deleted after the new manifest was saved).
- **Level dialog** (`LevelListDialog`, used for creating and for *Manage levels*): list of levels (lowest at the top) with buttons *Move up* / *Move down*, *Rename* (also double-click / F2), *Remove*, and the insert actions *Add dd2vtt files…*, *Add library maps…* (picker with search over all ordinary maps of the library; multilevel maps are not offered) and *Add empty level*. New entries are inserted **directly below the selected entry** (or at the end if nothing is selected), so levels can be inserted at any position. Changes are applied on OK; removing existing levels asks for confirmation on OK (listing the levels to delete). Removing the **last** level asks for confirmation that the whole multilevel map will be deleted.
- **Manage levels** (map context menu and the overlay button): opens the level dialog for an existing multilevel map. If it is the open map, the current level is saved first; if the current level was removed, the nearest remaining level is opened; if all levels were removed, the multilevel map is deleted and an empty new map is shown.
- **Renaming:** the multilevel map is renamed from the library like any map (package folder + manifest file). Individual levels are renamed in the level dialog (names: same rules as map names; duplicates are allowed).
- **Thumbnail:** the map browser thumbnail/tooltip shows the **last opened level** (the level's thumbnail is copied to the package `thumbnail.png` on every save), or the lowest level if the map was never opened.
- Implementation: `dmmt.model.MultiLevelManifest`, `dmmt.service.MultiLevelService` (create/apply level plans, load/save level with shared settings, thumbnails, natural sort and default names; unit-tested), `MapLibraryService` (package detection, rename/copy/move of `.dmlevels` packages), `dmmt.ui.LevelListDialog`, `MapBrowser` (multi-selection, menus), level switcher in the application.

### 3.32.1 Multilevel map refinements (v3.6)

- **Switcher layout:** `[icon] [level dropdown] [▼ down] [▲ up] [2 / 4] [manage]` — the down/up buttons sit next to each other right of the dropdown.
- **Level previews in the dropdown:** every entry of the level dropdown has a tooltip with the level name and a thumbnail preview (the level's own `thumbnail.png`, loaded in the background and cached; same style as the map browser tooltip).
- **DM zoom is map-wide:** the DM camera zoom is a shared setting (`shared.dmZoom`); the DM camera position stays per level. The player camera (position/zoom) stays per level.
- **Original name per level:** `Level.originalName` stores the name the level came from (dd2vtt file name without extension, or the library map name it was merged from). Levels created empty have none.
- **Move a level out** ("Move out as separate map…" in the level dialog's context menu, managing mode only): asks for a map name, prefilled with the level's original name, else `<multilevel map name> <level name>`. On apply the level package is moved **next to the multilevel map** (same library folder) as an ordinary map; the shared settings are applied to it first so it looks the same as inside the multilevel map. Fog, lights etc. are kept.
- **Collapse:** when a change leaves a multilevel map with exactly **one** level (levels deleted or moved out, or levels taken by a merge), the multilevel map becomes an ordinary map with the multilevel map's name in the same place (shared settings applied). Zero levels still deletes it. The level dialog's confirmation mentions this. Creating a multilevel map requires **at least two levels** (clicking OK with fewer shows a hint instead).
- **Dissolve** (context menu of a multilevel map → "Dissolve into separate maps…", with confirmation): every level becomes an ordinary map next to the multilevel map, named after its original name, else `<multilevel map name> <level name>` (duplicates get ` (2)`, …); the multilevel map is removed.
- **Drag & drop in the map browser** (each opens the level dialog for ordering/naming first; the drop target cell is highlighted with an icon that tells what will happen):
  - *map → map:* create a new multilevel map from both (dragged map is added as the higher level; location dialog prefilled with the target's folder and the common name).
  - *map → multilevel map:* add the map to the multilevel map (appended as the highest level).
  - *multilevel map → multilevel map:* merge: the target stays (name, shared settings); the dragged map's levels are appended and the dragged multilevel map is removed (levels not taken in the dialog stay in it; it collapses/is deleted when 1/0 levels are left).
  - *multilevel map → map:* add that map to the dragged multilevel map.
  - Folders dropped on maps and anything dropped on folders keep the move behaviour; dropping on a map no longer moves into the map's folder.
  - Highlight: `merge-target` pseudo class + icon (`LAYERS_PLUS` for create/add, `CALL_MERGE` for merging multilevel maps).
  - **Multiple selection:** dragging an entry that is part of the current selection drags **all selected entries** (the library root is never dragged; entries inside a selected folder go along with that folder). The drag image shows "N items" and the merge hint names the map count.
    - *Onto a folder / empty space:* every dragged entry is moved there (entries already in that folder are skipped). Entries that cannot be moved (name taken, folder into itself) are skipped; the rest are moved and a dialog lists the skipped ones.
    - *Onto a map, only maps dragged* (the target itself is ignored if selected): if the target or any dragged map is a multilevel map, the **main** map is the target if it is multilevel, otherwise the first dragged multilevel map; all other ordinary maps are added as levels and the levels of all other multilevel maps are merged in (those are removed/collapsed afterwards). If only ordinary maps are involved, a new multilevel map is created: target first (lowest), then the dragged maps in tree order. The level dialog is shown first as usual.
    - *Onto a map, folders among the dragged entries:* everything is moved into the target map's folder.
- **Level dialog:** levels are reordered by **drag & drop** within the list; rename / remove / move out / move up / move down are in a **right-click context menu** (shortcuts F2, Delete, Alt+↑/↓ remain); only the add buttons stay as buttons.
- **Batch import auto-grouping:** batch import (several files or a folder) groups files of the same folder that look like the levels of one building and imports each group automatically — no dialog — as a multilevel map. Other files are imported as single maps as before. Rules (case-insensitive, `_` read as space, separators ` _-.` around the number ignored):
  - **Numbered files:** `<shared part> <number>` or `<shared part> <number> <room label>` (label without digits), e.g. `haus_00 … haus_03` or `turm_upper_levels_02_barracks … turm_upper_levels_10`. Files with the same shared part form a group if there are at least two and all level numbers are distinct (so `Market 1 day` / `Market 1 night` variants stay separate). No fuzzy similarity — names like `Goblin Cave` / `Goblin Camp` are never grouped.
  - **Base file:** a file without a number whose whole name is the start of the shared part of exactly one group (`gottloser turm` → `gottloser turm upper levels`) joins that group as its lowest level (the longest such name wins).
  - **Order and names:** base file first, then by level number. The multilevel map is named after the base file, otherwise after the shared part. Level names: `Level <number>` plus ` – <label>` when present; the base file gets the number below the lowest (`Level 01` before `Level 02`), or `Base` if the lowest is 0.
- **Re-targeting the open map after a level change:** every change returns the relocations of all moved map files (library map → level, level → other multilevel map, level → separate map, last level → collapsed map). If the open map/level was moved, the app reopens it at its new place (as a level or a single map); if the open level was deleted, the nearest remaining level is opened; if nothing is left, an empty new map is shown; otherwise only the level list is refreshed.

## 4) Proposed `.dmmap` Structure (v1 Draft)

```json
{
  "schemaVersion": 1,
  "map": {
    "sourceType": "dd2vtt",
    "imagePath": "imports/catacombs/catacombs.webp",
    "grid": { "pixelsPerCell": 140, "cellSizeFeet": 5 },
    "rotationQuarterTurns": 0,
    "imageLayersLocked": true
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
    "ambientBrightness": { "NIGHT": 0.35 },
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
  "textBoxes": [
    {
      "id": "text-1", "x": 100, "y": 100, "width": 400, "height": 120,
      "backgroundColor": "#00000000", "borderColor": "#00000000",
      "runs": [ { "text": "Hello", "fontSize": 32, "color": "#FFFFFF" } ]
    }
  ],
  "textLayerVisible": true,
  "lastTextSettings": { "fontSize": 32, "textColor": "#FFFFFF", "backgroundColor": "#00000000", "borderColor": "#00000000" },
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
- **Large map images (> 4096 px):** GPU textures are capped at 4096 px, so large images are never drawn from a single downscaled texture alone. Implemented (`dmmt.render.ImagePyramidBuilder` / `ImagePyramidStore`):
  - On first use, a background thread decodes the image once and writes a multi-resolution **tile pyramid** (1024 px tiles, each level half the size, box-filtered; JPEG q0.92 for opaque images, PNG for images with alpha) plus a <= 4096 px overview into a disk cache (`%LOCALAPPDATA%\DungeonMasterMapTool\image-cache\<hash of path+size+mtime>`, fallback `~/.dmmt/image-cache`). Later sessions reuse the cache; caches unused for 60 days are pruned.
  - Rendering always draws the overview first. It then picks the pyramid level that has at least one source pixel per physical screen pixel (including zoom, HiDPI output scale and the transform), and draws only the visible tiles of that level. Tiles load asynchronously on two worker threads (newest requests first, stale requests dropped) into an LRU memory cache shared by DM, player and frozen-player renderers. Missing tiles show the overview until they arrive.
  - While the pyramid is being built (first open), the overview is shown.

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

## Phase 7 - Live-Play Improvements (planned)

1. Light presets (3.22).
2. Laser pointer on middle mouse hold (3.19).
3. Auto-save for maps on disk (3.21).
4. Handout window (paste from clipboard, show-to-players toggle, 90-degree rotation) (3.18).
5. One-click room reveal with hover preview (3.17), with unit tests for the flood fill (enclosed room, door boundary, gap tolerance, unenclosed area limited to map bounds).
6. Map browser thumbnails and search bar (3.20), with unit tests for the tree filter.
7. Right-click cancels the active tool (3.23).
8. Text boxes (3.24), with unit tests for word wrap layout and map rotation.

## Phase 8 - Import, Handout and Fog Improvements

1. Batch import of dd2vtt maps (3.26), with unit tests for folder scan and unique naming.
2. Handout upgrades (3.27), with unit tests for the grid layout.
3. Soft fog edges and fog fade animation (3.5), with unit tests for `FogShading`.
4. Copy/paste of lights, effects and text across maps (3.28).
5. Ambient weather (3.29), with unit tests for `WeatherEffects`.

## Phase 9 - Multilevel Maps

1. Multilevel map package format + `MultiLevelService` (create, apply level plan, shared settings, thumbnails) with unit tests (3.32).
2. Library integration: package detection, rename/copy/move, multi-selection merge, import multilevel map.
3. Level dialog (reorder, rename, insert anywhere, remove, delete-last confirmation) and level switcher overlay.

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
- **v3.9:** Settings window collapsible (nested) groups, spinners, keyword search, related categories adjacent, light menu choices as individual settings (3.25).
- **v3.8:** Settings window with categories, tooltips and search for all settings without a DM control; setting `ui.sections.hidden` hides DM controls tabs (3.25).
- **v3.1:** Tuning constants moved into the settings file, fog fade with separate reveal/hide times and easing, settings reference `docs/SETTINGS.md` (3.25).
- **v3.0:** Per-map player zoom slider (3.3).
- **v2.9:** Batch import of maps (3.26), multi-image handouts with grid layout and per-image delete (3.27), soft fog edges and fog fade animation (3.5), Phase 8.
- **v3.7 (current):** Migrated to Java 25 (LTS): release 25, JavaFX 25.0.4, Lombok 1.18.48, RichTextFX 0.11.7 (0.11.4 fails to load on JavaFX 22+), Ikonli 12.4.0, shade plugin 3.6.2; CI uses JDK 25; shaded jar manifest sets `Enable-Native-Access: ALL-UNNAMED` to silence JavaFX native-access warnings. `MultiLevelService` / `LevelListDialog` handle the sealed `Source` with exhaustive pattern `switch`es and record patterns, `getLast()` replaces `get(size() - 1)`, and the Windows app image and the documented jar launch use `-XX:+UseCompactObjectHeaders` (lower memory use).`n- **v3.6:** Multilevel map refinements (3.32.1): drag & drop merging in the library, move levels out / dissolve, collapse single-level maps, map-wide DM zoom, level previews in the switcher, drag & drop level dialog, automatic multilevel grouping on batch import.
- **v3.5:** Multilevel maps (3.32): import several dd2vtt levels or merge library maps into one map with per-level fog/cameras, shared map-wide settings and a level switcher overlay.
- **v3.4:** Pushing a `v*` tag builds Windows (app image zip), Linux and macOS (shaded jars; each jar bundles the JavaFX natives of the OS it was built on) and creates a GitHub release with those files attached. Default settings file removed from the Windows package.
- **v3.3:** GitHub Actions workflow (`.github/workflows/build.yml`): on push/PR to master it runs `mvn verify` (all tests) on `windows-latest` with JDK 25 and uploads the `DungeonMasterMapTool-windows` artifact: a zip of a jpackage app image (`DungeonMasterMapTool.exe` with a bundled trimmed Java runtime, no Java install needed; plus the README). README shows build/tech badges.
- **v3.2:** Recent maps list below the map browser (3.31).
- **v2.8:** Line effect tool and right-click show/hide menu with hidden badge for effect shapes (3.8).
- **v2.7:** Per-map toggle to disable effect animations (3.8).
- **v2.7:** Ctrl+C / Ctrl+V for lights, effect shapes and text boxes incl. multi-selection and across maps (3.28); ambient weather per map (3.29).
- **v2.6:** Global preferences moved to an editable settings file next to the jar, including texture defaults (3.25).
- **v2.5:** Animated effect textures (smoke, fire, water) for AOE shapes (3.8).
- **v2.4:** Text boxes on the map (3.24): rich text (size/color), background/border, wrap, layer toggle, per-map last-used settings, copy/paste between maps.
- **v2.3 (planned):** Added Phase 7 live-play specs: one-click room reveal (3.17), handout window (clipboard paste, show-to-players toggle, 90-degree rotation) (3.18), middle-mouse laser pointer (3.19), map browser thumbnails + search (3.20), auto-save for maps on disk (3.21), light presets (3.22), right-click cancels the active tool (3.23).
- **v2.2:** Per-map, per-time-of-day ambient brightness slider (saved in `.dmmap`, undoable).
- **v2.1:** Large map images (> 4096 px) render at full resolution via a cached tile pyramid instead of a single 4096 px downscaled texture (fixes blurry player view for e.g. 15k x 15k maps).
- **v2.0:** Walls drawn as red lines in the DM view; wall layer toggle (walls + door/window lines and badges, lights excluded).
- **v1.9:** Door/window icon badges in the DM view with single-click toggle; pointing-hand cursor over interactable objects (lights, doors, windows).
- **v1.8:** Add light / Remove light are one-shot click tools (place at click / remove the clicked light, then back to Select).
- **v1.7:** Implemented 3.15 and 3.16 (Phase 6 complete).
- **v1.6:** Added map browser sidebar (3.15), modern DM controls overlay and visual design (3.16), Phase 6. Map switcher dropdown and system file dialogs for open/save replaced by the library.
- **v1.5:** Async file IO; removed per-layer rotation and hand-drawn doors from scope; dirty-rect redraw replaced by idle throttling.
- **v1.5:** Performance mode toggle (temporary override, persisted on/off state).
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

- **Window sizing:** the DM window's initial size (`Tuning.DM_WINDOW_WIDTH/HEIGHT`) is clamped to the primary screen's visual bounds so it never exceeds the screen on small laptops.

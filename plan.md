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
- [x] Map tags, click-to-use suggestions, import tag inheritance and word-based name/tag search (3.34)
- [ ] Audio: ambient music categories, sound effect loops, clip cutting from long files and a mini player (3.35)
- [x] Auto-save for maps that already exist on disk (3.21)
- [x] Light presets (candle, torch, lantern, ...) placeable with one click (3.22)
- [x] Right-click cancels the active tool like Esc (3.23)
- [x] Soft fog edges and fog fade animation (3.5)
- [x] Batch import of dd2vtt maps: multi-file selection and whole-folder import (3.26)
- [x] Handout upgrades: multiple pasted images shown together in an auto-fitted grid, per-image delete (3.27)
- [x] Copy/paste of lights, effect shapes and text boxes (Ctrl+C / Ctrl+V), also across maps (3.28)
- [x] Subtle ambient weather per map: rain, snow, mist, dust motes, embers (3.29)
- [x] Multilevel maps: several levels (e.g. building floors from Dungeon Alchemist) shown as one map with a level switcher (3.32)
- [x] Additive multi-light blending: overlapping lights brighten and mix color instead of only the brightest light showing (3.6)
- [x] Inverse-square-style falloff in the dim-light band for more physically realistic light falloff (3.6)
- [x] Bright core highlight per light: additive hot-spot that brightens the map texture at the light's center instead of covering it (3.6)
- [x] Ambient darkness/tint rendered via multiply blend on its own canvas layer instead of a flat colour-over overlay, so Dawn/Dusk/Night darkening preserves the map's own detail/contrast (3.7)
- [x] Stream Deck plugin driving the DM view through the local control API, with key artwork and live highlighting (3.36)

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
- **Alt + mouse wheel over the map canvas:** adjust the shared brush size by 0.1 grid tiles per notch for Reveal brush, Fog brush, Draw and Line, clamped to `brush.minTiles` / `brush.maxTiles`. Both sidebar sliders and the cursor preview update immediately without moving either camera, editing content or adding undo history. With Select and a selected light, adjust its radius instead (3.6). Ordinary wheel still zooms the DM view; Ctrl + wheel takes precedence even with Alt held. Unsupported tools consume Alt + wheel without zooming (Circle and Box stay drag-sized; Pen stays fixed-width). Ignore contextual changes during a fog stroke or effect draw. Sidebars, dialogs and text editors are excluded. A mouse-transparent, canvas-bounded DM-only `Brush: 2.5 tiles` label follows the cursor while adjusting and for 0.9 seconds afterwards; it disappears on canvas exit, drawing or tool change.
- Pan/camera movement via drag and/or keybind.
- **Arrow-key nudging:** only while the DM map canvas has keyboard focus, arrows move every eligible selected light, effect, text box or unlocked image layer by 0.1 grid tile; Shift + arrow moves by 1 tile. Fixed increments ignore mouse snapping and follow the displayed DM axes, including after whole-map rotation. Reuse group movement and drag fog-history logic, preserving selection and relative spacing; locked images, walls and doors/windows remain immovable. Do not handle arrows without eligible selection, in controls/dialogs/text editing, with pointer tools, or during any mouse drag, drawing stroke or geometry gesture. Consume handled arrows and use normal keyboard repeat. A press is one undo operation; overlapping/repeated arrows form one gesture until all arrows are released, focus is lost or selection changes, including associated persistent light reveals. Positions persist normally; gesture state is session-only. Both views and selection outlines update immediately subject to freeze.
- Render overlays (fog, lights, shapes) with editing controls.
- Show player viewport rectangle when player screen is active. It looks like an application window: fully transparent interior with a cyan border and a thicker "Player view" title bar above the top edge. Only the title bar can be grabbed to drag the viewport (Select tool); it takes click priority over everything beneath it. The bar also shows the current player zoom (e.g. "125%", right-aligned) and, when the box is wide enough, a **Reset** button that sets the player zoom back to normal (100%, step 0); the button is dimmed while the zoom is already normal.
- **Player viewport edge scrolling:** during a valid title-bar drag only, pan the DM camera relative to the visible map interaction area, excluding sidebars and control overlays. The edge zone defaults to 40 logical pixels; speed increases linearly toward an edge up to 600 logical pixels/second, with the combined corner speed capped at that maximum. While the drag remains active, moving beyond an edge (including outside the DM window) continues scrolling at the maximum speed for that axis; clamp each axis before limiting combined corner speed. Use elapsed time and DM zoom to convert screen movement to world movement; recalculate the normal viewport drag each rendered frame so the grabbed point remains beneath a stationary cursor. Never separately translate the player camera, change either zoom or rotate the map; frozen output remains unchanged while only the staged viewport moves. Suspend over control overlays and resume on return. Stop on release/cancellation, focus loss, player-window closure, map/level switching and window deactivation. Keep interaction rendering active while scrolling and restore idle throttling afterwards. The complete viewport drag retains one undo operation and normal persistence; other object drags are unaffected. Add a title-bar tooltip hint. Live global settings: `player.viewportEdgeScroll.enabled` (true), `player.viewportEdgeScroll.zonePx` (40), `player.viewportEdgeScroll.maxSpeedPxPerSecond` (600), exposed through the existing settings window and documented in README and `docs/SETTINGS.md`.
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

- Freeze mode: player view remains fixed while DM prepares another map/view. While frozen, DM can still move the player viewport rectangle; on unfreeze, player view adopts that staged rectangle. Player view must stay visually clean: DM-only helper/debug geometry (wall guides, door/window state lines, viewport handles) is never rendered there.
- **Player scene transitions:** switching maps or levels while live uses a subtle 0.4-second eased crossfade from the fully composited outgoing player frame to the new scene, without camera travel, zoom effects or a black flash. Unfreezing uses the same transition only if the map, player viewport or player-facing content (fog, lighting, images, effects, text or weather) changed; unchanged freeze/unfreeze and DM-only camera/metadata edits do not animate. Normal live viewport manipulation stays immediate. Frozen map switches remain invisible until unfreeze. Handouts take precedence and window closure releases the snapshot; rapid switches fade from the currently displayed composite rather than flashing back to an earlier frame. This is session-only behavior, with no new settings.
- **Wall layer (DM only):** all walls (imported dd2vtt walls and manually drawn walls) are drawn as red lines. A **wall layer toggle** next to the wall tools in the Map building section shows/hides the wall lines together with the door/window lines and icon badges (shown by default at startup, session-only). While hidden, doors/windows cannot be clicked; choosing a wall tool shows the layer again. Lights and light tokens are not part of the wall layer and are always shown.

## 3.4 Map Switching

- Fast map/project switcher UI.
- If player view is frozen, switching/opening in DM view must not affect player output until unfreeze/swap action.
- Maps are switched from the **map browser** (3.15): double-clicking a map auto-saves the current map and opens the chosen one. A dimmed overlay with a spinner and "Loading <map>..." (or "Importing <file>..." for a single dd2vtt import, which opens the map afterwards; batch imports show none) covers the DM canvas while the switch runs (the spinner itself has no opaque box behind it: the dark theme rule for number spinners is scoped away from the progress indicator), so slow loads of large maps give visible feedback.
- **Freeze** snapshots the *entire* project for the player window (map, fog, lights, effects, camera) using a separate renderer/lighting engine, so players see the old map exactly as it was regardless of DM edits or map switches. Unfreezing adopts the currently open map and its staged viewport, crossfading only when the player-facing state changed (3.3).
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
- With the Select tool and one or more selected lights, **Alt + mouse wheel over the map canvas** increases/decreases each light's radius independently by one grid tile per notch (minimum one tile per light, no upper cap, preserving fractional tile radii). Mixed selections change only lights, preserving other objects and the selection. Each notch is one undo step for all changed lights, including persistent fog reveals, and updates illumination immediately without moving either camera. Reuse the brush wheel's DM-only cursor label and timeout to show `Light radius: 8.0 tiles` for one light or the resulting radius interval and light count for multiple lights. Ctrl + wheel retains player-zoom precedence; ignore radius changes during dragging/drawing or pointer tools. Without selected lights, Select still consumes Alt + wheel without zooming.
- **Add light** and **Remove light** are one-shot tools: arm the tool, then click the map to place a light at that spot, or click a specific light to remove it. After one successful click the tool returns to Select, so the next click doesn't place or remove another light. A Remove click that misses every light does nothing and the tool stays armed. Esc cancels.
- Each light has:
  - range/radius
  - intensity/falloff preset
  - optional flicker (torch-style)
- Player view shows resulting illumination only (not editor token glyphs).
- LOS and occlusion use imported dd2vtt wall data plus closed doors (open doors let light through).
- Right-click a light in the DM view for its menu: fog reveal mode, range (1-12 tiles), flicker preset (Off/Candle/Torch/Strong torch/Slow pulse), color preset (12 choices: Warm torch, Candle, Neutral, Moonlight, Arcane, Fire, Verdant, Sunbeam, Crimson, Frost, Rose, Toxic), "Blocked by walls", remove.
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
- **Additive multi-light blending**: overlapping lights accumulate brightness and blend their colors (weighted by each light's contribution in linear light) instead of only the brightest light winning per pixel, so e.g. two overlapping torches are brighter where they overlap and a red and a blue light mix toward purple. Total brightness per pixel is clamped to 1.0 (no over-exposure); ambient ("dark") contribution still blends in using the same weighted average as before. Rasterization stays a single parallel pass (sum brightness and weighted RGB per light instead of a max-compare), so performance is unchanged.
- **Falloff curve**: a light stays at full brightness through the inner half of its range (bright light, matching the usual D&D bright/dim light radius split), then dims through the outer half with a physically inspired, windowed inverse-square-style curve (fast initial drop-off that levels out, smoothly reaching zero at the edge) instead of a generic cubic ease, for a more realistic falloff without shrinking the bright-light radius.
- **Light tint** slider (Lighting section): strength of the light colour tint over lit areas (0-30%, default 5%). Saved per map (in the project file, `lighting.lightTint`), so different maps can use different strengths; updates live while dragging. New maps start at the default.
- **Bright core** slider (Lighting section): strength of a brighter hot-spot at the center of every enabled light (0-50%, default 15%). Unlike the normal light overlay (which only clears darkness and lightly tints, see above), the bright core actually brightens the map pixels beneath it using additive ("glow") compositing, so the map's own texture/detail stays visible but lit up, like the baked-in bright highlights some map art already has at lamp/torch positions, instead of a flat opaque circle painted over the art. Implementation: each enabled light draws a small radial-gradient highlight (light's own colour, full strength at its center fading to transparent at `lighting.brightCore.radius` fraction of its range, default 30%) onto a dedicated canvas layer stacked directly above the base map canvas (and below the walls/overlays/tokens layer and fog), with that layer's blend mode set to additive (`ADD`) so it brightens the map underneath instead of covering it; not occluded by walls (the highlight is small and sits right at the light's own position). Saved per map (in the project file, `lighting.brightCore`) like the light tint slider; off (0%) costs nothing extra to render.

## 3.7 Time-of-Day Lighting

- 4 presets: Day, Dawn, Dusk, Night (selector in the DM overlay, undoable).
- Preset affects ambient darkness + tint globally; Day = no darkness (dd2vtt maps have baked lighting).
- DM view shows darkness at reduced strength so the DM can still read the map.
- **Ambient brightness** slider (Lighting section, -50% to +100%, default 0%): adjusts the ambient darkness of the *current* preset for *this map* (effective darkness = preset darkness x (1 - brightness)). Stored per preset in the project (`lighting.ambientBrightness`, keyed by preset name, 0 entries omitted), so e.g. Night can be brightened on one map only. Updates live while dragging; one undo step per release; double-click resets. Disabled at Day (no darkness).
- **Ambient darkness/tint via multiply blend** instead of a flat colour-over-alpha overlay: previously the time-of-day darkness/tint (and the "clearing" of darkness under lights) was painted as one solid-colour layer blended with normal (`SRC_OVER`) alpha directly onto the same canvas as walls/overlays, which visibly washed the whole map toward one flat colour at higher darkness (most noticeable at Dawn 35% and Dusk 55%). Now the ambient darkness/tint is rendered as its own opaque image on a dedicated canvas layer (stacked directly above the bright-core layer and below the base map's own walls/overlays/light-glow canvas) whose node blend mode is `MULTIPLY`: each pixel's colour is `lerp(white, presetColor, darknessHere)`, and multiplying the map underneath by that colour darkens/tints it in proportion to the map's *own* brightness and detail instead of overwriting it with a flat colour, so texture and contrast stay visible even in heavily darkened areas. Per-light colour highlighting (the "light tint" glow in lit areas) stays a separate, normal alpha-blended overlay on the existing lighting canvas, now computed purely from each light's own colour (no longer averaged together with the ambient colour, since ambient darkening is handled entirely by the new multiply layer). Both layers share the same underlying per-pixel "how lit is this point" computation/cache (rasterised once per frame, reused by both outputs) so there is no extra rasterization cost; a no-op (and free) at Day (darkness 0).

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
- Keep the existing shortcuts, command semantics and "Undid/Redid: <action>" status messages. After undo/redo, briefly outline affected visible content in the DM viewport for approximately one second: resulting bounds for moves/resizes, restored objects, former locations for removals, and the specific portal for door/window changes. Fog outlines follow the changed cells, not an unnecessarily map-sized rectangle.
- Global actions without a meaningful local target (e.g. time of day or whole-map rotation) show the status message only. Never pan, zoom or switch maps for feedback. A new undo/redo replaces the previous highlight, and map/level switching clears it.
- Feedback is transient DM UI only: it does not change selection, intercept input, edit project data or history, persist in saves/thumbnails, or reach live/frozen player output.

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
  - **Multiple selected maps:** Add tags (3.34), Duplicate and Delete apply to all selected maps, including multilevel maps; the Delete key uses the same batch deletion with one confirmation listing the maps. Make multilevel map is enabled only when at least two ordinary maps are selected and uses the entire selection; it is disabled for a single map or selections containing multilevel maps. All other context-menu actions are disabled with multiple entries selected. Enter and F2 also require a single selection. Right-clicking a selected map preserves the selection; right-clicking an unselected entry selects only that entry. Batch map actions are disabled for mixed map/folder selections.
  - **Delete** always asks for confirmation (folders state how many maps they contain).
  - **Copy** creates a sibling named `<Name> (Copy)`, `<Name> (Copy 2)`, ... (a copy of a copy re-uses the base name).
  - **Rename** renames everything required on disk: the package folder and the `.dmmap` file (asset paths are relative, so they stay valid).
  - Names are validated (no empty names, no `<>:"/\|?*`, no reserved Windows names, no duplicates in the same folder; case-only renames work).
- File operations that touch the open map save it first and then re-point the app to the new location (deleting the open map switches to an empty new map).
- **Saving a new map** and **importing a dd2vtt** use a custom in-app **location dialog** (no system file browser): it shows only the folder tree of the library, allows creating folders, and asks for the map name (import pre-fills the dd2vtt name). The system file browser is only used to pick the external dd2vtt / image source files.
- Leaving an unsaved new map (New/open another map) asks Save / Discard / Cancel.

## 3.16 DM Controls Overlay & Visual Design

- All non-library DM controls live in a **compact overlay panel on the right** side of the DM view, organised in **collapsible sections** (collapsed state is remembered): Tools, Fog of war, Lighting, Effects, Map building, Player view. The whole panel can be collapsed to its header and scrolls if the window is small.
- **Individual control visibility:** Settings > DM controls tabs also provides a collapsible group for each tab with show/hide choices for every individual control (including light presets, both brush-size sliders and individual calibration fields). Store hidden control ids globally in `ui.controls.hidden`, empty by default. Apply immediately, on startup and after hand-editing the settings file. Hide associated labels/value readouts, empty rows and unused separators without changing control values, active tools, project data or keyboard shortcuts. Whole-tab visibility is independent; individual choices survive hiding and restoring a tab.
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
- The hover tint is green for reveal (amber for an unenclosed region) and red while Shift is held to hide. Pressing or releasing Shift updates the preview without moving the cursor; losing window focus clears the modifier state. This is DM-only feedback and does not change fog until clicking.
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
- **Closing the handout window** stops showing the handout but keeps its images in order for reopening during the same application session. Reopening does not automatically show them to players; selection and selected-only display are reset. Images are removed only through Delete / Remove all images or when the application exits.
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
  - Split the query into whitespace-separated words, matching case-insensitive substrings of map names or tags (3.34) and folder names. Every word must match; different words may match different fields on one map. Filter live while typing without changing the search bar.
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
- **Tuning values** (v3.1): every behaviour/look constant a DM may want to change is a setting, defined once in `dmmt.service.Tuning` (key, default, range, comment, restart flag) and read by the code through `Tuning.<NAME>.get()`; no duplicate literals. Groups: window sizes, player zoom/calibration limits, DM zoom, mouse pick tolerances and debounce, ping and laser pointer, undo depth (default 10 steps), brush/effect/text defaults and limits, light presets and the light context-menu choices, light defaults, time-of-day colours, light map/shadow quality, fog fade (reveal/hide/light fade times, max step, easing `linear|smooth`, DM fog opacity, sharpness/softness limits), weather (default intensity, particles, colour, opacity per type), performance-mode values (incl. optional fog fade), frame-rate cap, image/texture caches, map library folder (default relative path `dmmap-projects`, resolved beside `dmmap-audio` relative to the settings file), auto-save choices, tooltip timing, wall/grid look, door/window state colours (`ui.doorOpenColor`, `ui.doorClosedColor`, `ui.windowOpenColor`, `ui.windowClosedColor`).
- `AppSettings` loads `Tuning` whenever it (re)reads the file, so the defaults are active from startup and hand edits apply when the window regains focus (values marked *restart* shape controls or windows and need a restart). On startup any missing known key is written to the file with its default.
- Every entry is documented in `docs/SETTINGS.md` (searchable keywords per entry); `SettingsDocumentationTest` fails when a settings key is not documented.
- **Settings window (v3.8):** a cog button in the DM controls header and in the status bar opens a non-modal `Settings` window (`dmmt.ui.SettingsWindow`) with every setting that has no control in the DM controls (all `Tuning` values, the effect texture defaults, `ui.recentMaps.max`). Settings are grouped by category (list on the left), explained by a description and tooltip (default, key, range), edited with a type-specific control (checkbox, colour picker, choice list, validated text/number field with clamping), individually resettable, and **text-searchable** (all words must match category, key, name or description; results are grouped by category). Changes are written to the settings file and applied at once through `AppSettings.applyEdit` (entries marked *restart required* only apply after a restart). `AppSettings.editableSettings()` describes the entries; `Tuning.Setting` carries kind, min, max and options.
- **API URL context-menu visibility:** `api.showUrlOptions` defaults to `false` and controls whether API/key-image URL actions on DM controls and map-switch URL actions in the map library are shown. This is a live display-only toggle: hiding the options does not disable or change any API endpoint.
- **Grouping (v3.9):** categories are ordered so related ones are adjacent (editing/effects next to effect textures, fog, lights, time of day, weather together). `AppSettings.SettingInfo` carries an optional `group` path (nested with `/`, e.g. `Fire/Layer 1`, `Right-click menu/Colour choices`): a collapsible block, collapsed by default, for every subtopic with two or more settings (player zoom, calibration limits, pick distances, map image cache, tooltips, per time of day / weather type / light preset / effect texture, ...); there are no tabs. Numbers use spinners with the setting's range. Search also matches hard-coded keywords from `src/main/resources/dmmt/settings-keywords.properties` (copied from the *Keywords* lines of docs/SETTINGS.md, generic keys use `*`); keep it in sync when adding settings (`AppSettingsTest` requires keywords for every editable setting). The light right-click menu lists became fixed individual settings (`lightMenu.range1-10`, `lightMenu.color.<id>`, `lightMenu.flicker.<id>.depth/speed`, `lightMenu.brightness.<id>`) so no entries can be added that the code does not know; the old list keys are dropped at startup (`Tuning.lightMenu*()`).
- **Hidden DM controls tabs (v3.8):** the first settings category *DM controls tabs* lets the DM show or hide each tab (Tools, Fog of war, Lighting, Weather, Effects, Text, Map building, Player view, Performance) of the DM controls overlay. Stored as `ui.sections.hidden` (comma separated ids, empty = all shown), applied live and when the settings file is edited by hand; hidden tabs keep their data and settings.

## 3.26 Batch Import of Maps (implemented)

- The library's **Import** actions (toolbar button, folder context menu "Import map here…") open the system file chooser with **multi-selection** enabled (`.dd2vtt`, `.uvtt`). A new **Import folder…** action (toolbar button + folder context menu "Import folder here…") opens the system **folder chooser** and imports every suitable map found in that folder **and its sub-folders**. "Suitable" = `.dd2vtt` / `.uvtt` files (case-insensitive). The chosen folder's sub-folder structure is **re-created in the library** under the target folder (a sub-folder is only created once a suitable map is actually imported from it, so sub-folders with no maps never appear). A plain multi-file selection (not a folder) always imports flat into the target folder, even if the picked files came from different folders.
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
- A **Previous map** button beside the Recent maps heading opens the most recently opened existing map other than the current map. Repeated presses switch back and forth between the last two maps, using the normal save/load and player-freeze behavior. It is disabled when no previous map is available. Keep the previous map available during the session even when `ui.recentMaps.max` is 1; after restarting, use the persisted recent history.
- Register the button as `maps.previous` in control discovery, including disabled state, key artwork and right-click command/image URL copying. The Stream Deck plugin discovers it under **DM Control > Maps**. Remote switching rejects unsaved new-map content with HTTP `409` instead of opening a save dialog, and retains the existing busy/modal guards.
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
  - *Make multilevel map*: select at least two ordinary maps and choose "Make multilevel map…" to merge all selected maps. The action is disabled for a single map or selections containing multilevel maps; drag & drop can also merge maps (see below). The level dialog shows the selected maps in natural name order, then the location dialog asks for name/folder (default: the shared map name — with a suffix such as " (multilevel)" if that name is taken — and the first map's folder). The original maps are **moved** into the multilevel map (they disappear as separate maps; fog, lights etc. are kept). If the open map is converted, it is saved first and the new multilevel map is opened on that level. If a change fails, moved maps are put back and nothing is deleted (loose originals and removed levels are only deleted after the new manifest was saved).
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
  - **Numbered files:** `<shared part> <number>` or `<shared part> <number> <room label>` (label without digits), e.g. `haus_00 … haus_03` or `turm_upper_levels_02_barracks … turm_upper_levels_10`. The number may also be parenthesized at the end, `<shared part> (<number>)` or `<shared part> (<number>) <room label>`, e.g. `kings castle (1) … kings castle (3)` — the pattern left by renaming several files to the same name at once. Files with the same shared part form a group if there are at least two and all level numbers are distinct (so `Market 1 day` / `Market 1 night` variants stay separate). No fuzzy similarity — names like `Goblin Cave` / `Goblin Camp` are never grouped.
  - **Base file:** a file without a number joins a group as its lowest level only when its entire filename stem matches the group's shared part (case-insensitive, with underscores read as spaces). `schilftritt_tor` joins `schilftritt_tor_00 … schilftritt_tor_03`, but `schilftritt` stays separate; it may join `schilftritt_00 … schilftritt_03`. A shorter name must never match just the start of a longer shared part.
  - **Order and names:** base file first, then by level number. The multilevel map is named after the base file, otherwise after the shared part. Level names: `Level <number>` plus ` – <label>` when present; the base file gets the number below the lowest (`Level 01` before `Level 02`), or `Base` if the lowest is 0.
- **Re-targeting the open map after a level change:** every change returns the relocations of all moved map files (library map → level, level → other multilevel map, level → separate map, last level → collapsed map). If the open map/level was moved, the app reopens it at its new place (as a level or a single map); if the open level was deleted, the nearest remaining level is opened; if nothing is left, an empty new map is shown; otherwise only the level list is refreshed.

## 3.33 Duplicate Import Detection (implemented)

- When one or more `.dd2vtt`/`.uvtt` files are picked for import (single map, multi-file/folder batch import, or the
  files chosen to build/extend a multilevel map), the app compares each file's name against the **original file
  name** stored in every map already in the library. Duplicate detection is always by original file name
  (extension stripped, case-insensitive) - **never** by the map's current name in the library, since maps can be
  renamed after import. For multilevel maps every level's own original file name is checked individually.
- Each imported (ordinary) `.dmmap` stores `map.originalFileName` (the source file name, extension stripped) the
  first time it is imported from a dd2vtt/uvtt file; custom maps (no source file) leave it `null`. Multilevel levels
  already store the same information per level (`Level.originalName`, 3.32.1). **Maps/levels saved before this field
  existed have no original file name and are skipped entirely during duplicate detection** - they are never matched
  as a duplicate and never cause a false positive, regardless of their current map name.
- If any picked file matches, the app follows the `import.duplicateBehavior` setting (default `ask`):
  - `ask`: a dialog (`DuplicateMapsDialog`, same dark theme as the rest of the app) lists only the matching files,
    each with its own checkbox (all ticked by default), plus **Select all** / **Select none** buttons for quickly
    toggling every row at once. Three actions are offered: **Cancel** (aborts the whole import, nothing is
    imported), **Don't import duplicates** (skips every listed duplicate regardless of the checkboxes), and
    **Import selected** (imports the ticked duplicates, skips the unticked ones).
  - `always`: every duplicate is imported without asking.
  - `never`: every duplicate is skipped without asking.
  - Files that are not duplicates are never shown in the dialog and are always imported, regardless of the setting.
- Implementation: `dmmt.service.DuplicateCheckService` (scans the library - including multilevel manifests - for
  existing original file names; unit-tested), `dmmt.ui.DuplicateMapsDialog`, `Tuning.IMPORT_DUPLICATE_BEHAVIOR`
  (`import.duplicateBehavior`, settings window + `docs/SETTINGS.md`). Wired into `handleImportDd2vtt`
  (single + multi-file chooser), `handleImportDd2vttFolder` (folder import) and `pickDd2vttFiles` (the level file
  picker used both for "Import multilevel map" and "Add dd2vtt files…" in the level dialog).

## 3.34 Map Tags (implemented)

- Store tags in each ordinary map's `map.tags` array, including individual multilevel levels. Older maps without tags start with an empty list. Trim tag names, normalize them to uppercase using `Locale.ROOT`, and keep them unique case-insensitively. Existing saved tags, suggestions, chips and the tag input are always presented in uppercase; casing conversion must not autocomplete names.
- Right-click a single map or multilevel map to open **Manage tags...**, using the existing dark dialog theme. Show its tags with easy removal, a tag input and an Add action. Several selected maps support **Add tags...** only; removal is not available.
- While typing, suggest existing tag names from all other library maps and levels in a dark overlay directly below the tag field, without resizing the dialog. Show at most five suggestions, with all rows fully visible and no unnecessary vertical scrolling; size the overlay including its themed padding and border. Up/Down navigates, Enter accepts the selected suggestion, and clicking accepts a row; acceptance fills the field without adding the tag. Escape dismisses the overlay before closing the dialog. Hide on focus loss, empty input or dialog closure. Never automatically complete the input.
- On dd2vtt/uvtt import (single, batch, automatic grouping and new multilevel levels), apply known library tags whose full name is contained in the original filename, case-insensitively.
- Rank suggestions by fit before selecting the best five: exact matches first, then prefixes, then other substring matches. Within a category prefer an earlier match position, fewer extra characters, then alphabetical order. Re-rank as the input changes, case-insensitively.
- A multilevel map displays the case-insensitively deduplicated union of its levels' tags. Adding a tag applies it to every level; deleting one removes it from every level. Combining maps retains each level's own tags; dissolving or extracting restores those tags including later whole-map changes. No per-level tag editing in Manage levels.
- The existing search bar checks each whitespace-separated search word against the map name or any tag, using case-insensitive partial matches and AND between words. For example, `tav haven` can match tag `tavern` and map name `Haven`. Preserve matching-folder visibility and ancestor folders.
- Tag edits persist immediately through the library-operation workflow, preserving unsaved open-map changes and synchronizing the open level so later saves cannot overwrite tag edits. No new global settings.

## 3.35 Audio: Ambient Music and Sound Effects (implemented)

Optional, fully self-contained audio subsystem so the DM can run ambient music and layered sound effects from the
same application. It must never be in the way of DMs who do not use it, and must not noticeably cost frame rate.

### 3.35.1 Library and storage

- A single, global audio library (not per map, not stored in `.dmmap`), kept in an app-managed folder next to the
  map library: `audio.folder` (default `dmmap-audio`, relative paths start next to the settings file, restart
  required). Layout: `<folder>/library.json` (index), `<folder>/files/` (the audio files),
  `<folder>/peaks/` (cached waveform peaks, derived data that may be deleted at any time).
- Supported formats: **MP3 and WAV**. Other files are rejected with a clear message. The extension alone is not
  trusted: an MP3 is only accepted when its MPEG frames really cover at least half of the file, so files that merely
  carry an `.mp3` name (damaged downloads, or the encrypted library formats some other audio tools use for their
  internal storage) are refused at import instead of ending up as unplayable tracks of a few seconds. WAV must be
  uncompressed PCM.
- Importing **copies** the picked files into `files/` under a sanitized, unique file name, so the library keeps
  working when the original (USB stick, download folder) is gone. The display name is independent of the file name
  and defaults to the original file name without extension.
- Every track stores: `id` (UUID), `name`, `file` (name inside `files/`), `kind` (`music` or `effect`),
  `categoryId` (music only), `durationMs`, `originalFileName`, `sourceTrackId` + `sourceStartMs`/`sourceEndMs`
  (only for clips cut out of a long file, purely informational) and `addedAt`.
- Library operations: import (multi-file and whole folder), rename, delete (removes the file from `files/`), move a
  music track to another category, change a track's kind. The index is written atomically (temp file + move) exactly
  like the settings file, and a corrupt/missing index starts an empty library instead of failing the app.
- Importing copies files on a background thread and always reports what it is doing: a progress bar plus a status
  line ("Importing x of y - <file name>") in the library window, the toolbar disabled while the import runs, a
  closing summary ("12 files imported") and an error dialog listing the files that failed. Importing a folder of a
  hundred files therefore never freezes the window.
- **Folder import walks subfolders** and uses the folder structure as categories (music only):
  - a file lying directly in the picked folder goes into the category that is currently selected, exactly like a
    file picked by hand;
  - a file lying in a subfolder goes into a category named after its **direct parent folder**, no matter how deep
    it is nested (`Fantasy/Combat/Boss/track.mp3` picked at `Fantasy` lands in "Boss");
  - the category is matched **case-insensitively** against the existing ones ("combat" finds "Combat") and, when
    nothing matches, created with the folder's **exact** spelling and the default colour/icon;
  - when importing into the sound effects view there are no categories, so the folder names are ignored and every
    file found becomes a sound effect.
  - Hidden folders and files are skipped, symlinks are not followed, and unsupported files are ignored silently
    instead of being reported as failures.

### 3.35.2 Kinds and categories

- Two kinds of audio: **music** (plays one track at a time inside a category) and **sound effects** (seamless loops
  layered on top of the music, e.g. wind, rain, birds, waves).
- Music is organised in **categories** (Adventure, Combat, Tavern, ...). Create, rename and delete categories; a
  deleted category's tracks move to "Uncategorised" instead of being lost (confirmation dialog states this).
  "Uncategorised" is **hidden from the overlay by default** (it is a leftovers bin, not an ambience); existing
  libraries are migrated once (`schemaVersion` 1 -> 2) and it can be shown again like any other category.
- A category has a **colour** (`#RRGGBB`) and an **icon**. The icon is always tinted with the category colour, in
  the picker, the library window and the audio overlay.
- **Sound effects have a colour and an icon too.** They are a flat list (no categories) and searchable by name.
- The **icon picker** offers the **complete Material Design icon set** (all ~7400 icons of the bundled pack), not a
  short curated list, because a DM names categories anything from "Dragon" to "Submarine". It opens on a small
  set of **suggested** icons (adventure themes for categories, weather/nature for sound effects) and has a
  **search field** that filters the whole set by icon name, so typing `tree` finds every tree icon. Results are
  capped (the grid shows the first 300 matches plus a "refine your search" hint) so the dialog stays instant.
- Categories and sound effects can be **hidden from the audio overlay** ("Hide in overlay" in the right-click menu
  of the library window, with "Show in overlay" to bring them back). Hiding is purely cosmetic: a hidden entry
  still exists, still plays, still appears in the library window and keeps its local API endpoint - it just does
  not take up a slot in the overlay. Typical use: a "Christmas" category that should not clutter the ring in
  July. The library window shows hidden entries **greyed out** (dimmed name and icon) instead of adding a text
  marker, so they stay readable but are obviously inactive.
- A category that contains **no music at all** is left out of the overlay as well, even when it is not hidden: an
  empty ring button cannot be played and would only take up a slot. It reappears as soon as it has a track.
- Tracks move between categories **and between music and sound effects** by drag & drop in the library window and
  via a context menu ("Move to >", "Use as sound effect", "Use as music"), with multi-selection support. Dropping
  tracks on the "Sound effects" entry turns them into sound effects, dropping sound effects on a category turns
  them back into music of that category.

### 3.35.3 Cutting clips out of long files

- A long recording (one or two hours, e.g. a YouTube ambience mix) can be imported and split into standalone songs
  in a dedicated **Cut clips** window.
- The window shows a **waveform** of the whole file (min/max peaks per pixel column) so silent gaps between songs
  are immediately visible, plus a zoomable detail view, a time ruler and a movable playhead. Clicking the waveform
  seeks, space toggles play/pause, so any point of the file can be auditioned at any time.
- Peaks are computed once in a background thread (WAV via `javax.sound.sampled`, MP3 via a pure-Java decoder) with
  a progress indicator, and cached in `peaks/<trackId>.peaks` so reopening is instant.
- A selection (drag on the waveform, or exact start/end time fields with millisecond precision) defines a clip.
  **Auto-detect tracks** proposes one selection per detected song by scanning for silence longer than
  `audio.cut.minSilenceSeconds` below `audio.cut.silenceDb`; proposals can be edited or deleted before saving.
- Playback **loops what is shown** (toggle "Loop", on by default): the selection when there is one, otherwise the
  visible part of the waveform, so zooming into a song is enough to audition it over and over. Reaching the end of
  the looped range jumps back to its start without stopping; playing from before the range is allowed and simply
  runs into the loop. With the toggle off, playback stops at the end of the selection as before.
- **Create clip** writes a **real, standalone file** into the library (the user's explicit choice; a clip keeps
  working if the source file is later deleted):
  - **MP3:** frame-exact copy of the MP3 frames inside the range (no re-encode, no quality loss, very fast);
    the range snaps to frame boundaries (max ~26 ms) and an ID3v2 tag of the source is not copied. The LAME/Xing
    (`Xing`/`Info`/`VBRI`) header frame of the source is metadata, not audio: it is excluded from the frame index
    and therefore never copied into a clip, because it describes the length and seek table of the *source* file and
    would otherwise make players treat a two-minute clip as a three-hour one and seek to the wrong positions.
  - **WAV:** PCM sample range copied into a new WAV file with the same format.
  - The new track is added to the chosen kind/category with an editable name and stays independent of the source.
- The source file itself is never modified. It may be deleted afterwards ("Delete source file" offered once all
  clips are saved).
- Cutting runs on a background thread with visible feedback: the header progress bar and status line report
  "Creating clip x of y - <name>" and end with the number of clips added (or a failure list). The clip buttons and
  the detected-tracks list are disabled while clips are being written so the same clip cannot be created twice, and
  closing the window cancels the pending work.

### 3.35.4 Playback

- **Music channel:** exactly one category plays at a time. Its tracks play in shuffled order
  (`audio.shuffle`, default on; off = library order) and are faded into each other over `audio.crossfadeSeconds`
  (default 4 s, 0 = hard cut). The channel loops the category endlessly, never replaying the same track twice in a
  row unless the category has a single track. Controls: play/pause, previous, next, stop (fades out).
- **Effects channel:** any number of sound effects (capped by `audio.maxEffects`, default 32) play as **seamless
  loops** at the same time. Starting and stopping an effect fades it in/out over
  `audio.effectFadeSeconds` so it never clicks.
- **Volumes:** master, music and effects volumes are independent, persisted in the settings file and applied as
  `master x channel` (a logarithmic/perceptual curve, `volume^2.2`, so sliders feel linear). Pausing is per channel:
  pausing effects pauses **all** currently playing effects at once and resumes them together. The play/pause button
  in the status bar is the "everything" button instead: it pauses music **and** all running effects together, and
  resumes both.
- **Mute:** the mute button in the overlay fades everything to silence over `audio.panicFadeSeconds` and keeps the
  current playlist position; pressing it again fades back in. There is no global mute hotkey.
- Audio is DM-side only: it is never sent to the player view and is independent of freeze, maps and projects.
  Switching, saving or closing a map never interrupts playback.

### 3.35.6 UI

Audio is deliberately **not** part of the DM controls sidebar: it is not a map tool, and a DM who never plays music
should not scroll past it. It lives in two places instead - a small transport group in the status bar, and a
full-screen overlay that is opened from there.

- **Status bar group** (bottom right of the DM window, right-aligned, only present when `audio.enabled`):
  previous, play/pause, next, and the **Audio** button that opens the overlay. Play/pause here is the "everything"
  button: it pauses and resumes music and all running sound effects together (3.35.4). The button shows the colour
  and icon of the running category, so the status bar doubles as an "what is playing" indicator; a tooltip names
  the current track. The transport buttons are disabled while no category is playing.
- **Audio overlay** (3.35.6): a translucent panel drawn **inside** the main window on top of the map, not a second
  window. It fades in over `audio.overlayFadeSeconds`, toggles with the `M` key, closes with `Escape`, with the
  Audio button, or with a click on the dimmed background, and never blocks the map while it is closed.
  - Layout follows the two-ring idea: a **left ring of round category buttons** and a **right ring of round sound
    effect buttons**, each ring laid out on a circle and growing outwards into further rings when there are more
    entries than fit on one circle. The buttons are **small and icon-only** so many entries fit on a ring: the
    entry's icon in **its own colour**, the name only as a tooltip. The **ring around the button is not coloured**
    per entry - it uses the application's accent colour for every button, so "which ones are on" reads at a glance
    (filled accent ring plus glow when the category plays / the effect runs) while the colours stay a property of
    the icons.
  - The centre of the left ring holds the music transport (previous, play/pause, next, stop), the **name of the
    active category**, the current track and elapsed/total time; the centre of the right ring holds the same-looking
    pause/resume button for all effects and the names of the running effects. Both centres use identical button
    styling so the two halves of the overlay look like one control.
  - Below the rings: master, music and effects volume sliders with percentage readouts, the mute button and the
    button that opens the audio library window.
  - Only entries that are **not hidden** (3.35.2) appear in the rings; an empty library shows a hint that links to
    the library window.
  - Everything is keyboard reachable, and the overlay repaints only while it is open, so a closed overlay costs
    nothing.
- **Local control API:** the status bar group registers `audio.previous`, `audio.play`, `audio.next` and
  `audio.overlay`; the overlay registers `audio.musicPlay` (the play/pause button in the centre of the music ring; music only, unlike `audio.play`), `audio.stop`, `audio.mute`, `audio.library`,
  `audio.effectsPause`, `audio.masterVolume`, `audio.musicVolume` and `audio.effectsVolume`. In addition **every
  category and every sound effect gets its own toggle endpoint**, registered dynamically whenever the library
  changes: `audio.category.<id>` plays that category (or stops it when it is already playing) and
  `audio.effect.<id>` switches that effect on or off. The id is the library id of the entry (a UUID), so an
  endpoint keeps working when the entry is **renamed** and never collides with another entry; the readable name is
  in the `label` field of `GET /api/controls`. Hidden entries keep their endpoints. Because the audio
  endpoints are (re-)registered after the library changed, they get the **same right-click menu as every other
  control**: "Copy API URL" *and* "Copy key image URL"; re-registering never duplicates those menu entries. A key
  image exported for a category or a sound effect is drawn in **that entry's colour** instead of plain white (very
  dark colours are lightened so the glyph stays readable on the dark key background), so a stream deck full of
  audio buttons looks like the overlay.
- **Audio library window** (own dark-themed window, like the handout/settings windows; it and every audio dialog
  carry the application icon in their title bar): the left side is split into two clearly separated sections - a
  **"Music categories"** list (add/rename/delete, colour and icon editing, drag & drop target) and, below a
  separator, a single **"Sound effects"** entry under its own heading, so the two kinds never look alike. The
  right side shows the track table of the selected category or of the sound effects (name, duration, source), with
  import, rename, delete, change kind, move, search and the **Cut clips** action. The right-click menu of a
  category and of a sound effect offers **"Choose colour..."**, **"Choose icon..."** and **Hide in overlay** /
  **Show in overlay**.
- The whole feature can be switched off with `audio.enabled = false`: no audio is loaded, the status bar group and
  the overlay disappear together with their API endpoints, and nothing audio-related runs.

### 3.35.7 Architecture and performance

- Package `dmmt.audio`: `AudioKind`, `AudioCategory`, `AudioTrack`, `AudioLibrary` (model), `AudioLibraryService`
  (storage/index), `AudioFormats` (format detection + duration), `Mp3FrameIndex` (MP3 frame parsing),
  `AudioClipCutter` (MP3/WAV cutting), `WaveformPeaks` (peak extraction + silence detection + cache),
  `AudioEngine` (playlists, crossfade, channels, volumes) and `AudioOutput`/`JavaFxAudioOutput` (the thin JavaFX
  Media layer behind an interface, so the engine logic is unit-testable without sound hardware).
- Decoding, peak extraction and all file IO happen on background threads; the JavaFX thread only updates labels.
  Playback itself uses the platform's native media pipeline (javafx-media), so mixing several streams costs
  essentially no frame time; the render loop is never woken up by audio. The now-playing readout updates at most
  four times per second, and only while the section is visible.
- New dependencies: `org.openjfx:javafx-media` (playback) and `com.googlecode.soundlibs:jlayer` (pure-Java MP3
  decoding for waveforms; MP3 frame parsing for cutting is implemented in-house).

## 3.36 Stream Deck Plugin (implemented)

A companion Elgato Stream Deck plugin drives the DM view through the local control API (see "Local DM Control
API"), so a DM can run lighting, fog, the player view and especially the audio ambience from hardware keys. The
plugin lives in `streamdeck-plugin/` inside this repository and is distributed as a `.streamDeckPlugin` file; the
application itself never depends on it and never talks to the Stream Deck.

### 3.36.1 Application-side additions

- All fixed API control names are mapped by stable control id in `src/main/resources/dmmt/api/control-names.properties`, loaded by `dmmt.api.ControlNames`. API discovery and command responses use these short names independently of UI tooltips, accessibility descriptions and sidebar settings labels. Edit this single file and rebuild/restart to change names without changing endpoints. Dynamic audio category/effect ids retain their live library names unless explicitly mapped; unknown ids fall back to control text, accessible text, then id. No new application settings.
- Tests use an independent `src/test/resources/dmmt/api/control-names.properties` fixture rather than asserting editable production names. URL-copy menu tests explicitly enable URL options; the application default remains hidden.
- Fixed control tooltips are mapped separately in `src/main/resources/dmmt/api/control-tooltips.properties`, keyed by the same stable ids. This mapping controls app hover help and the API descriptor's `tooltip` field (including command responses and batched discovery). The plugin preserves `tooltip` when relaying discovery to the property inspector. The Stream Deck control picker displays `tooltip`, falling back to the stable control id if a description is unavailable; the short `label` is used only for key titles, never dropdown labels. Registered wrapped controls and repeated controls share the mapping, including later tooltip refreshes. Unmapped/dynamic controls retain live tooltip/accessibility text, then their display name. Tests use an independent tooltip fixture. Edit either bundled properties file and rebuild/restart; no new application settings.

- **Key images over HTTP.** Every actionable control answers
  `GET /api/controls/<section>/<name>/image`, returning a 144x144 PNG as a binary response
  (`image/png`). Numeric controls additionally accept `?operation=increment|decrement` for the badged variants.
  `image` is a reserved final path segment: no control id may end in `.image`. The image is rendered on demand on
  the JavaFX thread from the live control, so a music category or sound effect always exports its current colour
  and icon. Nothing is written to disk. The endpoint needs no window focus but does require the API to be enabled.
- Right-click on an actionable control offers **"Copy key image URL"** below the "Copy API URL" items, and the
  numeric controls offer increment/decrement variants. This is the URL the user pastes into a Stream Deck action.
  This replaces the earlier "Open key image" browser-page export, which is gone: the URL covers the same need
  without a disk cache or an OS file association.
- **Binary API responses.** `LocalApiServer` gains a `LocalApiServer.Binary(String contentType, byte[] body)`
  result type; handlers returning it bypass JSON serialization. Caching, security, Host and origin checks are
  unchanged, and image responses are cacheable (`Cache-Control: private, max-age=60`) unlike JSON commands.
- **Batched discovery.** `GET /api/controls?ids=a.b,c.d` returns only the listed controls, in the requested
  order, so a poller can watch exactly the ids it needs instead of describing every control each second. Unknown
  ids are skipped rather than failing, so deleting a music category degrades one key instead of the whole page;
  the caller detects the gap from the missing entry. At most 128 ids per request.
- **Nameable and cacheable discovery.** A control's `label` falls back to its own text and then to its accessible
  text before the raw id, so music categories and sound effects are discovered by name and a client can offer a
  pick list instead of UUIDs. Each descriptor also carries a short `image` fingerprint derived from exactly what
  determines the artwork (glyph plus colour), so a client can cache key images and refetch one only after the
  user re-icons or re-colours the entry.
- **Map identity in state.** `GET /api/state` additionally reports the open map's library UUID as `id` (empty
  when no library map is open), so a map key can highlight exactly its map instead of comparing display names.
  The UUID is resolved off the JavaFX thread and cached per open map file, so polling never rescans the library.

### 3.36.2 Actions

Actions are generic and predefined rather than one action per control: the user picks the control in the property
inspector or pastes a copied URL. Music categories and sound effects are addressed by their library id, which the
user supplies (usually by pasting a copied URL), because they are user data and change over time.

- **Control toggle** - any button/toggle control, chosen from a list of the known controls or pasted.
- **Control value** - slider/spinner control with a mode of set / increase / decrease and an amount.
- **Dropdown** - selects a dropdown option by zero-based index; highlighted while that option is selected.
- **Music category** - toggles one music category by id; highlighted while that category is playing.
- **Sound effect** - toggles one sound effect loop by id; highlighted while the loop runs.
- **Switch map** - switches to a map UUID with an optional zero-based level index; the picker shows each map's
  name only, regardless of whether it has multiple levels. The key is highlighted while that map (and level) is
  open. Library maps have no control in the application and therefore no key image endpoint, so
  a map key falls back to a **generic map artwork shipped with the plugin**, drawn in the same style as the
  control images (dark background, accent glyph) and composited with the same highlight states. A pasted key
  image URL still overrides it.

Every action shares the same connection fields (host, port) and an optional key image URL. Pasting any copied
URL - command URL, index URL, key image URL or map switch URL - fills in host, port and target automatically.

**Picking a control** is two-step: a **section** dropdown (audio, fog, lighting, player, ...) narrows the
control list, because the application exposes over a hundred controls and a single flat list is unusable. The
section list is derived from the control ids, so it needs no extra endpoint and stays correct as controls are
added. Music category and sound effect actions skip the section step; their list is already scoped.

**Key titles.** Every action can show a caption under its artwork, chosen per key: **nothing** (default, the
artwork alone), the **name** of the target, or - where one exists - its **value**. The name comes from the
`label` reported by discovery (so a music category shows "Combat" and a renamed category updates by itself),
from the map list for a map action, and the value is the dropdown choice or the current slider value. A key
whose target has gone missing shows a short problem caption regardless of this setting.

**Value amounts** default to the control's own `step` from discovery rather than to a hard-coded number, so an
increase/decrease key moves a volume slider by the same amount as the application's own UI without the user
having to know the control's units. An explicit amount overrides it.

### 3.36.3 Visual feedback and polling

- A key shows the control's artwork fetched from the key image endpoint, composited into a PNG at display time:
  **active** keys get a bright accent border, **inactive** keys are dimmed, **disabled** controls are dimmed
  further and greyed, and a control that is **unknown or unreachable** gets a red border and a short title. This
  makes "which music category is playing" readable at a glance: starting another category clears the previous
  key because the application reports only one category as active.
- The API's own responses are sufficient for this: category and sound effect endpoints are toggles and report
  `value` in discovery and in each command response. No extra state endpoint is required; only the batched
  discovery filter, the image endpoint and the map UUID in `/api/state` are added.
- **One shared poller per application address** in the plugin process, never one timer per key. It requests the
  union of the ids of all currently visible keys in a single `GET /api/controls?ids=...` call, plus at most one
  `GET /api/state` call when a map action is visible.
- Polling runs only while at least one key of that address is visible; the last key disappearing stops the timer
  entirely, so an idle Stream Deck page costs nothing.
- The interval is a user setting (default 1000 ms, minimum 250 ms). A request is never started while the previous
  one is still in flight, and a poll is skipped when nothing changed in the id set and the previous poll failed
  recently.
- On errors the poller backs off (1s, 2s, 5s, 10s, 20s cap) and returns to the normal interval on the first
  success, so a closed application or a disabled API does not produce a request storm.
- Pressing a key applies the state from the command response immediately and schedules one short follow-up poll
  (about 250 ms later) to pick up side effects such as another category stopping. Keys are only redrawn when
  their rendered appearance actually changes.
- Key images are fetched once per key configuration and cached in memory by URL, so polling transfers only JSON.
- A failed command flashes the Stream Deck's alert icon. The **success** checkmark is off by default and can be
  switched on globally: the key's own highlight is already the confirmation, and a checkmark covering the
  artwork on every press is noise. Stateless buttons are the only case that benefits, so it stays available.

## 4) Proposed `.dmmap` Structure (v1 Draft)

```json
{
  "schemaVersion": 1,
  "map": {
    "sourceType": "dd2vtt",
    "imagePath": "imports/catacombs/catacombs.webp",
    "originalFileName": "catacombs",
    "tags": ["DUNGEON", "UNDERGROUND"],
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

## Phase 10 - Audio: Ambient Music and Sound Effects

1. Audio library model + `AudioLibraryService` (storage, import copy, categories, tracks) with unit tests (3.35.1, 3.35.2).
2. Waveform peaks, silence detection and frame-exact MP3 / PCM WAV cutting with unit tests (3.35.3).
3. `AudioEngine` with playlist, crossfade, channels and volumes behind an `AudioOutput` interface, unit-tested without sound hardware (3.35.4).
4. Sidebar mini player, audio library window and cut-clips window; settings and local API ids (3.35.6).

## Phase 11 - Stream Deck Plugin

1. Serve control key images over the API (`/api/controls/.../image`) with binary responses and a "Copy key image URL" menu item (3.36.1).
2. Batched control discovery (`GET /api/controls?ids=...`) for cheap polling (3.36.1).
3. Stream Deck plugin in `streamdeck-plugin/`: generic actions, shared poller with backoff, PNG highlight composition and cached key images (3.36.2, 3.36.3).

## Local DM Control API

- Provide an opt-in HTTP API for every DM control, reachable from other devices on the local network and usable without window focus. Bind all IPv4 interfaces; copied control/map URLs and discovery URLs advertise an active LAN IPv4 address rather than localhost (loopback fallback only when no LAN address exists). No authentication or automatic firewall changes. Use only on trusted networks; do not expose the API to the internet. Commands run on the JavaFX thread through the same controls/actions as manual interaction, including selected-object updates, undo, persistence and player freeze semantics.
- API enabling/disabling and port changes apply live from Settings, and after external settings reload, without restart. Repeated settings callbacks leave an unchanged server running. A failed port change reports the error and preserves the existing listener.
- Live global setting `api.copyAddress` (`network` default, `local` alternative) selects LAN IP or `127.0.0.1` for copied control/map URLs and discovery URLs. This only changes generated URLs, not listener binding or LAN accessibility; use the running listener's port even after a failed port change.
- Each control has a stable endpoint. Buttons invoke their action; toggles invert current state with no parameter; value controls accept a URL-encoded `value`; sliders also accept `increment` or `decrement` amounts, bounded by their normal UI range. Dropdown values identify their displayed choices. Invalid commands return explicit HTTP errors.
- Dropdowns additionally accept `index` (zero-based UI option order), mutually exclusive with `value`. Discovery includes the current index; right-click offers copying an index URL using the current selection. Invalid/out-of-range indices fail without changing the selection.
- Every actionable DM control offers a right-click "Copy key image URL" action; the URL serves a square PNG using its tool icon over the API. Numeric controls additionally offer distinct increment/decrement images. Images are rendered on request from the live control, never cached on disk or saved in projects; failures are surfaced in the DM status bar. Use device-independent names in code, UI, storage and documentation.
- Right-click URL-copy actions for controls, key images and library maps are hidden by default and can be shown with the live `api.showUrlOptions` setting; API routes remain available either way.
- Key artwork uses meaningful control-specific icons; dropdowns without their own icon use their tab icon (weather, effects, player, levels), never a generic list or skin arrow. Remaining controls retain their own icon or use a fitting control/section fallback.
- Right-click each DM control to copy its command URL; value controls include their current value as an example. Sliders additionally provide increment/decrement URL examples.
- Maps have persisted UUIDs from import/creation; legacy maps receive a persisted ID when first indexed. Rename/move preserve IDs; copying creates new IDs. Multilevel packages have a map UUID and support switching by zero-based level index.
- Right-click library maps to copy the map-switch URL. Map switching uses the existing save/load and frozen-player workflow.
- Document configuration, endpoint discovery, URL encoding, slider operations, map/level commands and external control setup in README and settings documentation. Accept local-interface IP Host headers, preserve browser-origin protections, and surface startup/command failures.

## 8) Open Decisions (Track Here)

- ~~Exact tile-to-inch calibration UX.~~ Decided: manual screen-diagonal entry + test square (v1.1).
- ~~Whether to store fog as stroke history, bitmap mask, or hybrid representation.~~ Decided: bitmap mask (v0.8).
- Minimum supported GPU/OpenGL profile for JavaFX on older laptops.

## 9) Change Log

- **v3.13 (current):** Stream Deck plugin (3.36): control key images are served over the API
  (`GET /api/controls/<section>/<name>/image`, with `?operation=increment|decrement`), `LocalApiServer` supports
  binary responses, `GET /api/controls?ids=...` returns just the listed controls for cheap polling,
  `GET /api/state` also reports the open map's library UUID, discovery labels fall back to the control's own
  text or accessible text so music categories and sound effects are listed by name instead of by UUID,
  discovery reports an artwork fingerprint so cached key images are refetched after an icon or colour change,
  and right-click offers "Copy key image URL" in place of the removed "Open key image" browser export. The
  companion plugin in `streamdeck-plugin/` adds generic actions for toggles, values, dropdowns, music
  categories, sound effects and map switching, highlights active keys and uses a single shared poller per
  application address with error backoff.
- **v3.12.1:** Audio polish (3.35.2/3.35.6): the icon picker now offers the whole Material Design icon
  set with a search field, "Uncategorised" is hidden from the overlay by default (library `schemaVersion` 2) and
  empty categories are left out of the rings, the library window separates "Music categories" from "Sound effects",
  drag & drop converts tracks between music and sound effects, every audio window and dialog inherits the
  application icon, and categories and sound effects both have "Choose colour..." in their right-click menu.
  Key images exported for a category or sound effect are drawn in that entry's colour.
- **v3.12:** Audio subsystem (3.35): global audio library in an app-managed folder (MP3/WAV, imported by
  copy), music categories with colour and icon, sound effects as seamless loops, cutting standalone clips out of
  long recordings with a waveform view and silence auto-detection, a status bar transport group with a two-ring
  audio overlay (`M`) offering separate master/music/effects volumes, per-channel pause and a mute fade,
  shuffle + crossfade playback, and `audio.*` settings plus local
  control API ids for every audio control, category and sound effect. Added `javafx-media` and `jlayer` dependencies.
- **v3.11:** Duplicate import detection (3.33): importing dd2vtt/uvtt files (single, batch/folder, or the
  files used to build a multilevel map) now checks each file's original file name against the library (including
  per-level for multilevel maps) and, on a match, asks via `DuplicateMapsDialog` whether to still import it (per-file
  checkboxes, Select all/none, Cancel / Don't import duplicates / Import selected). Added `map.originalFileName` to
  the `.dmmap` format; older maps without it are never treated as duplicates. Added `import.duplicateBehavior`
  setting (`ask` default / `always` / `never`) to skip the dialog and apply a fixed behavior instead.
- **v3.10:** Batch import improvements (3.26): folder import now re-creates the imported folder's sub-folder structure in the library (a sub-folder only appears if it actually contains a suitable map; empty sub-folders are never created). Automatic multilevel grouping (3.32.1) also recognises the `Name (1)`, `Name (2)`, ... numbering left by renaming several files to the same name at once, with or without a trailing room label.
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
- **v3.8 (current):** Additive multi-light blending (3.6): overlapping lights sum brightness and blend color (weighted in linear light) instead of the brightest light winning per pixel; ambient darkness blends in the same way. Falloff curve: dim-light band now uses a windowed inverse-square-style curve instead of a cubic ease, while the inner bright-light half-radius stays full brightness. Bright core slider (3.6): an additive hot-spot per light on its own blend-mode canvas layer that brightens the map art at the light's center instead of covering it. Ambient time-of-day darkness/tint (3.7) now renders as an opaque multiply-blended image on its own canvas layer instead of a flat colour-over overlay, so Dawn/Dusk/Night darkening preserves the map's own detail/contrast instead of washing it toward one flat colour.
- **v3.7:** Migrated to Java 25 (LTS): release 25, JavaFX 25.0.4, Lombok 1.18.48, RichTextFX 0.11.7 (0.11.4 fails to load on JavaFX 22+), Ikonli 12.4.0, shade plugin 3.6.2; CI uses JDK 25; shaded jar manifest sets `Enable-Native-Access: ALL-UNNAMED` to silence JavaFX native-access warnings. `MultiLevelService` / `LevelListDialog` handle the sealed `Source` with exhaustive pattern `switch`es and record patterns, `getLast()` replaces `get(size() - 1)`, and the Windows app image and the documented jar launch use `-XX:+UseCompactObjectHeaders` (lower memory use).`n- **v3.6:** Multilevel map refinements (3.32.1): drag & drop merging in the library, move levels out / dissolve, collapse single-level maps, map-wide DM zoom, level previews in the switcher, drag & drop level dialog, automatic multilevel grouping on batch import.
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

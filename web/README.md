# Online Play web client

Browser client for the Online Play host (plan.md section 3.41). Vanilla TypeScript, Canvas 2D, no runtime dependencies.

```
npm install
npm test          # vitest unit tests (protocol, clock, bitset, vision, weather, shared vectors)
npm run build     # tsc --noEmit && vite build -> ../src/main/resources/web
npm run dev       # dev server; proxies /api, /a, /t and /ws to http://localhost:7080
```

The Java host serves the build output from the classpath `/web` (`/` and `/sheet/<actorId>` return `index.html`).
`src/vectors.test.ts` checks `../src/test/resources/online/vectors.json` (shared with the Java tests) when it exists.

Layout: `bitset.ts` (base64 + zlib bitset codec), `store.ts` (snapshot/delta), `clock.ts`, `vision.ts`, `weather.ts`,
`render.ts` (canvas), `net.ts` (WebSocket), `app.ts` (map UI), `sheet.ts` (mock sheet), `login.ts`.
`lighting.ts` mirrors desktop light-tint accumulation, falloff and indoor/daytime eligibility.
Tint uses the host's optional `lighting.tintDarknessFactor` to match the DM view without reducing player ambient darkness;
older hosts that omit it retain full-strength player tint. Overlapping lights share a single capped tint overlay.
Settings kept in localStorage: `dmmt.volume`, `dmmt.muted`, `dmmt.maxParticles` (default 1500).

The map uses floating left Navigate/Drawing/Measure tools and a collapsible right Chat/Actors/Settings rail.
Drawing commands carry map/level identity; the host assigns and enforces campaign/player ownership and publishes
the normal map overlays, including generated effect textures. Texture choices/defaults come from the host rather
than a separate browser list. Measurements are shared session-only annotations, replayed to late joiners and cleared
on map/level changes. Both features are advertised in `hello`, so older hosts keep their tools disabled.
Audio preferences and logout live in Settings; the roster reports host-measured round-trip ping.
Right-click without dragging switches to Select without dismissing flyouts; right-drag pans without changing
the active tool. Empty left-drag box-selects
owned drawings, controlled tokens and measurements for group movement. Drawing undo is browser-local,
bounded to 100 acknowledged edits per map and uses the existing authoritative ownership-checked commands.
Measurement delete crosses are drawn/picked at a constant screen size and shown only to the creator
(the desktop DM also gets them). Pen/brush cursors preview world-space stroke width.
Brush borders use a viewport-bounded union mask so textured and flat strokes have outlines without
internal seams at overlaps. The edge is cached independently of texture animation.

`tools.test.ts` covers ownership, geometry and grid-tile measurement labels. For real-browser UI regression,
run `npm run dev`, then open `http://localhost:5173/checks/player-layout.html` (adjust the port if necessary).
It exercises actual layout controls and canvas gestures with local test state, without a running Java host.
The page reports PASS/FAIL; it is not included in the production bundle.

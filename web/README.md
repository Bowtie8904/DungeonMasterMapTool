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

The map uses floating left Navigate/Drawing/Measure tools and a collapsible right Chat/Actors/Compendium/Settings rail.
Settings is anchored at the bottom; character sheets open from Actors. Chat's recipient dropdown targets Everyone,
Gamemaster or another connected player; private messages and rolls are purple with a Whisper recipient label.
Messages use separate sender/timestamp cards. Roll cards have styled dice, a summed modifier, a large total and
an initially collapsed, keyboard-accessible **Roll breakdown**. Sheet rolls list named trait/passive/Experience,
advantage/disadvantage, weapon and effect contributions with individual dice and signed impacts.
The host validates optional `contributions: [{label, expression}]` against the rolled formula and returns/persists
`breakdown: [{label, expression, rolls, total}]`, using its own dice rather than client-provided totals.
Contributions can include bounded `notes: [{label, impact}]` to explain dice-pool adjustments such as
Proficiency buffs; these are context, not additional terms in the total. Weapon damage dice and fixed bonuses
are separate breakdown rows.
These fields are additive; compatibility versions are unchanged. Older hosts/history fall back to formula
and dice details. Expanded cards and the reader's scroll position survive new messages.
`shared-cards.ts` renders description-only feature/domain/equipment cards, with no sheet automation controls.
Sheet chat icons and inventory-to-chat drags require `hello.chatCards:true`. Posting uses
`POST /api/chat-cards {actorId,category,item,toId?}`; the host resolves the entry from the owner's saved sheet,
adds `sharedCard` to an ordinary chat message and keeps a text fallback for older clients. Inventory drags wait
for source autosave (including across same-origin pop-out windows using BroadcastChannel).
`GET /api/chat-cards/{cardId}` returns live stock; `POST /api/chat-cards/{cardId}/grab {actorId}` transfers one unit
to an owned receiving character, respecting consumable caps. Only recipients of the original message may grab.
`cardStock {actorId}` invalidates availability; owner-only `sheetInventory {actorId,inventory}` updates open sheets.
Transfers increment the persisted `inventoryRevision`; stale PUTs receive 409 and refresh inventory without
overwriting pending non-inventory edits. Legacy saves still work before transfers; after a transfer they must
reload and preserve this additive field. Both sheets are saved through a replayable write-ahead record so an
interrupted write cannot leave only half a transfer. Online protocol and DM Link compatibility are unchanged.
Use `/roll 2d6+3` or `/r 2d6+3` in chat (no separate dice composer). Optional `toId` targets a player or `dm`;
legacy `whisper:true` remains DM-only. Recipient names are included as additive `to` fields by the host.
Player recipients require `hello.playerWhispers:true`; older hosts offer only Everyone/Gamemaster, with the
legacy whisper flag retained for DM chat and rolls so private rolls cannot accidentally become public.
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
`checks/chat-cards.html` previews real chat/roll cards with collapsed and expanded details side by side.
`checks/shared-cards.html` checks description-only cards, share icons, live stock, actor selection,
inventory-to-chat dropping and the cross-window save handshake in a real browser, without a Java host.

# Online Play web client

Death moves use the additive `hello.deathMoves` capability and authenticated `POST /api/death-move`
(`actorId`, `move`: `blaze`, `avoid`, `risk`). Both floating and standalone sheets flush pending saves
first; unsupported hosts and request failures show an explicit error. The host checks ownership and
fully marked HP, rolls the dice, and journals the sheet and public chat result together. Risk It All is
a death-move roll, not an action roll: it offers no ordinary roll rewards or reaction buttons, and its
HP/Stress recovery is manual on older hosts. Avoid Death adds an effect with `blocksMovement`, `until:longRest` and
`unconsciousHp` (marked HP at application); decreasing HP removes it, including host-applied ally healing.
Optional `scars` persist on the sheet and travel with `sheetFeature`; usable Hope is `hope.total - scars`.
Scars are not effects and do not expire on rest. No settings or compatibility-version changes.

Hosts advertising `hello.deathMoveOptions:true` provide authoritative `GET /api/death-move/{actorId}`
choices after the sheet flushes pending saves. The shared floating/pop-out dialog lists disabled reasons,
Reliquary bonuses, Blessed Hope spending, and optional Mask spending with explicit wearing confirmation.
It also routes configured Spectral Form, Cathartoid Egg, Battle-Hardened and Unbreakable action buttons
through host death-move validation. Mask counters offer table-confirmed **Gain token** and manual 0–4 adjustment.
Narratives, range and wearing are table-controlled. Life Ward uses the existing host feature action and
an ally picker including offline characters, after confirmation of Close range; targets retain wards through
their rests. Every completed caster long rest increments `longRestRevision` so the host can expire their wards.

Risk chat uses the host's `deathResult` outcome while displaying the original Hope/Fear dice. Sender-only
**Apply recovery** offers numeric/slider HP and Stress allocation within the budget; criticals clear all
recoverable marks. **Won't Stay Dead** first confirms and applies permanent HP with no healing in that request.
Recovery uses authenticated `POST /api/death-recovery` with the original roll `{id,ts}` after save flush,
and host claims are idempotent. Pending recovery blocks further sheet rolls. Permanent HP are distinct locked
marks, never reduce HP capacity, and cannot be cleared by pips, actions or downtime (including rest-reroll
recovery metadata). When all HP are permanently marked, death-move and recovery controls stop offering
further attempts; ordinary Blaze/Risk death remains table-controlled. Spectral Form ends on further HP clearing or full HP; its full-HP trigger blocks reentry
until HP falls below full. Its physical mitigation is effect-gated; phase movement remains host-controlled.
Feature/inventory revisions synchronize death fields and card/egg changes into floating and pop-out sheets,
including conflict refresh; `rollUpdate` merges death results and feature consequences into history.
Duplicate/stale `sheetFeature` revisions are complete no-ops, including their inventory/card payloads.
The shared Store merges roll updates for both map chat history and standalone sheet history.
Older hosts retain the basic three moves and show explicit unsupported-feature errors for expanded actions.
No settings or compatibility-version changes are required.

Browser client for the Online Play host (plan.md section 3.41). Vanilla TypeScript, Canvas 2D, no runtime dependencies.

Daggerheart ancestry automation is maintained in `dmmap-daggerheart` with the schema in
`docs/DAGGERHEART_DATA.md`. `engine.ts` exposes configured trait/attack/damage actions, suggested roll modes,
effect-gated mitigation and explicit Hope/Fear outcome overrides; `view.ts` shares those dialogs with chat
follow-ups. Target healing/buffs and cross-player reactions execute on the host against current sheets.
Blood Hunter rules use conditional current-HP costs/dice, effect-gated stats and success-gated Psychometry
chat claims. The rest dialog provides a free restricted Mutagens brewing slot with trait/benefit selectors;
feature upgrades unlock choices and +2/-2 automatically. Volatile Toxins locks marked HP until the next
short or long rest, blocking manual clearing, healing and host death recovery without reducing capacity.
Brewing choices display benefit descriptions; expanded buffs resolve their individual descriptions from
current rules, including saved brews. Volatile Toxins shows its own feature text. Shadowed Grit excludes reactions.
No automatic Werewolf transformation selection is performed. See `docs/DAGGERHEART_DATA.md` for the
additive metadata contracts; Online and DM Link compatibility versions and global settings are unchanged.
`checks/blood-hunter.html` exercises the real rest and roll dialogs, cancellation, benefit limits, locked
HP controls, Psychometry's Spellcast routing, Chasing Death costs/dice and Veilwalker's next-attack option.
Limited uses (per rest, scene or session) persist across reconnects and host restarts; scene and session limits reset when the DM clicks End scene / New session, which also posts a highlighted chat announcement (`system: "sceneEnd"|"newSession"`).
`hello.rollRewards:true` and a host-created `rewardClaimed:false` marker enable the original roller's
one-time chat reward button: +1 Hope on Hope results, +1 Hope and clear 1 Stress on criticals.
`POST /api/roll-reward` takes `{actorId,roll:{id,ts}}`; the host validates persisted dice/outcome, roller and
actor ownership, caps and the unclaimed marker, then journals sheet changes and the claimed history entry
together. `sheetFeature` updates all owner sheets/pop-outs; `rollUpdate.rewardClaimed` updates chat/history.
Fear-to-Hope feature conversions only change the outcome; no automatic Hope gain. Critical recovery remains
available at maximum Hope when Stress can be cleared; no-benefit claims are rejected without consuming them.
Older hosts and historical messages without the marker offer no button. This adds no settings and does not
change Online or DM Link compatibility versions.
`checks/ancestry-actions.html` exercises actual action/damage dialogs, cancelled/disconnected sends,
combined resource costs, Ignition/Retract defaults, Surefooted and overridable advantage suggestions.

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
Optional chat identities are stored on the host in `campaign.json` as `players[].chatIdentity`
(empty or missing = account name, maximum 60 characters), not in browser storage.
Settings sends `chatIdentity {identity}` only with `hello.chatIdentity:true`; older hosts
disable the form. Browsers and pop-out sheets never send identity edits on reconnect.
The host saves edits before broadcasting the updated roster, reporting save failures without changing the identity.
The host resolves all future
message/roll/card sender and whisper-recipient names, sharing the identity across that player's sockets.
Roster entries add `chatIdentity` without changing `playerName`, displayed as `username (identity)`.
These additions do not change Online protocol or DM Link compatibility versions.

Daggerheart testing actors are created only by the desktop DM (Actors tree context menu) and may be used freely
by their assigned player. `GET /api/sheet/{id}` supplies the authoritative `testingCharacter` marker separately
from saved sheet data; players cannot promote ordinary actors through PUT. Marked sheets show a testing badge
and **Edit testing build** instead of normal level-up. `daggerheart/testing.ts` rebuilds class/subclass stages,
unrestricted multiclasses, heritage, domains, all-level active domain cards and custom resource definitions from
the compendium, preserving equipment, personal data and retained resource values. `testingBuild` persists the
selected build and is reapplied on load; removed-source effects, usage and pools are discarded. Test cards cannot
be vaulted, and adding an accessible compendium card is duplicate-safe. Normal sheets retain their creation,
advancement and capacity rules; the host also rejects testing-only fields, free identity changes and excess
multiclasses/loadout capacity on normal sheets. Missing actor markers default to false. These fields are additive;
Online protocol and DM Link compatibility versions are unchanged. No global setting enables these exceptions.

The map uses floating left Navigate/Drawing/Measure tools and a collapsible right Chat/Actors/Notes/Settings rail plus a Compendium button after Notes that opens a floating window.
There is no top-center session, campaign, map or account heading. The bottom-left **Connected players**
overlay contains a slightly transparent connection-status badge, visible even before the first roster
and retained across roster refreshes and reconnects. This layout-only change leaves compatibility versions unchanged.
`countdowns.ts` renders the optional `state.countdowns` array (`id`, `name`, `initialValue`, `value`) as a
read-only, translucent top-right list beside the sidebar. Countdown and collapsed active-effect rows use
40%-opaque backgrounds for readability over maps; expanded effects retain a darker background. It follows sidebar expansion/collapse,
grows/shrinks without scrolling, and hides for empty lists or older hosts. Its subtle **COUNTDOWNS** header
is shown only when there are countdowns. Updates use the existing snapshot
and `set` delta contracts; players have no countdown edit commands. Countdown state is persisted by the DM
in the campaign, not browser storage. Compatibility versions remain unchanged.
`checks/countdowns.html` covers five-entry layout, sidebar placement, text safety, read-only rendering,
steps/deletion, reconnects and older-host fallback in a real browser.
The desktop DM embeds the same `CompendiumPanel` and styles in a JavaFX WebView (`/?dm-compendium=1`),
with native in-app move/resize/close chrome and no minimize/pop-out. `dm-compendium.ts` uses a per-host
capability passed in the URL fragment (removed immediately), never a player login. The token authorizes
compendium reads and `/api/dm/compendium/actors`, `/add`, `/link` only; host actions also require a loopback
connection. They resolve entries from host files, persist against the latest sheet/history and notify
connected player sheets, including pop-outs. Offline characters receive the same persisted additions.
`sheetInventory` optionally includes `compendiumRevision`, `loadout` and `vault`; only a newer compendium
revision replaces domain cards, so ordinary inventory updates preserve local card edits. The host rejects
stale compendium revisions, protecting additions even from older clients until they reload the sheet.
The desktop-only capability is never included in player snapshots or invite links. Compatibility versions
are unchanged; old clients still read the persisted sheet normally.
`notes.ts` provides private folder trees and sheet-style plain-text editors, with explicit Save/Reload,
unsaved-draft guards and a permanent shared Group Notes file. `hello.notes:true` enables the feature;
older hosts show an unavailable explanation without sending note requests. `GET /api/notes` lists the
authenticated player's entries plus `group.txt`; `GET /api/notes/content?path=...` returns `{path,text,revision}`.
`POST /api/notes` accepts `{action:"create",path:<parent|"private">,name,folder:boolean}`,
`{action:"rename",path,name}`, `{action:"delete",path}` or `{action:"save",path,text,revision}`.
Paths use `private/<folders>/<name>.txt` or `group.txt`; the host derives campaign/player ownership from
the session, never a request-supplied player ID. Notes are limited to 512 KiB of UTF-8 text. Saves reject
stale content revisions with 409, keeping the browser draft; Reload confirms before discarding changes.
The host stores ordinary UTF-8 text files in `<campaign>/notes/players/<playerId>/` and `<campaign>/notes/group.txt`.
Only window dimensions are stored in localStorage (`dmmt.notes.size.<campaignId>.<playerId>`), never note text.
Group Notes cannot be renamed/deleted and is available to later campaign members. Refresh reloads the
folder listing; Reload in an editor fetches other tabs'/players' saved text. No DM desktop control or
application setting is added, and neither compatibility version changes.
Notes and Compendium title bars support standalone browser pop-outs using `/?popout=note&path=...`
and `/?popout=compendium`. These query routes use `/` on existing hosts and the existing authenticated socket;
no new server route, protocol or compatibility-version change is needed. Note pop-out opens the browser
window in the click gesture, saves any pending edits, and navigates only on successful save. Blocked pop-ups
and save failures preserve the source editor/draft. Standalone notes fill their browser window, keep
Save/Reload and unload guards, and do not overwrite the floating editor's remembered dimensions.
Compendium pop-outs retain browsing, filters and owned-character Add actions. Add opens/focuses a named sheet
window and uses origin/source-checked, acknowledged `postMessage` requests with per-request deduplication.
The live receiving sheet applies and saves the addition; the compendium never saves a stale sheet copy.
Compendium entries have no drag/drop actions, and floating/standalone sheets do not accept compendium drops.
Every compendium row has an inventory-style **Link in chat** icon, including non-addable categories and players
without owned actors. `hello.compendiumChat:true` enables requests to `POST /api/chat-cards` with
`{compendium:{category,entryId},toId?}`. The host resolves the entry from its own data and posts an existing
shared-feature description card with no transfer offer or sheet mutation. Compendium cards use a
`Compendium - <section label>` heading and optional `badges: string[]` matching inventory detail chips;
the section is no longer a badge and stats are not repeated in the description. Browser and desktop DM
chat honor these badges; cards without the field keep their existing category-specific badges.
Optional `sections: [{name?,text}]` preserves separate description and named feature/spell blocks,
with compendium-style borders in browser and DM chat. Flattened `item.text` remains for older clients.
All categories use this layout, including equipment features, class/subclass/ancestry/community/transformation
features and grimoire spells. Inventory weapon/armor links also border their feature. The DM uses native
JavaFX styled text for bold, italic, bold italic, lists and line breaks rather than displaying Markdown markers.
Map links respect the chat recipient;
standalone links go to Everyone. Older hosts and disconnected sessions show an explicit error. These are additive
contracts; Online and DM Link compatibility versions are unchanged.
`popout.test.ts` covers window opening, source/origin
checks, acknowledged additions, existing-sheet reuse, blocked pop-ups and timeout handling.
`checks/tool-popouts.html` exercises both standalone tools and the receiving live sheet in a real browser;
`checks/notes.html` also covers title-bar pop-out behavior and save/blocked-window failures.
A timeout explicitly asks the user to check the sheet
before retrying an unconfirmed addition. Both tool pop-outs retain their own session socket and survive
closing the map tab; disconnect/kick status keeps unsaved note text available to copy.
`notes.test.ts` covers tree boundaries and authenticated/error-aware requests; `checks/notes.html` exercises
the actual sidebar, dialogs, editor draft protection, resize memory, conflicts and folder operations in a browser.
Settings is anchored at the bottom; character sheets open from Actors.
The desktop DM opens the same sheet in an embedded WebView by double-clicking an actor or Select-mode actor token.
`?dm-sheet=1` uses an actor-scoped, revocable loopback capability supplied in the URL fragment and immediately
removed from browser history; HTTP requests use a Bearer header and the socket uses the capability at upgrade.
These connections act on behalf of the owner but do not count in the player roster or claim a player login.
`hello.sheetSync` and optional `sheetChanged` notifications refresh complete saved sheets for owners and their
DM windows. `GET` and successful `PUT /api/sheet/{id}` include an opaque `version`; updated clients send it in
`If-Match`. A stale save returns 409 with the latest sheet/version and an explicit reapply-edit message.
Pending local edits are not silently overwritten by notifications. Legacy clients keep the existing partial
inventory/feature revisions; Online Play and DM Link compatibility versions and settings are unchanged.

Chat's recipient dropdown targets Everyone,
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
Chat's rail icon gets an accessible unread dot for live entries received while another section is open or
the sidebar is collapsed; opening Chat clears it. Snapshots do not mark history unread. Chat follows new
entries and delayed card resizes (including sender-only roll action buttons) only while at the bottom.
Queued programmatic scroll events cannot disable following just because content grew without the scroll
position changing. Chat retains older-message scroll positions across
panel switches; **Jump to latest** resumes following. `checks/chat-attention.html` exercises these behaviors
and identity settings in a real browser, using local state without a host.
Feature/action results, equipment rolls and Spellcast rolls also carry an optional
`rollSource: {name, text, source}` description snapshot. A separate, initially collapsed **Source** disclosure
shows formatted rules without sheet actions or grab controls. Spellcast titles identify their card or individual
grimoire spell; follow-up spell damage and rerolls retain source context. Both embedded and pop-out sheets send it.
The host accepts only descriptive strings (name/source up to 240 characters, text up to 16,000), rejects malformed
context and persists it with the message, using the same whisper visibility as the roll/chat.
This is descriptive client-supplied context, not an authoritative mechanics contribution.
Older hosts/clients/history simply omit the optional disclosure; compatibility versions remain unchanged.
Host-only chat moderation sends additive `chatDeleted {id,ts}` events to the original message audience.
Browsers remove the entry without marking chat unread; no player delete UI or command exists.
Removal is persisted on the host before notification, including revoking shared-card access.
Older clients ignore the event and converge on their next snapshot; Online and DM Link versions are unchanged.
`shared-cards.ts` renders description-only feature/domain/equipment cards, with no sheet automation controls.
On the sheet, loadout domain-card resource counters use the label "Tokens" and sit in the card header between the domain and recall cost.
They retain their existing editable values, limits and autosave behavior. Domain-card action groups have no enclosing border or panel padding; individual buttons retain their normal styling.
Sheet chat icons and inventory-to-chat drags require `hello.chatCards:true`. Posting uses
`POST /api/chat-cards {actorId,category,item,toId?}`; the host resolves the entry from the owner's saved sheet,
adds `sharedCard` to an ordinary chat message and keeps a text fallback for older clients. Inventory drags wait
for source autosave (including across same-origin pop-out windows using BroadcastChannel).
Links (including historical links) are description-only and cannot be grabbed. Explicit offers require
`hello.transferOffers:true` and add a positive integer `offerQuantity` to the post request and shared card.
Only carried, unequipped equipment and loot may be offered. Multi-unit offers prompt for quantity, default all. Emptied stacks are removed from the source sheet.
New clients refuse inventory sharing with older hosts whose links were claimable; older clients can post safe
links and claim one from explicit offers on new hosts. No compatibility-version bump is needed.
`GET /api/chat-cards/{cardId}` returns the lesser of the offer's unclaimed budget and current unequipped stock.
`POST /api/chat-cards/{cardId}/grab {actorId,all?:boolean}` transfers one unit, or the entire remaining offer
with `all:true`, atomically to an owned receiving character, respecting consumable caps. Only original
message recipients may grab. Offer quantities are not reservations; duplicate offers share live stock.
Host-only `transferClaims` counters live on the source sheet and are persisted in the same transfer transaction
as both inventories; sheet PUTs cannot change or delete them. Restarting cannot replenish an exhausted offer.
Equipment/loot controls use compact trailing action groups. Search filters name/description in place and retains
queries across rerenders. Loot descriptions clamp to two rendered lines with overflow-aware accessible expansion.
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
owned drawings, controlled tokens and measurements for group movement. Left-clicking a controlled token
also selects it. Both selection methods show dashed square token outlines; selected tokens expose a
rotation handle when the host advertises `hello.tokenRotation:true`. Dragging it previews rotation locally
and sends `command {cmdId,kind:"rotateToken",tokenId,mapId,levelId,rotationDeg}` on release. The host checks
current map/level, pause, visibility, ownership and finite angles, then applies an undoable persisted edit.
Rejected or cancelled rotation never replaces the authoritative token state. Older hosts retain selection
and movement without rotation handles; Online Play and DM Link compatibility versions are unchanged.
Arrow keys with the map focused move all selected controlled tokens by exactly half a grid tile per
press/repeat, through the existing move command and rollback path. They do nothing during pointer
gestures, while paused or in text controls. Door/window badges are always light gray; only their glyph
changes on opening/closing, and the browser draws no portal segments. DM rendering is unchanged.
Drawing undo is browser-local,
bounded to 100 acknowledged edits per map and uses the existing authoritative ownership-checked commands.
Measurement delete crosses are drawn/picked at a constant screen size and shown only to the creator
(the desktop DM also gets them). Pen/brush cursors preview world-space stroke width.
Brush borders use a viewport-bounded union mask so textured and flat strokes have outlines without
internal seams at overlaps. The edge is cached independently of texture animation.

`tools.test.ts` covers ownership, geometry and grid-tile measurement labels. For real-browser UI regression,
run `npm run dev`, then open `http://localhost:5173/checks/player-layout.html` (adjust the port if necessary).
It exercises actual layout controls and canvas gestures with local test state, without a running Java host.
The page reports PASS/FAIL; it is not included in the production bundle.
`checks/token-controls.html` isolates token click/box selection, half-tile arrows, movement rollback,
rotation commands/previews/cancellation, pause and older-host capability handling with the real app and canvas.
`checks/chat-cards.html` previews real chat/roll cards with collapsed and expanded details side by side,
and checks safe, independent source disclosures and source propagation from feature/local action rolls,
Spellcast, individual grimoire spells and follow-up spell damage.
Browser action confirmations and notices use the themed, keyboard-accessible dialogs in `src/dialogs.ts`,
never native `alert`, `confirm` or `prompt`. Prayer Dice rolls and Ethereal Visage rewards have no confirmation.
Sorcerer features use themed selection dialogs, not extra confirmations. Volatile Magic selects any number
of individual magic-damage dice to reroll in the original chat card. Manipulate Magic selects reach, +2
to the action result, or an additional target on eligible attacks/Spellcast rolls; its damage action doubles
one selected die. Both keep the manual feature button. Host dice edits preserve other dice, flat bonuses,
keep-highest rules, whole-roll doubling and saved history, with stale selections and repeat uses rejected.
Both damage-die edits post an action report with the selected faces and damage total before/after,
visible to the original roll's audience. Flat post-roll bonuses display just their amount, not `2 = 2`;
rolled bonuses retain their formula and result.
Channel Raw Power displays active domain cards and vaults the selected card for level-based Hope or a
one-use +2-per-card-level magic-damage option (only select it for a spell). Transcendence chooses exactly two
rest-duration passive buffs, with a trait picker for its trait bonus. Elementalist chooses an element during
creation and shows it on the feature; existing characters without a saved choice can select it there.
Its optional action +2 / damage +3 each costs 1 Hope. Minor Illusion rolls against 10; Natural Evasion
posts a d6. Enchanted Aid's unlimited d8 Help roll never spends the independent long-rest Spellcast swap.
Its manual swap button shares the chat swap's counter; that counter is grouped with the swap, not Help.
Arcane Charge's Hope-cost button, free magic-damage button and default-on magic mitigation all grant the
same long-rest Charged buff; its optional +10 magic-damage bonus consumes it. Remove the buff manually
when taking the alternative +3 reaction Difficulty. The new chat-edit dialogs require
`hello.sorcererAutomation:true`; older hosts show an update hint. Existing compatibility versions and
settings are unchanged.
The catalog editor preserves unsaved drafts on cancellation, including when closing its embedded DM window.
`checks/sheet-layout.html` checks the vertical trait sidebar on every tab and Experiences in Background,
two-column class/heritage features, compact stats, wider viewport-height floating sheet, pop-out layout,
independent sidebar scrolling, overflow and trait roll interaction at several window sizes without a Java host.
Trait marks are read-only circle-and-dot indicators in each tile's top-left corner, separate from the name,
using an 8 px ring and 3 px dot fully inside the border without increasing the compact tile height.
Only level-up rules mark traits or clear their marks.
Armor, HP and Stress slots share a fixed 14 px square size and wrap when needed; the layout check verifies all three tracks.
Armor reserves 114 px for two rows of six slots; HP and Stress share the remaining flexible width.
`checks/shared-cards.html` checks description-only cards, share icons, live stock, actor selection,
inventory-to-chat dropping and the cross-window save handshake in a real browser, without a Java host.
`checks/testing-character.html` checks testing-only controls, active cards, cancel/validation, class/multiclass
editing, resource updates and sheet refresh in a real browser using local compendium fixtures.

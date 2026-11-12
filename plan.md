# Dungeon Master Map Tool - Living Specification

## 1) Chosen Technology Stack

- **Language/runtime:** Java 25+ (compiled with release 25, JavaFX 25; shaded jar sets `Enable-Native-Access: ALL-UNNAMED`)
- **UI/rendering:** JavaFX + Canvas, custom dark CSS theme, Ikonli (Material Design Icons 2) for icons
- **Embedded compendium:** JavaFX WebView reuses the built browser compendium and styles. Packaged runtimes include its JDK dependencies `java.net.http`, `jdk.jsobject` and `jdk.xml.dom` in addition to the existing modules.
- **Build/deps:** Maven
- **Windows package completeness:** Local batch builds and CI app images must include all nine maintained `dmmap-daggerheart` JSON catalogs in `app/dmmap-daggerheart`, beside the packaged JAR and default settings location. Keep catalogs editable outside the JAR. Verify both EXE launchers, the bundled runtime, catalog contents and every application classpath resource (including browser HTML/assets, desktop/browser condition icons, stylesheet and application icon) before reporting packaging success. Packaging-only corrections do not change settings or remote compatibility versions.
- **Texture-memory budget:** before JavaFX initializes, default `prism.maxvram` to `1g` unless explicitly supplied by the user. Include the same limit in local and CI Windows launcher options (including the software compatibility launcher); retain hardware acceleration in the normal launcher. This is an on-demand texture-pool ceiling, not a reservation or Java heap setting. A dual-window empty-map run exceeded the old 512 MiB ceiling (about 526 MiB retained). Investigate canvas backing scale, layer allocation and clipping buffers without reducing display sharpness or changing calibration.
- **Texture investigation:** debugger inspection of an empty player view found all ten canvases with retained backing textures, although only base and fog-mask layers were visible. JavaFX 25 `NGCanvas` chooses the ceiling of the highest recommended display scale across all connected screens and marks canvas backing textures permanent; visibility alone does not release them. The affected-machine log's ten 33,177,600-byte steps match 3840 x 2160 RGBA allocations (about 316 MiB total), consistent with 2x backing scale on a 1080p canvas. Future memory optimization should defer allocation/release inactive layers while preserving HiDPI sharpness, fog, handouts, transitions and calibration; no global DPI override.
- **Windows graphics compatibility:** local and CI app-image exports include a second `DungeonMasterMapTool-Compatibility.exe` launcher with `-Dprism.order=sw`, configured before JavaFX starts. The normal EXE remains hardware-accelerated. Both launchers share the bundled runtime, application, settings and maps; compatibility mode changes no persisted settings or map data and may render more slowly. Document its use for Direct3D texture failures/black player screens, plus the equivalent JVM option in an existing export's launcher configuration.
- **Codegen:** Lombok (DTOs/models/boilerplate reduction)
- **Project save format:** Versioned JSON (`.dmmap`)
- **DD2VTT import:** Jackson for JSON parsing
- **Math/geometry:** JTS (or lightweight in-house geometry where enough)

## 2) Product Goals

- **Online condition interaction corrections:** Open the browser token menu from the canceled `contextmenu` event, not `pointerdown`, so the new menu cannot intercept the subsequent native menu event. Hidden creates a buff (including existing linked Hidden effects); Restrained, Vulnerable and custom conditions remain debuffs.
- **Online token labels:** Player-facing browser maps never show token names. Names are hidden by default, and only the DM can toggle a token's DM-only name label from its right-click menu, matching desktop token-label behavior; player clients do not need token labels.
- **Token condition preservation:** Deep-copy condition lists and entries when cloning tokens for movement, rotation, resizing and undo/redo; editing token geometry must not remove conditions or mutate history snapshots.
- **Linked condition removal saves:** When removing an actor effect, finish the character-sheet save before sending its token-condition removal. These two writes must not race and produce a false sheet-version conflict; genuine save failures still prevent the token update and remain visible. This is backward-compatible and does not change remote contracts.
- **Actor conditions across maps:** Linked conditions belong to the campaign actor's saved character sheet, not to a particular map token. Switching maps, hiding or deleting tokens must not remove actor effects. Fresh actor tokens and existing tokens on revisited maps inherit the sheet's current built-in and custom conditions; explicit removal clears them across that actor's tokens. Non-actor conditions remain token-local. Preserve legacy linkage metadata and read it by condition ID; no incompatible remote contract change.

Desktop tool for tabletop dungeon masters that:

1. Imports universal-export **dd2vtt** maps (e.g., from Dungeon Alchemist).
2. Provides smooth DM-side map navigation (zoom, pan, camera controls).
3. Supports optional borderless fullscreen **player view** on a second display as an independent window with its own taskbar entry.
4. Supports fog of war, lighting, and overlay drawing workflows fast enough for live sessions on a mid-range laptop.
5. Supports creating custom maps from imported images while keeping full feature parity with imported dd2vtt maps.

## 3) Current Implementation Status

- **Demiplane starting inventory corrections:** Preserve normal starting gold when all saved gold fields are absent; explicitly saved zero remains zero. Resolve Demiplane's `50ft of Rope` spelling to the existing `50 feet of rope` entry, preferring exact catalog names and never guessing ambiguous aliases. Include both official Brawler keepsakes, `hand wraps from a mentor` and `a book about your secret hobby`, in Items so imports receive their catalog descriptions. Keep the concise import popup and compatible host/sheet contracts unchanged.

- **Demiplane suggested traits:** Recognize the saved `use-suggested-traits` toggle and apply verified class allocations (standard SRD classes and Brawler), never the generic class fallback or final totals containing advancement bonuses. Manual allocations retain strict validation when the toggle is off. Import Martial Artist's saved foundation forms by name from the local stance catalog so the linked level-1 Brawler can be recreated without inventing required selections. Missing, ambiguous or unknown required allocations/forms still block import. Preserve the concise completion report and existing compatible host/sheet contracts.

- **Compendium-backed starting items:** Include Torch, 50 feet of rope, Basic supplies and the standard class-guide keepsakes in the Items catalog. Character Mancer creation loads that catalog and grants matching entries through the normal compendium inventory helper, preserving their descriptions and automation. Keep one of each standard supply, the selected potion and class keepsake, and starting gold unchanged. Custom class-item text remains plain inventory; missing required basic supplies produce an actionable creation error rather than silently granting incomplete gear. No settings or incompatible remote-contract changes.

- **Demiplane character import:** An uncreated actor offers Create Character or Import from Demiplane. Import opens a themed, accessible link dialog, fetches only a public Daggerheart character from the official Demiplane host, and recreates supported saved creation and level-up selections using local name-matched catalogs and existing automation. Preserve equipment, Experiences and saved resources where understood; never guess ambiguous matches. Keep the saved-result popup concise: show only the item quantity warning, unmatched feature/domain-card names, and the warning that the active loadout could not be verified. Do not list unmatched loot or other technical/unsupported-field details in the completion popup; retain reconstruction diagnostics internally. Quantities default to one and unverified loadouts to the first five acquired matched cards. Preserve unmatched/custom items without invented mechanics, and do not infer historical card swaps. Missing essential creation choices (including unreadable companion/stance/element/Experience-bonus selections) block import rather than inventing defaults; blocking failures still show actionable errors. Failed imports leave the actor uncreated unless a conflict loads a newer character; acknowledge success only after the normal version-checked sheet save. Host fetching is authenticated, actor-scoped, bounded and restricted to the official HTTPS character URL without redirects or user credentials. This additive endpoint preserves existing clients/hosts and sheet contracts; no settings or remote compatibility-version change.

- **Daggerheart adversary clipboard tokens (implemented):** Ctrl+V recognizes a validated `daggerheart-companion/adversary` JSON export, creates an undoable adversary token at the cursor and opens its attached statblock on double-click. The statblock tracks per-token HP, Stress and adversary tokens (conditions are not on the sheet; the token right-click controls manage them). It is laid out in non-horizontally-scrolling sections with separators: header, motives, three equal stat boxes, a vitals card with fixed-size HP/Stress slots (marking never resizes them), the standard attack, one card per feature and Experiences; all roll buttons share one fixed height. Attack rolls open a dialog with Advantage/Normal/Disadvantage, Experience and note like the player roll dialog; damage and other dice rolls post directly to chat. The DM-side and web reaction prompts render as a styled amber "Reaction roll" card. Original feature text keeps inline attack, damage and reaction-roll controls. Each feature card has a chat icon (same icon as compendium links) that links the feature to all players as a compendium-style card with normalized plain text (no buttons or markdown emphasis, whitespace collapsed); markdown links in the text, e.g. [Zombie Legion](http://localhost:9980/?adversary=...), render as clickable links in the statblock and open in the default browser via LinkOpener/HostServices (http/https only; an embedded JavaFX WebView was tried but cannot render the Vaadin-based Companion pages, whose light-dark() feature check sends it into an endless load), and are reduced to their label in chat; it needs a running online session and is additive, with no compatibility change. DM rolls post to chat and honor the secret-roll toggle. Reaction instructions post a player chat prompt with character-specific roll buttons; player rolls use the normal character reaction-roll flow, and prompts reveal neither Difficulty nor success/failure. Imported tokens start hidden, and copied adversaries start with fresh encounter state and no conditions. Their saved state persists with the map; regular token right-click controls govern player visibility, and the statblock remains DM-only. Artwork is fetched only from loopback PNG endpoints and stored locally. The import preserves ordinary text paste and ignores exported combat state. Omit tags, separate combat notes, roll history, feature-use tracking, sheet-level visibility, damage-calculation controls and sheet undo controls. Adversary data is part of project snapshots; `RemoteCompatibility.VERSION` is incremented and existing compatibility-rejection tests cover mismatches. Parser/persistence, reaction prompt and player-button tests cover the import and reaction flow; map gesture and token copy behavior are also exercised by the existing token interaction/history paths.

- **DM chat action awareness:** Beneath DM-visible Online Play messages, show subtle, non-executable labels for the actual chat actions reported by connected player browsers, attributed to the player. Include rewards, ally reactions, claims and other message controls, including visibly unavailable actions with their explanation. Keep the default footprint bounded to a compact preview with an accessible expandable remainder. Refresh on control/sheet/message changes and remove disconnected clients' reports; never expose player-to-player whispers or treat reports as gameplay authority. Reports are transient, not saved history, and older clients simply provide no summary. Negotiate this additive capability; no settings, new DM tool or compatibility-version change.
- **DM chat open responsiveness:** Virtualize chat history so only visible cards are rendered and laid out; defer scrolling to the latest message until normal JavaFX layout. The chat fills the available flyout height and keeps visible spacing between message cards. Preserve message order, live updates/deletes, bottom-stick behavior, visible action refreshes and disclosure state across card updates/recycling. Presentation-only; no settings or remote compatibility changes.

- **Stable roll disclosures:** Preserve each viewer's open or closed roll breakdown and source when an existing roll or action message is updated, in both the browser and DM chat. Render updated results and actions normally; new messages start collapsed. This is local presentation state, with no network, settings or compatibility-version changes.

- **Stable online movement vision:** Preserve each token's latest local move through release and acknowledgement until authoritative token state catches up. Older deltas, acknowledgements and rejections must not undo newer local moves; genuine corrections, token removal and snapshots remain authoritative. Keep attached lights at the same reconciled position during and after dragging, and do not commit tentative vision to explored memory. Cover delayed state, multiple moves, rejection and resync. Browser-only, backward-compatible fix with no new settings or compatibility-version changes.

- **Loot row highlighting:** give inventory body rows a subtle blue-tinted background on hover and keyboard focus within the row, without changing expansion, controls or equipment styling. Presentation-only; no settings or compatibility changes.

- **Structured Loot table:** use Item, Qty, Usage & actions and Tools columns. Keep the type badge beside the item name when space permits; stack usage counters above full-width, naturally wrapping action buttons. Descriptions start fully collapsed; click a row's non-interactive area or its accessible item-name disclosure button to reveal/hide the full description. Preserve expansion across sheet refreshes; quantity, action and tool controls never toggle it. Read-only viewers may expand descriptions. Keep link-in-chat, transfer and delete icons in a separate, top-aligned horizontal row at the far right. Preserve equipment layout, usage/resource behavior, permissions, transfers and deletion. Presentation-only; no settings or remote compatibility changes.

- **Structured roll action sections:** Chat roll buttons render in three subtly separated groups: roll results (primary blue Hope rewards, weapon damage, Continue Run and damage triggers of the rolling feature), feature effects (secondary buttons for other triggers of the feature that made the roll) and other features (indigo-tinted, never gray, for triggers from other features such as rerolls or added dice). Roll triggers may set `rollSection: "result" | "feature" | "other"`; otherwise triggers bound to their own roll (`card:`, `feat:`, `featureAction:`, `option:`, `damageSource`, or the roll carries the trigger's feature key) are feature effects, or results when they roll damage (`damage`, `resourceRoll`, `spentTokenDamage`), and all others are other features. Explicit overrides: Stunning Sunlight damage (`result`), Deathrun End Run, Companion's build-on-success and Feed (`feature`). No buttons are added or removed; presentation-only, so settings and compatibility versions are unchanged.

- **Dread usage and ally-reaction corrections:** Refresh usage labels with sheet resource updates. Track Umbral Veil activation and Dark Army summoning at the card level, matching existing host usage keys; token spending never consumes those limits, and unsuccessful Spellcast attempts do not consume Dark Army. Tag ordinary incoming-damage chat reports with owned actor metadata so allied Hideous Retribution reactions appear without requiring special mitigation. Preserve the existing local damage and host-owned special-mitigation paths.

- **Dread online automation feedback:** Blighting Strike criticals use Hope damage. Umbral Veil tokens expire with the scene, without a clear control. Hideous Retribution offers Spellcast-trait reactions on its card and allied incoming-damage reports, then Stress-paid damage on the reaction result. Siphon Essence damage offers explicit target-marked-HP healing. Terrify, Wall of Hunger, Dark Army and Damnation keep only Spellcast on their cards and put follow-up actions on the appropriate results. Dire Strike is a weapon-damage Hope payment removing GM Fear; Jump Scare is a magic-damage action. Summon Horror and positive-Hope-quantity Darkfire roll damage directly. Spectral Mist grants physical immunity to the caster and claiming allies, expiring on an action roll or scene end (solid-object traversal remains manually ended). Dark Army grants a fiend-token buff ending at zero. Avatar of Terror mirrors Force of Nature's maintained form with preselected Fear-scaled damage and Hope-paid action options; spotlight Hope remains manual. Invoke Torment exposes its added damage in the breakdown. Use additive automation metadata and existing feature/roll paths, retaining existing remote clients/hosts without settings or compatibility-version changes.

- **Visible sheet layout stability:** Position visible feature/domain cards synchronously after a sheet rebuild and before restoring scroll, so edits and chat-producing actions cannot briefly display overlapping, full-width feature text. Keep resize/layout notifications coalesced and hidden/minimized sheets deferred. Browser-only regression fix; no settings or compatibility-version changes.

- **Online Play sheet performance:** Reuse revision-aware parsed catalogs and stored-sheet data without sharing mutable transaction copies. Avoid repeated catalog hydration and party reconciliation within one action; preserve all linked/offline/disabled actor rules, migration-on-read behavior, revision conflicts and crash recovery. Keep campaign mutations serialized until cross-actor transactions can safely use narrower locking. Make routine history appends and message lookup independent of total history size; replacements retain the original message position and identity, never become a second message at the end. Preserve legacy history files, deletions, whispers and idempotent journal recovery.
- **Efficient browser sheet synchronization:** Share unchanged catalog downloads across actors and tools, revalidate revisions after catalog edits/reconnects, and coalesce redundant refreshes. Support optional committed sheet representations in action responses while retaining old hosts/clients and existing full-sheet conflict checks. Never lose pending edits, silently retry non-idempotent actions, or acknowledge failed persistence. Keep initial saves/migrations, creation, companion state, incoming damage, rests, Deathrun original dice/stale-counter checks, rewards and inventory transfers intact.
- **Low-resource Online Play clients:** Separate owned-sheet data/loading from expensive hidden-window rendering; preserve effects overlays and chat actions for unopened sheets. Coalesce UI/layout work and avoid unrelated chat-action refreshes. Reuse catalog data and reduce redundant requests before adding reduced visual quality; any low-resource setting changes must be documented. Preserve fog/vision and all gameplay information. Cover request counts, invalidation, concurrency, slow/deferred responses, legacy fallback and interrupted persistence with regression tests. Internal performance changes do not change DM Link compatibility; new Online Play contracts must be additive/capability-gated. Add performance diagnostics/benchmarks using existing tooling rather than production timing assertions.
- **Online Play non-blocking delivery:** Every browser connection has its own ordered, bounded outgoing queue (`OnlineOutbox`) drained on a worker thread, so a slow or stalled player socket never holds the server lock or delays other players. A connection more than 512 messages or 8 MB behind drops its backlog and receives one fresh snapshot (the existing seq/resync rules apply). Kick, DM-sheet close and session end close the socket only after the queued messages were written. Browser protocol and `RemoteCompatibility.VERSION` are unchanged.
- **Online Play move validation cost:** An accepted/rejected token move reads the character sheet from disk once (shared by condition inheritance, the movement-block check and phase movement) instead of three times, shortening the time the DM UI thread holds up the player's ack and the relay to other players. Protocol unchanged.
- **Online Play adaptive publish tick:** The 250 ms state rebuild on the DM UI thread backs off (up to 1 s) when a tick is slow, so it uses at most ~20% of the UI thread on heavy maps; explicit actions still call `publishNow()` immediately. Change-driven publishing was rejected because the app has no reliable dirty signal and stale player views would be worse than the cost.
- **Performance boundaries:** Keep the existing readable `history.jsonl` format: cold indexing and actual edit/delete file replacement may remain linear in history bytes, but warm lookups/tails and routine journaled new results must not reparse/rewrite all prior messages. Retain cross-actor transaction serialization and full reconciliation safeguards rather than risk missing linked grants. Negotiate `sheetPatches` and `sheetActionResponses` independently; compact saves require the same `If-Match` precondition and all full-sheet validation, and feature-action representations belong only to the requesting actor. No offline replay of spending/rolls, no gameplay-quality reduction and no new settings are required for these optimizations.

- **Continue Deathrun from chat:** Alongside End Run, the latest owned Deathrun damage result offers Continue Run while its matching active run has at least two original weapon dice remaining. Open the existing weapon-damage dialog with its preselected Deathrun option and lowest-die removal selector; reuse original damage, never reroll. Hide continuation for stale results, ended/exhausted runs and other players; recheck current state on click. Additive browser UI only; no settings or remote contract change.

- **Sheet-backed feature request sizes:** Incoming-damage and rest transactions may carry a character sheet up to the existing 512 KiB sheet limit, plus the configured message allowance for transaction metadata. Keep ordinary feature requests bounded by the message limit; reject oversized sheets and metadata before applying changes. Cover large-sheet Octarine Brace/Recovery requests and rejection without side effects. Backward-compatible host fix; no settings or remote-version change.

- **Deathrun roll-control visibility:** Honor `hidden` on nested roll-dialog controls despite label layout styles. Place the original-die removal selector inside the Deathrun option row on the right, matching other dropdowns/spinners, defaulting to the lowest-valued remaining original die (first remaining die on ties). Show it only when continuing a selected Deathrun after its first damage roll, never on ordinary rolls or the initial damage roll. Browser-only correction; no settings or compatibility changes.

- **Bone/Valor online automation feedback:** Forceful Push is offered only after Hope/critical rolls; Untouchable adds half current Agility rounded up to Evasion (3 gives +2, -1 gives 0); I See It Coming rolls/reports its d4; Strategic Approach has no manual long-rest prepare/clear controls. Octarine Brace grants GM Fear. Redirect rolls d6s from its card and offers Stress payment/reporting only on results containing a 6. Support Tank offers paid Hope/Fear rerolls on ally rolls, not a card action. Armorer repairs allies automatically and names them in rest chat. Know Thy Enemy's information and automatic Fear removal are Instinct-result actions; Rousing Strike is a critical-attack action; Inevitable is a player-confirmed failed-action buff. Recovery is a short-rest ally selection granting one long-rest move at that ally's short rest. Rise Up clears Stress automatically on committed incoming damage. Cruel Precision is one preselected weapon-damage option with a highest-current-trait Agility/Finesse selector on both attack-result and equipment damage paths. Breaking Blow's attack/damage actions grant a buff preselecting +2d12 on the next weapon damage, consumed only when selected. Ground Pound's result offers 4d10+8 damage; Wrangle pays Hope from its result. Hold the Line grants a manually removed buff. Lead by Example offers Stress payment on damage results. Deathrun is a weapon-attack option and card action granting a tracked buff: auto-selected damage option starts at +1 Proficiency, then removes a chosen die from the original damage for each subsequent target without rerolling, offers End Run on each result, and ends before rolling zero dice. Unyielding Armor dice remain hidden until the committed damage report. Preserve existing remote clients/hosts through additive automation metadata and existing message paths; no settings or new DM tools.

- **Blade/Midnight follow-up:** Uncanny Disguise's avoid-scrutiny advantage excludes weapon attacks. Vitality records its permanent benefits as a buff hidden from the left sidebar, removable only in sheet edit mode; removal reverses the selected benefits. Rage Up uses one attack option with a 1-2 spinner. Glancing Blow opens the normal damage window with half current Proficiency rounded up, retaining damage options and cancellation-safe, atomic Stress payment and chat delivery.

- **Blade/Midnight feedback:** Not Good Enough is a post-damage reroll action updating the result and reporting in chat; Whirlwind, failed-attack Glancing Blow (half current Proficiency rounded up), and successful-Spellcast Eclipse are chat actions. Pick and Pull excludes weapon attacks. Versatile Fighter offers a preselected highest-current-trait attack selector and a Stress-paid individual damage-die maximizer. Rage Up and Reaper's Strike are attack options, with Rage Up carried to that attack's damage. Uncanny Disguise replaces its tokens on its single Stress-paid disguise action, tracks a buff and preselects token consumption for actions, not reactions. Uncanny/Deadly Focus end with the scene; Phantom Retreat toggles a rest-expiring buff; Vanishing Dodge applies manually removed Hidden. Dark Whispers pays Stress with Spellcast; Mass Disguise creates a real countdown; Spellcharge automatically gains/reports tokens on committed incoming magic damage; Night Terror chooses Fear up to the live pool and reports its d6 results. Vitality must offer its printed permanent choices on testing characters. Keep existing clients/hosts compatible; no settings changes.

- **Player actor list:** Use compact, button-free rows with a circular portrait, prominent character name, smaller class/subclass text and a clearly separated level column. Row text is not selectable. Double-click anywhere on a row to open/focus its sheet; Enter/Space provides keyboard access. Single-clicking the name or row never moves the camera. Preserve disabled/testing indicators and read-only sheet access. Refresh level/class/subclass from the existing owned sheet handles on load and edits; report load failures inline. Token customization and sheet pop-out remain accessible inside the sheet, not as actor-list buttons. Browser presentation only; no settings, API contracts or remote compatibility-version changes.

- **Actor-token sheet access:** In the DM map's Select tool, double-click an actor token to open/focus its character sheet only when the token belongs to the selected campaign and the actor is assigned to an existing campaign player. In Online Play, double-click a token to open/focus its sheet only when the token represents an actor owned by the signed-in player. Unassigned and foreign-campaign tokens keep normal selection and dragging. No settings, API contracts or remote compatibility-version changes.

- **Full Stress Vulnerable:** Saving a character with every Stress slot marked automatically adds the linked Vulnerable debuff if no Vulnerable effect already exists. Persist full-Stress provenance on the generated effect; clearing any Stress removes only that generated effect, never a pre-existing Vulnerable effect from another source. Explicitly applying Vulnerable through token controls takes manual ownership. Normalize all sheet persistence paths, including resource actions, rests and multi-actor transactions, and keep actor token badges synchronized. This is additive saved-sheet metadata using existing condition messages; no settings, new DM tool or remote compatibility-version change.

- **DM player actor resource bars:** Show two compact, upright bars over the bottom of player-owned and testing Daggerheart actor tokens in the selected campaign: sheet-red HP (`#ef5350`) and sheet-purple Stress (`#ab7df0`), both showing marked `on` / slots. Zero marked slots leaves the corresponding bar empty; all marked slots fills it. Stack two 9 px bars with a 1 px gap, using the compact Armor/Hope counter layout below. Keep the entire bar stack inside the token's bounding box so it does not cover tokens below; optional token names retain their original position below the token. Read the saved actor sheet and refresh on sheet saves, campaign selection and actor changes. Include hidden and disabled player actors and testing actors even without a player owner; exclude other unowned actors and ordinary tokens. Never render these bars in the second-screen or browser player view or add resource fields to map/remote snapshots. This is a passive DM decoration, not a new tool or setting; no remote compatibility change.

- **DM Armor and Hope token counters:** Flank the HP/Stress bars with a sheet-gray shield (`#78909c`) on the left containing one number for remaining unmarked Armor slots (`armorTrack.slots - armorTrack.on`, minimum zero), and a sheet-yellow star (`#f5b942`) on the right containing current Hope (`hope.on`). Use a 20 px wide shield and a 22 px star with bold 14 px numbers; scale down below 64 px token diameter. Reserve 48 scaled pixels for the counters and gaps, narrowing the bars as needed (at most 56 px wide) to keep the entire display inside the token footprint. Keep counters upright and DM-only, refreshing with the same saved-sheet resource data; omit a counter on legacy sheets missing its track rather than inventing a value. No settings, new tools, player snapshot fields or compatibility-version change.

- **DM adversary token HUD:** Daggerheart adversary tokens show the same compact DM-only HUD as player actors: HP and Stress bars (red/purple, marked / maximum slots from the per-token combat state, empty when none are marked) flanked on the left by the gray shield showing the adversary's Difficulty; no star on the right. Same geometry and scaling as the player HUD, drawn only on the DM canvas (never in second-screen/browser snapshots), updating whenever the statblock changes marks. No settings, tools or compatibility change.
- **Adversary token artwork and editing:** pasted adversary artwork is stored as the token's `sourceImage` with a centered square crop (zoom 1), so non-square images are cropped, never stretched. The default adversary token color is red (`#D64545`); the export's token color is ignored. Right-clicking an adversary token also offers HP and Stress rows with -/+ buttons (the menu stays open while adjusting; undoable, refreshing an open statblock), and pasting an adversary no longer opens its statblock (double-click does). It offers "Edit token appearance...", opening the standard token editor (color, square/circle, border, image pan/zoom) as one undoable change to that token's own definition. Removing an adversary token (any path) also drops its per-token definition from the project (undo restores it), so no unused definitions or artwork references are saved. Adversary advantage/disadvantage rolls use `2d20kh1`/`2d20kl1`; the dice syntax supports `kl` (keep lowest). No compatibility change.

- **Character token border thickness:** Match normal DM tokens' border width of 5.5% of token diameter in both the browser crop preview and finished PNG. Keep the complete ring inside the artwork so it is not clipped when used as a portrait or map token.

- **Character token artwork:** Character portraits and the player actor list open a themed token editor with image selection, clipboard paste (including Ctrl+V), drag-to-pan and slider/wheel zoom. Only circular tokens with a mandatory colored border are supported; no artwork name or border/shape toggle. Default to the authenticated user's assigned color and 1 tile, with size in 0.5-tile increments. Retain source image and crop settings for later edits. Save the finished bordered token as actor artwork, refresh open sheets and both actor lists, and resolve updated artwork and size for linked actor tokens on the current and revisited maps while preserving position, rotation, conditions and visibility. Authorize changes on the host, including disabled actors and actor-scoped DM sheets. Additive artwork editor endpoints preserve legacy image uploads and existing remote clients; no compatibility-version bump, new DM sidebar tool or settings.

- **DM connected players overlay:** mirror the browser's passive bottom-left Connected players card on the desktop DM map while hosting. Include a Live/Paused heading, the Dungeon Master row with the campaign DM color and Host label, and one row per connected account with its saved color, player name, current chat/character identity and best connection ping in milliseconds (`--` while unknown). Grow the overlay to fit its contents, up to the available map width, and wrap longer names; shrink again when names or the map become shorter. Refresh through the existing Online Play roster timer even when its sidebar is closed; clear and hide the overlay when hosting stops. Keep it mouse-transparent so map interaction is unchanged. This is a read-only display, not a new DM tool; no settings, local API commands or remote compatibility changes.

- **Connected player pronouns:** Display each connected account as `username (actorname [pronouns])` when its first enabled character has pronouns; omit brackets when empty and omit `(you)`. Use the same Background-first legacy fallback as the sheet header, publish an optional additive roster field, refresh on saved pronoun changes and actor identity changes, and show it on both desktop and browser rosters. Let the browser roster grow with its contents up to available viewport width, wrapping long names. Older clients/hosts remain compatible; no DM Link version or settings changes.

- **Measurement-color control registration:** `online.measure.color` must be declared in `AppSettings.SIDEBAR_CONTROLS` as well as registered on the picker, with API name/help and palette artwork. Panel creation must succeed; hiding the picker must preserve its value and local API discovery/editing.

- **Campaign account colors:** persist a hex color for each player and a separate DM color (default red, `#e53935`). Automatically backfill existing accounts on campaign load and assign new accounts on save from eight distinct player colors (blue, green, yellow, orange, purple, cyan, pink and white), choosing the least-used color in stable palette order and reusing colors only after the palette is exhausted. Never reassign saved colors after reconnects, renames, account removal or campaign reloads; account-color editing is a future feature. Browser drawing/shape and measurement color pickers default to the authenticated account color; desktop effect/drawing and measurement pickers default to the selected campaign DM color. Per-object/per-tool overrides remain available without changing the account color. Add a left-side color dot for the DM and each player in the browser Connected players overlay. Publish additive account colors in hello/roster and optional measurement color, preserving older messages and ownership checks; no Online or DM Link compatibility-version bump. Expose/hide the new measurement color control as `online.measure.color` through existing local API/control visibility.

- **Actor and character names:** Actor names and their saved character-sheet name (including the Background Name field) are one identity. In sheet edit mode, keep name typing local to the text field and commit/schedule its save only when the field loses focus; typing pauses and background saves must not persist a partial name. Apply this to both the header name and Background Name field. Saving a changed sheet name renames the campaign actor; renaming an actor in the DM tree updates its saved sheet, open sheets and player actor lists. Existing mismatches use the actor name until a name is explicitly edited. Preserve sheet-version conflict checks, disabled-player restrictions and historical chat names; new chat and rolls use the updated actor identity. Existing contracts remain compatible.

- **Character feature block:** replace the separate class/subclass and heritage panels with one Features block, ordered Hope features, class features, subclass features, ancestry features, community features, then transformation features (stable order within each category; preserve explicitly grouped subfeatures and saved arrays). Card view reuses the domain-card rendered-height flow: at most two natural-height columns, placing each next card in the shorter column, left on ties, and one column below the same 610 px threshold. Add a header search across names, sources and full text, including grouped subfeatures, and the same compact Card/List controls. List rows show name, source and chat link, and expand to the complete existing feature with all actions and subfeatures. Keep the feature view choice, search and expanded rows across sheet refreshes; its view choice is local to the open sheet and independent of the account's domain-card preference. Read-only sheets can search and expand rows without saving or enabling feature actions. Retain the transformation control. Browser presentation only; no settings, API or compatibility-version changes.

- **Domain-card switch sizing:** keep the Loadout Card/List switch compact with 28 px square buttons and 18 px icons, with 14 px total separation from the active-count label. Override the generic 40 px icon-button dimensions only for this switch so it does not inflate the header.

- **Domain-card view preference:** add an icon-only Card/List segmented switch at the far right of the Loadout header, after the active count; Card is the default. Persist the choice on the authenticated campaign player account (and separately for the campaign DM), not on a character or only in browser storage, so it follows the user across sheets, pop-outs and devices. Both Loadout and Vault use the chosen view, including testing sheets. List rows align level, name, domain, Recall cost, card-actions menu and chat-link control in fixed shared columns. Clicking or keyboard-activating the row expands/collapses the existing full card, including text, grimoire spells, tokens and feature actions; row action controls do not toggle expansion. Preserve sorting, searches, drag/drop, empty slots and read-only navigation. Save failures remain visible and retain the previous preference. Add authenticated preference GET/PUT endpoints without exposing another user's data; older accounts default to Card, older clients ignore the additive fields, and new clients show an update-required message on unsupported hosts. No LAN compatibility-version bump or new DM toolbar tool.

- **Domain-card layout (Card view):** Loadout and Vault use at most two balanced columns, with one column when there is not room for two 300 px cards. Each card keeps its natural content height and stays intact, never stretching to match a neighboring card. Process cards in ascending level order without changing saved order, placing each at the next available vertical position in the shorter column (left on ties). Levels flow from top to bottom across both columns, not down the entire left column first and not by fixed left/right alternation. Balance by rendered height rather than card count, including after searching or resizing; retain card controls, empty loadout slots and drag/drop. Browser-only presentation: no settings or compatibility-version changes.

- **Disabled actors:** The DM can toggle an actor's persisted disabled state from its actor-tree context menu. Disabled actors remain visible to their assigned player, whose sheet is read-only with a disabled indicator beside the character name and whose actor token cannot be moved; the DM retains full sheet and token control. Read-only navigation and background flushes must not send sheet saves; preserve pending edits until the actor is re-enabled. Exclude disabled actors from actor-derived choices, actions, reactions, Copycat sources, item claims, buffs and automatic global effects. Existing effects previously applied by the actor remain in place. Enforce restrictions on the host as well as the browser, including stale controls and older clients; re-enabling restores normal behavior.

- **Online token conditions:** Add a Conditions submenu to token right-click menus with independently toggleable Hidden, Restrained and Vulnerable conditions, plus Custom (a styled name-entry dialog; multiple named custom conditions may coexist). Use bundled condition icons instead of letter badges: Hidden uses `cond_invisible`, Restrained uses `cond_restrained`, and Vulnerable uses `cond_concentrating`. Custom conditions choose an icon in the name-entry dialog, defaulting to `cond_bleeding`; offer every other condition icon except those reserved for the three built-ins, show the icon choices without visible labels, and clearly highlight the selected icon. Use slightly enlarged condition badges on tokens. Players can apply/remove conditions on every token they control, including actor and non-actor tokens; the DM can do so on any token. Show a distinct condition badge on each affected token. For actor tokens, each condition also creates its own actor debuff effect; removing that effect through the actor's effects UI removes the matching token condition and badge. Use the SRD descriptions for Hidden ("Any rolls against a Hidden creature have disadvantage. After an adversary moves to where they would see you, you move into their line of sight, or you make an attack, you are no longer Hidden."), Restrained ("Restrained characters can’t move, but you can still take actions from their current position."), and Vulnerable ("When a creature is Vulnerable, all rolls targeting them have advantage."). Enforce Restrained movement on the host for players while allowing the DM to move the token freely. Keep token-condition and actor-effect state synchronized in online snapshots and deltas, and authorize mutations on the host.

- **Grace/Codex follow-up:** Endless Charisma permits only one Hope-or-Fear reroll per original non-attack trait roll. Teleport consumes its long-rest use on every committed Spellcast attempt, including failure, and disables further casts until refreshed. Copycat keeps its Cancel footer visible while only the card list scrolls, with a search field filtering eligible cards.

- **Grace/Codex feedback pass:** remove shadowing duplicate grimoire automation keys; use consistent compact Spellcast controls and Banish difficulty layout. Targeted Tava's Armor and Parallela select one actor, including offline sheets, with explained buff reports; Parallela offers a preselected consumable attack option. Move outcome-dependent payments/damage onto successful cast results, retaining independent wall damage controls. Tie cast-time and success-time limits to the specified stage. Inspirational Words regenerates without preparation controls and reports recipient choices. Never Upstaged gains tokens through confirmed damage and preselects whole-pool damage spending, preserving the printed damage bonus rather than modifying the attack's hit roll. Invisibility links a targeted effect's action option to the caster's token pool. Share the Burden uses one exclusive, bounded, atomic ally Stress transfer and caster Hope reward. Safe Haven grants rest-expiring extra-move effects to caster and entering allies, including casting from downtime. Endless Charisma offers paid individual Duality-die rerolls; Grace-Touched payments are manual. Copycat selects unique level-8-or-lower cards from other actors' active loadouts, in ascending level order, costs half level rounded **up**, and embeds usable borrowed automation until rest or source vaulting. Reuse these shared workflows for directly analogous cards; retain backward-compatible capability gating.

- **Blood feedback pass:** Lifeblood Talisman creates a unique transferable enchanted item with bearer-only, 1-Hope mitigation when marking at least 2 HP; it disappears on recast or when its caster has no marked HP (not merely on rest). Brand of Castigation pays Stress on damage results. Vitality Manipulation pays Stress on successful cast results and reports marking/clearing 1 Stress, or 2 with Hope/critical; ally-cast advantage is optional. Parasite of the Will pays HP on successful cast results. Mutual Suffering has a Spellcast-trait Reaction button and a rest counter consumed only by the successful-result action; the original incoming HP amount and adversary marks remain table-managed. Vital Ward invites allies through chat, links resistance to the particular caster circle, and expires all linked benefits when that circle ends. Bloodbath combines its Hope payment and Spellcast attempt in one card control and puts each ally's paid Stress/HP recovery claim on the roll. Glyph of Hemorrhaging pays HP and grants its caster buff on successful cast results; Severe damage ends it and the GM can remove it manually for 2 Fear. Sanguine Feast offers mutually exclusive successful-result payments of 2 Hope and 1/2/3 HP, reports 2/4/6 target HP, and attaches a caster-only, once-per-message conditional refund for the original HP amount if the target dies. Claims, costs, expiry and replay exclusions are host-validated. These are additive online capabilities; retain older-client/host connections and the DM Link compatibility version, gate new semantics on a new online capability, and rebuild the web bundle and classpath resources. No settings or DM controls change.

- **Domain-card automation corrections:** Astral Projection marks 1 Stress; Bloodbath, Arcane Deflection and Shared Clarity spend 1 Hope when their existing actions are used. Bold Presence's condition avoidance has its own once-per-rest limit without limiting its Presence roll option. Targets' narrative effects remain manual. The completed individual review and remaining automation decisions are recorded in the ledger below.

- **Further domain-card cost and roll-option corrections:** Deft Maneuvers and Wild Surge mark Stress; Divination, Manifest Wall, Reaper's Strike, Smite and Transcendent Union spend the stated Hope on their existing actions. Deft Deceiver and Reckless offer their paid advantage as optional roll choices, rather than charging separately from the roll. Narrative prerequisites remain manual; the complete review and remaining implementation decisions are recorded below.

- **Mending Touch and Summon Horror:** Mending Touch offers unlimited 2-Hope healing of either 1 HP or 1 Stress, plus a shared once-per-long-rest upgrade to 2 of either track. Players confirm the narrative condition and target at the table. Summon Horror's existing once-per-scene action marks 1 Stress.

- **Dark Army and Thorn Skin:** their token pools clear automatically on either rest, including while the card is vaulted. Rest-time resource resets include vaulted card definitions, with active definitions taking precedence for duplicate resource IDs. Summoning fiends has its own once-per-long-rest limit and Thorn Skin's cast has its own once-per-rest limit; spending or clearing existing tokens does not consume those limits. Dark Army's Spellcast success is still player-confirmed.

- **Touched-card passive groundwork:** `conditionalModifiers` may require a minimum count of cards from one domain in the active loadout, excluding the vault. Older clients ignore these new modifiers rather than incorrectly applying them unconditionally. Apply this condition consistently to stats, breakdowns, roll bonuses and host-side Spellcast trait calculation. Arcana, Blade, Bone, Splendor and Valor encode their unconditional passive bonuses with this gate. Further Touched gates and Grace's remaining automatic-cost substitution decision are described below.

- **Touched roll options:** `conditionalRollOptions` are ignored by older clients and require the indicated number of matching active-loadout cards. Codex-Touched offers its Stress-paid Proficiency bonus without consuming the separate vault-swap use; Sage-Touched offers an optional natural-environment Spellcast bonus and a shared once-per-rest Agility/Instinct bonus. Dread-Touched offers the once-per-rest Fear-pool action-roll bonus; its separate Fear-prevention reaction is described below.

- **Touched mitigation and action gating:** Bone-Touched's attack avoidance and Splendor-Touched's two shared-use damage replacements require four matching active-loadout cards. Separate conditional metadata is ignored by older clients, rather than exposing ungated actions or counting vaulted cards.

- **Arcane Barrage:** the grimoire spell offers a themed quantity dialog for spending 1 or more available Hope and rolls that many d6 magic damage dice. Recheck the spell is in the active loadout, its use remains available, and Hope is sufficient before sending; canceling or a failed send spends nothing. The action and resource payment are client-side, as for other sheet damage rolls; older clients ignore the new action metadata.

- **Armor-dependent domain passives:** Armorer grants +1 Armor Score and Fortified Armor grants +2 damage thresholds only while armor is equipped. Put these in backward-compatible conditional modifier metadata so older clients do not apply the bonuses without armor.

- **Blink Out:** its Spellcast roll has Difficulty 12. On a success, its chat reaction prompts for a positive whole number of Hope (including the caster's mandatory Hope) and spends it through the host, which verifies the active card, successful original roll and available Hope. Canceling or a rejected request spends nothing; teleportation itself remains manual. Older hosts and clients must not offer an unsupported reaction.

- **Book of Homet:** Pass Through and Plane Gate roll against their printed difficulties (13 and 14). The host consumes the spell's independent rest use automatically on success, never on failure, resolving the spell within its active grimoire and rejecting rolls after it is vaulted or removed. Players resolve travel manually. New clients disable these rolls on older hosts that cannot track their limits.

- **Banish:** its Spellcast result has a capability-gated chat action to roll d20s equal to the current Spellcast trait. The host stores the highest result on the active card and reports the dice; a nonpositive trait cannot produce the roll. Players can edit or clear that difficulty manually, and handle rest and reductions at the table.

- **Arcana-Touched:** replace its generic sheet action with a once-per-rest chat reaction that swaps Hope and Fear dice. Both the client and host require four Arcana cards in the active loadout, and the host rechecks the count when the action is submitted.

- **Blood- and Valor-Touched:** at four matching active-loadout cards, Blood-Touched grants Evasion equal to one per three marked HP and gains a Hope when damage would mark at least two HP. Valor-Touched clears one marked Armor Slot when damage marks HP without marking an Armor Slot. Apply these rewards when the damage dialog is confirmed, after mitigation; older clients ignore the new conditional metadata.

- **Codex-Touched swap:** with four active Codex cards, once per rest choose a vault card to replace Codex-Touched without Recall cost. Recheck the loadout, target and rest use when selected. The Stress-paid Proficiency roll option remains unlimited and independent of the swap.

- **Midnight-Touched:** with four active Midnight cards, offer a Stress-paid damage option using the attack's Fear die, with success confirmed by the player. A separate once-per-rest action grants Hope only at zero Hope; the player and GM handle the prevented Fear at the table.

- **Frenzy and Healing Field:** Frenzy applies its damage and Severe threshold bonuses and forbids Armor Slots only while its manually ended effect is active. Healing Field offers a free 1-HP or 2-Hope 2-HP choice, sharing one long-rest use; allies claim their healing as with the Bard's party benefits.

- **Eldritch Flesh and Power Through Pain:** derive Eldritch Flesh's thresholds from marked Stress and Power Through Pain's damage bonus from twice marked HP. Refresh visible stats when Stress changes; retain Eldritch Flesh's manual Fear-triggered armor action.

- **Shadowhunter and Voice of Reason:** the player toggles Shadowhunter's low-light state, enabling its Evasion bonus and attack advantage only while the card is active. Voice of Reason offers player-confirmed social advantage and automatically preselects one extra Proficiency die for damage while all Stress slots are marked; recheck the condition when submitting a roll.

- **Dread-Touched Fear prevention:** with four active Dread cards, offer a Stress-paid reaction on Fear rolls. Reject known failures; when the GM keeps Difficulty hidden, the player confirms success. The GM handles the prevented Fear manually, and the reaction does not consume the separate once-per-rest bonus.

- **Further domain-card corrections:** Body Basher applies its current Strength to eligible Melee weapon damage; Cruel Precision uses one current Finesse or Agility bonus on weapon damage, never both. Deadly Focus creates a +1 Proficiency effect ending with the scene or manual removal; Critical Inspiration uses a shared-use Bard-style ally claim. Book of Yarrow's Magic Immunity creates an effect that negates magic damage until rest.

- **Grimoire difficulties and Lean on Me:** use printed fixed Spellcast difficulties for Wall of Flame, Transform, Illusion, Mysterious Mist, Arcane Door and Timejammer. Runic Lock consumes its rest use only on a successful Difficulty 15 cast, like Homet. Lean on Me clears the user's Stress and lets exactly one ally claim the matching benefit; the failed roll and proximity remain player-confirmed.

- **Conjure Swarm beetles:** marking Stress conjures a tracked effect. While active, the next non-negated incoming damage automatically drops one severity, even if it is reduced to no HP; the beetles disperse unless the player spends Hope to retain them. Previewing or canceling damage does not consume the effect or Hope.

- **Weave the Flesh, Pick and Pull, and Uncanny Focus:** Weave the Flesh costs 1 HP and offers a free choice of ally HP/Stress recovery or a Stress-paid upgrade for both, sharing the rest limit. Pick and Pull offers optional situational advantage outside weapon attacks. Uncanny Focus uses the same scene-expiring Proficiency effect as Deadly Focus; its target's additional HP and the GM's Fear are handled at the table.
- **Safe Haven and Specter of the Dark:** Safe Haven charges Hope when summoned, not again when selecting its extra rest move. Specter of the Dark grants a manually ended physical-damage immunity effect until the player makes an action roll targeting another creature.
- **Additional printed Spellcast difficulties:** Final Words, Forest Sprites, Resurrection, Rift Walker, Salvation Beam and Veil of Night use their printed difficulties in the Spellcast roll. Their subsequent target, duration, cost, recovery, or vault consequences remain manual.
- **Complete catalog audit follow-through:** individually inspect all 236 cards and every nested grimoire spell against the class/subclass patterns. Record each disposition below, including narrative-only behavior and genuine pattern uncertainties. Fix non-fixed chat damage so configured costs and shared limits are checked before sending and consumed only after sending; cancellation spends nothing. Bare Bones replaces the unarmored base score/thresholds while retaining level and passive bonuses. Natural Familiar's damage/eyes controls require a summoned familiar. Quantity roll options and mitigation reuse the existing token-pool patterns. Grace-Touched retains its loadout-gated passive, but substitutions and payments are table-managed without action buttons. Unknown target difficulties require player-confirmed success, while known failed rolls cannot claim success-only benefits.
- **Protective domain effects:** Shield Aura uses a host-targeted effect with one recipient per caster; recasting removes the caster's previous aura without affecting another caster's aura. Its extra armor reduction applies only to that recipient and ends when its applied reduction leaves zero HP. Vital Ward chooses physical/magic resistance at cast time, replaces the previous choice, and ends after caster damage marks at least 2 HP; allies enter through activation-bound chat claims, and leaving the circle is table-managed. Rejuvenation Barrier grants the caster and eligible ally claimants d4 healing and a manually ended, situational physical-resistance effect. New host-dependent effect metadata is capability-gated, preserving older clients/hosts without changing the LAN compatibility version.

- **Individual domain-card review:** all 236 catalog entries and every nested grimoire spell have been read individually against the class/subclass examples. The ledger below records the result, not a claim that every narrative clause is automated. Supported costs, dice, limits, passives, prepared effects and party claims use the existing patterns. Target movement, adversary conditions, hidden difficulties and narrative prerequisites remain table-confirmed. The explicitly open items below need a choice of semantics or a new shared workflow; they have not been silently treated as fully automated.
- **Final audit corrections:** nested grimoire reactions resolve their exact book/spell source on the host and require the book to remain active. Blighting Strike failure payments are mutually exclusive and cannot be claimed on critical successes. Signature Move uses a d20 Hope die and its successful-result recovery is linked to selecting that option. Runic Adrenaline's HP-paid d8 is post-roll, with a conditional d8 advantage die. Boost pays before the attack and prepares separate attack/damage bonuses; a missed attack's damage bonus is explicitly discarded. Smite stores a charge until the next successful weapon damage, doubles the total, changes its type to magic, and excludes spell damage. Rain of Blades and Towering Stalk pay at cast time rather than when damage is rolled. Never Upstaged and Sigil of Retribution spend their whole pools. Not Good Enough offers one reroll of all 1s/2s; selecting an arbitrary subset remains manual.
- **Token preparation and current traits:** Flight replaces its previous tokens with current Agility, minimum one. Inspirational Words, Restoration, Strategic Approach and Fane regenerate on long rest, even in the vault; compute regeneration after rest-expiring effects are removed. Strategic Approach has a minimum of one. Existing explicit reset/preparation controls remain manual overrides, not additional automatic awards. The current-trait expressions are `@total_<trait>`, `@total_spellcast` and `@total_proficiency`; existing base-value expressions retain their meaning.
- **Compatibility:** optional `domainCardAutomationV3` identifies hosts supporting the Grace/Codex targeted effects, linked tokens, atomic claims and borrowed cards in addition to audited nested reactions. Retain V1/V2 for older clients; current clients disable host-dependent domain actions on hosts without V3. LAN `RemoteCompatibility.VERSION` is unchanged because connection/snapshot contracts remain backward-compatible.

- **Weapon feature automation audit:** every weapon entry was reviewed individually; the decisions are recorded in the weapon ledger below. Weapon-owned roll modifiers apply only to rolls made with that weapon (never to spell or non-weapon damage), unless a modifier explicitly targets the primary weapon (Trusty). Weapon-owned chat triggers appear only on their own weapon's attack/damage results unless they explicitly target primary-weapon attacks (Entangling); the host enforces the same rule. Effects that only change the target, nearby adversaries or GM state (Scary, Deadly, Destructive/Incendiary consequences, Burning, Eruptive, Freezing, Hooked, Tethered, Doubled Up, Venomous, Disturbing, Nonlethal, Poisonous, Piercing, Echo, Long, Sheltering) stay manual with their descriptions. Costed after-hit options (Concussive, Grappling, Restraining, Entangling, Deforming, Magnetic, Draining, Catalytic) are buttons on that weapon's attack result, not sheet buttons; Catalytic grants one ally an exclusive claim to a consumable +3 next-attack bonus and Draining clears your Stress. Quick/Ricochet/Bouncing/Volleyed/Aimed/Dueling/Omnipresent/Extending/Charged/Accelerator/Devastating are attack-roll options; Follow-Up/Greedy/Painful are damage options (Painful is preselected and required while payable; Greedy spends 10 coins, one handful, of the sheet's gold). Devastating's Stress-paid attack option carries a d20 damage die to that attack's Damage button. Lucky/Rebounding reroll failed attacks (Rebounding with disadvantage; rerolls are tagged `reroll`), Targeted and Locked On grant a preselected next-attack effect, Inverted gains Hope on a Fear attack, Invigorating/Lifestealing roll their host-side check dice, and Bolstering grants the attacker 1 Hope and every other player a once-per-player Hope claim on a critical attack. Versatile and Stockpiled add a second attack row with the alternate trait/range/damage; rolls keep the weapon name and carry a `weaponProfile:<label>` tag so chat Damage buttons and the host use the alternate profile. Otherworldly offers the physical/magic damage-type selector. Brutal (one extra die per maximum face), Recursive (repeating), Serrated (1 counts as 8) and Self-Correcting (1 counts as 6) are host-rolled damage rules gated by `hello.weaponAutomation`. Reloading is host-rolled after every non-reroll attack with that weapon; a 1 creates a manual "<Weapon>: Unloaded" debuff that blocks its attacks until its Reload action marks a Stress. Channeling's Stress-paid magic halving stores the halved-away amount in a rest-duration "Claws of Yifang: Channeling" effect, accumulating, preselected and consumed on the next Claws damage roll. Pompous blocks attacks while Presence is above 0. Parry rolls the weapon's Proficiency damage dice (no modifier) to chat; Deflecting marks an Armor Slot and posts Evasion plus the unmarked Armor Slots before marking (Armor Score for Eldritch Vambrace); Braced is a Stress-paid incoming-damage option. Ethereal, Watchful, Make Them Dance, Webslinger and Life-Linked use existing action/roll patterns. New fields are additive; the Online Play host advertises `weaponAutomation`, older hosts block only the new damage rules, and the LAN compatibility version and settings are unchanged.
- **Armor feature automation audit:** every armor entry was reviewed individually; the decisions are recorded in the armor ledger below. Damage-dialog options may now `gainHope` (capped), post a `report` line, `markStress` (mandatory Stress that overflows into HP when Stress is full, Bulky), require `when.avoidsAll` (Armor was marked and no HP is marked) and `storeRoll` their reduction die as a consumable bonus on the next magic damage roll (Capacitor). Roll modifiers support `armorFull` (Last Stand). Armor automation adds `overflowHope` (Hope when clearing more Armor than is marked), `equipEffect` (added on equip, removed on unequip), and three host-enforced sheet rules applied whenever a sheet is saved: `armorFullEffect` (added once when the Armor track becomes full), `armorHalfEffect` (kept only while at least half the slots are marked) and `zeroHopeClearArmor` (clears Armor when Hope drops from above 0 to 0). Rest passives support the `noArmorMarked` condition. The web client is served by the host, so the LAN compatibility version and settings are unchanged.
- **Item and consumable automation audit:** every entry of `items.json` and `consumables.json` was reviewed individually. Decisions confirmed with the user:
  - Every consumable gets at least a Use-style action that consumes one unit and posts an action report; consumables with a lasting effect also add an Effects-tab entry with the printed duration (`scene`, `rest`, `longRest` or manual). Purely narrative consumables only report.
  - Multi-portion consumables track portions as an item resource (Boomberry Jam 5, Pastries of Warmth 4). Actions with `portions` spend one portion and keep the unit; the unit is consumed and the pool refilled for the next unit when the last portion is used. Throwing the jam rolls `(portions)d8+4` physical damage and uses up the jar.
  - Lightning Scarab: a damage-dialog option negates the electrical damage, adds the incoming damage to a `storeDamage` resource and rolls Duality Dice (2d12) in the report; an explode action reports and clears the stored total; the pool resets on a long rest.
  - Superior Alconite Crystal: a charge resource gates the +1d4 Spellcast option (`requiresResource`, not spent). The Online Play host checks the option's die after the roll (`expendOn: 4`) and on a 4 removes the charge and reports it on the roll card; the downtime move recharges.
  - Hero's Powder: inhaling adds a scene buff whose action/reaction roll option has a die-size dropdown (`dieSizes`: d4 default, d6, d8, d10, d12, d20) and ends the buff when used.
  - Doomsday Potion: the action rolls each Hope slot (2d12 keep highest for marked slots), adds a scar for each slot rolled below 12 and stops at the first 12, which clears all HP and Stress and adds a scene Invincible buff (physical/magic immunity, Proficiency doubled, death-move reminder).
  - Ally benefits use chat claims like Bard songs (Bottled Song, Hero's Helm, Hopehold Flare, Arcane Prism); ranges stay table-confirmed.
  - Abyssal Sap makes a Finesse attack; its chat Damage button rolls 1d8+1 physical and reports the Restrain.
  - Attachable items are real attachments: an **Attach to…** action chooses an equipped or carried weapon/armor satisfying the item's requirements (no existing feature, Melee range, bow), consumes the item and adds its feature, automation and/or attack trait to that gear; **Detach** restores the stored baseline and returns the item. Covers Bloodstone (Brutal), Greatstone (Powerful), the six Gems (attack trait), Tarrasque Sinew (Titan-Slayer), Tortoise Horn (Concussive), Airblade Charm, Valorstone (Resilient) and War God's Sigil (Herald of War). Catalog refreshes keep attached values as overrides.
  - Other corrections: Ring of Resistance and Mirror of Marigold no longer have use-burning buttons (their mitigation does the work); Veilcloak's shine costs a Hope and its limit no longer restricts its advantage option; Warp Pendant costs a Stress; Ancient Runestone costs a Hope and rolls its d6 table; Tears of the Undying Hero is a drinkable long-rest effect, not a downtime move; consumables with a repeatable "for the scene/until rest" ability (Henry's Sugar, Umbra Lily Petals, Umbral Potion, Potion of Spiraling Vision) are consumed once and grant an effect whose action repeats the ability; Potion of Vigilance ends when HP is marked; Demiurge's Draught is a one-time attack Proficiency option instead of a permanent Proficiency buff; Corrector Sprite, Crucible Frames, Titan's Girdle and Paragon's Chain are roll options with their limits; recipe downtime moves (`downtime.craft: "<consumable id>"`) are resolved through the host rest route like Forager, which adds the complete catalog consumable (automation and description) to the inventory, stacking with identical copies and rejecting the rest when 5 are already carried. Older hosts do not add the item.
  - New additive metadata: action `table` (rolled outcome table with optional steps), `@amount` substitution for `selectAmount` actions, `minimumHope`, `refreshUses`, `portions`, `doomsday`; values `@hope` and `@<resource id>`; `mark` amount `all`; modifier `min`/`max` clamps; roll-option `dieSizes`, `requiresResource`, `expendOn`; mitigation `storeDamage`; rest passive `minHope`; `resourceRoll.resource: "gold"` (handfuls); `automation.attach`. Unknown fields remain ignored by older clients. Only `expendOn` needs the host; older hosts simply do not expend the charge. No settings, DM tools or compatibility-version changes.

- **Grace chat follow-ups:** Enrapture's optional Stress payment and Hypnotic Shimmer's once-per-rest activation belong on their successful Spellcast results, not their cards. Casting remains available after those benefits are spent. Thought Delver exposes only its Hope payment on the card; the committed payment report offers an optional Spellcast roll. Shared `chatOnly` actions and `followUpAction` references keep that roll off the card and preserve ordinary roll cancellation, affordability and active-card checks. The host adds optional `actionFollowUp` report metadata; older clients ignore it and older hosts retain existing behavior. No settings or LAN compatibility-version change.

### Individual domain-card disposition ledger

`Supported` means the identifiable sheet-side mechanics have an implemented pattern; any stated table conditions remain manual. `Manual` means the card has no safely inferable sheet mutation, or its supported payment/roll intentionally leaves the outcome to the table. `Open` identifies further automation not implemented pending the specific decisions listed after this table. Books name every reviewed spell separately.

| # | Card | Disposition and pattern |
|---|------|-------------------------|
| 1 | A Soldier's Bond | Manual: no automation, as requested. |
| 2 | Adjust Reality | Open: 5-Hope replacement of an existing total within the plausible dice range, including ally rolls. |
| 3 | Arcana-Touched | Supported: active-domain gate, passive bonus, shared-rest dice swap. |
| 4 | Arcane Reflection | Supported: Hope quantity d6 roll; reflection and redirected damage are GM-resolved. |
| 5 | Armorer | Supported: equipped-armor modifier and rest recovery. |
| 6 | Astral Projection | Supported: Stress payment/rest use; projection and return are manual. |
| 7 | Avatar of Terror | Supported: maintained transformation buff, preselected Hope-paid actions and live Fear d6 damage; spotlight Hope manual. |
| 8 | Banish | Supported: cast, host d20 pool and stored maximum; reductions/rest are manual as requested. |
| 9 | Bare Bones | Supported: tier-specific unarmored base thresholds and current Strength Armor Score. |
| 10 | Battle Cry | Supported: Bard-style ally Hope/Stress claims; no advantage-condition tracker. |
| 11 | Battle Monster | Manual: existing payment; creature conditions are table-resolved. |
| 12 | Battle-Hardened | Supported: existing death-move/use pattern. |
| 13 | Blade-Touched | Supported: loadout-gated damage and Severe threshold modifiers. |
| 14 | Blighting Strike | Supported: Hope/critical d6+1 and Fear d10+1 damage, exclusive failed-cast payment choices. |
| 15 | Blink Out | Supported: Difficulty 12, successful-result Hope dialog and host payment. |
| 16 | Blood Bind | Supported: successful-cast payment and explicit later damage; spotlight duration is manual. |
| 17 | Blood Puppet | Supported: separate control/attack casts, successful-control Hope payment, puppet damage. |
| 18 | Blood Spike | Supported: successful Spellcast physical damage. |
| 19 | Blood-Touched | Supported: active count, marked-HP Evasion and post-mitigation Hope reward. |
| 20 | Bloodbath | Supported: combined host-paid Hope/use and Spellcast, paid ally healing claims on the roll; adversaries table-resolved. |
| 21 | Body Basher | Supported: eligible weapon damage uses current Strength. |
| 22 | Bold Presence | Supported: current Strength Presence bonus and separate limited avoidance action. |
| 23 | Bolt Beacon | Supported: successful Spellcast magic damage; beacon consequences are manual. |
| 24 | Bone-Touched | Supported: gated Evasion and limited attack avoidance. |
| 25 | Book of Ava | Power Push: supported successful damage. Tava's Armor: targeted +1 Armor Score, rest expiry and recast replacement. Ice Spike: existing damage; target consequences manual. |
| 26 | Book of Exota | Repudiate: Spellcast Reaction and success-only rest use. Create Construct: paid summon, command roll and fixed physical damage; construct damage/dispersal manual. |
| 27 | Book of Grynn | Arcane Deflection: Hope payment, table interruption. Time Lock: manual target lock. Wall of Flame: fixed cast difficulty and passage damage, manual wall duration. |
| 28 | Book of Homet | Pass Through and Plane Gate: independent fixed difficulties and host success-only rest uses; travel manual as requested. |
| 29 | Book of Illiat | Slumber: manual targets/awakening. Arcane Barrage: Hope quantity damage dialog. Telepathy: manual communication. |
| 30 | Book of Korvax | Levitation: manual movement. Recant: Hope payment, manual target Reaction (15)/memory. Rune Circle: initial Stress-paid and later free fixed damage, manual entry tracking. |
| 31 | Book of Norai | Mystic Tether: manual target movement. Fireball: successful Proficiency damage, manual target reaction and halving. |
| 32 | Book of Ronin | Transform: fixed cast difficulty, manual transformation. Eternal Enervation: long-rest cast use, manual permanent adversary Vulnerable on success. |
| 33 | Book of Sitil | Adjust Appearance: manual appearance. Parallela: paid targeted next-attack effect and source-exclusive replacement; extra target manual. Illusion: fixed difficulty, manual illusion. |
| 34 | Book of Tyfar | Wild Flame: successful fixed magic damage. Magic Hand: manual manipulation. Mysterious Mist: fixed difficulty, manual visibility. |
| 35 | Book of Vagras | Runic Lock: success-only rest use/Difficulty 15. Arcane Door: Difficulty 13 and success-only Hope payment. Reveal: manual information. |
| 36 | Book of Vyola | Memory Delve: manual information. Shared Clarity: Hope payment; shared knowledge is manual. |
| 37 | Book of Yarrow | Timejammer: fixed difficulty, table time effects. Magic Immunity: paid, rest-expiring magic immunity effect. |
| 38 | Boost | Supported: Stress before attack, separate prepared advantage/d10 effects; explicit miss discard. |
| 39 | Brace | Supported: existing payment and incoming-damage mitigation. |
| 40 | Brand of Castigation | Supported: Stress-paid action on damage results; target mark and narrative consequences manual. |
| 41 | Breaking Blow | Supported: weapon attack/damage result actions grant a preselected next-weapon-damage 2d12 buff, consumed only on committed use. |
| 42 | Burning Blood | Supported: Difficulty 12 and successful-result HP payment; ignition targets manual. |
| 43 | Chain Lightning | Open: secondary target sequence and damage carryover. |
| 44 | Chains of Affliction | Manual: existing payment; adversary conditions and ending are GM-resolved. |
| 45 | Champion's Edge | Supported: three distinct critical-attack Hope choices, each usable once on that roll. |
| 46 | Chokehold | Manual: existing payment; holding/release are table-resolved. |
| 47 | Cinder Grasp | Supported: successful Spellcast magic damage; ongoing burning is manual. |
| 48 | Cloaking Blast | Manual: existing payment; target/visibility consequences are table-resolved. |
| 49 | Codex-Touched | Supported: gated Proficiency option and independent vault swap. |
| 50 | Confusing Aura | Supported: printed difficulty and use/payment; adversary action consequences manual. |
| 51 | Conjure Swarm | Supported: Fire Flies damage, summoned beetle mitigation and optional retention. |
| 52 | Conjured Steeds | Open: variable recipient count, rider ownership, attack/damage bonuses and damage expiry. |
| 53 | Copycat | Supported: ascending unique active-card picker, rounded-up half-level Hope payment and usable borrowed card until rest/source vaulting. |
| 54 | Corrosive Projectile | Supported: successful Spellcast magic damage; corrosion targets manual. |
| 55 | Counterspell | Supported: explicit Spellcast Reaction and host success-only vaulting transaction. |
| 56 | Crimson Adamance | Supported: existing incoming-damage mitigation. |
| 57 | Critical Inspiration | Supported: limited Bard-style exclusive ally benefit. |
| 58 | Cruel Precision | Supported: one preselected weapon-damage option with a current Agility/Finesse dropdown, highest selected by default. |
| 59 | Damnation | Supported: successful-cast chat action selects Stress and rolls d20 damage; defeat consequences manual. |
| 60 | Dark Army | Supported: success-only summoning, rest-cleared fiends, quantity damage/defense and token-bound buff. |
| 61 | Dark Whispers | Supported: Stress-paid Spellcast; GM information manual. |
| 62 | Darkfire | Supported: positive Hope quantity/scene payment and automatic Spellcast-count damage; reactions/half damage manual. |
| 63 | Deadly Focus | Supported: limited Proficiency effect ending with the scene or manual removal. |
| 64 | Death Grip | Supported: explicit vines damage; target Reaction (13) and restraint manual. |
| 65 | Deathrun | Supported: paid attack option/card action, tracked first-roll weapon dice with +1 Proficiency, subsequent chosen-die removal without rerolling, exhaustion and End Run actions; movement/targets manual. |
| 66 | Deft Deceiver | Supported: Stress-paid situational advantage selected with the roll. |
| 67 | Deft Maneuvers | Supported: Stress/rest use; situational maneuver manual. |
| 68 | Delerium Grasp | Supported: successful Spellcast magic damage; condition manual. |
| 69 | Dire Strike | Supported: weapon-damage Hope payment removes GM Fear; target-marked HP table-confirmed. |
| 70 | Disintegration Wave | Supported: printed difficulty/use; eligible objects and targets manual. |
| 71 | Divination | Supported: Hope/rest payment; prophecy content manual. |
| 72 | Dread-Touched | Supported: active gate, limited Fear-pool bonus and successful Fear-prevention payment. |
| 73 | Earthquake | Supported: printed difficulty/use; terrain and targets manual. |
| 74 | Eclipse | Supported: printed difficulty/use and successful-Spellcast chat action; visibility and target effects manual. |
| 75 | Eldritch Flesh | Supported: marked-Stress thresholds and explicit Fear-triggered armor action. |
| 76 | Encore | Open: link ally damage to a new Spellcast and vault on Fear success. |
| 77 | Endless Charisma | Manual: existing payment/recovery; social prerequisites table-confirmed. |
| 78 | Enrapture | Supported: only Spellcast on the card; successful-cast chat offers the once-per-rest Stress payment. Target attention remains manual. |
| 79 | Falling Sky | Supported: Stress quantity d20+2 per Stress; successful targets manual. |
| 80 | Fane of the Wilds | Supported: loadout-plus-vault Sage tokens, long-rest regeneration, repeatable post-roll +1 payment. |
| 81 | Ferocity | Manual: existing payment; adversary consequences table-resolved. |
| 82 | Final Words | Supported: printed difficulty; information manual. |
| 83 | Flight | Supported: printed difficulty, replacement tokens from current Agility, minimum one. |
| 84 | Floating Eye | Manual: payment; sensory navigation manual. |
| 85 | Forager | Manual: existing action; found supplies/fiction manual. |
| 86 | Force of Nature | Open: mandatory Hope before actions, transformation bonuses and defeat-linked armor recovery. |
| 87 | Forceful Push | Supported: damage option restricted to Hope/critical attack context; displacement manual. |
| 88 | Forest Sprites | Supported: printed cast difficulty; sprite tasks/manual duration. |
| 89 | Fortified Armor | Supported: thresholds only with equipped armor. |
| 90 | Frenzy | Supported: temporary damage/Severe bonuses and Armor prohibition. |
| 91 | Full Surge | Supported: existing limited-use recovery/payment. |
| 92 | Get Back Up | Supported: existing payment and mitigation/recovery. |
| 93 | Gifted Tracker | Supported: one-Hope question and contextual Evasion report; tracked identities manual. |
| 94 | Glancing Blow | Supported: failed-attack chat action; linked weapon damage at half current Proficiency rounded up. |
| 95 | Glyph of Hemorrhaging | Supported: successful-result HP payment creates a Severe-expiring caster buff with Stress-paid extra-HP action. |
| 96 | Glyph of Nightfall | Supported: success-only Hope payment; difficulty reduction manual. |
| 97 | Goad Them On | Manual: existing payment; forced adversary behavior manual. |
| 98 | Gore and Glory | Manual: existing payment/reward; defeat condition table-confirmed. |
| 99 | Grace-Touched | Manual: gated passive remains; players resolve substitutions themselves without action buttons. |
| 100 | Grisly Harpoon | Supported: successful physical damage; pulling targets manual. |
| 101 | Ground Pound | Supported: Strength action roll and 4d10+8 result damage button; target reactions, half damage and terrain manual. |
| 102 | Healing Field | Supported: shared long-rest healing choices and party claims. |
| 103 | Healing Hands | Open: fixed cast difficulty exists; independent target long-rest limits and healing ownership. |
| 104 | Healing Strike | Supported: Hope-paid targeted ally healing; prior adversary damage and proximity table-confirmed. |
| 105 | Hideous Retribution | Supported: card/allied-damage Spellcast-trait reaction, then Stress-paid magic damage on its result. |
| 106 | Hold the Line | Supported: paid manually removed stance buff; movement and narrative target restrictions manual. |
| 107 | Hush | Supported: cast-success payment; target silencing/ending manual. |
| 108 | Hypnotic Shimmer | Supported: only Spellcast on the card; successful-cast chat consumes the rest use and reports target Stress/Stunned. Targets remain table-resolved. |
| 109 | I Am Your Shield | Open: transfer the original incoming attack/damage to a different actor without duplicate application. |
| 110 | I See It Coming | Supported: existing contextual Evasion roll/report. |
| 111 | Inevitable | Supported: explicit failed-action preparation, consumed next-action advantage. |
| 112 | Inspirational Words | Supported: current-Presence long-rest pool and exclusive ally HP/Stress/Hope claims. |
| 113 | Invigoration | Open: Hope quantity dice exist; recipient's chosen exhausted feature needs an authoritative reset selector. |
| 114 | Invisibility | Supported: successful-cast target effect; recipient action option spends caster tokens and ends at zero. |
| 115 | Invoke Torment | Supported: confirmed full-Stress target doubles damage with separately attributed bonus; qualifying-defeat Hope manual. |
| 116 | Jump Scare | Supported: magic-damage Stress payment; target proximity and condition manual. |
| 117 | Know Thy Enemy | Supported: Instinct-result information Hope payment and optional Stress payment automatically removing GM Fear. |
| 118 | Lead by Example | Supported: damage-result Stress payment and exclusive next-ally Hope/Stress claim; eligible adversary table-confirmed. |
| 119 | Lean on Me | Supported: shared-rest self clear and one matching ally claim. |
| 120 | Life Leash | Open: redistribute existing HP between two willing actors and track per-ally rest limits. |
| 121 | Life Ward | Supported: existing host-targeted death-move effect; proximity remains table-confirmed. |
| 122 | Lifeblood Talisman | Supported: transferable enchanted item, bearer Hope mitigation, caster zero-HP/recast expiry. |
| 123 | Manifest Wall | Supported: printed difficulty/Hope/use payment; wall geometry manual. |
| 124 | Mass Disguise | Supported: Stress payment creates a real Countdown (8); appearances manual. |
| 125 | Mass Enrapture | Manual: existing payment; per-target attention/interruptions manual. |
| 126 | Master of the Craft | Open: one-time permanent Experience selection and permanently vaulted card. |
| 127 | Mending Touch | Supported: targeted healing choices/costs and shared long-rest upgrade; touching and fiction table-confirmed. |
| 128 | Midnight Spirit | Supported: paid one-spirit summon, effect-gated cast and current-Spellcast-count damage that consumes the spirit; carrying/movement manual. |
| 129 | Midnight-Touched | Supported: gated Fear-die damage and independent zero-Hope action; GM Fear prevention manual. |
| 130 | Mutual Suffering | Supported: Spellcast Reaction button and success-only rest counter; incoming HP and adversary marks table-managed. |
| 131 | Natural Familiar | Supported: summoned-effect-gated dice/eyes and rest expiry; creature fiction manual. |
| 132 | Nature's Tongue | Supported: situational option/payment; dialogue manual. |
| 133 | Never Upstaged | Supported: HP-event-confirmed token award and all-token next-success damage spending. |
| 134 | Night Terror | Supported: limited live-Fear quantity selection, Fear removal and d6 chat damage; target conditions manual. |
| 135 | Not Good Enough | Supported: post-damage reroll action updating the result and reporting in chat. |
| 136 | Notorious | Supported: existing passive/situational roll modifiers. |
| 137 | Octarine Brace | Supported: incoming-damage mitigation grants GM Fear atomically on application. |
| 138 | Octarine Wall | Supported: printed difficulty/use; wall geometry and eligible attacks manual. |
| 139 | On the Brink | Supported: existing incoming-damage mitigation. |
| 140 | Onslaught | Manual: existing payment; adversary sequence manual. |
| 141 | Otherworldly Shimmer | Manual: limited action; affected creature circumstances manual. |
| 142 | Overwhelming Aura | Supported: printed difficulty; adversary reactions manual. |
| 143 | Parasite of the Will | Supported: successful-result HP payment and subsequent Hope disadvantage action; target manual. |
| 144 | Phantom Retreat | Supported: Hope-paid toggle effect ending on return or rest; movement manual. |
| 145 | Pick and Pull | Supported: optional lock/trap/steal advantage. |
| 146 | Plant Dominion | Supported: printed difficulty/use; plant manipulation manual. |
| 147 | Power Through Pain | Supported: damage derived from twice marked HP. |
| 148 | Premonition | Open: rescind a move and all its consequences after GM narration, then choose another move. |
| 149 | Preservation Blast | Supported: current Spellcast-trait dice count, not Proficiency; successful magic damage. |
| 150 | Rage Up | Supported: paid attack option carrying its bonus to linked damage. |
| 151 | Rain of Blades | Supported: Hope paid on cast, successful magic damage; Vulnerable target extra die remains manual. |
| 152 | Rapid Riposte | Open: failed incoming Melee attack versus a chosen active weapon's Stress-paid damage. |
| 153 | Reaper's Strike | Supported: Hope/rest-paid attack option; target's additional HP manual. |
| 154 | Reassurance | Manual: limited benefit/payment; narrative prerequisite table-confirmed. |
| 155 | Reckless | Supported: paid optional attack advantage. |
| 156 | Recovery | Supported: one long-rest move in a short rest; optional paid ally selection grants a temporary one-long-move rest buff. |
| 157 | Redirect | Supported: card rolls current-Proficiency d6s, offers Stress payment/report only when a 6 appears; target application manual. |
| 158 | Rejuvenation Barrier | Supported: caster/party d4 recovery and situational resistance effect; barrier membership manual. |
| 159 | Restoration | Supported: current-Spellcast long-rest pool and targeted HP/Stress recovery; other condition clearing is manual. |
| 160 | Resurrection | Open: fixed difficulty exists; dead actor state, permanent consequences and success transaction. |
| 161 | Rift Walker | Supported: fixed difficulty; journey/return manual. |
| 162 | Rise Up | Supported: passive Proficiency and automatic Stress recovery on committed HP damage. |
| 163 | Rousing Strike | Supported: limited critical-attack result action with self/ally HP-or-d4-Stress claims; seeing/hearing table-confirmed. |
| 164 | Rune Ward | Supported: existing incoming-damage mitigation. |
| 165 | Runic Adrenaline | Supported: conditional d8 advantage and HP-paid post-trait-roll d8. |
| 166 | Safe Haven | Supported: summon payment, caster/ally entry buff and one extra downtime move until rest; downtime cast supported. |
| 167 | Sage-Touched | Supported: active gate, natural-environment option and shared-rest trait bonus. |
| 168 | Salvation Beam | Open: printed difficulty exists; variable recipients, paid quantity and HP distribution. |
| 169 | Sanguine Feast | Supported: exclusive 1/2/3-HP payments, doubled target report, caster-only exact conditional refund; defeat table-confirmed. |
| 170 | Savor the Anguish | Manual: existing payment/recovery; target damage condition table-confirmed. |
| 171 | Scramble | Supported: existing limited action; movement manual. |
| 172 | Second Wind | Supported: existing limited recovery. |
| 173 | Sensory Projection | Supported: printed difficulty/use; sensory navigation manual. |
| 174 | Shadowbind | Manual: ordinary Spellcast; per-target temporary restraint is GM-managed. |
| 175 | Shadowhunter | Supported: low-light toggle gates Evasion/advantage. |
| 176 | Shape Material | Manual: existing payment; material/geometry manual. |
| 177 | Share the Burden | Supported: exclusive ally claim atomically transfers bounded Stress to the caster and grants capped Hope, with an explained chat report. |
| 178 | Shared Trauma | Open: choose and transfer recovery/marks without duplicate actor updates. |
| 179 | Shield Aura | Supported: source-exclusive targeted extra-Armor effect and causal zero-HP expiry. |
| 180 | Shrug It Off | Supported: existing payment and mitigation. |
| 181 | Sigil of Retribution | Supported: level-capped d8 pool, explicit target-event award and all-dice damage spending; target identity manual. |
| 182 | Signature Move | Supported: shared-rest Hope d20 selection and option-linked successful recovery. |
| 183 | Siphon Essence | Supported: long-rest outcome-gated damage, extra Fear die and target-marked-HP healing on its damage result. |
| 184 | Smite | Supported: limited Hope-paid charge, doubled magic weapon damage, consumed on use. |
| 185 | Soothing Speech | Supported: existing rest-recovery modifier. |
| 186 | Specter of the Dark | Supported: physical immunity effect; qualifying targeting action ends it manually. |
| 187 | Spectral Mist | Supported: caster/ally-claim physical immunity, action/scene expiry; solid-object passage ends manually. |
| 188 | Spellcharge | Supported: automatic tokens from committed magic HP, capped at current Spellcast and reported in damage chat; quantity damage spending. |
| 189 | Splendor-Touched | Supported: active gate, passive benefit and shared limited damage substitutions. |
| 190 | Splintering Strike | Open: secondary-target damage sequence and shared use/result consumption. |
| 191 | Stealth Expertise | Supported: existing stealth preparation/result pattern. |
| 192 | Strategic Approach | Supported: current-Knowledge long-rest tokens, minimum one, existing spending options. |
| 193 | Stunning Sunlight | Supported: per-target Hope payment and two fixed reaction-outcome damage buttons; target Reaction/condition manual. |
| 194 | Summon Horror | Supported: Stress/scene payment rolls Spellcast-count d8+1 magic damage; target reaction/Stress manual. |
| 195 | Support Tank | Supported: two ally-result actions pay the feature owner's 2 Hope and reroll the selected Duality die; range/failure table-confirmed. |
| 196 | Swift Step | Manual: existing payment; movement manual. |
| 197 | Tactician | Supported: Tag Team Hope d20 option; ally Experience contribution manual. |
| 198 | Telekinesis | Supported: separate lift/throw casts and throw-only physical damage; movement manual. |
| 199 | Teleport | Supported: fixed difficulty and exclusive familiarity modifiers; travel manual. |
| 200 | Tell No Lies | Manual: ordinary Spellcast; truthful speech/refusal Stress managed by GM. |
| 201 | Tempest | Supported: three named casts with distinct successful fixed magic damage; weather/conditions manual. |
| 202 | Terrify | Supported: successful-cast chat action rolls target Stress; fleeing and Fear-success Vulnerable manual. |
| 203 | Thorn Skin | Supported: limited cast, rest-cleared tokens and mitigation. |
| 204 | Thought Delver | Supported: only Hope payment on the card; its chat report opens the optional deeper-thought Spellcast roll without paying again. Information and Fear awareness remain manual. |
| 205 | Through Your Eyes | Manual: sensory link/switching/termination; no numerical sheet benefit. |
| 206 | Towering Stalk | Supported: separate limited climbing use and Stress-paid attack cast/damage; see limit interpretation below. |
| 207 | Transcendent Union | Open: Hope/use payment exists; merged actors, traits and consequences need shared ownership semantics. |
| 208 | Troublemaker | Manual: limited payment; affected target condition manual. |
| 209 | Twilight Toll | Supported: rest-cleared token pool and quantity damage spending. |
| 210 | Umbral Veil | Supported: Fear-count tokens bound to a scene-expiring buff, with no manual clear action. |
| 211 | Unbreakable | Supported: existing death-move action. |
| 212 | Uncanny Disguise | Supported: single Stress-paid replacement-token disguise buff; preselected action-only token spending, ending at zero. |
| 213 | Uncanny Focus | Supported: limited Proficiency effect; additional target HP/GM Fear manual. |
| 214 | Unleash Chaos | Open: current-Spellcast session pool, token commitment before cast and success-only d10 damage. |
| 215 | Untouchable | Supported: half current Agility rounded up adds to Evasion, including negative/odd values and source breakdowns. |
| 216 | Unyielding Armor | Supported: host-rolled mitigation dice appear only in the final committed damage report. |
| 217 | Valor-Touched | Supported: gated threshold bonus and post-damage Armor recovery. |
| 218 | Vampiric Strike | Supported: Hope-paid self HP/Stress recovery; successful attack causing 2+ target HP is table-confirmed. |
| 219 | Vanishing Dodge | Supported: Hope-paid linked Hidden buff, removed manually; teleport manual. |
| 220 | Veil of Night | Supported: printed difficulty; visibility/party membership manual. |
| 221 | Versatile Fighter | Supported: preselected highest-current-trait attack dropdown and Stress-paid individual damage-die maximum. |
| 222 | Vicious Entangle | Supported: successful Spellcast magic damage; temporary restraint manual. |
| 223 | Vital Ward | Supported: paid resistance choice, activation-bound ally entry buffs and caster 2-marked-HP damage expiry; movement/non-damage termination manual. |
| 224 | Vitality | Supported: one-time two-benefit selection and permanent vaulting, including testing-sheet selection. |
| 225 | Vitality Manipulation | Supported: ally-cast advantage, success-result Stress payment and outcome-dependent target Stress report. |
| 226 | Voice of Dread | Manual: ordinary Spellcast; target Stress/restraint handled by GM. |
| 227 | Voice of Reason | Supported: situational social advantage and full-Stress extra damage die. |
| 228 | Wall of Hunger | Supported: fixed difficulty with success-only chat payment; wall geometry and targets manual. |
| 229 | Wall Walk | Manual: existing payment; movement manual. |
| 230 | Weave the Flesh | Supported: shared-rest HP payment and exclusive ally recovery/paid upgrade. |
| 231 | Whirlwind | Supported: paid attack chat action; multi-target consequences manual. |
| 232 | Wild Fortress | Supported: printed difficulty/Hope payment and dome HP pool; geometry/destruction manual. |
| 233 | Wild Surge | Open: Guardian-style rising die fits, but forced increment and overflow/rest Stress need shared expiry handling. |
| 234 | Words of Discord | Supported: fixed difficulty and repeat-target -5 option; control/target identities manual. |
| 235 | Wrangle | Supported: Agility action roll and Hope-payment result action; target movement manual. |
| 236 | Zone of Protection | Supported: fixed difficulty/use; zone membership and affected adversary consequences manual. |

### Individual weapon-feature disposition ledger

All 336 weapon entries were reviewed by feature. `Supported` names the implemented pattern; `Manual` features change only targets, allies or the narrative and keep their descriptions.

| Feature (weapons) | Disposition |
|---|---|
| Reliable (13), Heavy (8), Cumbersome (9), Protective (10), Barrier (4), Brave (1), Padded (4), Massive (4), Powerful (10) | Supported unchanged: passive modifiers / kept-dice damage. |
| Sharpwing (1), Bonded (1) | Supported: damage bonus only on that weapon's damage (confirmed). |
| Paired (12), Double Duty (2), Focused (4) | Supported unchanged: opt-in primary-weapon damage options. |
| Trusty (1) | Fixed: always-on +1 on primary-weapon attacks (was an option restricted to the hammer itself). |
| Quick (16), Ricochet (4) | Fixed: Stress-paid attack options instead of sheet buttons. |
| Bouncing (3) | Supported: attack option with a Stress quantity, one per target. |
| Volleyed (1), Aimed (4), Dueling (1), Omnipresent (1), Extending (1) | Supported: attack options (Hope cost, disadvantage/ignore, advantage, -1 per range step). |
| Charged (1), Accelerator (1) | Fixed: Stress-paid +1 Proficiency attack options; Accelerator once per scene with its missing Stress cost. |
| Devastating (2) | Fixed: Stress-paid attack option carrying d20 damage dice to that attack's Damage button. |
| Follow-Up (4), Greedy (1), Painful (2) | Supported: damage options; Greedy spends 10 gold coins (confirmed); Painful required (confirmed). |
| Concussive (2), Grappling (1), Restraining (1), Entangling (1), Deforming (1), Magnetic (1) | Fixed: costed buttons on the weapon's attack result (confirmed); target effects manual. Entangling requires a primary-weapon attack. |
| Draining (1), Catalytic (1) | Fixed: attack-result buttons; Draining clears your Stress, Catalytic grants one ally an exclusive consumable +3 next attack. |
| Lucky (1), Rebounding (1) | Supported: failed-attack rerolls (Stress / with disadvantage). |
| Targeted (1), Locked On (1), Inverted (1) | Supported: next-attack effects and Fear-attack Hope; Targeted/Inverted apply to any weapon attack. |
| Invigorating (2), Lifestealing (1) | Supported: host-rolled d4/d6 checks clearing Stress or the chosen HP/Stress. |
| Bolstering (1) | Supported: critical attack grants the attacker 1 Hope and other players a Hope claim (confirmed). |
| Versatile (17), Stockpiled (5) | Supported: alternate-profile attack rows (confirmed). |
| Otherworldly (5) | Supported: physical/magic damage-type selector. |
| Brutal (3), Recursive (1), Serrated (2), Self-Correcting (1) | Supported: host damage rules. |
| Reloading (10) | Supported: host d6 after each attack; Unloaded until a Stress-paid Reload (confirmed). |
| Pompous (1) | Supported: attacks blocked above Presence 0. |
| Channeling (1) | Supported: halving plus rest-duration stored damage for the next Claws damage roll (confirmed). |
| Parry (2), Deflecting (2), Braced (1) | Supported: Proficiency weapon dice roll, Evasion report using unmarked Armor Slots before marking (confirmed) or Armor Score, Stress-paid incoming-damage option. |
| Startling (4), Swatter (1), Healing (1), Persuasive (1), Watchful (1), Ethereal (1), Make Them Dance (1), Webslinger (1), Life-Linked (1) | Supported: sheet actions, rest passive, roll options, scene effects and trait action rolls. |
| Scary (3), Deadly (2), Destructive/Incendiary consequences (3), Burning (2), Eruptive (1), Freezing (2), Hooked (4), Tethered (1), Doubled Up (2), Venomous (1), Disturbing (1), Nonlethal (1), Poisonous (1), Piercing (6), Echo (1), Long (1), Sheltering (1), Returning (5), Retractable (2), Hot (1), Timebending (2), Incorporeal (1) | Manual (confirmed for target-only effects). |

### Individual armor-feature disposition ledger

All 110 armor entries were reviewed by feature. Armor features never keep a bare sheet button that only marks a resource when the rule can be resolved by the damage dialog, a roll option or a chat trigger.

| Feature (armor) | Disposition |
|---|---|
| Flexible, Heavy, Very Heavy, Warded, Resilient, Gilded, Physical, Magic, Painful, Channeling, Fortified, Difficult, Conduit, Field Repairs, Bulwark Frame, Juggernaut, Practical, Hidden Notes, Enchanted, Cumbersome, Mnemonic, Wall-Crawling, Self-Healing, Magnificent, Vigilant, Aquatic, Attuned, Blessed, Shifting, Gravitic, Anchored | Supported unchanged. |
| Reinforced | Fixed: the host keeps a "Reinforced" +2 damage thresholds buff while every Armor Slot is marked and removes it when one is cleared (confirmed). |
| Quiet | Fixed: the +2 move-silently option is excluded from weapon attack rolls (confirmed). |
| Impenetrable, Integrated Wards | Fixed: the redundant bare sheet button was removed (Impenetrable stays a last-HP damage option; Integrated Wards is ordinary Armor). |
| Sharp, Punishing Weight | Fixed: damage options limited to Melee weapons. |
| Timeslowing | Fixed: marks an Armor Slot and posts d4 + Evasion to chat. |
| Capacitor | Fixed: once-per-scene magic damage option reducing by 1d8; the roll is stored as a consumable bonus on the next magic damage roll (confirmed). |
| Trickster's Step, Brace Point | Fixed: Armor-paid rerolls offered on failed Agility (with advantage) or Strength/Agility/Finesse reaction results. |
| Terrifying | Fixed: Stress-paid button on successful Melee weapon attacks posting the push-back report. |
| Echoing Plate, Glasslight, Cataclysm Plate | Fixed: damage-dialog reports when Armor is marked (Glasslight only when no HP is marked; Cataclysm once per rest). |
| Phasebound | Fixed: the negate option consumes its once-per-scene use; the bare button was removed. |
| Mirrorwoven, Steelwoven | Fixed: Armor-paid Spellcast reflect action added. |
| Last Stand | Fixed: +1 to action, reaction and damage rolls while all Armor Slots are marked (confirmed). |
| Overflowing | Fixed: clearing Armor beyond the marked slots gains 1 Hope once per clear, client and host (confirmed). |
| Rattleclad | Fixed: optional +2 threaten/intimidate and -2 stay hidden action-roll options. |
| Runesleep | Fixed: rest passive clearing 1 Stress when no Armor Slot is marked. |
| Oathbound | Fixed: equipping adds a manual "Oathbound" +2 Evasion buff; unequipping removes it (confirmed). A "Break vow" action ends the buff and marks every free Armor Slot. |
| Thornmail | Fixed: Armor-paid physical damage option reporting 1 damage to the attacker. |
| Last Bastion | Fixed: the host adds a scene "Last Bastion" resistance buff when the Armor becomes full, including manual marking (confirmed). |
| Helper, Tempered Spirit | Fixed: once-per-scene +1 Evasion scene buff; +1 Armor Score and +1 Evasion scene buff. |
| Flicker of Courage | Fixed: avoid-all damage option gaining 1 Hope and giving the GM 1 Fear. |
| Steady the Line, Grim Confidence | Fixed: Armor-paid +1 Hope action; once-per-scene Hope gain when marking Armor. |
| Last Light Engine | Fixed: the host clears 1 Armor Slot when Hope drops to 0 (confirmed). |
| Beacon of Despair | Fixed: the host keeps a "Beacon of Despair" debuff while at least half the Armor Slots are marked (confirmed). |
| Lined, Bulky | Fixed: Stress-paid minor-damage negation; Bulky marks a Stress when the final severity is Severe, never blocks damage, and marks HP instead when Stress is full. |
| Absorbing, Resplendent | Fixed: once-per-scene actions clear 1 Armor Slot. |
| Stellar | Fixed: Stress-paid Spellcast advantage roll option. |
| Fortune-Favored | Fixed: once-per-scene chat trigger turning a Hope result into Fear. |
| Bloodthirsty | Fixed: critical Melee weapon attack button clearing 1 HP. |
| Ghostwalker | Fixed: missing Stress cost added. |
| Divine | Fixed: gain 1 Hope whenever Armor is marked. |
| Accursed | Fixed: HP-marking damage follow-up d4 for the attacker's Stress. |
| Vitreous | Fixed: the negate option adds a manual -5 thresholds "Vitreous Cracked" debuff that the downtime repair action ends. |
| Hungry Sigils | Supported: any-number magic Armor; the duality Fear check stays manual. |
| Hopeful, Stress Sink, Shared Protection, Emberwoven/Burning, Crushing Weight, Truthseeking, Blinkstep, Spiked Interior, Silken Guard, Gliding, Quick-Striding | Manual (confirmed where asked). |

### Open automation decisions and alternatives

- **Roll replacement and undo:** Adjust Reality and Premonition. Adjust Reality chooses a plausible total for self/ally dice, not a reroll; Premonition undoes the move and its consequences, not just its total. Consider linked result revisions versus original-result edits and a transaction that reverses already-applied consequences. Support Tank now authoritatively rerolls the chosen ally Duality die with atomic owner payment.
- **Merged actors:** Transcendent Union still needs shared trait, cost and use ownership. Copycat's separate caster-owned borrowed automation, rounded-up cost and source-vault/rest expiry are now implemented.
- **Mounts:** Conjured Steeds. Named recipient effects can encode the attack/damage bonuses, but resolve variable steed count, rider versus steed ownership and steed damage expiry without applying those bonuses to every ally.
- **Targeted healing and condition changes:** Healing Hands and Salvation Beam. Bard claims fit fixed party benefits; per-target limits and distributing a Stress-paid healing quantity instead require targeted transactions. Vitality Manipulation now uses outcome-specific target reports as requested. Mending Touch, Healing Strike and Restoration already use targeted actions and are not unresolved for recipient ownership.
- **Transfers and linked damage:** I Am Your Shield, Redirect, Life Leash, Shared Trauma and Encore. Consider a shared source-event transaction versus sender payment plus recipient claim. Exact HP amounts, success-only uses, death/defeat and conditional refunds must use the original event, not an unrelated editable number. Lifeblood Talisman now transfers as a unique enchanted item; Mutual Suffering tracks its Reaction/success-only use with incoming HP table-managed; Sanguine Feast binds its conditional refund to the original payment. Share the Burden has an exclusive atomic Stress transfer with bounded caster Hope gain.
- **Variable damage sequences:** Chain Lightning and Splintering Strike. Consider one roll with per-target application versus sequential linked rolls. Chain Lightning stores the cast total as the targets' reaction Difficulty and expands to new targets; Splintering Strike distributes one damage total with additional per-target dice. Shared-use consumption must not duplicate the original use.
- **Unleash Chaos:** editable token controls exist. Resolve session-start current-Spellcast preparation, choosing/spending tokens before the cast, success-only damage and Stress-paid replenishment. Consider a cast-time quantity dialog with linked damage versus a prepared pending expenditure.
- **Permanent grants:** Master of the Craft remains open: resolve Experience ownership, reversibility and re-import before permanent mutation. Vitality now has an explicit one-time two-benefit selection and permanent vaulting.
- **Transformations:** Force of Nature and Avatar of Terror. Existing toggled effects, Fear-based dice and defeat/spotlight confirmations fit individually; a mandatory Hope payment before every action, including non-card actions, needs one shared path with automatic no-Hope reversion and cancellation-safe expiry. An optional cost checkbox would not enforce the rule.
- **Grace-Touched:** substitutions are intentionally resolved by players, without action buttons, as requested in the feedback pass.
- **Invigoration:** Hope quantity dice are implemented. Consider a host selector for a specific actor/feature exhaustion record versus an ally claim authorizing a manual reset. Do not reset every use or confuse once-per-session with rest limits.
- **Untouchable:** half current Agility rounds up, explicitly confirmed for this feature. Glancing Blow also rounds half current Proficiency up; these do not establish a general rounding rule. Rapid Riposte's weapon linkage remains open.
- **Versatile Fighter:** the per-roll trait selector leaves the stored weapon unchanged; its damage option maximizes exactly one selected die for Stress.
- **Wild Surge:** Guardian's counter/effect pattern is appropriate, but its optional advance button is insufficient for mandatory advancement after every action and Stress on overflow/rest/manual expiry. A shared automatic expiry/payment hook is required.
- **Not Good Enough:** offered after damage, not as a pre-roll option; the reroll revises the existing damage result and posts a chat report.
- **Towering Stalk interpretation:** climbing has a once-per-rest use and the separate Stress-paid attack is unlimited in the current metadata, following the separate paragraphs. Confirm whether the opening once-per-rest limitation instead governs attack casts too.
- **Non-fixed damage reactions:** costs and limits are checked again and paid only after a successful client send, matching other sheet damage. Local per-roll markers are not a host-authoritative cross-reload transaction; moving these reactions into the shared host damage workflow would give stronger duplicate/acknowledgment guarantees.
- **Vital Ward termination:** incoming damage marking at least 2 HP ends the effect. Manual HP pips and HP costs do not carry an incoming-damage event; decide whether the same threshold should include those marks or remain explicitly table-resolved.
- **Resurrection:** consider a host-managed revival transaction versus manual actor-state edits. Success restores the chosen dead creature and rolls d6 for permanent vaulting; failure blocks recasting for a week, not for a rest. Game-time week tracking is not represented by ordinary rest uses.
- **Multiple protective casters:** Shield Aura, Tava's Armor and Parallela currently use unique named recipient effects. Recasting preserves other casters' effects on other recipients, but casting the same named effect on one recipient replaces it. Confirm whether same-name effects from different casters should stack, coexist without stacking, or replace one another.

- **Hope-dependent sheet refresh:** manually changing Hope pips immediately refreshes derived stats, their source tooltips, the mini strip and action availability. Conjure Shield must lose/gain its Proficiency Evasion bonus when Hope crosses below/above 2 without reopening the sheet. Preserve page scroll, persistence and existing compatibility/settings contracts; rebuild the browser bundle.

- **Wizard online automation:** Adept is an optional duality-roll choice requiring an Experience, doubling its modifier and replacing its Hope cost with 1 Stress. Honed Expertise automatically rolls a d6 when using an Experience and refunds that Experience's cost on 5+, including Adept's Stress (intentional extension of the printed Hope-only rule). Show the d6 and refund outcome in chat without adding it to the action total; canceled or unsent rolls spend nothing. Conjure Shield adds Proficiency to Evasion only at 2+ Hope, including the stat breakdown. Face Your Fear upgrades to 2d10 with Fueled by Fear and 3d10 with Have No Fear. Thrive in Chaos is offered on damage-result chat cards, costs 1 Stress and reports that the target marks an additional HP; attack success remains player-confirmed. Keep metadata in classes.json, preserve settings and compatibility versions, update documentation and rebuild the packaged web bundle.

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
- Rendering performance requirements: profile bright-core, ambient/light-map and remaining player work separately, explicitly identifying application-thread timings rather than GPU timings. Retain unchanged ambient, bright-core and fog layers; render weather on a separate cached layer at the existing animation cadence, with cached outdoor clipping invalidated by indoor geometry, camera, canvas size and displayed project changes independently in each view. Reduce particle submission overhead using batched rain paths and reusable snow sprites where measured beneficial, preserving density, appearance, indoor exclusion, lightning transmission, freeze, handouts and transitions. Do not raise frame-rate defaults or alter saved map data.
- Ambient/light-map optimization: cache per-view indoor classification, daylight and lightning exposure independently of light flicker, invalidating on project, indoor geometry/settings, camera and raster dimensions/scale changes. Cache transformed visibility polygons while their source geometry and view are unchanged, keeping flicker radius/brightness dynamic. Update the lightning image only when exposure or color changes. Benchmark serial and parallel lighting paths and use a measured pixel-budget cutoff; preserve identical output, light order, live settings, openings, freeze and performance-mode behavior without adding settings.
- Lighting scheduling: below 1,024 raster pixels, or on a single-core runtime, process serially (one polygon band); otherwise retain parallel bands and pixel composition. Rendered light-map CPU benchmarks with 0/1/19 lights found parallel rendering faster at normal budgets and serial processing beneficial primarily for tiny maps.
- Player pixel-cost pass: hide inactive player compositing layers (weather, lightning, lighting, text, fog and pointer overlays) and retain unchanged player effects/text instead of clearing them on DM-only interaction frames. Preserve native resolution, layer ordering, live tuning, flicker/texture cadence, pings/laser expiry, scale calibration, handouts, transitions and frozen output. No new settings or render-scale changes in this pass.
- DM pixel-cost pass: retain unchanged DM effects and foreground (text, token decorations, walls and portals) independently of moving pointers, tool previews and weather. Hide inactive DM lighting, weather, fog and foreground canvases without disabling the input canvas. Invalidate retained content on all relevant project, view, editing, visibility and live-style changes. Preserve existing frame rates, native resolution, appearance, layer order and player behavior; add no settings.
- Interaction scheduling: ordinary mouse hover/enter/exit must not raise the map frame rate in either normal or performance mode. Dragging, painting, panning, laser use and moving map-tool previews retain the configured interaction cadence. Refresh changed Select-mode portal highlights promptly without boosting both views; retain unchanged DM pointer/selection overlays during animation frames, preserving pointer expiry, live styles, selections and viewport controls. No frame-rate defaults or settings change.
- [x] Performance metrics readout in the status bar next to the performance toggle: actual/limit fps, average and worst render time per frame, and heap in use/allocated (MB, e.g. `120/512 MB`), refreshed about once a second; the two slowest render parts (`dm effects`, `dm lighting`, `dm fog`, `player ...`, `light engine`, `base redraw`, ...) are appended and the tooltip lists every part (`dmmt.render.FrameProfiler`)
- [x] Performance mode toggle (bottom-left of the status bar, default off, persisted as `ui.performanceMode` in the settings): a temporary in-memory override (`dmmt.render.PerformanceMode`) that never modifies project data or other settings and reverts fully when switched off. While on (all values are settings, see 3.25): the light map is rasterised at 1/8 instead of 1/4 resolution (`performance.lightMapScale`), effect textures still animate but only at 10 fps (`performance.animationFps`) and light/emitter flicker is switched off, the render loop runs at most at 5 fps when nothing happens (`performance.idleFps`, or the idle FPS if lower) and at 20 fps while the user interacts (`performance.interactionFps`), map images use tiles two pyramid levels coarser (`performance.imageLevelBias`), and soft texture edges use at most 6 feather passes (`performance.maxFeatherPasses`). The fog image is drawn at the lowest fog sharpness (`fog.cellsPerGrid.min` cells per tile) by merging mask cells, without touching the saved fog mask or the fog sharpness setting; fog fading is off unless `performance.fogFade = true`. Visibility polygons are unchanged because they drive gameplay (reveal while lit)
- [x] One-click room reveal bounded by walls/doors/windows (3.17)
- [x] Handout window: paste a clipboard image, toggle showing it on the player screen, rotate in 90-degree steps (3.18)
- [x] Laser pointer on middle mouse button hold (3.19)
- [x] Map browser thumbnails + search bar for maps and folders (3.20)
- [x] Character token library, circular image editor, map instances and portable token export (3.38)
- [x] Map tags, click-to-use suggestions, import tag inheritance and word-based name/tag search (3.34)
- [ ] Audio: ambient music categories, sound effect loops, clip cutting from long files and a mini player (3.35)
- [x] Auto-save for maps that already exist on disk (3.21)
- [x] Light presets (candle, torch, lantern, ...) placeable with one click (3.22)
- [x] Right-click cancels the active tool like Esc (3.23)
- [x] Soft fog edges and fog fade animation (3.5)
- [x] Batch import of dd2vtt maps: multi-file selection and whole-folder import (3.26)
- [x] Handout upgrades: multiple pasted images shown together in an auto-fitted grid, per-image delete (3.27)
- [x] Persistent recent handouts list in the handout window (double-click to reuse, right-click to remove, `handout.recentMax`) (3.27)
- [x] Copy/paste of lights, effect shapes and text boxes (Ctrl+C / Ctrl+V), also across maps (3.28)
- [x] Subtle ambient weather per map: rain, snow, mist, dust motes, embers (3.29)
- [x] Multilevel maps: several levels (e.g. building floors from Dungeon Alchemist) shown as one map with a level switcher (3.32)
- [x] Additive multi-light blending: overlapping lights brighten and mix color instead of only the brightest light showing (3.6)
- [x] Inverse-square-style falloff in the dim-light band for more physically realistic light falloff (3.6)
- [x] Bright core highlight per light: additive hot-spot that brightens the map texture at the light's center instead of covering it (3.6)
- [x] Ambient darkness/tint rendered via multiply blend on its own canvas layer instead of a flat colour-over overlay, so Dawn/Dusk/Night darkening preserves the map's own detail/contrast (3.7)
- [x] Stream Deck plugin driving the DM view through the local control API, with key artwork and live highlighting (3.36)
- [ ] Online Play: DM-hosted browser clients, campaigns and actors (3.41)
- [ ] LAN remote workspace control: same DM UI on a second PC, host-owned settings/state/assets, completed-edit synchronization and host-authoritative conflict handling (3.39)

## 4) Core Functional Requirements

## 3.1 Import and Map Model

- The application UI is English-only: JavaFX-provided control labels must remain English regardless of the computer's display locale, so they do not introduce mixed-language dialogs.
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
- The player window has no owner and is not always on top. Minimizing the DM window leaves the player view visible; other applications can cover either window normally. The player view has its own taskbar entry. Closing the DM window still closes the player view and exits the application.
- On Windows, assign Player View a distinct per-window AppUserModelID so it is not grouped with the DM taskbar button, and set `DWMWA_EXCLUDED_FROM_PEEK` so previewing other windows does not fade the player screen. Apply both whenever the player window is opened, without changing always-on-top, ownership or behavior on other platforms. Report native integration failures explicitly; no new settings.
- Player View has a distinct cyan monitor-and-eye window icon, rendered at 16, 24, 32, 48, 64, 128 and 256 pixels for taskbar and window-switcher clarity. Keep the DM/application icon unchanged; reuse the player icons on reopening, on every platform. No new settings.
- On Windows, explicitly install small and large native window icons (`WM_SETICON`) for the undecorated player window. Persist the same multi-resolution design as an `.ico` in the per-user application cache and set `AppUserModel.RelaunchIconResource` before the separate AppUserModelID. Restore previous native icons and release owned handles on window hiding, and clear all assigned shell properties. Verify native icon handles and taskbar icon metadata, not only JavaFX image lists.
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
- Rendering: quarter-resolution light map (cached LOS polygons per light, recomputed only on move/geometry change). Day skips lighting when there are no enclosed rooms or indoor darkness is effectively off.
- **Additive multi-light blending**: overlapping lights accumulate brightness and blend their colors (weighted by each light's contribution in linear light) instead of only the brightest light winning per pixel, so e.g. two overlapping torches are brighter where they overlap and a red and a blue light mix toward purple. Total brightness per pixel is clamped to 1.0 (no over-exposure); ambient ("dark") contribution still blends in using the same weighted average as before. Rasterization stays a single parallel pass (sum brightness and weighted RGB per light instead of a max-compare), so performance is unchanged.
- **Falloff curve**: a light stays at full brightness through the inner half of its range (bright light, matching the usual D&D bright/dim light radius split), then dims through the outer half with a physically inspired, windowed inverse-square-style curve (fast initial drop-off that levels out, smoothly reaching zero at the edge) instead of a generic cubic ease, for a more realistic falloff without shrinking the bright-light radius.
- **Light tint** slider (Lighting section): strength of the light colour tint over lit areas (0-30%, default 5%). Saved per map (in the project file, `lighting.lightTint`), so different maps can use different strengths; updates live while dragging. New maps start at the default.
- **Bright core** slider (Lighting section): strength of a brighter hot-spot at the center of every enabled light (0-50%, default 15%). Unlike the normal light overlay (which only clears darkness and lightly tints, see above), the bright core actually brightens the map pixels beneath it using additive ("glow") compositing, so the map's own texture/detail stays visible but lit up, like the baked-in bright highlights some map art already has at lamp/torch positions, instead of a flat opaque circle painted over the art. Implementation: each enabled light draws a small radial-gradient highlight (light's own colour, full strength at its center fading to transparent at `lighting.brightCore.radius` fraction of its range, default 30%) onto a dedicated canvas layer stacked directly above the base map canvas (and below the walls/overlays/tokens layer and fog), with that layer's blend mode set to additive (`ADD`) so it brightens the map underneath instead of covering it; not occluded by walls (the highlight is small and sits right at the light's own position). Saved per map (in the project file, `lighting.brightCore`) like the light tint slider; off (0%) costs nothing extra to render.

## 3.7 Time-of-Day Lighting

- Global live settings `lighting.indoorDarkness` and `lighting.daylightThroughOpenings` default to true. Disabling indoor darkness makes rooms follow outdoor ambient lighting at Day/Dawn/Dusk (Night is unchanged); disabling daylight skips aperture light-field calculations, including indoor lightning transmission through openings. Performance mode temporarily treats both switches as off without modifying settings or map data. Indoor classification remains available for outdoor-only weather and fog reveal remains unchanged.
- Performance mode caps each light map at `performance.lightMapMaxPixels` (default 5000), or the normal `lighting.lightMapMaxPixels` budget if lower, in addition to its resolution divisor. The pixel budget includes rounded image dimensions.
- 4 presets: Day, Dawn, Dusk, Night (selector in the DM overlay, undoable).
- Preset affects outdoor ambient darkness + tint; Day = no outdoor darkness. Dawn (warm orange-peach) and Dusk (deep magenta-rose) use strongly saturated multiply tints by default so they are clearly distinct from Day; stored custom settings are unchanged.
- By default, enclosed rooms use the Night preset's darkness/tint and per-map Night brightness at every time of day. Reuse Reveal room boundaries and outside detection, including all doors/windows regardless of state. Indoor lights retain their illumination, colour, bright cores and flicker while indoor darkness is enabled; outdoor light sources are visually inactive except at Night. Saved enabled state and explicit fog-reveal modes are unchanged.
- At Day/Dawn/Dusk, only open exterior windows and doors admit steady daylight. All closed windows and doors block sunlight. An exterior portal must join an enclosed interior to an outside flood-fill component (or the map's exterior bounds); interior portals only transmit existing daylight when open, never emit it. Each virtual source shines exclusively through its own aperture, so closing a neighbouring exterior opening cannot remove or redirect its contribution. Sunlight has full strength through the inner 30% of its reach and a gentle smooth falloff thereafter; reach is eight grid tiles or eight aperture widths, whichever is larger. Daylight never reveals fog. Geometry, portal type/state, map bounds and fog resolution changes invalidate cached indoor/daylight fields. Both DM and live/frozen player renderers use the same rules; existing flicker/performance switches still apply.
- Sunlight's exterior source is one map grid tile from the opening, independent of aperture length. Neighbouring rooms/walls at least one tile away must not block the incoming sunlight merely because the old, length-dependent source would lie behind or inside them. Walls less than one tile away can still block the incoming beam; walls inside the receiving room continue to cast shadows. The one-tile threshold is inclusive, measured in world units using the map's grid scale, not fog cells or screen pixels.
- DM view shows darkness at reduced strength so the DM can still read the map.
- **Ambient brightness** slider (Lighting section, -50% to +100%, default 0%): adjusts the ambient darkness of the *current* preset for *this map* (effective darkness = preset darkness x (1 - brightness)). Stored per preset in the project (`lighting.ambientBrightness`, keyed by preset name, 0 entries omitted), so e.g. Night can be brightened on one map only. Updates live while dragging; one undo step per release; double-click resets. Disabled at Day (no darkness).
- **Ambient darkness/tint via multiply blend:** render a dedicated opaque ambient image above bright cores and below walls/overlays/light glow. Per-pixel multiplication preserves map texture and contrast; light colour highlights remain a separate alpha-blended overlay sharing the same cached light raster. At Day this is a no-op only outside enclosed rooms.

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
- **Animated textures:** a *Texture* dropdown in the Effects section sets the texture of new shapes and of the selected shape (undoable). Textures: Flat color, Smoke, Fire, Water, Lava, Acid/slime, Ice/frost, Lightning, Arcane runes, Darkness/void, Mist/fog, Blood, Spider web, Holy light, Grease/oil, Sand/dust storm, Wind gusts, Radiation/aura, Poison/toxic gas, Swamp/mud/bog, Rubble/debris, Thorns/brambles, Force/shield (hex grid), Necrotic/shadow rot, Entropy/void/portal (small spiral vortices), Chasm/broken earth (the shape is a hole: broken earth plates split by black cracks form a rim along the shape edge (about 1.6x `texture.featherCells` wide) that breaks up into a solid black void towards the middle; for freehand strokes the void follows the merged final outline of the stroke, not the brush path; default brown, 100% opacity, static). Arcane runes use a seamless field of compact, distinct angular glyphs (about one per grid cell at the default tile size) with restrained glow, rather than an oversized circular diagram, so the symbols remain legible in small effect shapes. Blood is dense (little empty space between pools). They are procedurally generated, seamlessly tiling tiles drawn as scrolling and/or pulsing layers in both DM and player views, for circle, box and freehand shapes alike. Every texture is colourised with the effect color; picking a texture loads its default color (smoke grey, fire red, water blue, ...) and default opacity (denser textures such as lava, darkness, blood and web start at 100%), both of which can then be changed freely (e.g. orange water = lava-like, green = acid). Blood and Spider web are static; the spider web is a connected net of irregular little webs (uneven spokes and rings with missing threads) that continues across tile borders without gaps. Soft textures (smoke, fire, mist, darkness, holy light, sand, wind, lightning, radiation, poison, necrotic, portal) fade out towards the shape edge (feathered over up to 0.9 grid cells) instead of ending in a hard cut; solid textures (water, lava, blood, web, swamp, rubble, thorns, force, ...) keep a crisp edge; each texture's edge is switchable in the settings file via `texture.<kind>.softEdges` (true/false). The opacity slider (10-100%) is stored per shape and applies directly to textured shapes; *Players see* still applies. A per-shape **border** toggle (next to the texture dropdown, default off, saved as `border`) outlines a textured shape in its effect color (freehand strokes get one outline around their final merged area); flat shapes always keep their edge line. Only textures that actually move (`OverlayTextures.isMoving`) keep the render loop at full frame rate (wakes the idle throttle). Missing/unknown `texture` = flat color (older saves). Generated tiles are cached per texture+colour (LRU, 128 entries) so maps with many differently coloured textures never regenerate tiles while rendering. Implementation: `dmmt.render.OverlayTextures` (table of definitions, unit-tested).

- Arcane animation uses smooth, seeded wandering with irregular direction and speed changes instead of constant linear scrolling. Motion is consistent between DM and player views, including when soft edges are enabled; zero layer speeds disable drift.
- **Animations toggle** (play icon next to the texture/border toggles): global for all maps (persisted as `ui.effectAnimations`, default on). When off, textures render as a static frame in DM and player view and the render loop no longer wakes for them, which improves performance on weak machines.
- **Light emission:** effects that do not emit light are dimmed by darkness like the map (drawn below the lighting pass, lit only by light sources and other emitters); emitting effects are drawn above it at full brightness. Effects can emit light (bulb toggle next to the border toggle, saved per shape as `emitsLight`). Emitting shapes add a glow to the lighting map (only visible when the map is dark, e.g. night) in the effect's colour: full strength inside the shape, fading out over `lightRange` grid cells beyond its edge. Picking a texture sets the toggle to that texture's default (OverlayTextures.defaultEmitsLight). The bulb toggle is available for every effect shape (any texture, including flat color) regardless of that default, so light can be added to (or removed from) a shape later; it is only disabled for pen and line strokes. Flat color uses the built-in light strength 0.5, range 2 cells and no flicker. Defaults (settings): `texture.<kind>.emitsLight` (on for fire, lava, lightning, arcane, holy, radiation, portal, force), `.lightStrength` (0-1) and shape edge, in grid cells. Flicker: `texture.<kind>.lightFlicker` (depth 0-1; fire 0.3, lightning 0.6, lava 0.12, arcane/portal/radiation/force 0.1, holy 0.05, others steady) and `.lightFlickerSpeed`; it follows the map's light flicker toggle (not the animations toggle) and dims/shrinks the glow like light-source flicker. Shape light ignores walls (no shadows). Older saves default to off.
## 3.9 Map Ping Tool

- DM can place quick pings on the map to direct player attention.
- Ping appears immediately in DM and player views at world position.
- Ping should be visually obvious (short animation + fade out).
- Support at least one default ping style initially; extensible for variants later.
- Online players have a Ping button in the left map toolbar, with no flyout, and `P` activates it outside text inputs/dialogs. Its target cursor indicates the armed tool; one left-click sends one ping and returns to Select.
- In Select, holding left-click still for 600 ms sends one ping without switching tools. Moving more than 4 screen pixels cancels the hold and preserves ordinary dragging/box selection. Release, cancellation, focus loss, tool/map/level changes and pause cancel pending holds.
- Player and DM pings share the existing world-position animation and are visible to the DM and every online player. The host validates authenticated requests, finite coordinates, current map/level and pause state; pings are transient, not edits/history.
- Advertise an additive `mapPings` browser capability; older hosts disable the player tool and hold gesture. Existing DM pings/snapshots and LAN compatibility version remain unchanged. No new settings or DM controls.

## 3.10 Doors, Windows, and dd2vtt Interactables

- Imported and manually drawn doors and windows are represented as the same interactable map objects.
- DM can open/close doors and windows directly from DM view.
  - Every door/window shows a round **icon badge** (door/window glyph, open/closed variant, matching red/green default state colours for both types; independently configurable via `ui.doorOpenColor`, `ui.doorClosedColor`, `ui.windowOpenColor`, `ui.windowClosedColor`) at the middle of its line in the DM view only, drawn above the fog so it is always visible. Changing built-in window defaults preserves existing explicit user colour choices.
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
  - Hovering a map entry shows its complete name even when the name is clipped in the tree row; long names remain readable and the tooltip may include the thumbnail and level-count details.
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

- All non-library DM controls live in a vertical **icon rail on the right** side of the DM view with one icon per section: Tools, Fog of war, Lighting, Weather, Effects, Text, Map building, Player view, Performance. Clicking an icon opens that section as a **flyout overlay to the left of the rail** (scrolls if the window is small); clicking the same icon closes it, clicking another icon replaces it. The flyout stays open while working on the map (it does not close on canvas clicks) and nothing is open on startup (no open/collapsed state is persisted). Hiding a tab (`ui.sections.hidden`) removes its icon from the rail and closes it if open. There is no panel header, settings cog or collapse button: Settings is opened from the status bar cog. API controls `ui.controls` (close the open section / open the first visible one) and `ui.panelSettings` remain available. The obsolete settings `ui.controlsExpanded` and `ui.section.<id>.expanded` are dropped from the settings file at startup.
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
- **Draw walls:** click to place the first point, then each subsequent click immediately creates an individual wall from the previous point and continues the chain from its endpoint. Preview the next segment to the cursor in the DM view only, including half-tile snapping (Shift bypasses snapping independently for each point). Mouse release does not add a segment; repeated points/segments shorter than 2 world pixels are ignored without advancing the last point. Each segment is stored, saved and undone/redone separately, with immediate lighting and room-boundary updates and normal persistent-fog history. Esc or a right-click without dragging ends the chain and returns to Select, retaining completed walls; changing tools, arming Ping, undo/redo or switching maps/levels clears the pending point. Right-drag panning and zooming preserve it. Pending points/previews are session-only and do not block auto-save between clicks. Reuse the existing hideable `building.drawWall` control and local API action; no new settings.
- **Draw door / Draw window:** drag endpoints with flexible length and half-tile snapping (Shift bypasses snapping). Created segments are ordinary doors/windows: existing open/close behavior, lighting, room boundaries, wall-layer visibility and persistence apply. Creation is undoable. In Select, their right-click menu changes the type to Door or Window without changing endpoints or open state; type changes are undoable.
- **Door/window on a wall:** while Draw door/Draw window is armed and Shift is not held, hovering within the wall pick radius of a wall previews (DM-only, yellow) a door/window on that wall's continuous line (the wall plus every collinear wall touching or overlapping it). If that line is at least one tile long, the door/window is exactly one grid tile long, centred on the cursor's projection; otherwise it matches the line exactly (start to end). Its edges snap to half tiles (world half-tile grid for axis-aligned walls, half-tile steps from the wall start otherwise) and it is clamped inside the continuous line, so it may straddle the joint between wall segments. A click without dragging places it and splits every collinear wall overlapping that tile, so no wall runs through the opening (remaining pieces shorter than 0.5 world pixels are dropped). Placement and split are one undo operation with normal lighting/room-boundary/persistent-reveal updates. Dragging still draws a free-length door/window as before; Shift suppresses the suggestion and keeps free placement. The preview obeys the wall-layer toggle and is session-only.
- **Room label:** a Map building tool places a DM-only, auto-sized text box in the room clicked, using the Reveal room flood fill and its wall/door/window boundaries to find an interior position. If no enclosed room is found, use the clicked position. Placement does not change fog and works with fog disabled. Room labels reuse text-box editing, styles, selection, movement, resizing, deletion, copy/paste, rotation and undo/redo, and persist with the map. They are always DM-only, show no hidden-eye badge, and expose no player-visibility option. Draw door, Draw window and Room label are individually hideable DM controls and accessible through the local API.
- **Room-label hover preview:** reuse the cached Reveal room detection and preview to outline/tint the detected region blue while Room label is armed, including with fog disabled. Shift does not change its colour or reveal/hide fog. Clear preview when leaving the canvas, changing tools, editing or switching maps; geometry changes invalidate the cache.
- **Room-label defaults:** new labels use their own subtle style, independent of selected/last-used Text options: font size 30, light text `#EEEEEE`, translucent dark background `#1E1E1E99`, transparent border `#00000000`. Global settings `roomLabel.fontSize`, `roomLabel.textColor`, `roomLabel.backgroundColor`, `roomLabel.borderColor` are editable/searchable in Settings, with no additional DM controls. Defaults apply only to newly created labels; existing labels remain individually styleable with normal Text controls.
- **Room-label centering:** new labels anchor their box centre to the detected room's interior centre (or clicked fallback point). Auto-sizing while typing/deleting, font/style changes and later edits reposition the box to preserve that centre, including the in-place editor. A deliberate manual move (drag, group move, arrow nudge) permanently disables anchoring for that label; a no-op click does not. Anchoring state/point persist, copy correctly through history and map rotation, and undoing a manual move restores anchoring. Pasting is a manual placement and detaches the pasted label from its source room. Existing labels without anchor metadata retain their saved placement.
- **Outside-area labels:** use exactly Reveal room's outside definition (flood fill touches the outer map/fog-grid bounds). Centre the label at the clicked cursor position rather than the region centroid, preserving that clicked anchor while typing. Multiple labels may name separate parts of the same outside area.
- **Automatic imported room labels:** global live Settings toggle `import.autoLabelRooms`, default on, creates one DM-only label per enclosed room when importing dd2vtt/uvtt maps, including individual, batch, folder and multilevel imports. Reuse Reveal room's grid barriers/fill and outside definition; all doors/windows bound rooms regardless of open state. Skip outside, empty and barrier-only regions, enumerate each enclosed component once in deterministic grid order, and name sequentially `Room 01`, `Room 02`, ... (at least two digits). Use dedicated room-label defaults and interior-centre anchoring, saved with the imported map. Detection does not reveal fog, label outside areas, relabel existing saved maps or require JavaFX/UI-thread access; perform it within the existing background import flow. Turning the toggle off affects subsequent imports only.
- **Small import regions:** automatically label only regions that contain a complete, axis-aligned square at least one map grid square wide and high inside their detected interior (measured from contiguous fog-grid cells in world units). A large bounding box or area alone is insufficient: thin L-shaped byproducts are skipped, while L-shaped rooms with a full-square interior section qualify. The square may be anywhere in the region, without alignment to the map's tile lines. The minimum is inclusive. Skipped regions do not consume room numbers. This filter affects only import auto-labeling, not Reveal room or manual labels.
- **Erase walls:** also removes imported/manually drawn doors and windows. Hover visibly highlights exactly the wall segment or portal that the next click will erase; the preview and deletion share picking rules, including portal badges, and obey the wall-layer toggle. Door/window Select context menus also offer Delete. Deleting a door/window (eraser or Delete) replaces it with an ordinary wall with the same endpoints in the same undo operation; erasing that wall afterwards leaves a true opening. All removals are undoable, update lighting/room boundaries and persistent light reveals, and save normally; highlighting is DM-only and session-only.
- Implementation: `dmmt.ui` package - `Icons` (icon buttons, tooltips, cached icon cursors), `ControlSection` (rail icon + flyout content of a DM controls section), `Dialogs` (dark text/confirm/save-changes/error dialogs), `MapLocationDialog`, `MapBrowser`; disk operations in `dmmt.service.MapLibraryService` (unit-tested). `assets`/`imports` folders next to loose `.dmmap` files are hidden from the tree.

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
  - Stored as `thumbnail.png` inside the map package, next to the `.dmmap` (hidden from the tree like `assets`/`imports`). Saves check a durable image-input signature and regenerate only when inputs change or the thumbnail is missing (3.37). Moving/renaming/copying a package carries it along automatically.
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
- The in-place editor's insertion caret matches the active text colour, including empty boxes, room labels, colour changes and movement between differently styled text runs.
- Box style: **background color** (neutral light gray `#EEEEEE` by default, with a dark `#1E1E1E` default text colour so new boxes are readable on any map), **border color** (transparent by default), rounded corners. Colors may carry opacity (custom color dialog); "no fill" / "no border" buttons reset to transparent.
- Select tool: click a box to select, drag to move, drag one of the 8 handles to resize, `Delete` removes it. Create/edit/move/resize/style/delete are undoable.
- **Per-box visibility (like effect shapes):** a *Players see* toggle in the Text section and a right-click menu (*Visible to players*) on a text box hide a single box from the player view; the DM still sees it dimmed with a crossed-out eye badge. Undoable, copied with the box, saved as `playerVisible` (default true).
- **Text layer** toggle hides/shows all text boxes in both DM and player views (saved with the map). Choosing the Text tool shows the layer again.
- Text boxes are drawn above map, lighting and effects but **below fog**, so fog hides them from players.
- **Last used settings** (font size, text color, background, border) are stored per map (`lastTextSettings`) and, as fallback, globally in the settings file (3.25). Selecting the Text tool loads the map's settings, or the global ones when the map has none.
- `Ctrl+C` copies the selection, `Ctrl+V` pastes it at the mouse position (or the view center) — also into a different map opened afterwards (see 3.28; text boxes are part of the copied items).
- **Global text rotation (player view only):** two rotate buttons (left/right, 90-degree steps) in the *Text* section turn all text boxes (box and text, around the box center) on the **player view only**, e.g. so they face players at a table; the DM view always shows them upright so they stay readable and editable. It is independent of the map rotation (map rotation only moves box positions, never their size or orientation), applies to every map and is persisted globally as `text.rotation` (0/90/180/270) in the settings file (`dmmt.render.TextBoxGeometry`).
- Text boxes persist in the save file (`textBoxes`, `textLayerVisible`, `lastTextSettings`).
- Room labels (3.16) are text boxes with a persistent DM-only marker. This marker overrides player visibility across rendering and copies; labels use normal DM opacity without a hidden-eye badge, and editing/style controls cannot make them player-visible.
## 3.25 Settings File

- All global preferences (sidebar/section state, player screen, tile inches, fog sharpness, light tint, auto-save, text defaults, last import folder) live in `dmmt-settings.ini` **next to the jar** (working directory when run from an IDE; override with `-Ddmmt.settings=<path>`). Nothing is stored in `java.util.prefs` any more; on first start existing values are imported once from the old preferences.
- Plain `key = value` lines with `#` comments; the file is created with every known setting and its default, kept in sync when the app changes a value (atomic write), and unknown keys are preserved. Invalid values fall back to the defaults.
- **Texture defaults** are configurable: `texture.<kind>.color`, `.opacity`, `.softEdges`, `.emitsLight`, `.lightStrength`, `.lightRange` and per animated layer `texture.<kind>.layerN.speedX/speedY/scale/opacity/pulseDepth/pulseHz`, plus global `texture.tileCells`, `texture.featherCells`, `texture.featherPasses`. Hand edits are picked up when the window regains focus (other settings on next start); per-effect values already stored in a map are unaffected. The repository keeps a reference copy of all built-in defaults in `dmmt-settings.default.ini` (generated by `AppSettings.renderDefaults()`; the user's own `dmmt-settings.ini` stays git-ignored). Tests verify generated defaults using temporary files, not the editable reference copy; explicitly regenerate the reference with `mvn test -Dtest=DefaultSettingsFileTest -Dupdate.default.settings=true` after changing a default, setting or texture.
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
- Handouts remain session-only (3.18); only the recent handouts list below is persisted.
- **Selection and partial display (3.30):** click selects one image, Ctrl/Shift+click adds or removes images, Ctrl+A selects all, clicking empty space clears the selection. The right-click menu offers "Show only this image to players" (or "Show only the N selected images to players" when several are selected) and Delete (all selected); an eye-check toggle next to the "Show to players" toggle does the same for the selection and switches the handout off again when pressed a second time. The layout keeps every image readable: for up to 8 images every slicing arrangement (side by side or stacked at each level, in image order) is tried so the smallest image is as large as possible, e.g. a slim vertical image sits next to a stack of two wide ones; more images fall back to rows, and the DM preview is never rotated (rotation applies to the player view only, which the rotate tooltips say) so the DM can tell the images apart. Both need the player window to be open. Only the chosen images are laid out and shown on the player screen (the DM preview still lays out every image and marks the ones the players see with green frames; selected images have blue frames). The "Show to players" toggle shows all images again (turning it on always shows the whole handout; turning it off returns to the map); deleting images also removes them from the shown subset, and the handout returns to the map when the subset becomes empty.
- Implementation: `HandoutLayout` (pure, unit-tested: single image, rows/columns choice, wide vs. tall images, rotation, removal), `HandoutWindow` (image list, hit-testing, selection, context menu), `renderPlayer` draws all images via the shared layout.
- **Recent handouts (persistent):** a vertical **Recent** list on the right side of the handout window shows medium-sized thumbnails (about 160 px wide, aspect ratio kept), most recent first. Every pasted image is added to the top of the list immediately when pasted, regardless of whether it is later shown or deleted from the current handout. **Double-clicking** a recent image (or its context menu entry "Add to handout") adds it to the current handout (end of the image list) and moves it to the top of the recent list instead of adding a duplicate. Duplicates are detected by image content (SHA-256 of size and ARGB pixels), so pasting the same image again also just moves it to the top. Right-clicking a recent image offers **Remove from recent handouts**, which deletes its stored file (images currently in the handout stay); this lets the DM keep a permanent set of recurring handouts and discard one-off ones.
- The list persists across restarts in the folder `dmmap-handouts` next to `dmmt-settings.ini`: one lossless PNG per image named by its content hash plus an index file `recent.txt` (file names, most recent first). Index entries whose file is missing are ignored. Disk work (hashing, PNG encoding, index writes) runs on a single background worker so pasting never blocks the UI; the folder is only created when the first image is stored.
- The number of stored images is limited by the live setting `handout.recentMax` (default 100, 0 to 1000; 0 turns the recent list off and stores nothing). When the limit is exceeded, the oldest entries and their files are deleted; lowering the limit trims the list the next time the handout window opens or an image is added.
- The recent list lives inside the handout window, so it is not a DM sidebar control (no visibility toggle or local API endpoint needed).
- Implementation: `dmmt.ui.RecentHandouts` (folder/index store, unit-tested: add, dedupe/move-to-top, limit trimming, removal, reload), `HandoutWindow` (recent `ListView`, double-click/context menu).

## 3.28 Copy & Paste of Lights, Effects and Text (implemented)

- `Ctrl+C` copies the current selection: a single selected light, effect shape or text box, or a multi-selection (marquee / Ctrl+click) containing any mix of them. Image layers are not copied. (There are no copy/paste buttons.)
- `Ctrl+V` pastes all copied items as one undoable step ("Paste"), keeping their relative arrangement, with the group centred on the mouse cursor (or the view centre when the cursor is outside the canvas). Pasted items get new ids; a single pasted item is selected, several pasted items become the multi-selection.
- The clipboard lives in the app (not the system clipboard) and survives map switches, so items can be pasted into another map. Positions and sizes are copied as-is in world units (no rescaling between maps with different grid sizes). Pasting text boxes re-enables a hidden text layer.
- Pasted lights keep all their settings (range, colour, flicker, reveal mode, on/off); persistent reveals are recorded in the fog history so undo restores the fog.

## 3.29 Ambient Weather (implemented)

- The Weather DM controls include a house-icon **Weather indoors** toggle (`weather.indoors`) for the currently selected weather type. It reads and writes the live global `weather.<type>.indoors` setting (not per-map history), refreshes on weather/map/settings changes, and is disabled for None. It can be hidden via `ui.controls.hidden` without disabling API access. API discovery, no-parameter toggle invocation, right-click copy URL and key-image endpoint use the standard control adapters.
- Each weather type has a live global `weather.<type>.indoors` flag in Settings. Rain, snow and thunderstorms default to false; mist, dust and embers default to true. Disabled indoor weather is clipped against the same enclosed-room classification as indoor lighting, independent of fog visibility, camera zoom and wall-layer visibility. Thunderstorm lightning still enters through open exterior windows/doors using the cached, occluded aperture-light footprint, synchronized with outdoor flashes at every time of day including Night; closed openings and walls block it. Enabling indoor thunderstorms restores unrestricted flashes. Lightning never reveals fog.
- *Weather* section in the DM controls: a type dropdown (None, Rain, Snow, Mist, Dust motes, Embers, Thunderstorm) and an intensity slider (10-100%, default 40%, double-click resets). Both are stored per map (`weather.type`, `weather.intensity`) and changes are undoable; older saves = no weather.
- **Thunderstorm:** rain plus occasional screen-wide lightning bursts in both views. Each strike has 3-5 closely clustered, irregular flashes with varied brightness and overlapping, lingering fade-outs; the flash-duration setting controls the whole burst including its fading tail. Lightning temporarily lifts ambient darkness and adds a pale highlight below text and fog; it never reveals fog or changes light visibility. Intensity controls only rain. An independent, undoable **Lightning interval** slider (2-120 seconds, default 7; lower = more frequent, double-click resets) is enabled for thunderstorms, persisted as `weather.lightningIntervalSeconds` and shared across multilevel maps. Strikes vary deterministically around the chosen interval so both views share their timing; animation off disables flashes, and player freeze preserves the captured flash. Global settings configure thunderstorm rain particles/colour/opacity, default interval, flash duration, brightness and colour. The local API discovers Thunderstorm as an appended dropdown choice (existing indices stay stable) and exposes `weather.lightningInterval` through the normal slider endpoint, including set/increment/decrement and undo.
- Weather particles keep map details readable (rain streaks ~22%, snow ~50%, dust and embers fade in and out). Mist ranges from visible smoke wisps to completely opaque fog at 100% intensity: cached, high-contrast smoke-noise banks drift over a darker, colour-tinted fog body whose opacity grows with intensity squared. Bank opacity grows with the square root of intensity (default maximum 15% per bank), making billows visible at intermediate intensity. At 100% the map is intentionally hidden, but billows remain visible above the opaque fog body; text and fog-of-war remain above weather. Banks cover the whole viewport and wrap seamlessly on both axes, including copies crossing the screen edges, so the middle is not systematically denser than the edges. Existing mist bank-count, colour and opacity settings remain live; intensity does not change bank count. Setting bank count or opacity to zero disables all mist; nonzero opacity controls bank contrast, not the opaque endpoint.
- Shown in both DM and player views, above map, effects and lighting but **below text boxes and fog**. It is screen-space: particle positions are a pure function of index and time (`dmmt.render.WeatherEffects`), so DM and player canvases need no shared state.
- Follows the map's *Animations* toggle (off = static frame, render loop not woken) and Performance mode (half the particles, 5 fps animation). The frozen player view keeps the weather of the moment it was frozen.
- Implementation: `WeatherType`, `WeatherEffects` (unit-tested: counts, determinism, wrap, clamping), `DmProject.WeatherState`, `CanvasMapRenderer.drawWeather`.
## 3.30 UI Polish Pass (in progress)

- Source files stay UTF-8; tooltips use real characters (em dash, degree sign), never mojibake.
- **Status bar clarity:** separate the current activity/message from persistent player-output state (Player view off, live, or frozen). Visually separate player status from the current song using spacing and a divider. Frozen status is bold blue text, remains fully readable, and is shown even when the player window is closed (with an explicit window-off qualifier). Keep the current activity readable in the available width and expose its full text on hover.
- **Optional rendering diagnostics:** actual/target FPS, frame timing, heap use and render-stage timings are shown in a separately opened diagnostics popover, not permanently in the status bar. Its chart icon is distinct from the Performance mode toggle. The diagnostics toggle keeps the panel open until toggled off again; clicking elsewhere or pressing Escape does not dismiss it.
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
- **Per level:** map rotation, fog of war (mask), DM and player camera, lights, walls, doors/windows, effects, text boxes, grid, image layers. Each level retains its own saved rotation; rotating one level does not rotate the others. A level that was never opened starts with its cameras centred on the level.
- **Shared by all levels** (`shared` in the manifest): time of day + ambient brightness per preset, weather (type + intensity + lightning interval), image layer lock, fog on/off, player zoom, text layer visibility and last-used text settings. The open level holds the live values; on every save they are written to the manifest, and whenever a level is loaded they are applied to it. So changing any of them on one level applies to all levels. When a multilevel map is created, the shared settings are taken from the lowest level.
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
- On dd2vtt/uvtt import (single, batch, automatic grouping and new multilevel levels), apply known library tags plus the configurable `import.autoTags` whitelist whose full name is contained in the original filename, case-insensitively. Settings > Import exposes this comma-separated list, initially `TAVERN, INN, DUNGEON, CAVE, FOREST, CASTLE, VILLAGE, TOWN, CITY, SEWER, TEMPLE, CRYPT, RUINS, CAMP, BRIDGE, HARBOR, SHOP, RIVER, SWAMP`. Trim, uppercase and deduplicate configured tags. Changes apply to future imports without a restart or retroactively changing existing maps; an empty list disables only whitelist tags, preserving library-tag inheritance.
- Rank suggestions by fit before selecting the best five: exact matches first, then prefixes, then other substring matches. Within a category prefer an earlier match position, fewer extra characters, then alphabetical order. Re-rank as the input changes, case-insensitively.
- A multilevel map displays the case-insensitively deduplicated union of its levels' tags. Adding a tag applies it to every level; deleting one removes it from every level. Combining maps retains each level's own tags; dissolving or extracting restores those tags including later whole-map changes. No per-level tag editing in Manage levels.
- The existing search bar checks each whitespace-separated search word against the map name or any tag, using case-insensitive partial matches and AND between words. For example, `tav haven` can match tag `tavern` and map name `Haven`. Preserve matching-folder visibility and ancestor folders.
- Tag edits persist immediately through the library-operation workflow, preserving unsaved open-map changes and synchronizing the open level so later saves cannot overwrite tag edits.

## 3.34.1 Map Export and Import (implemented)

- Right-click one map or multilevel map (or a multi-selection of maps) in the map browser and choose **Export...** (**Export N maps...**). A save dialog writes one `.dmmpack` archive (a zip with one top-level folder per map holding the map package as stored in the library; loose legacy maps are packaged on the fly). The open map is saved first if it is part of the export.
- **Import maps here...**, **Import folder here...** (which also finds `.dmmpack` files in sub-folders and re-creates their folder structure) and the import file dialog all accept `.dmmpack` files next to `.dd2vtt`/`.uvtt`, mixed freely; archives go through `BatchImportService` like every other file (duplicate-name check applies to dd2vtt/uvtt only). Every map in the archive is extracted as a new package, renamed `Name (2)`, ... when the name is taken, and receives fresh ids. Archive paths are validated (no path escapes) and a partially extracted import is rolled back.
- Implemented by `MapExportService`; no DM-controls tool is involved, so no settings or local API entry is needed.

## 3.35 Audio: Ambient Music and Sound Effects (implemented)

Optional, fully self-contained audio subsystem so the DM can run ambient music and layered sound effects from the
same application. It must never be in the way of DMs who do not use it, and must not noticeably cost frame rate.

### 3.35.1 Library and storage

- A single, global audio library (not per map, not stored in `.dmmap`), kept in an app-managed folder next to the
  map library: `audio.folder` (default `dmmap-audio`, relative paths start next to the settings file, restart
  required). Layout: `<folder>/library.json` (index), `<folder>/files/` (the audio files),
  `<folder>/peaks/` (cached waveform peaks, derived data that may be deleted at any time).
- Prepared PCM copies live in `files/playback/` (`*.playback.wav` and `*.peak-safe.wav`) and
  `files/limited/` (`*.limited.wav`). Library values remain bare file names; lookup, preparation and deletion
  resolve the appropriate subfolder in code without a schema change. Existing copies are moved manually,
  not migrated by the application; originals remain directly in `files/`.
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
- Sound effects store a per-track `looping` flag in the global library, defaulting to true for new and existing
  entries. Their library right-click menu offers a single checked **Looping** toggle, including multi-selection.
  It is checked when every selected effect loops; toggling applies the chosen state to all selected effects.
  Looping effects display a small repeat icon beside their name in the library; play-once effects and music do not.
  Play-once effects are momentary buttons in both the overlay and local API, never selected. Every click or API
  trigger starts an independent play from the beginning, allowing overlapping plays of the same effect.
  They start immediately at their configured volume without fade-in, play to their end without preparing or
  crossfading into a repetition, and stop immediately without fade-out when stopped or evicted at the limit.
  `audio.effectFadeSeconds` applies only to looping effects; global mute still applies to all audio.
  All running instances share the
  effects pause/stop, volume, mute and `audio.maxEffects` limit; the oldest instance makes room at the limit.
  Disabling looping on an active loop clears its selection and lets its current playback finish once without
  restarting it. Enabling looping affects future triggers only; already-triggered one-shots still finish once.
- The **icon picker** offers the **complete Material Design icon set** (all ~7400 icons of the bundled pack), not a
  short curated list, because a DM names categories anything from "Dragon" to "Submarine". It also includes custom
  illustrated mountain and cave icons, searchable alongside the bundled set. Its **suggested** icons favour
  wilderness and natural ambience (forest, mountain/terrain, volcano, trees, leaves, mushrooms, grass, waterfalls,
  campfires, animals, water and weather) over modern/urban themes; sound effects emphasize natural sounds. A
  **search field** filters the whole catalogue by icon name. Results are capped (the grid shows the first 300
  matches plus a "refine your search" hint) so the dialog stays instant.
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
  seeks, space toggles play/pause, so any point of the file can be auditioned at any time. Playback always starts
  at the playhead, including the very first time the preview voice is opened: a seek issued before the media is
  ready is remembered and applied (and the start delayed) when it becomes ready, instead of being dropped and
  playing from the beginning of the file. The transport button shows a **pause icon while the preview is running**
  and a play icon while it is stopped.
- Peaks are computed once in a background thread (WAV via `javax.sound.sampled`, MP3 via a pure-Java decoder) with
  a progress indicator, and cached in `peaks/<trackId>.peaks` so reopening is instant. The analysis stores minimum,
  maximum **and RMS** per fixed-length frame of `audio.waveformFrameMs` (default 50 ms) rather than a fixed number of
  buckets per file: the resolution no longer depends on the file length, zoomed views stay sharp, and the cache stays
  valid unless the frame length itself changes. Drawing downsamples the frames to the pixel columns it needs.
- A selection (drag on the waveform, or exact start/end time fields with millisecond precision) defines a clip.
  **Auto-detect tracks** proposes one selection per detected song; proposals can be edited or deleted before saving.
- Detection works on the **RMS level per frame**, not on peaks, so a click, a reverb tail or tape hiss inside a gap no
  longer hides it. It is designed to work without tuning:
  - **Automatic threshold** (`audio.cut.autoThreshold`, default on): the silence threshold is derived from the file
    itself as `musicLevel - audio.cut.dropDb` (default 26 dB), where `musicLevel` is the 90th percentile of all frame
    levels. A quiet ambience mix and a loud battle mix therefore use different thresholds without the user changing
    anything. When the automatic threshold is off, the fixed `audio.cut.silenceDb` is used instead.
  - The default drop was measured against a corpus of real one-hour uploads. At 30 dB the threshold lands below the
    short fades between the songs of a playlist, so clearly audible cuts are missed; 26 dB finds them while the
    local-contrast rule below still keeps quiet passages inside a single piece from splitting it.
  - **Relaxation:** if the first threshold splits the file into no more than one track, the detection retries with
    the threshold raised in steps (up to 10 dB below the music level), so noisy recordings still split.
  - **Hysteresis:** a frame enters silence below the threshold but only leaves it 6 dB above, and silences separated
    by less than 300 ms of noise are merged, so single clicks do not break a gap apart.
  - **Local contrast:** a candidate gap is only accepted when its median level is at least `audio.cut.minGapDropDb`
    (default 18 dB) below the louder of its neighbourhoods (75th percentile of the 5 s before and after it). This is
    what suppresses false positives in quiet passages inside a single long ambient piece.
  - Gaps shorter than `audio.cut.minSilenceSeconds` and pieces shorter than `audio.cut.minTrackSeconds` are still
    dropped, except that a **short gap is accepted when it is isolated**: playlists frequently cut from one song to
    the next with a fade of only a few hundred milliseconds, far below any sensible minimum, while the short rests
    inside percussive music come in dense clusters. A gap down to 150 ms therefore counts as a border when the music
    runs uninterrupted for at least a minute either side of it, which tells the two apart without another setting.
    The status line reports the threshold that was actually used.
- **Change detection** (`audio.cut.detectChanges`, default on) splits recordings whose songs are *crossfaded* into
  each other, where no silence exists at all - the common case for hour-long YouTube ambience mixes.
  - The same decode pass that produces the peaks also produces a **timbre fingerprint per second**: the energy of 24
    log-spaced frequency bands from a 1024-sample FFT, made logarithmic, mean-free and unit-length so it describes
    the colour of the sound and not its volume. The fingerprints are stored in the peaks cache.
  - Borders are the peaks of a **Foote novelty curve**: for every second, the audio in the
    `audio.cut.changeWindowSeconds` (default 45) before and after it is compared with itself and with the other
    side. The value is high exactly where two internally similar blocks meet that do not resemble each other.
  - A peak becomes a border when it stands out `audio.cut.changeSensitivity` (default 5) robust deviations from the
    rest of the file. Borders keep at least `max(changeWindowSeconds, minTrackSeconds)` distance from each other,
    stronger peaks winning, which prevents the repeating section structure inside one piece from being proposed.
  - Borders within 5 seconds of a silent gap are dropped as the same border found twice. Silent gaps are cut out of
    the result, change borders are not, because there is nothing to trim.
- **Loop detection** (`audio.cut.detectLoops`, default on) finds uploads that are one piece repeated back to back. Outlier-based peak picking cannot see
  such a file: when every border is equally strong and evenly spaced, no peak stands out from the rest, so the
  novelty detection alone returns nothing and the whole hour stays a single proposal.
  - The mean fingerprint similarity is measured at every lag from `audio.cut.minTrackSeconds` up to 10 minutes. The
    curve is detrended against its own moving median, so a sharp spike counts even though hour-long ambience is
    globally self-similar. The shortest lag that stands out 6 robust deviations *and* is confirmed by a further
    multiple of itself is the loop period.
  - The loop is only applied where it actually holds: the file is scanned for the longest run of seconds whose
    fingerprint matches the one a period later, so a mix that merely *ends* in a loop keeps its varied beginning.
  - The repetitions start at the beginning of that run, not at some offset inside it. The run is where the looped
    material begins, so it is the one repetition border the file actually tells us about; a seamless loop has
    almost no novelty contrast between one phase and the next, so letting a search pick the phase freely only
    drifted it off that border and left an orphaned stretch in front of the first proposal. Only the period is
    refined against the novelty curve, within two seconds of the measured lag, so the borders land on the audible
    seam rather than on the nearest whole second.
  - Only one pass of the loop is proposed: everything from the start of the run to the first repetition border.
    The repetitions after it are dropped without comparing fingerprints again, because the period is not a whole
    number of seconds and a second-aligned comparison would drift out of alignment; the truncated repetition an
    upload ends with goes the same way. The status line reports how often the piece repeats.
  - A looped piece often has quiet passages of its own, so the silence detection splits every repetition into
    several proposals and none of them spans a whole period. The kept pass is therefore whichever proposals fall
    inside that first repetition, which may be more than one; keeping those splits is deliberate, because a loop of
    two songs played in turn should stay two proposals. Without this a three-hour upload of one looped piece stayed
    at 139 proposals - the same fragments over and over.
- **Repeated pieces are proposed only once.** Hour-long uploads are frequently one piece looped verbatim; a proposal
  whose fingerprints match the previous proposal with at least 0.95 similarity and whose length is within 10 % of it
  is dropped. The status line reports how many repeats were removed. Together with the loop detection this turns an
  hour of one song looped 35 times into a single proposal of that song.
- The detected-tracks list supports Ctrl/Shift multi-selection. **Merge selected** replaces the selected proposals
  with one range from their earliest start to their latest end, including intervening gaps; unselected proposals
  remain unchanged. **Delete selected** (also Delete/Backspace while the list has focus) removes proposals and their
  waveform markers and clears the preview selection. **Create selected**, beside **Create all**, exports only the
  selected proposals; **Create all** exports only the remaining list and never automatically restores deleted tracks.
  Proposal editing and detection are disabled during export.
- **Clip name** is retained when selecting proposals and after export. **Create selected** and **Create all** use
  its trimmed text as the base with at least two-digit list-position suffixes (e.g. `Forest 01`, `Forest 03`).
  Before each batch, scan existing library display names (all categories/kinds, case-insensitive) for that exact base
  followed by a space and a numeric suffix, and offset the list positions by the highest existing number. Thus after
  `Combat 12`, exporting another file starts at `Combat 13`; gaps are not reused and unrelated names are ignored. A blank base
  falls back to the source track name. **Create clip** uses the entered name as-is, without an added suffix.
- Select exactly one proposal, adjust the millisecond-precision **From/to** fields or drag a new waveform range,
  then click **Update bounds** to replace that proposal's start/end. The list, waveform markers and both batch
  export actions use the updated range; other proposals are untouched. Reject invalid, empty or out-of-file ranges.
- **Dragging the borders in the waveform** is the direct alternative to Update bounds. The two edges of the
  *highlighted* range are grab handles: within `audio.cut.edgeGrabPixels` (default 6) of an edge the cursor turns
  into a horizontal resize cursor and pressing there drags that edge instead of starting a new selection. The
  opposite edge stays put and the dragged edge is clamped so the two can never cross (the range keeps at least one
  millisecond). This works for
  a hand-drawn selection and, when exactly one proposal is selected in the list, for that proposal: its range in the
  list and its waveform markers follow the drag live, so no Merge/Update round-trip is needed. Only the highlighted
  range has handles, so the borders of the other proposals can never be moved by accident. `Shift` while pressing
  forces a new selection even on top of a handle.
- **Every selected proposal is highlighted** in the waveform, not only the last one clicked: Ctrl/Shift
  multi-selection paints the highlight band and the brighter wave colour over all of them, so what "Create
  selected", "Merge selected" and "Delete selected" will act on is visible. The editable From/to range (and the
  drag handles) still belong to a single range: the one selected proposal, or the hand-drawn selection.
- Playback **loops what is shown** (toggle "Loop", on by default): the selection when there is one, otherwise the
  visible part of the waveform, so zooming into a song is enough to audition it over and over. Reaching the end of
  the looped range jumps back to its start without stopping; playing from before the range is allowed and simply
  runs into the loop. The media-end event also restarts the loop, even if the final reported position is
  slightly below the file duration. Short selections repeat at their exact end, without a minimum playback span.
  With the toggle off, playback stops at the end of the selection or file.
  Editor loops use the same preloaded equal-power overlap as effects (`audio.effectLoopCrossfadeSeconds`,
  capped at half the selected/visible range). This previews the blend without modifying exported clip samples.
  Pause freezes both voices; seeking, changing the loop range or disabling Loop cancels the previous blend.
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
  (`audio.shuffle`, default on; off = library order). The channel loops the category endlessly, never replaying the
  same track twice in a row unless the category has a single track. Controls: play/pause, previous, next, stop
  (fades out).
  **Music crossfades** reuse the sound-effect loop mechanism (preloaded second voice + equal-power overlap) with
  their own music setting `audio.musicCrossfadeSeconds` (default 4 s, 0 = hard cut, capped at half the ending track's
  length; independent of `audio.effectLoopCrossfadeSeconds`). Shortly before a song ends (crossfade + 10 s) the
  upcoming song is preloaded as a silent second voice; the overlap starts once it is ready. This covers every
  automatic music transition: a song following another, a single-song category repeating itself (even with
  looping off), and Loop current song repeating the current track. Preloading only peeks at the playlist: the
  playlist position and any end-of-cycle reshuffle are committed when the transition actually starts, and a
  preloaded voice is replaced when the upcoming song changes (loop toggle, shuffle change, removed track).
  Unknown durations, voices that are not ready in time and failed preloads fall back to the end-of-track event
  (hard cut; failures are logged and stop the music). Pause freezes a blend, mute/volumes/source replacements
  apply to both sides, and stop, manual previous/next and category/track changes release the preloaded voice and
  fade out both sides. Manual previous/next keep their short linear fade. Pending and running music blends use
  the smooth `audio.effectLoopUpdateFps` cadence.
  A session-only **Loop current song** toggle (off at startup) repeats the current track at its end (crossfading
  into its own beginning) instead of automatically advancing to another track. Manual previous/next and category/track selection
  still work; the toggle then applies to the newly selected song. While on, its button has an accent background,
  glow and contrasting icon so the active state is clearly visible. It is never saved in settings or projects.
  Starting an individual music track starts its category with that song first, then follows the normal playlist
  ordering and shuffle behavior.
- **Effects channel:** any number of sound effects (capped by `audio.maxEffects`, default 32) play at the same
  time, as **seamless loops** by default or once when configured in the library. Starting and stopping a looping effect fades it in/out over
  `audio.effectFadeSeconds` so it never clicks.
  Repetitions use a preloaded second voice and an equal-power overlap (`audio.effectLoopCrossfadeSeconds`,
  default 0.5 s, capped at half the clip length) instead of native media repeat, masking restart gaps in ambience
  such as rain. Setting this to 0 disables the overlap. Pause/resume, mute, stop and the effect cap apply to both
  sides of an overlap; pausing freezes its progress.
  Active effects and pending/running music crossfades advance at `audio.effectLoopUpdateFps` (default 30) for smooth blends, independently of the
  slower `audio.updateFps` readout updates; neither timer wakes the map renderer.
- **Volumes:** master, music and effects volumes are independent, persisted in the settings file and applied as
  `master x channel` (a logarithmic/perceptual curve, `volume^2.2`, so sliders feel linear). Pausing is per channel:
  pausing effects pauses **all** currently playing effects at once and resumes them together. The play/pause button
  in the status bar is the "everything" button instead: it pauses music **and** all running effects together, and
  resumes only the currently active selections and paused one-shot instances. Unselecting a category or looping effect stops it; no play/pause button
  may reselect or restart a stopped entry, even when it was the last-used category. With nothing selected,
  and no one-shots running, play/pause does nothing. Paused selections remain selected and resume normally.
- **Mute:** the mute button in the overlay fades everything to silence over `audio.panicFadeSeconds` and keeps the
  current playlist position; pressing it again fades back in. There is no global mute hotkey.
- Audio is DM-side only for the player view and DM Link, independent of freeze, maps and projects; Online Play browsers receive a state sync and play the files themselves (3.41.5).
  Switching, saving or closing a map never interrupts playback.

### 3.35.5 Per-file loudness matching

- Analyze new MP3/WAV imports (including folder imports and newly cut clips) on the existing background workers.
  Measure gated, K-weighted integrated loudness, targeting -23 LUFS, with a -1 dBFS sample-peak ceiling and a
  maximum boost of 24 dB. Silence remains unchanged. Short effects are measured over their available samples.
- Save the automatic gain and an optional absolute gain override in decibels per library track. Older entries
  retain their original level until explicitly analyzed. Never modify imported originals or source files.
- JavaFX media volume cannot amplify above unity. Where amplification needs headroom, create an app-managed
  PCM WAV playback copy with that headroom baked in; keep originals for clip cutting. Gain adjustments remain
  fixed during automatic playback and preserve dynamics.
- Manual overrides allow -60 to +24 dB relative to the original, independently of automatic peak headroom.
  Overrides above peak-safe headroom prepare a gain-specific PCM playback copy using a stereo-linked lookahead
  peak limiter with smooth gain release and a -1 dBFS sample ceiling, not hard sample clipping. Only such
  overrides reduce dynamics; restoring automatic returns to the uncompressed, peak-safe playback source.
  Prepare copies in background workers, persist only after success, keep originals unchanged, and report failures.
  Reopen active instances at their current positions when their playback source changes, retaining pause, fade,
  crossfade and loop state. Waveform previews use the same gain/limiter and source-switch semantics.
- The audio library exposes a per-file Loudness action and gain column: show the automatic recommendation,
  edit the saved override, reset to automatic, and analyze an older file. Changes apply to currently playing
  music/effects and waveform previews at their current positions. Ordinary volume-only edits do not reopen
  voices; limiter source changes do. Master/channel volumes, fades, loops and mute
  continue to multiply the per-file gain. Prepared playback copies are removed when their track is deleted.
- An older file analyzed while already playing switches automatically to its prepared copy at the current
  position when the media source is ready; applying a manual override analyzes an older file if required.
- Analysis/preparation errors are reported through the existing import/dialog error flow, not silently ignored.
  Analysis is bounded-memory, interruptible, and does not block the JavaFX thread. No new global settings.

### 3.35.6 UI

Audio is deliberately **not** part of the DM controls sidebar: it is not a map tool, and a DM who never plays music
should not scroll past it. It lives in two places instead - a small transport group in the status bar, and a
full-screen overlay that is opened from there.

- **Status bar group** (bottom right of the DM window, right-aligned, only present when `audio.enabled`):
  previous, play/pause, next, the **Audio** button that opens the overlay, and a **Library** button that opens the
  audio library window directly, so importing, tagging and cutting do not require opening the overlay first.
  Play/pause here is the "everything"
  button: it pauses and resumes music and all running sound effects together (3.35.4). The Audio button shows the colour
  and icon of the running category, so the status bar doubles as an "what is playing" indicator; a tooltip names
  the current track. The transport buttons are disabled while no category is playing.
  A fixed-width, 180-logical-pixel track-name display sits immediately to the left of the transport buttons.
  It keeps the current name while paused and is blank when music is stopped. Names that fit remain still;
  longer names scroll left automatically at 30 logical pixels/second, with a one-second pause at each end
  before repeating from the beginning. The full name is also available as a tooltip. Track changes and renames
  restart the scroll; scrolling stops while the display is detached or its window is hidden and on shutdown.
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
    active category**, the current track and elapsed/total time, plus the **Loop current song** toggle; the centre of the right ring holds the same-looking
    pause/resume button for all effects and the names of the running effects. Both centres use identical button
    styling so the two halves of the overlay look like one control. The effects pause/resume toggle retains its
    selected state for the API but has no yellow selected background, glow or icon tint, matching music play/pause.
  - Below the rings: master, music and effects volume sliders with percentage readouts, the mute button and the
    button that opens the audio library window.
  - Only entries that are **not hidden** (3.35.2) appear in the rings; an empty library shows a hint that links to
    the library window.
  - Everything is keyboard reachable, and the overlay repaints only while it is open, so a closed overlay costs
    nothing.
- **Local control API:** the status bar group registers `audio.previous`, `audio.play`, `audio.next`,
  `audio.overlay` and `audio.libraryWindow` (its Library button; it opens the same window as the overlay's
  `audio.library`, but is a different control);
  the overlay registers `audio.musicPlay` (the play/pause button in the centre of the music ring; music only, unlike `audio.play`), `audio.stop`, `audio.mute`, `audio.library`,
  `audio.effectsPause`, `audio.musicLoop` (session-only loop-current-song toggle), `audio.masterVolume`, `audio.musicVolume` and `audio.effectsVolume`.
  Every music file has an `audio.track.<id>` button endpoint that starts its category at that track, including
  files in hidden categories; track endpoints follow library changes and expose the file's current name.
  In addition **every
  category and every sound effect gets its own toggle endpoint**, registered dynamically whenever the library
  changes: `audio.category.<id>` plays that category (or stops it when it is already playing) and
  `audio.effect.<id>` switches that effect on or off. The id is the library id of the entry (a UUID), so an
  endpoint keeps working when the entry is **renamed** and never collides with another entry; the readable name is
  in the `label` field of `GET /api/controls`. Hidden entries keep their endpoints. Audio
  category/effect toggle states stay synchronized with playback even while the overlay is closed, including
  hidden entries, so Stream Deck polling clears deselected category highlights without opening the overlay.
  Because the endpoints are (re-)registered after the library changed, they get the **same right-click menu as
  every other control**: "Copy API URL" *and* "Copy key image URL"; re-registering never duplicates those menu entries. A key
  image exported for a category or a sound effect is drawn in **that entry's colour** instead of plain white (very
  dark colours are lightened so the glyph stays readable on the dark key background), so a stream deck full of
  audio buttons looks like the overlay.
- **Audio library window** (own dark-themed window, like the handout/settings windows; it and every audio dialog
  carry the application icon in their title bar): the left side is split into two clearly separated sections - a
  **"Music categories"** list (add/rename/delete, colour and icon editing, drag & drop target) and, below a
  separator, a single **"Sound effects"** entry under its own heading, so the two kinds never look alike. The
  right side shows the track table of the selected category or of the sound effects (name, duration, source), with
  import, rename, delete, change kind, move, search and the **Cut clips** action. The right-click menu of a
  music file offers **"Play"**, starting its category at that song (not the waveform preview). The menu of a
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
- Numeric API tests assert numeric response values independently of locale; spinner editor-text assertions use the spinner's converter rather than assuming a dot decimal separator.
- Fixed control tooltips are mapped separately in `src/main/resources/dmmt/api/control-tooltips.properties`, keyed by the same stable ids. This mapping controls app hover help and the API descriptor's `tooltip` field (including command responses and batched discovery). The plugin preserves `tooltip` when relaying discovery to the property inspector. The Stream Deck control picker displays `tooltip`, falling back to the stable control id if a description is unavailable; the short `label` is used only for key titles, never dropdown labels. Registered wrapped controls and repeated controls share the mapping, including later tooltip refreshes. Unmapped/dynamic controls retain live tooltip/accessibility text, then their display name. Tests use an independent tooltip fixture. Edit either bundled properties file and rebuild/restart; no new application settings.

- **Key images over HTTP.** Every actionable control answers
  `GET /api/controls/<section>/<name>/image`, returning a 144x144 PNG as a binary response
  (`image/png`). Numeric controls additionally accept `?operation=increment|decrement` for the badged variants.
  `image` is a reserved final path segment: no control id may end in `.image`. The image is rendered on demand on
  the JavaFX thread from the live control, so a music category or sound effect always exports its current colour
  and icon. Nothing is written to disk. The endpoint needs no window focus but does require the API to be enabled.
- Master, music and effects volume key images all use the volume-high speaker glyph, including their
  increment/decrement variants, rather than music-note or waves glyphs.
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
- **Control value** - slider/spinner control with a mode of set / increase / decrease and an amount, or a colour
  picker control with a native colour input that sets its selected RGB value. Colour keys highlight while that
  colour is selected and show a solid key-sized swatch of the configured colour instead of the control artwork.
  Colour pickers are set-only; they do not offer numeric increment/decrement.
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

## 3.37 Import and Loading Responsiveness

- Read map image dimensions from headers, reusing the image-pyramid dimension helper; never decode full pixels merely to obtain width/height. Invalid images report an import/add-image error rather than adopting fabricated dimensions.
- Persist a thumbnail input signature covering only visible image assets, their versions, transforms, ordering and whole-map rotation. Skip thumbnail rendering on saves whose inputs are unchanged; recover missing thumbnails and invalidate changed assets. Fog, lighting, notes and camera changes do not invalidate image-only thumbnails.
- Image pyramid identities survive library package rename/move. Preserve asset-content/version and pyramid-option invalidation; legacy caches remain usable or migrate safely. Do not hash complete large images on the UI/render thread.
- Prefetch only the adjacent multilevel floors' overviews and initial viewport tiles in low-priority background work. Bound retained prefetch data, cancel obsolete requests after switching/closing, preserve per-level loading/shared state and frozen output, and report preparation failures without replacing the current map.
- Audio loudness analysis decodes once to collect peak and bounded block-energy statistics, applying both loudness gates to those statistics afterwards with unchanged loudness/peak-safety semantics. Generate waveform peaks in that decode where compatible with the existing waveform analysis/cache; do not compromise timbre fingerprints or clip detection. Playback-copy preparation may retain its necessary second pass.
- Once an audio source is copied safely, persist and show a pending library entry. Rename/category/style edits remain available; pending/failed entries cannot play, enter playlists or open playback previews. Preparation completion atomically publishes analysis and playback sources without overwriting intervening metadata edits. Persist preparation state, surface failures, and recover interrupted preparations on next launch.
- Audio imports use a window-independent preparation queue with per-item copy/analysis/playback stages and within-file progress, cancel remaining/pending work, retry failures and prioritise pending tracks. Closing the library window does not cancel imports; app shutdown does. The task UI remains discoverable through the existing audio library/status controls; no new DM sidebar tool is required.
- Audio library import feedback uses a full-width, multi-row panel separate from library totals and search: explicit ready/active/waiting/failed/cancelled counts, one progress row per concurrently active file with human-readable stage names, and wrapping action buttons that retain their complete labels. Explain that cancellation stops unfinished work without deleting ready tracks, retries include cancelled items, and closing the window leaves imports running. Disable inapplicable actions; filenames/errors retain full tooltips. Keep controls readable at narrow window widths.
- The audio table's Preparation column also shows a full-width progress bar under the stage/percentage for active tracks, and an indeterminate bar while queued. Ready, failed and cancelled rows show their status without a misleading progress bar; errors remain available through tooltips. Reused table cells clear old graphics.
- Preparation is the first audio table column and hides automatically when every track in the current category/search results is ready (including an empty list). Pending, failed and cancelled rows keep it visible; queue polling and category/search changes update visibility without resetting column widths or selection.
- Shared resource-aware scheduling bounds concurrent copying, image preparation and audio analysis independently. Live `work.mode` choices are `session` (default; conservative CPU concurrency) and `preparation` (more background throughput); memory-intensive image preparation stays single-worker. Visible/current work takes precedence over speculative prefetch. Existing rendering/playback clocks, fog geometry, calibrated scale and project persistence stay unchanged.
- Document the workflows in README and all new settings in `docs/SETTINGS.md` and the bundled defaults. Test header reads, thumbnail invalidation, move-stable cache identities, adjacent-only prefetch, one-pass loudness parity, pending playback exclusion, metadata preservation, cancellation/retry and scheduler bounds.

## 3.38 Character Tokens (implemented)

- The left sidebar has mutually exclusive Maps and Tokens tabs; collapsing the sidebar hides both as before. The token browser uses the map library's dark tree styling, thumbnails and names, nested folders, name/folder search and drag-and-drop organization.
- Create named tokens from image files or clipboard images, or from a color and a default character silhouette. The crop editor supports zoom and pan on import and later edits, replacement and removal of the image. Choose Circle (default) or Square; the crop preview and all rendered artwork use that shape. Border is optional and on by default; disabling it removes both the colored outline and added background, preserving image transparency. Image-free tokens retain their silhouette. Default diameter/side length is 1 grid tile, in 0.5-tile increments. Existing tokens default to circular with borders.
- Browser context menus support editing, renaming, duplication and deletion; folders support creation, rename, move and deletion. Deleting a library token never removes placed instances.
- Drag a library token onto the active DM map to create an independent instance, hidden from players by default. Instances can move, resize, toggle player visibility, toggle an optional DM-only name tag (off by default), and be removed through a context menu or Delete/Backspace.
- Movement snaps to half-grid positions; Shift permits free movement. Tokens participate in normal selection, Ctrl multi-selection, marquee selection, group dragging, arrow nudging, copy/paste, undo/redo, whole-map rotation and player freeze behavior.
- While dragging a token's resize handle, show a live DM-only size label at its center using the shape tool's label style. Display the full token diameter in grid tiles (e.g. `2.5 tiles`), never its radius; remove the label when resizing ends.
- Selected map tokens have a DM-only rotation handle above them; dragging it rotates continuously around the token center without angle snapping. Unmodified mouse-wheel scrolling over the selected token rotates proportionally to wheel delta instead of zooming; elsewhere normal zoom remains. Ctrl/Alt wheel retain their existing behavior, and drawing, pointer tools, group selection and active drags must not rotate tokens accidentally. Rotation is per instance (default zero), undoable, copied and persisted; live/frozen player output, selection, hit testing, resizing, culling and whole-map rotation respect it. Rotation handles are canvas selection affordances, not new sidebar controls or settings.
- Library image/crop, shape, border, name and color edits propagate to every map using that token; placed positions, sizes, rotation, visibility and name-tag flags remain independent. Maps retain self-contained token definitions so deleting the library entry or exporting/importing elsewhere preserves the full state. Imported map tokens need not enter the token browser.
- Reusable definitions and nested logical folders live in `dmmap-tokens/tokens.json` beside the settings file. Deleting tokens or folders permanently removes their library metadata and PNG assets not shared by remaining entries, including obsolete artwork. Maps retain their own last-saved copies; deletion does not first update unopened maps. While a token remains in the library, opening, saving or exporting a map resolves its latest definition. Common raster imports include PNG, JPEG, GIF, BMP, WebP and TIFF; unreadable formats report an explicit error.
- Token browser controls must be hideable through Settings and reachable through the local control API. Document the workflow in README and any new settings in `docs/SETTINGS.md`.
- **Token lights:** a placed token instance (never a library definition, so it is not pulled in with the template) can carry its own light. The token context menu offers **Add light (torch)** (default: the Torch light preset) and, once present, a **Light** submenu with the same options as a map light's menu (Light on, Fog reveal, Range, Brightness, Flicker, Color, Blocked by walls) except **Remove light**; switching it off disables it. The light is stored on the instance (`TokenInstance.light`, id `token-light-<tokenId>`), always sits at the token center and follows moves, rotation and map rotation. It is lit/fog-revealed like any light via `DmProject.effectiveLights()`, but has no separate light glyph or selection. Adding/changing it is undoable; it is copied, pasted (with a fresh id), persisted and exported with the token. Adds optional token JSON, so remote compatibility version is 4.
- Editor Ctrl+V pastes a clipboard image without stealing ordinary text paste. Crop centers may reach the source image edges, exposing transparent space outside the source. Hidden instances use the standard crossed-out-eye badge. Token artwork is shaded by lighting; DM badges and labels remain legible above lighting.
- Token originals and crops are PNG files referenced by relative paths, in the library's `images` folder and each map's `assets\tokens` folder, rather than Base64 in persisted JSON. Existing embedded images remain readable and migrate on save. Content-addressed assets preserve frozen snapshots and export includes the latest linked assets without changing the source map.
- Runtime definitions reference immutable file-backed artwork rather than retaining Base64 originals. Originals are decoded only in the editor; rendered crops load lazily into a byte-budgeted cache. Session-owned artwork snapshots survive library cleanup and map relocation and are removed at shutdown. Token artwork has independent retained DM/player canvases below lighting, so token movement does not invalidate the map background. Preserve hidden/player/freeze behavior, legacy saves, shared library updates and export portability.

## 3.39 LAN Remote Workspace Control

### 3.39.1 Purpose and scope

- Operate one workspace from two PCs on the same trusted LAN. PC A is the session laptop/host, with
  the authoritative workspace, player monitor and speakers. PC B is a remote control client, typically
  the stronger desktop used for map creation. This is not an external collaboration or multiplayer feature.
- Both PCs use the same application distribution and JavaFX DM interface. B renders locally from
  synchronized state; do not stream video, screenshots or the host desktop, or inject OS-level input.
- Both A and B remain fully usable during a connection. A has authority when edits conflict.
  The first version permits exactly one connected remote client; reject additional clients explicitly.
- Full feature parity is required: map creation/import/editing, map and token libraries, multilevel maps,
  fog, walls/doors/windows, lights, effects, text, tokens, handouts, settings, audio import/library/editing/
  playback, save/export, undo/redo and player-view controls. A partial control panel is not completion.
- Player windows and all live audio output (ambient, music, overlay, engine) exist only on A while connected. One exception: the audio editor/library preview on B plays locally. B requests a host `playbackFile` job, downloads A's playback file (with baked gain, re-fetched when the gain changes) and previews it through the normal local preview path. While downloading, the editor shows the modal `RemoteBusyDialog` ("Downloading audio", with an MB counter from the connection's transfer progress) so controls cannot be used. `RemoteCompatibility.VERSION` is 3 (host `preview`/`stopPreview` commands and `State.preview` removed).
  B displays playback state and controls A's output; it does not open a local player window or play audio.
- Asset transfer progress (uploads/downloads, direction plus MB counter) is shown by the temporary, mouse-transparent `RemoteTransferOverlay` at the bottom center of B's map view above the status bar, fed by the connection's transfer progress each frame and hidden when idle or disconnected. The Session tab no longer shows transfer progress.
- Internet access, cloud services, multi-client collaboration, offline editing/merging, remote desktop,
  local player previews/audio monitoring on B and automatic compute offloading are out of scope.

### 3.39.2 State ownership and local interaction

| State | Ownership and behavior |
|---|---|
| Projects, active map/level, libraries and assets | A owns and persists them; both UIs show accepted changes |
| Settings and performance mode | A's effective values apply on both PCs; changes from either persist on A |
| Player camera, monitor, calibration, freeze/staged state, handouts | Shared session state, executed against A's output |
| Audio transport, categories/effects, volumes and preparation jobs | A owns execution and status; both UIs can control them |
| Undo/redo | One host-owned history using existing operation eligibility |
| DM camera, active tool and selection | Independent per PC; never force the other DM to follow |
| Mouse, unfinished geometry, focus, open dialogs, clipboard | Local; clipboard content transfers only through an explicit action |
| Native window placement, file-picker source directory, cache location, transport credentials | Machine-local client mechanics, not alternative workspace settings |

- Both PCs display the same active map/level. A map switch invalidates gestures and dialogs tied to
  the previous map; they must not accidentally commit into the new map.
- Local selections reference stable object IDs. Accepted deletion or replacement clears invalid
  selections and refreshes affected editors with an explicit notice if unsaved input is invalidated.
- B does not run host autosave, automatic fog reveal, audio scheduling, library maintenance or other
  authoritative background jobs. Local rendering must never mutate the synchronized project.
- Host connection/revoke/stop controls and local Disconnect remain available regardless of shared
  control visibility settings. B cannot host another session or connect onward while in client mode.

### 3.39.3 Host setup and pairing

- The **Session** tab in the left sidebar (next to Tokens; hideable via `session` in `ui.sections.hidden` / `session.browser` control, API control `session.browser`; forced visible while a session is active) holds the Host/Connect panel. There is no status-bar session indicator or main-menu entry.
- Opening a remote map/import shows a modal progress dialog (indeterminate or byte progress); asset uploads from B to A show the same dialog. B's DM camera is centered by default and remembered per map, never sent to A.
  Hosting/connecting do not depend on enabling the existing local DM control API.
- Host setup offers a session name, LAN interface/address and port, with secure approval required.
  Start hosting explicitly for each application session; remember configuration, never silently restart
  hosting or reopen a connection at launch.
- Remote control is strictly opt-in. Standalone use must not build synchronization snapshots, hash or
  publish assets, scan recent libraries, poll remote state, create remote workers, discover/advertise
  hosts or open listeners. Opening setup may enumerate interfaces only on explicit user request;
  host preparation begins only with Start hosting (client preparation only with Connect).
  Stop detaches all editor/frame hooks immediately, cancels queued remote preparation and lets already
  running bounded work finish without further publication. Idle preparation threads expire; ordinary
  local editing, rendering and library persistence retain their standalone behavior.
- The primary connection flow is approval-only: A explicitly starts hosting, B explicitly discovers
  and selects A, clicks Request control, and A approves that live connection. No copied text, code or
  fingerprint checkbox is required for this flow, including subsequent hosting sessions.
  Discovery includes the session certificate fingerprint as an unverified connection pin: TLS must
  match that exact certificate for control and artwork channels, but discovery does not authenticate
  host identity against a malicious LAN. Explain that names are unverified and this flow is for a
  trusted LAN; A must approve only an expected request. Retain copied-details/code verification as
  an optional manual, independently verified route. Never use a trust-all TLS context.
  Approval-only requests share the bounded rate limit, expiring single pending request and original
  protected-connection grant delivery. Neither discovery nor a request exposes workspace data.
- Round 2: discovery auto-selects the first found host (no connection). Request approval offers **Allow once**, **Always allow** (whitelist, future requests auto-granted) and **Block** (blacklist, future requests rejected without prompt), plus **Deny**. Decisions are host-local in `clients.txt` (storage dir; max 200 entries) keyed by client name + peer IP, which is unauthenticated; entries are removable in the "Allowed and blocked clients" pane. Pair-with-code requests only honor blocks. Sidebar tab headers are equal-width and never clipped; the Session tab has generous spacing. No protocol change.
- The hosting dialog shows listening state, reachable address/port, optional Copy connection details,
  a short-lived pairing code with regenerate action, connected-client status and Stop hosting.
  Copyable details must not contain a long-lived credential.
- B can discover available LAN hosts or enter an address manually. Discovery advertises only minimal
  connection metadata, not pairing codes, credentials, library contents or project data.
  Use a bounded, explicitly triggered IPv4 LAN multicast search (UDP 7074, link-local TTL 1);
  only an explicitly started host answers. Advertise session name, control/artwork ports, compatibility version and
  unverified session certificate pin. Selecting a result clears manual verification and pairing input
  and enables the approval-only request path. Search ends after a short deadline and is
  cancelled when setup closes or a connection starts. Discovery failure never prevents manual
  connection or stops an otherwise usable encrypted host. IPv6-only interfaces use manual details.
- The optional manual route establishes host identity verification bound to pairing;
  do not use permissive certificate trust or treat an unauthenticated discovery response as identity.
  Codes expire, are rate-limited and become unusable after successful pairing.
- After an approval-only request or manual code verification, A explicitly approves or rejects B. Show the claimed client name and address,
  and explain that approval grants full workspace control, file transfer and settings modification.
  No remote filesystem or workspace data is exposed before authentication and approval.
- Remember the last address on B, not permanent access permission. Unexpected certificate/host-identity
  changes require new pairing. Reconnection to the same still-running session may reuse its revocable
  session credential; a host restart or Stop hosting invalidates that credential.
  Remember explicitly submitted host/client names, host interface address and control/artwork ports
  in a bounded machine-local `setup.properties` alongside remote session storage, not the shared INI.
  Read it only when setup opens; serialize background writes with atomic replacement. Never persist
  certificate fingerprints, pairing codes, verification flags, credentials or session state. Loading
  previous configuration must not overwrite input edited while loading or initiate network work.
  Missing saved interfaces remain unselected with an explicit notice rather than binding another NIC.
- Portable Windows builds provide an in-app private-LAN permission flow when Start hosting is clicked.
  Check the selected interface's network profile and DMMT-managed firewall rules off the UI thread.
  If permission is missing, explain the scope and offer Allow private LAN access, Host without changes,
  or Cancel. Only explicit consent may launch a Windows administrator/UAC request. Restrict rules to
  the actual packaged DMMT executable, selected TCP control/artwork ports and UDP 7074, inbound,
  Private profile and LocalSubnet. Never grant shared java.exe/javaw.exe permission, enable Public
  access, disable the firewall, change the network profile, enable UPnP or forward router ports.
  Rules persist across sessions and same-path upgrades; a changed path/ports needs permission again.
  Provide Check access and Remove permission actions; cancellation/failure is visible, never claimed
  as success. Missing managed rules do not prove traffic is blocked, and configured rules do not prove
  end-to-end reachability. Other OS/development runs explain the limitation without changing rules.
  Windows checks suppress PowerShell progress and keep stdout status separate from stderr diagnostics;
  module-loading progress must not turn a successful permission check into an invalid-status error.
- Do not change firewall rules, enable UPnP or forward ports automatically. Listener/binding failures,
  blocked discovery and likely firewall/connectivity problems receive actionable messages; manual
  address entry works independently of discovery. Do not claim to reliably detect a firewall block.
- Remote hosting must not weaken the existing API's origin/host protections or grant authenticated
  transfer access through its unauthenticated endpoints. Explain that the legacy API remains separately
  configured and trusted-network-only; it is not the remote session authentication mechanism.

### 3.39.4 Client transition and connection UX

- Connection states are explicit: Standalone, Connecting, Awaiting approval, Synchronizing, Connected,
  Reconnecting/Disconnected and Returning to local workspace. Hosting has separate waiting/connected/
  stopped/error states. Never display Connected before the initial authoritative state is usable.
- Verify protocol compatibility before replacing B's workspace. Both PCs must have the same explicit
  remote compatibility version defined in code. Increment it whenever a remote-interface change
  breaks compatibility (messages, commands, snapshots, settings or asset-transfer semantics).
  Compatible fixes and unrelated application changes do not require a bump. Never hash application
  files or require identical application builds; packaging and compiler differences do not matter.
  Preserve a typed, locally constructed compatibility error in B's UI, including both compatibility versions and
  protocol versions. Never expose arbitrary parser/transport exception messages or secret payloads.
- Before entering remote mode, finish/cancel local gestures and jobs through normal UI flows, resolve
  unsaved work using Save/Discard/Cancel, and close B's local player output and stop local playback.
  Do not switch contexts while standalone background work could write into the wrong workspace.
- Preserve B's standalone workspace identity, local settings and view context. An unsuccessful or
  cancelled connection leaves its workspace usable and restores any temporary transition state;
  do not discard local work or replace settings merely because pairing started.
- Show meaningful loading stages: Connecting, Waiting for approval, Loading workspace, Downloading
  assets. Large transfers show bytes/progress and cancellation. No authoritative edits until the
  baseline settings, state and necessary active-map assets are ready.
- Authenticated host operation/artwork failures must reach B as actionable error dialogs, with an
  expandable read-only stack trace and Copy details action. Preserve original causes on A; bound
  diagnostic payloads and never include pairing codes, credentials or raw protocol/parser payloads.
  Deduplicate repeated polling failures so dialogs do not flood the UI. Distinguish failed workspace
  publication from a temporary busy state and allow recovery after corrected artwork/context changes.
  Keep unchanged library listings stable (selection, scroll and thumbnails); failed previews are
  explicit and retryable, not cached as absent. Read-only map preview queries require the same host
  library context and a still-existing entry, but not the exact listing revision: on-demand thumbnail
  generation changes listing stamps, and concurrent previews must not fail because of that. While workspace edits are paused, safe local dialogs
  and library browsing remain accessible; unsafe edits report why they are unavailable.
  Shared artwork caches may contain validated namespaces (handouts and operations); account for their
  contents recursively within the cache quota while rejecting symlinks, escapes and malformed files.
  Remote compatibility version 2 carries bounded authenticated failure details in operation replies
  and workspace-wait statuses; both PCs must update for this diagnostic contract.
  Version 4 adds the optional per-token `light` object to token instances in snapshots and edits;
  strict remote decoding rejects it on older peers, so both PCs must update.
- Keep a persistent banner/status indicator: "Remote control - <session> on <host>", connection status,
  current transfer/error status and Disconnect. A shows "Hosting - <client>" with revoke/stop access.
- In remote mode, normal Save/Open/library actions target A. Import pickers and drag/drop read B's
  files; exports download to B. Destination-sensitive dialogs and status messages identify the host.
- Library drag-and-drop on B supports moving maps, multilevel maps and folders (including multiple
  selections) into host folders or the library root through the existing guarded host commands,
  never B's filesystem. Catalog-only browsing remains read-only. This is a compatible UI fix;
  the remote compatibility version remains unchanged.
- Closing B ends its connection, not A's application. Explicit Disconnect restores B's prior standalone
  workspace and settings; it does not automatically resume local audio or reopen a local player window.
- Stop hosting/revoke on A ends B's access without closing A's map, player window or audio.
  Closing A uses normal save/shutdown handling and tells B the host has gone away.

### 3.39.5 Host-backed settings

- Apply A's settings through a temporary effective settings context on B, before presenting the
  remote workspace. Do not overwrite B's INI file, bundled defaults or standalone preferences.
- Every ordinary application setting shown on B reads A's value. Changes from either UI, including
  live host file reloads, propagate through the same validation/application path and persist on A only.
  Failed changes retain the last accepted value and display the host's error on B.
- This includes rendering/performance options, control visibility, fog/effect styles, work scheduling,
  library/storage configuration, player calibration and audio options. Do not introduce independent
  B-only performance overrides in the first version. B still needs hardware capable of its DM view.
- Hardware/path settings address A's devices and filesystem. Display host monitor/device choices and
  use a host-backed browser where a setting requires choosing a host directory; never pass B's drive
  path as though it existed on A. Missing host devices are reported, not substituted with B's hardware.
  Folder browsing is an authenticated, on-demand metadata-only request, never a background scan.
  List host roots or an explicitly entered absolute host directory, with parent navigation and
  128-directory pages. Bound one directory to 4096 children and 1 MiB of names/paths; larger listings
  report an explicit limit and permit entering a narrower path. Do not read file contents or create
  directories. Recheck the live lease after IO and before returning paths. Choosing a folder still
  submits the existing settings edit and its revision/conflict checks, not a filesystem mutation.
- Startup-only JVM/graphics launcher options remain machine-local prerequisites, not remotely applicable
  live settings. Clearly distinguish them from the shared application settings.
- Security/lifecycle restrictions remain local to the relevant role: B cannot approve itself, revoke
  A's authority or change its connection into another host silently through shared settings.
- On disconnect, atomically restore B's local settings context and remove host settings watchers.
  Settings synchronization must not produce feedback loops or write host values into B's local file.
- Document the settings ownership model and all actual new configuration keys/defaults in
  `docs/SETTINGS.md` and bundled defaults when implemented. This spec does not introduce working keys.

### 3.39.6 Edit commits, conflicts and shared history

- Reuse shared application operations for local UI, remote UI and applicable legacy API actions.
  Do not implement remote behavior by firing A's selected controls: B's selection/tool is independent.
  Commands identify explicit targets and context, with stable IDs and relevant expected revisions.
- Local drawing/dragging previews remain on the originating PC. Commit completed shapes, fog strokes,
  object transforms and text edits at their normal gesture boundary, rather than transmitting every
  mouse event. A's player view follows existing local behavior during A's own gestures.
- B may show a pending preview after submission, visibly distinct from accepted content. A's commit
  updates both DM views and player output subject to freeze. Rejection removes/reconciles the pending
  preview and explains why; never show an unacknowledged edit as durably saved.
- Continuous output controls (player-camera movement, sliders) use bounded/coalesced updates with
  a final value and existing gesture/undo semantics. Pings/laser relay bounded transient events with
  expiration; they are not persisted or replayed on reconnect. No remote construction previews required.
- Serialize authoritative mutations on A's application thread; file IO/preparation happens off-thread.
  Assign a session epoch, monotonic state revisions, command IDs and explicit acknowledgement/errors.
  Broadcast accepted changes to both interfaces, including changes originating from A and its API.
- Conflict checks operate on the affected objects/settings and map/level context. Unrelated additions
  or edits do not fail simply because the global session revision advanced.
- A's active local gesture protects the affected targets against conflicting remote commits. Reject
  stale B edits after host changes/deletion/switching; refresh B from authoritative state and explain
  that its edit was not applied. A's local final edit wins an overlapping race without restoring deleted
  objects or overwriting unrelated accepted data. No retroactive rollback of unrelated B operations.
- Library/settings/modal confirmations must also carry relevant revisions; a dialog opened earlier
  must not silently overwrite newer host data when confirmed.
- One shared undo/redo history, retaining the existing undoability rules. Either PC reverses the latest
  undoable accepted operation, regardless of origin. Show the next action description and origin
  (This PC/Host/Remote) in history UI/tooltips; do not create a separate B history.
- Pending/rejected commands do not enter history. Redo invalidation, compound gestures, autosave and
  persistent light/fog changes follow the existing host semantics.

### 3.39.7 Assets, imports, exports and long-running jobs

- Remaining library/audio/file workflows share a bounded authenticated domain-operation channel.
  Each domain exposes an explicit allowlist of typed query/mutation actions, not reflection or
  arbitrary paths. Queries do not mutate data; commands serialize on a host session sequence and
  retain exact in-progress/completed receipts. B retains only its uncertain in-flight command across
  reconnect, never silently queues offline edits. Domain revisions protect dialogs against A's
  intervening edits. Asset uploads/downloads use the existing separate verified bulk transport.

- A owns managed project/library storage; no shared network folder or matching drive layout is required.
  Imports from B upload actual files, validate them and copy all dependencies into A's managed storage
  before declaring success. Source removal on B must not break the imported result.
- Cover raster/custom maps, DD2VTT/UVTT, map packs, multilevel packages, token files/images, clipboard
  images, handouts and audio. Reuse existing format validation and managed-copy/export behavior.
- Separate state/control traffic from bulk asset transfers using authenticated channels, preferably
  a persistent bidirectional state connection and HTTP uploads/downloads. No local absolute asset paths
  in protocol references; use stable asset IDs/content hashes and validated relative package references.
- Transfer only missing assets to B; cache immutable content by hash and verify integrity. Fetch only
  needed content, not A's entire library/audio collection. Local render caches remain machine-local.
- Uploads use bounded staging space, validated names/types/size limits and atomic publication. Reject
  traversal, archive escape and unauthorized path access; never expose arbitrary filesystem read/write.
- Show upload, host import/preparation and publication as distinct stages. Reuse host background job
  scheduling, progress, cancellation/retry and metadata-preservation semantics. Audio clip/waveform
  tools can request host-produced data without enabling playback on B.
- Once accepted as a host job, an import/preparation continues if B disconnects or closes its library
  dialog. A can inspect/cancel it. Interrupted unpublished upload data is cleaned up; reconnect reports
  accepted jobs rather than creating duplicates.
- Export prepares a consistent host snapshot, including linked assets, then downloads atomically to
  B's chosen destination. Never report success before the destination write finishes; failures are
  explicit. Standalone A export behavior remains unchanged.
- Explicit Save on B targets A's accepted map/level. Its independent ordered receipt stream retains
  the exact in-progress/completed result across reconnect, rejects a stale map context or workspace
  revision before starting, and never writes B's replica. Existing maps use A's normal SaveTarget
  (including multilevel shared settings); new maps ask on B for a name and host-library destination,
  validated by A's existing library rules. Snapshot/capture precedes worker IO; acknowledge only
  after persistence and host reconciliation. Later host edits remain dirty, not overwritten. An
  accepted save may finish after disconnect/revoke; recovery must not repeat it or select a new name.
- The remote map browser uses an explicitly refreshed, bounded host catalog, never a scan of B's
  library. Catalog identities are session-scoped opaque handles, not host filesystem paths. Retain
  B's tree/selection/search/recent state for restoration. Host scanning runs off the application thread,
  rejects oversized/deep libraries explicitly, and starts only after an authenticated request. Initial
  browsing covers the folder/map tree and search; map commands, host thumbnails/tags/recents and
  automatic catalog invalidation remain separate parity work until wired end-to-end.
  Limit this catalog to 1024 items, 24 nested folders, 65536 inspected filesystem entries and 65536
  name characters; reject malformed topology, oversized labels and inconsistent current-map handles.
  B uses only synthetic handles in its existing tree, with no local thumbnail reads/recent-history
  writes. Ignore late responses and queued standalone thumbnails when switching browser contexts.

### 3.39.8 Resilience, performance and observability

- A continues player rendering, audio and normal local operation when B is slow, disconnected or closed.
  Network work must not synchronously block A's JavaFX thread, render loop or audio clocks.
- On connection loss, B retains a clearly stale last view with "Disconnected - editing paused".
  Disable authoritative editing, discard/cancel unfinished gestures explicitly and permit Disconnect.
  Do not silently queue offline writes. Retry with bounded backoff and a visible reconnect/cancel action.
- Resolve unknown command outcomes by command ID on the same session; do not replay blindly.
  Retries apply each command at most once. If A restarted or retained acknowledgement history is
  insufficient, force fresh synchronization and report uncertainty rather than guessing success.
- Reconnect resumes from revisions only when continuity is provable; otherwise fetch a new baseline.
  Join snapshot and subsequent updates without missing/reordering mutations or exposing mixed settings/
  map versions. A's own interaction is not blocked for the duration of an asset download.
- Bound transport queues, payload sizes, concurrent transfers, upload staging and client cache retention.
  Coalesce stale continuous previews/status updates, never drop durable commits; resynchronize a client
  that falls too far behind. Bulk transfers must not starve input acknowledgements or host playback.
- Do not add a hidden renderer/canvas stack on A for B. B renders its DM view only, without player
  canvases. Preserve native sharpness, existing frame-rate settings, calibration and freeze output.
- Synchronize animation parameters/seeds/timing where appropriate, not per-frame particle/texture data.
  A alone applies gameplay-affecting simulation results such as persistent fog revelation.
- Expose connection/synchronization/transfer failures and log command IDs, revisions and stage timings
  without credentials/pairing codes. Distinguish host commit latency from transfer/render time.
- Provisional acceptance budgets, to measure on documented hardware over wired LAN: small committed
  edits visible on the peer within 150 ms at p95, excluding bulk loading; synchronization adds at most
  10% to A's p95 application-thread frame work in the same representative scene. Record baseline and
  connected results, including concurrent uploads; do not achieve budgets by reducing output quality.
  These are implementation targets, not claimed existing performance guarantees.

### 3.39.9 Delivery and acceptance

Handouts reuse the existing modeless board on both PCs, with local image selection and host-owned
player presentation. Publish immutable PNG assets off-thread before accepting board changes; bound
decoded pixels as well as transfer bytes. Board mutations carry a session, revision and exact receipt.
Preparation rechecks authorization and host revisions before applying; host edits during an upload
win. A paused client cannot alter the board, and leaving remote mode restores its original local
handout window without hiding A's presentation. Recent-handout library synchronization is a separate
parity requirement; never read or update B's standalone recent list as if it were A's.

Player-output runtime state is separate from persisted map edits. Synchronize the host's actual
window visibility, canvas dimensions, scale-test state and monitor choices without creating player
canvases on the client. Window/test-square commands carry expected runtime revisions and bounded
exact receipts. Player viewport hit-testing on the client uses the host canvas dimensions.
Relay pings and laser positions through a bounded, context-scoped transient channel; expire them,
discard unsent events on connection loss, and never retry/replay them as workspace edits.

Workspace integration uses explicit domain targets, not host control firing or whole-project
last-writer-wins replacement. Capture committed project changes as stable-ID entity changes,
individual map/output properties and bounded fog blocks. Expected target revisions include
deletion tombstones and changes away-and-back. Authoritative commits merge into the current map,
never replace unrelated host edits or the independent DM camera. A map/level context epoch invalidates
old commands even when switching away and back to the same file. Protect host gesture targets
before accepting remote changes; compound commands validate completely before any live mutation.
Remote undo/redo must enter the existing host history, with explicit origin/description metadata.
Portable asset references must be resolved through the approved session's managed content store,
not interpreted as arbitrary host/client filesystem paths.
The application bridge must preserve its existing live project and history objects. Publish newly
referenced host artwork on a worker before advancing the portable committed baseline; reject edits
while that baseline is being prepared rather than applying against stale paths. Already-published
artwork is rewritten in memory at ordinary commit boundaries. Uploaded artwork is verified and
materialized off-thread before owner-thread conflict checks, with the original map epoch retained
throughout preparation. Closing/switching invalidates late preparation callbacks. History callbacks
apply only the original operation's targets and never restore an entire project over newer edits.
The application-owned bridge now observes history/undo/redo, map switches, copied asset paths,
token-definition updates and stable fog/player-camera boundaries. It is installed explicitly only
for a host session, so ordinary standalone frames do not capture synchronization documents.
Accepted remote edits enter the existing history with remote-origin metadata; player-freeze edits
operate the real output. Active entity, fog, text, group/nudge and slider targets are protected;
controls are refreshed after host gestures rather than resetting their in-progress commit baselines.
Persistent-light reveals and fog geometry changes generated by the host are included in the same
accepted document and targeted history inverse. Unchanged persistent lights are not reapplied after
undo restores fog. Background artwork preparation rechecks the original live control lease before
any application mutation. A typed preparing response pauses workspace edits without interrupting
settings synchronization or treating an incomplete baseline as ready.
Client workspace integration prepares and verifies active-map assets before displaying each accepted
baseline. Coalesce superseded snapshots while downloading, never acknowledgements; defer ordinary
host updates during a local gesture, then submit against the gesture's original accepted revisions.
Disconnect invalidates late preparation callbacks and pauses edits without replaying them offline.
Shared undo/redo requests use the same ordered workspace receipt stream as edits and carry the
expected host history version, so a delayed Undo never reverses a newer, different operation.
History descriptions/origins travel with workspace state; no independent client undo stack is used.
The internal client editor lifecycle now retains the standalone project object, selection, history,
multilevel identity and settings context while using a separate remote project. Completed editor
operations and Undo/Redo submit to the host; routine accepted changes preserve the client's camera
and live entity identities. An owner-thread settings pump refreshes effective controls and retains
exact uncertain settings requests. Client lighting computes render-only visibility without creating,
resizing or revealing an authoritative fog mask. Client autosave/local API/player-window creation
are suppressed by role; entering stops local playback and closes player/handout output. Closing the
replica closes its control connection/transfers and restores the local workspace without reopening
output or restarting audio. A separate status/Disconnect control remains usable while edits are paused.
This internal lifecycle is integrated with the modeless setup and unsaved-work prompts. Library/file
commands, host path dialogs and the remote audio facade still need wiring before menu exposure.
Host player-window visibility, scale-test state, actual canvas dimensions and monitor metadata now
travel through a separate authenticated runtime channel. Client readiness also waits for matching
output/map contexts. Runtime controls use expected revisions, ordered requests and bounded exact
receipts, including failed attempts; old or altered receipts never repeat side effects. Host-local
output mutations advance the revision even when changed away and back between network polls.
The client uses host geometry for viewport hit-testing, dragging and edge scrolling, without creating
player canvases. Calibration settings refresh the host controls through an in-memory owner-pulse
notification. Both PCs' laser trails remain independent; bounded pings/laser positions expire, clear
on context changes/loss/reconnect and never enter map history or the durable retry stream.
Handout board snapshots and completed mutations now use their own authenticated runtime authority.
The existing modeless UI applies accepted snapshots without echo, retains local selection, and
routes pasted images, show/hide, selected-only presentation, rotation and mirroring to A. PNG
encoding, hash publication, transfer verification and bounded image decoding happen off-thread;
clipboard source files are dimension-checked before decoding and import only the first frame.
Board limits are 64 images, 16,777,216 pixels per image and 33,554,432 pixels total, in addition to
asset byte quotas. Failed preparations retain no unaccepted decoded-image cache entries.
Host changes during uploads/preparation win through expected revisions, including changes away
and back. Commands use exact bounded receipts and recheck the live lease after decoding. Connection
loss freezes the client board; leaving remote mode restores its retained local handout window and
does not hide A's presentation. Initial workspace readiness waits for the handout baseline as well.
The remote board never opens B's persistent recent-handout store. Replicate A's recent list using
opaque validated IDs, verified original PNG descriptors and bounded thumbnail assets. Download
thumbnails, not all full-resolution images. Reusing/removing a recent entry uses the same revisioned,
exact-receipt stream as board operations; uploads add their new images to A's persistent recent list.
Serialize library disk work with local recent-list mutations and check its version before remote
mutations. Acknowledge persistence failures explicitly, including operations whose board side effect
already happened; do not repeat them after reconnect. The existing handout.recentMax setting governs
both PCs. Local recent-list changes and settings-driven trimming invalidate stale remote commands.
The host recent library is now integrated with the board authority and the same recent pane on B.
Opaque IDs and verified original/96-by-144 thumbnail descriptors travel with the handout state.
Only thumbnails are preloaded; adding a recent image prepares the board from its published original
on A, and B obtains the full-resolution asset when it becomes a board image. New remote pastes persist
in A's existing PNG/index store, and reuse/removal share its serial disk worker with local actions.
Version changes, including away-and-back reorderings, invalidate stale requests; in-flight own
persistence is distinguished from host mutations. Disk failures have durable failure receipts, and
late host edits cannot overwrite the host board even if a recent image has already been saved.
Local index/PNG operations continue to use the configured settings-file location, not another
application's global/default settings path. Control polls carry a small state stamp and return an
unchanged acknowledgement rather than retransmitting the entire board and up to 1000 recent entries.
Session lifecycle coordination must prepare the certificate, asset store and both
listeners off the application thread, then install the host workspace on its owner. Starting either
listener is an all-or-nothing operation. Stop revokes access immediately, invalidates pending startup
and client-transition callbacks, and completes only after control and bulk workers release their sockets.
Client setup keeps the standalone workspace untouched through pairing and approval, installs the
replica only after settings and workspace baselines arrive, and reports Connected only after required
artwork is ready. Failed setup restores any installed context; ordinary connection loss retains a
paused replica for exact-receipt recovery. Explicit reconnect and Disconnect remain distinct actions.
The setup window is modeless so hosting does not block the editor. Its copied connection details
contain only the session label, address, ports, compatibility version and certificate fingerprint, never the pairing
code or reconnect credential. Pasted metadata is untrusted: the user must explicitly confirm that
the fingerprint was obtained from the intended host, and editing the host identity clears that
confirmation. This is the optional advanced/manual route; discovered hosts use Request control
without copied details or codes, pinned to the unverified discovery certificate. Host approval shows
the claimed client name/address and the full-control permission. A new request brings A's session
window forward once, even if it was hidden, without approving automatically.
Closing a pending client setup cancels that attempt; hiding an already connected or hosting window
does not end the session. The Session menu exposes this window without starting a session.

Implemented session connection coordination: in-process, session-only host certificate provisioning
without external executables, explicit `RemoteCompatibility.VERSION` negotiation, and bounded host/client
connection coordinators. Approval and grant delivery are bound to the original TLS connection;
every settings request requires a live authenticated lease. Poll authoritative state without permitting
unauthenticated reads, and recover exact in-flight command receipts on reconnect. Network IO and
approval waits run on cancellable background workers; application-owned state is accessed only
through its owner-thread dispatcher. The Session menu exposes this lifecycle explicitly.
Session certificates are deliberately regenerated when hosting starts; discover/select/request again
for a new host session (only manual connection requires new copied/verified details). No private key or access credential is
written to disk, and hosting is never started implicitly.
One approved client is active at a time. Incoming approval requests always show a modal approval
dialog on the host (`RemoteApprovalDialog`; the Session tab card remains an alternative). A request
arriving while a client is approved or connected is never rejected for that reason: the second
bounded TLS transport slot holds the challenger without blocking the active client's control
traffic, and if the host allows it the new connection replaces the old one, which is closed. Only
one pending request exists at a time (others get Busy), and pairing-code requests are still Busy
while a client is approved. TCP-level
cancellation bounds TLS shutdown/deadlines even when the peer stops reading. Stop completes only
after peer cleanup and host-owned access disposal; callers must not block the owner thread waiting
for shutdown. The client coalesces incoming state, bounds command queues, and reports SETTINGS_READY
only as an internal settings baseline, never as a synchronized/usable remote workspace.
Idle polls carry the last accepted revision and sequence; unchanged responses contain no settings
snapshot. Only changed settings/baselines are serialized and transferred, rather than sending the
entire settings collection every heartbeat.

Implemented settings foundation: reuse `AppSettings` typed access and runtime
application, isolate host snapshots from the standalone INI, route edits as nonblocking host requests,
apply only accepted revisioned snapshots, pause writes on connection loss and restore local settings
on context closure. File-picker source directories remain client-local and session-only. The application session lifecycle now integrates this foundation with explicit hosting and connection.

Implemented host authority foundation: a host settings authority validates requested values using existing setting
metadata, persists before acknowledging, versions individual keys (including changes away and back),
and returns explicit conflicts/errors. A session epoch and ordered client sequence bound a finite retry
receipt cache: an expired receipt or missing sequence requires resynchronization, never blind replay.
Local settings edits and external file reloads participate in the same revision tracking. Transport must
marshal operations through the application thread and schedule persistence without network-thread races;
the application lifecycle supplies the authenticated UI/network integration. The settings-file
writer has a nonblocking remote path: owner-thread preparation, worker persistence and owner-thread
reconciliation/commit. Local host edits during IO are preserved and checked before acknowledgement.
Frequent state polling reads accepted memory without filesystem calls. Authoritative snapshots include default text for absent keys, so explicit defaults written
by the INI renderer do not create phantom conflicts on reload.

Implemented transport-independent pairing/access lifecycle: cryptographically random,
short-lived pairing codes with bounded verification attempts, expiring approval requests, explicit
host approval, one-client enforcement, revocable session-only reconnect credentials and stop/revoke
cleanup. Client names are untrusted display claims. This component does not authenticate network
transport: invoke it only through the exact-pinned encrypted connection; the approval-only discovery
route supplies channel continuity, whereas the optional manual route independently verifies host identity.
Only explicit Start hosting enables listeners. An application-thread host
session coordinator binds this access lifecycle to the settings authority: snapshots and commands
require a live connection lease; reconnect retains command receipts, while revoke/stop destroys them.

Implemented application-thread client settings coordinator connects `AppSettings.HostContext`
to the host request/receipt protocol. Bound pending edits and allow only one in-flight command; retain
its exact identity for retry after connection loss, explicitly cancel unsent edits, apply accepted
snapshots without echo, and surface conflicts/save failures/uncertain outcomes. Reconnect must verify
the settings epoch and sequence continuity before allowing further edits. No network or workspace
context switch is enabled by this component.

Implemented encrypted transport boundary: TLS 1.3 with an exact SHA-256 host
certificate pin supplied through discovery or optional host-copied/independently verified details.
Discovery pins are never presented as independent identity proof. Perform protocol/compatibility-version negotiation
before submitting an approval request, pairing code or credential.
Use length-prefixed, strictly parsed JSON envelopes with bounded payloads/nesting and socket deadlines;
close malformed/timed-out channels. An explicitly bound listener permits one transport connection
by default; the session router uses two bounded slots to reject an additional peer explicitly.
Stop closes the listener and all accepted sockets, including pending handshakes. The transport accepts
a supplied host key store, with session-only key/certificate generation now provided by `RemoteIdentity`.

Implemented editor-domain foundation: committed map properties, stable-ID entities and bounded raw
fog blocks have target revisions, deletion tombstones, host gesture protection, map/level context
epochs and exact retry receipts. Targeted application preserves unrelated edits, existing object
identities and the independent DM camera, including targeted undo data. Client reconciliation preserves
a pending completed gesture across unrelated host updates, rolls rejected edits back and retains
uncertain requests for exact receipt recovery. Host application gesture/history hooks and portable
artwork preparation are wired. Host/client application lifecycles and the internal setup window are
also implemented. The Session menu now opens the opt-in host/connect window.
Portable artwork checks reject ordinary filesystem references at the network boundary.

Implemented managed bulk transport: session-authorized, separately pinned TLS connections stream
64 KiB chunks into quota-bounded content-addressed storage, verify SHA-256 and length before atomic
publication, report byte progress and support cancellation. A live control lease is required, and
revocation invalidates existing transfer checks. Downloads expose only explicitly published hash
objects, not a path endpoint; map/layer/token artwork references can be packaged as `asset:<sha256>`
and independently materialized into verified local caches. Bulk traffic does not share the control
socket. Large workspace payloads retain the 1 MiB frame cap through uncompressed chunks with a
32 MiB aggregate limit and an overall transfer deadline; no peer-provided compressed fog is inflated.
Control command queues wake immediately when work arrives rather than waiting for an idle poll.
Map/token/audio imports and exports use this managed transport and exact domain-operation receipts.

Implementation and verification checklist:

| Surface | Implementation status |
|---|---|
| Strict opt-in and standalone overhead | Inactive frame/gesture boundaries do not capture state or initialize services; lazy preparation threads expire, and stopped sessions skip queued incoming/outgoing artwork work. Standalone, stop/restart and queued-cancellation regression coverage implemented |
| TLS, remote-version compatibility, code/approval, one-client access, revoke/stop | Host/client application lifecycle, atomic two-listener startup, approval/revoke and joined socket/worker shutdown implemented |
| Shared settings, conflicts, retry receipts, local INI isolation | Nonblocking host persistence and client settings pump integrated; host monitor choices/calibration, in-memory host refresh and authenticated map/audio folder browsing with revision-bound confirmation implemented |
| Host/Connect dialogs, discovery/manual setup, banner, local-workspace restoration | Discovery-first Request control and approval on A need no copying/codes; advanced manual verified pairing retained. Modeless setup, explicit discovery, remembered non-secret setup, byte progress, prompts and local restoration integrated |
| Committed tokens, effects, text, lights, walls/doors, fog and image layers | Host and client editor bridges integrated; verified asset preparation/upload precedes commits, unfinished gestures remain local |
| Map/level switching, independent DM state, host gesture protection, shared history | Host history/context hooks, client Undo/Redo, library switches and remote level selector integrated; save/discard guards and workspace revision validation precede leaving the host map |
| Map/token libraries, imports/exports, validated assets and job synchronization | Host-backed map tree/search/thumbnails/tags/recents, folders, context menus, multilevel management, uploads/downloads and token library/editor integrated through bounded exact-receipt operations |
| Explicit Save, new-map destinations and multilevel persistence | Client Save/Ctrl+S waits for completed edits, uses A's save target or a B-side name/host-folder dialog, validates destinations against A's library, and acknowledges persisted snapshots with exact reconnect receipts; later edits stay dirty and B never writes its replica |
| Host player output, freeze, calibration, host devices/paths, handouts, pings/laser | Real freeze/history, window/test-square controls, host monitor choices/calibration, host-sized viewport, expiring pointers, handout board/PNG transfers, persistent host recent-handout library and library-folder browsing integrated |
| Audio library/import/editing/playback, host-only scheduling/output | Existing audio UI uses a remote backend for host-only output, uploads, `playbackFile` download for local editor preview on B,, waveform/cut/loudness jobs and cancellation; disabled local audio uses an inert client facade. Enabling/disabling audio applies live, including API registration; changing A's audio folder remains restart-pending |
| Two-process acceptance and performance evidence | Independent JVM peers verify approved TLS pairing, completed edits, exact operation receipts, host persistence and byte-identical client INI preservation. A 24-edit loopback run measured 7 ms median / 9 ms p95 acknowledgement latency; this is not a physical-LAN or GPU/frame/memory benchmark |

Map imports preflight A's original filenames using A's ask/always/never duplicate policy. Confirmations
are one-use and context/revision-bound. Single-source imports retain B's chosen map name and host
destination; folder imports never switch away from the active map. Save-before-import renews the
confirmation after persistence and rejects newly unapproved duplicates explicitly. Partial imports
report per-source failures on B. Renaming/moving the open map preserves A's live objects and shared
history. Explicit Save exposes a persistence completion future for subsequent map actions.

1. Extract shared operations/settings context incrementally; prove standalone UI, history, persistence,
   player freeze and the existing local API remain unchanged.
2. Deliver authenticated host/connect/disconnect and baseline synchronization with a vertical slice:
   token move, completed shape, host setting change, player viewport/freeze and shared undo.
3. Complete every map/editor/library action, asset transport, audio/handout workflow, host device/path
   settings UI and reconnect/conflict behavior. Maintain a parity checklist by existing UI action,
   recording implementation, ownership and verification; disabled substitutes do not count as parity.
4. Document actual setup, host/client labels, file destinations, recovery, network/firewall guidance,
   settings behavior and performance in README, `docs/SETTINGS.md` and API docs as applicable.
   New DM sidebar tools, if any, must be hideable and accessible through the local API; connection
   lifecycle controls stay in the main menu/status UI.

- Automated checks cover protocol validation/versioning/authentication, code expiration/rate limiting/
  revocation, unauthorized asset access, ordered snapshot/delta delivery, revisions, conflict scope,
  command deduplication, shared history, settings isolation/restoration, path validation, atomic
  transfers, cancellation/retry, reconnect outcomes and bounded queues.
- Two-process acceptance: edit from both PCs with independent cameras/tools/selections; completed
  shapes appear only after commit; simultaneous independent edits succeed; host-overlap conflicts
  reject B clearly; map switches invalidate stale edits; undo/redo produces identical accepted state.
- Settings acceptance: change settings from both UIs and reload A's INI; verify both effective contexts,
  host-only persistence, A device/path choices, and byte-for-byte preservation of B's local settings file.
- Workflow acceptance: create/import maps and tokens from B, edit/export them, import/cut/play music
  on A, switch maps/levels, paste/show handouts, control calibrated/frozen player output and save/reload
  on A. Remove original B sources and confirm the workspace remains self-contained.
- Recovery acceptance: disconnect during a gesture, after command submission before acknowledgement,
  during upload and during a host-owned job; reject a second client; restart A; stop/revoke hosting;
  close B; restore B's standalone workspace. No duplicate entities/playback, silent loss, partial
  published assets, accidental B autosaves or player/audio disruption on A.
- Benchmark idle and busy maps, large uncached imports, cached reconnects and audio preparation while
  connected; report p50/p95 commit latency, host frame work, memory/queue bounds and transfer throughput.

## 3.40 Application Logging (implemented)

- All activity is logged through `dmmt.log.Log` (slf4j-style `{}` placeholders; a trailing `Throwable` is logged as the exception), a thin facade over `System.Logger` which the JDK routes to `java.util.logging`. No third-party logging dependency.
- `dmmt.log.LogSetup` configures logging once, before JavaFX starts (launcher): console handler plus a rotating file (`dmmt-<n>.log`, 5 files of 2 MB) in a `logs` folder next to the settings file. One line per entry: `timestamp LEVEL [thread] Class - message`, followed by the stack trace when present. Uncaught exceptions on any thread are logged as errors.
- JVM properties (not settings-file entries): `-Ddmmt.log.level=ERROR|WARN|INFO|DEBUG|TRACE` (default `INFO`, applies to application loggers only; libraries stay at `INFO`) and `-Ddmmt.log.dir=<folder>` to relocate the log folder.
- Levels: INFO = lifecycle and user-visible operations (startup/shutdown, map open/save/auto-save/import/export, library changes, window opening, player freeze, audio playback, API/remote sessions; every DM status-bar message is also logged), DEBUG = internal detail, WARN = recoverable failures, ERROR = broken features. Failures always include the exception.
- Never log per-frame, per-mouse-event or per-audio-buffer activity, nor secrets (remote tokens, pairing codes, TLS key material).
- Music and sound effects are logged by name, never by id alone: `AudioLibraryService.trackLabel(id)` renders `'Name' [id]` and is used by the engine, preparation queue, remote audio commands and UI.
- Local API: every non-polling request logs `METHOD path -> status (ms, client)` at INFO (key images and the control list at DEBUG), executed controls log id plus label, rejections log at WARN. Remote sessions log access decisions, TLS/compatibility rejections, firewall rule changes, asset and workspace transfers.
- Every committed edit (`pushHistory`) logs `Edit: <label>`.

## 3.41 Online Play: DM-hosted browser clients (in progress)

### Beastbound Ranger companions
- Add a conditional **Companion** tab immediately after Character, before Domain Cards. Store the animal's name/type, Experiences, standard attack, physical/magic damage type, damage die/range, Evasion, Stress, availability and learned training on the owner's sheet, not as a separate actor.
- Follow the official SRD Ranger rules and companion sheet: Evasion 10, three of six Stress slots, two +2 Experiences, d6 Melee damage using the owner's Proficiency and Spellcast trait. Command/attack rolls can spend Hope on a companion Experience and inherit the owner's applicable attack benefits, including Ranger's Focus. A successful Hope command may grant advantage to the next action that builds on it, with the prerequisite confirmed by the player.
- Character creation includes companion setup for Beastbound. Existing/multiclass Beastbound sheets without a companion show only a Create companion button opening the same focused wizard. Do not invent an animal or overwrite an existing companion. Deferred setup includes earned training and milestone Experiences.
- Normal level-ups include one companion training choice, an additional choice when Expert Training is acquired, and two when Advanced Training is acquired; the training grants are one-time, not per subsequent level. Intelligent, Vicious, Resilient and Aware each have three selections; other options have one. Intelligent targets an Experience; Vicious raises the damage die (d6/d8/d10/d12) or range by one step. Each new owner Experience also grants a companion +2 Experience.
- Light in the Dark adds a distinctly marked extra owner Hope slot before scars. Implement Creature Comfort's shared Stress recovery or Hope, Armored's owner Armor substitution and Bonded's last-HP rescue. Battle-Bonded's conditional Evasion is passive; Loyal Friend announces its once-per-long-rest use while players resolve the redirected damage. Positional/narrative prerequisites remain player-confirmed, never assumed automatically.
- Any positive companion damage marks exactly one Stress. Full Stress makes it unavailable until the start of the next long rest, returning with one Stress cleared. Downtime moves clearing the owner's Stress clear the same amount for an available companion. Preserve edits, training history, spent uses, pop-outs and persistence. Keep added sheet/config fields optional and existing rolls usable; no settings or DM controls, and no breaking Online Play or DM Link compatibility changes.
- Armored also protects the companion when Loyal Friend redirects owner damage to it; choose either companion Stress or one owner Armor Slot, never both interceptions for the same attack.
- Companion training uses the normal level-up card picker pattern: visible descriptions, selection counts, remaining uses and Remove one controls, with card-based Experience/attack detail choices instead of dropdowns. Wizard descriptions remain sentence case; uppercase styling applies only to section headings.
- Companion attacks reuse the active weapon card, controls and chat-sharing styling; put the command/attack guidance in its feature box. Space Command/Take damage controls and use full-size character Stress pips. Light in the Dark is the last usable Hope slot before scars. Battle-Bonded is a player-checked passive, not an activated Evasion effect. Loyal Friend's sheet action only announces its use to chat and spends its long-rest use; players resolve the redirected damage themselves. Hold Them Off is a damage roll option costing 3 Hope after a successful weapon attack.
- Keep Hold Them Off's normal 3-Hope action button in addition to its damage roll option. Loyal Friend announces/spends immediately without a confirmation prompt. Host chat-sharing validation resolves the generated companion weapon from the owner's saved companion state; it is shareable but cannot be transferred as inventory.
- Explain Spellcast commands, spending Hope on companion Experiences and conditional next-action advantage directly in Beastbound's Companion feature, refreshing that description on existing sheets as well.
- Verify completed level-one Ranger creation through the actual Character Mancer and rendered sheet: initialize six usable HP slots and preserve them across save/reload, not just the pure build helper.

### 3.41.1 Terminology and UI
- **DM Link** is the existing 3.39 LAN remote control between two DM machines. The former "Session" tab is renamed **DM Link** (labels, README, `docs/SETTINGS.md`); the stable ids `session` (`ui.sections.hidden`) and `session.browser` (`ui.controls.hidden`, local API) are unchanged so existing settings keep working.
- **Online Play** is a new tab next to it, for players in browsers with restricted access. Its text states that it is not DM control. Hideable via `online` in `ui.sections.hidden` and `online.browser` in `ui.controls.hidden`; the tab's controls (`online.*`) are exposed by the local API and individually hideable. DM Link and Online Play work independently of each other.
- **Panel layout (UI overhaul):** a header with a session card (active campaign and system, status dot and text, invite link with a copy button, Start/Stop) above four subtabs. Tables and right-click menus replace rows of buttons; F2 renames and Delete removes in every list. All prompts use the app's own `Dialogs`/`OnlineDialogs` (no platform `TextInputDialog`).
  - **Campaigns:** table of name, game system, players and actors; selecting a row makes it the active campaign (locked while a session runs). Context menu: Open, Rename, New campaign, Delete. **New campaign** asks for a name and a game system (only *Daggerheart* for now; stored as `system`, no gameplay effect yet).
  - **Players:** table of username, password state, owned actors and online status with ping. Context menu: Set password, Rename, Kick, Add, Delete. Adding asks for username and password (confirmed, at least 4 characters). Renaming, changing the password or deleting kicks the player's open tabs; deleting also clears actor ownership and token control links.
  - **Actors:** a folder tree like the map and token browsers, with all folders expanded by default, search, an artwork thumbnail per actor (default token art when none) and the owner as a second line. Users can still collapse folders, with that choice retained through tree refreshes. Context menus: on actors Place at view centre, Owner (submenu), Change/Remove artwork, Rename, Remove; on folders New actor here, New folder, Rename, Delete (contents move up); on empty space New actor/New folder. Actors and folders are moved by dragging onto folders; dragging an actor onto the DM map places it there (like the token browser). Double-click places at the view centre.
  - **Connected:** table of connected players with tab count and ping (green < 80 ms, amber < 200 ms, red above); context menu Kick. Refreshes every 2 s while running.
- **Pause:** there is no pause button in the panel. While a session runs, the key from `online.pauseKey` (default Space; empty disables) toggles pause unless a text field has focus or a modifier is held. The DM view shows an amber "Online game paused" banner at the top of the map with a Resume button; players get a non-blocking banner (pointer events pass through) and can keep browsing, chatting and rolling. The local API control `online.pause` remains.

### 3.41.2 Principles
- **Door/window locks:** persist `Interactable.locked` (default false, including legacy/imported maps). The DM right-click menu provides a checked **Locked** toggle with undo/redo; a small padlock adorns only DM badges. DM open/close remains unrestricted. Browser players left-click portal badges in Select to open/close unlocked portals only while unpaused; locked/paused clicks do nothing and player visuals never reveal lock status. The host rechecks pause, map/level identity and the current lock before applying an undoable state change and publishing it. Optional `hello.portalControls` and `doors[].locked` are additive Online Play contracts (no online version bump); older hosts do not enable the controls. The strict DM Link workspace schema gains the persisted lock field, advancing `RemoteCompatibility.VERSION` from 6 to 7; mismatched hosts/clients are rejected before approval/replacement. No new settings or DM control-panel tools.
- **Unobstructed browser map header:** players see no top-center session/game-night, campaign, map or account heading. The connection-status badge lives in the bottom-left connected-player overlay, remains visible while connecting/reconnecting, and is slightly transparent. Roster refreshes retain the badge and its current status. This is presentation-only; no settings or compatibility-version changes.
- **Approved browser layout and player tools:** full-viewport map, compact left Navigate/Drawing/Measure icon rail with mutually exclusive flyouts. Drawing modes are small icon-only buttons (Pen first, brush, line, circle, rectangle), followed by a dropdown of every DM texture and style controls. Right-side Chat/Actors/Settings icon rail sits left of the active section; clicking its selected icon collapses the section, leaving the rail. Settings contains independent player audio and logout. Bottom-left connected-player overlay is subtle/translucent and shows each player's host-measured RTT (unknown until sampled), plus the host. **Active effects overlay:** below the left tool rail and above the roster, a subtle fixed-width (195 px), translucent list of the player's actors' active effects grouped by actor name (all owned actors' sheets are loaded eagerly). Each row has the effect name and a remove button (edits the sheet and autosaves); clicking the name expands the full source text (matching feature/heritage, domain card, weapon/armor feature or item by `ActiveEffect.source`, falling back to the effect's own text). Purely client-side (`web/src/effects.ts`); no protocol change.
- **Owned drawings:** browser-created effects are normal map overlays, saved and rendered by the DM as usual. Persist campaign/player ownership on each overlay; legacy/DM-created overlays belong to the DM. Only the matching campaign/player or the DM may change style, move, resize or delete a drawing. Validate ownership and geometry on the host; browser commands include map/level identity to reject stale gestures. DM undo/redo and copies preserve ownership. Drawing edits are blocked while paused.
- **Browser drawing defaults:** new browser drawings start at 100% opacity. Changing the texture while Select is active reactivates the previously chosen drawing mode and leaves its flyout open; existing selected-drawing style editing remains available.
- **Browser color editing:** color-picker input immediately updates the next drawing's color. Selected drawings receive throttled live color edits and a final edit on picker completion; host refreshes must not overwrite an in-progress or unconfirmed color choice. Texture assets remain generated by the host from texture kind and chosen RGB, with no second browser tint.
- **Browser brush borders:** the Border option outlines the union of a freehand brush's painted area, including dots, bends and self-crossings, for flat and textured/soft effects. No internal segment seams. Use a constant screen-size edge and viewport-bounded raster buffers; do not allocate world-sized masks.
- **Shared measurements:** distance, circle radius, cone and rectangle are shared session-only shapes with grid-tile labels. Live gestures are broadcast, and completed measurements remain after release. The creator and DM can move/delete them. Clear on map/level switches and session restart; never save in map files. They are annotations, not light emitters or fog reveals. New optional online capabilities, texture metadata, roster and measurement messages preserve older clients/hosts; no version bump for additive Online Play changes. Persisted overlay ownership changes the strict DM Link workspace schema and requires its compatibility version to advance.
- **Gesture cancellation:** Escape/pointer cancellation discards a new measurement and restores the original position when cancelling a measurement move, including any live updates already broadcast. Cancelling an unpublished gesture sends no deletion. Measurements removed by the DM stop the creator's active move when that removal arrives.
- **Browser map interaction:** a right-click released without dragging returns to Select without closing the open tool flyout. Right-drag pans while retaining the active tool and flyout; movement beyond 4 screen pixels counts as dragging even if the pointer returns to its starting position. Left-drag on empty space box-selects owned drawings, controlled actor/token instances and owned measurements; selected objects can be moved together. Brush/Pen show a true world-size cursor preview. Freehand creation shows only the stroke, never a selection box. Ctrl+Z (Cmd+Z on macOS) cancels an active drawing or undoes the player's acknowledged drawing edit through ordinary ownership-validated commands; history is map-local and bounded. Completed measurements show a subtle delete button to their creator and the DM, never to other players.
- **Browser interaction cursors:** Select shows a grab cursor over controlled tokens and owned drawings/measurements (including selected groups), a diagonal resize cursor over an owned selected drawing's resize handle, and a pointer over an owned measurement's delete button. Active moves/pans show grabbing; active resize retains the resize cursor. Non-owned objects keep the normal cursor. Drawing/measurement creation retains crosshair feedback, and paused maps do not advertise editing. Refresh feedback on tool, selection, permission, measurement and pause updates without requiring pointer movement. Cursor hit tests share the same ownership checks, priority and geometry as pointer actions. No settings or protocol changes.
- Strictly opt-in: nothing listens, polls or allocates until **Start online session** is clicked. Offline play and the second-screen player view are unchanged.
- The host sends full state except hidden actors and tokens; clients render everything locally, including vision. There is no anti-cheat. The host validates game rules (movement ownership, pause, collisions, doors) and persists data.
- Additive Online Play messages do not change DM Link compatibility. Persisted overlay ownership and portal locks do change its strict workspace schema; `RemoteCompatibility.VERSION` is 7 (ownership advanced it to 6, locks to 7). The online protocol has its own constant `dmmt.online.OnlineProtocol.VERSION`, bumped on incompatible online changes.

### 3.41.3 Domain model
- **Daggerheart death moves:** enable the sheet header's Death move control when all available HP are marked. Confirm the choice before executing. Blaze of Glory posts a formatted public report describing the GM-approved critically successful final action and death. Risk It All posts host-rolled Hope/Fear d12s and the corresponding recovery/death/critical instructions; sender-only chat controls apply recovery once, with no ordinary action-roll Hope/Stress reward or reaction buttons.
- Avoid Death rolls a host-owned Hope d12, applies an Unconscious effect that blocks token movement only (actions and targeting are not programmatically restricted), and gains a scar when the roll is at or below level. Unconsciousness ends when marked HP decrease or on a long rest; it remains manually dismissible with the other effects. Scars persist separately from effects, cross out Hope slots, clamp current Hope to remaining capacity, and survive rests/reloads/level changes. Provide explicit scar adjustment/healing controls with GM-approval guidance.
- Execute death moves against the authenticated owner's saved sheet, reject incomplete HP tracks, and journal sheet changes with their public chat/roll result before broadcasting. Advertise optional `hello.deathMoves`; unsupported hosts report an actionable error. New sheet fields, roll-result annotations and the authenticated death-move endpoint are additive Online Play changes; no Online or DM Link compatibility-version bump, new settings or desktop DM tools.
- **Approved death-move extension:** JSON `automation.deathMove` metadata describes the Reliquary (+1 Risk Hope), Blessed (equipped armor, optional Hope spend, once/long rest), Mask (persistent 0..4 tokens, manual gain/adjustment, wearing confirmation, one token for +1 Risk/Avoid Hope), Phoenix Feather (+1 scar check, nonstacking copies), Reanimated failed-Risk conversion (one permanently marked HP), Spectral Form, Cathartoid Egg, Battle-Hardened, Life Ward and Unbreakable. Raw matching Duality dice are critical; otherwise adjusted Hope >= Fear succeeds, and recovery uses adjusted Hope. Optional sender-only Risk chat buttons apply a chosen HP/Stress allocation once, or all recovery on a critical. Won't Stay Dead confirms its permanent cost, preserves raw dice and enables recovery unless the last HP becomes permanent. Permanent HP are separately persisted and cannot be cleared by healing/rests.
- Spectral Form clears 1 HP, gates physical resistance and allows actor tokens through walls/closed doors while retaining pause, ownership, finite-position and map limits. Further HP clearing or reaching full HP ends it; the latter excludes Spectral Form for that trigger. Repositioning from inside solid objects remains GM-controlled because maps contain wall segments rather than solid volumes. Cathartoid Egg consumes one egg and records a persistent narrative transformation without inventing recovery or changing builds.
- Battle-Hardened (active card, 1 Hope, once/long rest) and Unbreakable (active card, host d6 HP recovery then vault card) are death prevention alternatives, with existing card actions routed through the same host validation. Life Ward spends 3 Hope and targets an ally with player-confirmed Close range; a caster has one ward, which ends on recast/caster long rest or automatically clears 1 HP instead of the target's death move. Support offline/cross-owner targets and full-HP changes from damage and manual saves. Costs, effects, inventory and chat claims are journaled together and revisions reject stale saves. Advertise additive `hello.deathMoveOptions`; retain the existing basic endpoint for older clients and do not change Online/DM Link compatibility versions.
- **Daggerheart countdowns:** campaign-owned ordered countdowns with stable ids, a nonblank name, a nonnegative integer initial value and a current value initially equal to that value. Only the hosting DM can create, delete or step by one, bounded inclusively by 0 and the initial value. Persist every edit in `campaign.json`; failed saves retain the previous state and report the error. Older campaigns default to no countdowns.
- Show a compact, content-height, translucent countdown list at the top right of the map beside (not behind) the right sidebar/flyout, following each client's existing theme. Countdown row backgrounds use 40% opacity in both DM and player views for readability over maps; browser active-effect rows use the same opacity when collapsed, retaining their darker expanded background. Expect up to five entries; no scrolling. The DM overlay exists only while hosting, with an always-available Add button, a name/initial-value creation dialog, and inline minus/plus/delete buttons. Players see only names and current/initial values, without editing commands or endpoints. Empty browser lists are hidden.
- The browser list includes a subtle **COUNTDOWNS** header only when at least one countdown exists; an empty list hides both the header and its rows.
- Publish optional `state.countdowns:[{id,name,initialValue,value}]` through snapshots and diffed `set` deltas, including an empty array after deleting the last entry. Older browsers ignore the additive category; newer browsers hide it on older hosts. Neither online nor DM Link compatibility versions change: campaign storage is separate from DM Link workspace snapshots. Hide the DM overlay via `ui.controls.hidden=online.countdowns` and its Add button via `online.countdowns.add`; local API discovery retains Add and dynamic `online.countdowns.<id>.down`, `.up`, `.delete` controls, including when the overlay is hidden.
- **Shared descriptions and features:** DM chat renders the compendium Markdown subset (bold, italic, bold italic, lists and line breaks) as native styled text, never raw markers or HTML. Compendium links retain optional structured `sections` (description plus individually named feature/spell blocks); browser and DM render named blocks with the expanded compendium's bordered feature styling. Keep flattened text for old clients/history. Cover all categories, including multiple ancestry/community/class/transformation features and grimoire spells. Inventory weapon/armor features use the same bordered treatment. No compatibility-version change for additive metadata.
- **Compendium chat presentation:** Show `Compendium - <section label>` above the entry name. Replace the section badge with entry-detail badges matching inventory links (weapon slot/type/trait/range/damage/hands/tier, armor score/tier/thresholds, domain/level/type/recall, item type/rarity and relevant class/heritage details). Keep descriptions/features in the body without duplicating badge details. Browser and DM chat honor optional shared-card `badges`; missing badges retain existing inventory/legacy rendering. This additive presentation metadata does not change compatibility versions or transfer behavior.
- **Compendium action layout:** Add and Link in chat use matching 32px icon buttons in a single right-aligned, vertically centered row with a consistent gap. Add uses a clearly visible SVG plus, not a smaller text glyph.
- **DM catalog editor:** The DM compendium includes a host-only guided editor for all nine Daggerheart JSON catalogs, including nested automation. Browse/search entries, edit existing entries and create new entries from category templates or duplicates. Use stable Overview, Features, Automation and Advanced tabs instead of nested accordions. Overview shows identity, descriptions, category stats and a live readable preview. Features/spells use a named list and dedicated detail panel. Automation selects the entry or an individual feature/spell, then shows named rule cards with focused editors grouping activation, conditions, effects, and costs/limits. Deep structures open a separate breadcrumb-addressable panel, never a stack of expandable subsections; preserve scroll during same-panel structural edits. Keep Save and draft status visible outside the scrollable content. Retain typed controls, inline help, optional fields and ordered list add/remove/reorder controls, including uncommon supported mechanics and custom map keys in Advanced; no raw JSON editing is required. Stable entry IDs cannot change after creation. Save explicitly validates and atomically updates the configured JSON file, preserves unrelated entries/unknown fields and rejects stale file revisions. Failed saves retain the draft; navigation/closing warns about unsaved changes.
- **Compendium/editor readability:** All text, search, numeric and multiline fields, selects/options, placeholders and read-only/disabled fields use explicit dark-theme foreground/background colors, including in JavaFX WebView. Keep normal and placeholder text at least 4.5:1 contrast; never rely on native light-theme control defaults.
- Catalog-backed names, descriptions, equipment statistics and automation follow saved JSON automatically in the compendium and existing sheets, including open sheets, without recreating characters. Preserve current tracks, quantities, spent uses, selections, advancements and manual overrides; starting class/creation statistics affect creation only. Optional catalog links identify entries by stable ID, with legacy name/source matching where links are absent. Custom items remain independent. Catalog writes are DM-only, never player-authorized. Reuse the existing compendium DM control (including its hide setting and local API access); no new rail tool or setting. Additive catalog notifications/links do not change Online Play or DM Link compatibility versions.
- **Compendium actions:** Online Play compendium rows never support entry drag/drop (including standalone sheets/pop-outs). Keep Add for supported categories and add an inventory-style Link in chat icon for every category, without requiring an owned actor or adding inventory. The host resolves category/entry ID against its compendium and posts a non-transferable description using the existing shared-feature card format. The map uses the selected chat recipient; standalone compendium links go to Everyone. Advertise the additive `hello.compendiumChat` capability and report unsupported hosts/disconnections explicitly. No settings or compatibility-version changes.
- **Campaign notes:** browser players have a Notes sidebar tab between My actors and Compendium. Each player owns a private folder tree of named UTF-8 `.txt` files stored under `<campaignId>/notes/players/<playerId>/`; authenticated requests can only address that player's tree. A permanent `<campaignId>/notes/group.txt` (shown as **Group Notes**) is automatically created and readable/editable by every campaign player, including later additions. It cannot be renamed or deleted. No desktop notes control is introduced.
- Notes/folders support creation, rename and deletion via context menus (folder deletion explicitly confirms removal of its contents). Double-clicking a note opens a draggable, resizable sheet-style editor with explicit Save, Reload and unsaved-change protection. Remember editor size per campaign/player in browser layout storage; note names, folders and text live only in the campaign. Save uses a content revision to reject stale concurrent edits rather than overwrite another player's group edits. Failures are shown explicitly and retain the draft.
- Additive authenticated HTTP endpoints: `GET /api/notes` lists only Group Notes and the current player's tree; `GET /api/notes/content?path=...` reads text/revision; `POST /api/notes` performs create/rename/delete/save with server-derived player ownership. `hello.notes:true` advertises support; newer clients disable notes on older hosts. Online and DM Link compatibility versions remain unchanged because existing contracts and workspace snapshots are unaffected.
- **Browser tool pop-outs:** Notes and Compendium title bars offer the same pop-out affordance as character sheets. Standalone `/?popout=note&path=...` and `/?popout=compendium` views use the existing cookie/socket session and continue working if the map tab closes. Note pop-out saves pending edits before opening the standalone editor; save conflicts/errors or blocked pop-ups leave the original draft open. Standalone note editors retain Save/Reload and unload protection and fill the resizable browser window. Compendium browsing/filtering and adding entries to owned characters remain available; additions are applied by the receiving character sheet rather than overwriting another open sheet's unsaved data. These are client routes on `/`, requiring no new host routes, settings or compatibility-version changes.
- **Campaign** (separate from projects and maps): id, name, game system (`system`, default `Daggerheart`), players, actors, actor folders (`actorFolders`, `/`-separated paths, so empty folders persist), control links, per-player fog memory, chat/roll history. Many campaigns, stored below `<storage>/<campaignId>/` (`campaign.json`, `fog/<playerId>/<mapId>_<levelId>.bin`, `history.jsonl`, `artwork/<sha256>.png`). Default storage: `online.campaignsDirectory` or `dmmap-campaigns` beside the settings file (matching `dmmap-library` etc.; relative values resolve against the settings file). The earlier `campaigns` folder is not migrated.
- **Player:** id, name, password hash (Argon2id via the bundled Bouncy Castle; salt per player, parameters stored with the hash). The DM sets and resets passwords; passwords are never stored or logged in clear.
- **Exclusive player login:** one browser login session per account. Occupied accounts are omitted from the public login roster, which refreshes while the login form is open; stale or simultaneous login attempts receive `409 {error}`. Tabs and sheet pop-outs sharing the owning cookie are allowed. Login reserves the account for 30 seconds to open its first socket; the last socket disconnect, logout, kick or host stop releases it. Reconnects may reuse the cookie only while no other login has claimed the account. This is backward-compatible with existing online message shapes; neither online nor DM Link compatibility versions change.
- **Actor** (campaign-owned): id, name, folder (optional path into `actorFolders`), artwork (hash of a re-encoded PNG), owner player, mock sheet (not persisted). Missing `system`/`folder`/`actorFolders` in older `campaign.json` files load as defaults.
- **Control links:** a player controls their owned actors (several) and individual regular tokens (companions, summons) listed in `controlledTokens` as `{mapId, tokenId}` of the campaign store.
- **Placed-token movement permissions:** right-click a regular token on the DM map for **Player movement control**, with independently checked players from the selected Online Play campaign. Assign/revoke control per map/token instance, never per library definition; allow preparation before hosting and persist in the campaign. Failed saves restore the previous permissions and report the failure. Hidden tokens remain hidden and immovable until made visible. Controlled regular tokens use the existing actor movement, selection, vision, pause and collision rules in browsers. Snapshots and host validation both scope links to the current map. This exposes existing control links without changing Online Play or DM Link compatibility versions, settings or toolbar tools.
- **Actor tokens live in the map file:** `TokenInstance` gets optional `actorId` and `campaignId`; the map `schemaVersion` becomes 2 (old builds ignore unknown properties and render an ordinary token from the definition, so they still open the map). Placing an actor creates/uses a token definition with the actor's artwork so offline play renders it like any token. Actor tokens of another campaign show as "unknown actor" in the DM view and a DM-triggered "remove actor tokens of other campaigns" action cleans them up; nothing is deleted automatically. Actors with several tokens on different maps are fine.
- **Fog memory:** keyed by campaign, player, map id and level id, stored on the host. Phase 0 finding: `DmProject.id` is a UUID that is back-filled on load/save (`ProjectService.ensureId`), regenerated only on copies/imports (`MapLibraryService`), and `MultiLevelManifest.id` and its level ids are stable too, so renaming or moving a map keeps its memory. The key is `mapId` = manifest id (multilevel) or project id, `levelId` = level project id (or project id).
- **Visibility:** tokens with `playerVisible == false` are omitted from everything sent to clients. The client shows a cell when it is lit/visible from a controlled actor or token, remembered in the player's explored memory, or revealed by the DM (the DM fog mask is sent as a compressed bitmask and treated as "DM revealed").

### 3.41.4 Protocol (version 1)
HTTP (Javalin/Jetty, port `online.port`):
- `GET /` and `/assets/*`: the bundled web client (`src/main/resources/web`); `/sheet/:actorId` serves the same client (pop-out route).
- `GET /api/session` -> `{protocol, sessionName, campaignName, paused, maxPlayers, players:[{id,name}]}` (available accounts only; no secrets; not cached).
- `POST /api/login` `{playerId,password}` -> sets HttpOnly SameSite=Strict cookie `dmmt_online`; `401 {error}`, `409 {error}` (account already in use), `429 {error,retryAfterSeconds}` (failure rate limit per IP and per player). `POST /api/logout` releases the account and closes its sockets.
- `GET /a/<sha256>.<ext>`: hashed immutable assets (map images downscaled to `online.maxImageEdge`, actor artwork) with `Cache-Control: public, max-age=31536000, immutable`. Large images (with a built desktop pyramid) are additionally served as tiles from `ImagePyramidStore`'s on-disk pyramid: `GET /t/<key>/<level>/<tx>_<ty>.<fmt>` (level `levels` is the single overview tile `0_0`; key and indices are validated against the registered pyramid), described per image layer in the snapshot as `images[].pyramid {key, width, height, tileSize, levels, format}`. The browser draws the overview as a base and then the visible tiles of the level matching the current screen scale (LRU of 128 decoded tiles), so players see the full source resolution when zoomed in. The `/a/` fallback is downscaled with stepwise halving and JPEG quality 0.95 (PNG for images with alpha); the browser draws map images with `imageSmoothingQuality = 'high'`.
- `PUT /api/actors/<actorId>/artwork` raw image body; owner only; type/size validated, re-encoded with ImageIO to PNG, stored in the campaign folder.
- `GET /api/sheet/<actorId>` mock sheet JSON.
WebSocket `/ws` (cookie auth), JSON text frames `{type, ...}`, max frame `online.maxMessageBytes`:
- Ping: every 3 s the host sends `ping {id}` and the client answers `pong {id}`; the round-trip time per connection feeds the DM's ping column (best connection per player). Older clients that ignore pings just show "–". Additive `roster {players:[{playerId,playerName,connections,pingMs}]}` messages publish this same host RTT to browsers on membership/ping changes; -1 is unknown.
- Additive tool capabilities in `hello`: `campaignId`, `drawingTools`, `measurementTools`. State includes `effectTextures:[{id,label,color,emits,above}]` from the DM texture registry, plus `penWidthCells`; visible overlays include `ownerId` and `ownerCampaignId` (null = DM). New clients disable unadvertised tools; old clients ignore additions. Online protocol stays at 1. DM Link compatibility advances from 5 to 6 for persisted overlay ownership; version mismatch rejection remains before workspace replacement/approval.
- Drawing commands: `command {cmdId,kind:"drawing",action:"create"|"update"|"delete",id,mapId,levelId,shape?}`. Host validates numeric bounds, point limits, texture ids, pause, map identity and campaign/player ownership; overwrites client-supplied ownership/visibility; applies through normal DM overlay history. Pen/line suppress texture and emission as on desktop.
- Measurements: `measurement {cmdId,action:"upsert"|"delete",mapKey,id,kind:"distance"|"radius"|"cone"|"rect",x1,y1,x2,y2}`; owner comes from the authenticated socket. Host broadcasts `measurements {mapKey,items:[{id,ownerId,kind,x1,y1,x2,y2}]}` on changes and connection. Maximum 500 active measurements; mismatched map keys, invalid geometry and non-owner edits are rejected. Live gestures are throttled to 10 Hz by the client, with final updates on release. The DM can move/delete any measurement in Select mode. They are never project/campaign state or undo entries.
- server -> client: `hello {protocol,playerId,playerName,serverTime,sessionName,campaignName,paused}`, `snapshot {seq,state}`, `delta {seq,ops:[{op,...}]}`, `tokenMoved {mapKey,id,x,y}`, `ack {cmdId,seq}`, `reject {cmdId,reason,token?}`, `clockSync {id,clientSent,serverTime}`, `pause {paused}`, `chat {id,ts,fromId,from,toId?,text}`, `roll {id,ts,fromId,from,expression,rolls,total}`, `audio {...}` (see Phase 3), `kicked {reason}`, `error {message}`.
- client -> server: `command {cmdId,kind:"moveToken",tokenId,x,y}`, `clockSync {id,clientSent}`, `chat {text,toId?,whisper?}`, `roll {expression,toId?,whisper?}`, `fogExplored {mapId,levelId,gridKey,cells}`, `resync {lastSeq}`.
- Chat recipient dropdown: Everyone, Gamemaster, then other connected players (not the sender). `toId` targets `dm` or a validated campaign player; omitted/empty is public, and legacy `whisper:true` still targets the DM. Only sender and recipient receive whispers; the DM sees only public messages and whispers involving the DM. Persist recipient IDs and additive recipient names (`to`) for clear purple Whisper labels, including private rolls. A disconnected selected recipient must not silently become Everyone. Advertise `hello.playerWhispers:true`; clients offer player recipients only when supported and include legacy `whisper:true` for DM messages/rolls so older hosts cannot publish private rolls. These are additive Online protocol 1 changes; DM Link contracts and compatibility version are unchanged.
- Browser sidebar: title/rail label Chat (not Chat & dice); remove the standalone dice composer and character-sheet navigation shortcut. Open sheets by double-clicking compact My actors rows (or Enter/Space); rows show portrait, name, class/subclass and a separate level, without action buttons or camera navigation. Settings stays at the bottom of the navigation rail. Chat accepts `/roll` and `/r` followed by a dice expression, using the selected recipient.
- Browser chat attention: new live chat/roll entries received while Chat is closed mark its rail icon with a small accessible unread dot; opening Chat clears it. History snapshots do not create unread notifications. Follow new entries only while already at the bottom, preserve older-message reading positions across panel switches, and offer "Jump to latest" while scrolled up.
- Chat follow mode includes asynchronously loaded sender-only roll action buttons. Queued programmatic scroll events and content-height changes must not be mistaken for scrolling up; only a changed scroll position updates the reader's follow intent.
- Browser Settings has no editable chat identity. The host derives future chat, roll and shared-card sender/recipient names from the first enabled actor owned by the player in campaign actor order, falling back to the account name when none is enabled. Saved legacy identities are ignored. The roster displays the derived name alongside the account name and older clients cannot change it; account and login names remain unchanged. Historical messages keep their original names.
- Player chat and roll cards show the portrait of the same first enabled actor used for the sender name in both browser and DM chat, without a visible initial alongside the portrait. Capture the portrait with new messages so history stays consistent when actors change; use the existing initial avatar for missing or unavailable artwork, older messages and DM messages.
- `state`: `{mapId,levelId,mapName,grid:{pixelsPerCell},images:[{id,hash,url,x,y,width,height,rotationDeg,zIndex}],walls:[x1,y1,x2,y2,...],doors:[{id,x1,y1,x2,y2,open,kind}],lights:[{id,x,y,range,brightness,color,on}],lighting:{ambient,timeOfDay},weather:{type,intensity,lightningIntervalSeconds,seed},tokens:[{id,x,y,sizeTiles,rotationDeg,name?,image?,actorId?,controlledBy?:[playerId]}],dmFog:{originX,originY,cellSize,cols,rows,revealed(base64 deflate)}|null,fogMemory:{cells(base64 deflate)}|null, actors:[{id,name,ownerId,artwork?}],paused,chatTail:[...]}`.
- `ops`: `{op:"set", path:"<category>", value}` per changed category (`images`, `walls`, `doors`, `lights`, `lighting`, `weather`, `dmFog`, `paused`), `{op:"tokens", upsert:[...], remove:[ids]}`; a map switch sends a fresh `snapshot`. The host keeps a bounded versioned change log (`online.changeLogSize`); `resync {lastSeq}` returns the missed deltas or a full snapshot when the log no longer reaches back. Each delta is serialised once and the same bytes go to every client.
- Clock sync: client sends `clockSync` samples; the host answers with its monotonic millisecond time, the client keeps the best round-trip offset and uses it for time-based effects (weather, lightning).
- Pause: while paused the host rejects `moveToken` with `reject {reason:"paused"}`; clients show a banner and disable dragging; chat, rolls and sheet viewing keep working.
- Movement: the host accepts a move only for a token controlled by the sender (owned actor or assigned token), not when paused, not outside the map and not through a wall or closed door (segment test against walls/closed doors), applies it on the JavaFX thread with undo history, and broadcasts it; a rejected move rolls back on the client.
- Live movement relay: drags keep sending validated `moveToken` steps (50 ms throttle, so walls are checked along the drawn path). Right after accepting a step the host sends additive `tokenMoved {mapKey,id,x,y}` to every other connection (other tabs of the same player included, never the sender), only for tokens in the last published player state, so hidden tokens and rejected positions never leak. The relay does not change `seq`, the change log or `current`; the next delta remains authoritative. Clients ignore relays for another map key or unknown tokens. Older clients ignore the message; Online protocol and DM Link compatibility versions are unchanged.
- Browser smoothing: the browser draws tokens at interpolated positions (pure `web/src/motion.ts`). A new target glides from the shown position; during a stream of updates the glide lasts `clamp(gap*1.25, 50, 250)` ms, after an idle gap (>400 ms) `clamp(cells*60, 120, 400)` ms, and jumps over 40 cells snap. The locally dragged token is never animated. Hit-testing, vision and fog use authoritative positions; token-attached lights follow the shown position when drawn. The canvas keeps redrawing (33 ms frame cap) only while a token glides. Browser-local Settings toggle **Smooth token movement** (`dmmt.smoothTokens`, default on) disables interpolation for low-end devices.
- **Browser moving-light performance:** while a token is dragged, gliding or awaiting acknowledgement, token-light shadow polygons, fog/vision recomputation and the light tint are refreshed at most every `HEAVY_MOVE_INTERVAL_MS` (66 ms) in `render.ts` and reuse the previous result in between; the tint reuses its scratch buffers; fog cell rasterisation uses a scanline fill (`fillPolygonCells` in `vision.ts`, identical results to per-cell point-in-polygon). Internal only: no settings, API or compatibility-version change.
- **Browser performance settings:** a *Performance* group in the browser Settings panel holds individual browser-local switches (no performance-mode bundle; no DM controls, local API, host settings or compatibility-version changes): *Show weather* (`dmmt.showWeather`, default on; off skips all weather drawing and lightning), *Reduce weather particles* (`dmmt.reduceParticles`, default off; cap `REDUCED_MAX_PARTICLES` = 300), *Lightning flashes* (`dmmt.lightningFlashes`, default on), *Light flicker* (`dmmt.lightFlicker`, default on), *Simplified lighting* (`dmmt.simpleLighting`, default off; draws light-emitting effects without canvas `shadowBlur` glow) and *Animated effect textures* (`dmmt.animatedTextures`, default on; off freezes scrolling/pulsing overlay textures). `MapView` exposes `weatherEnabled`, `lightningEnabled`, `flickerEnabled`, `animatedTextures`; the frame loop idles when nothing animates.
- **Browser Settings rework:** the rail button uses a cog icon and the panel groups browser-local preferences into sections. **Audio:** *Enable sound* checkbox (inverse of `dmmt.muted`, default on) plus Master, Music and Ambient (the effects bus) sliders, all defaulting to 50% (`dmmt.volume`, `dmmt.musicVolume`, `dmmt.effectsVolume`; stored values are kept) and disabled while sound is off; the "Click to enable sound" gesture prompt stays under the checkbox. **Display:** *Smooth token movement* and *Show resources on my tokens* (`dmmt.tokenResources`, default off). The latter draws HP, Stress, Armor and Hope on the player's own tokens exactly like the DM view (`CanvasMapRenderer.drawResourceBar/Counter`: red HP bar and violet Stress bar showing marked/max, grey shield with available Armor left, gold star with Hope right). Values come only from the player's own loaded sheets (`SheetBus` refresh), so nothing is added to map snapshots and `ActorTokenResources` stays DM-only. **Logout** sits at the bottom of the panel. Browser-only presentation: no DM controls, local API, host settings or compatibility-version changes (`OnlineProtocol.VERSION`/`RemoteCompatibility.VERSION` unchanged).
- Browser Select: left-click selects a controlled token; click selection and rectangular selection use dashed square token outlines instead of circular selection rings. Selected tokens have a small rotation handle, matching the DM interaction, and arrow keys while the map has focus move selected controlled tokens by half a grid tile per press/repeat, using the existing authoritative movement validation and rollback. Do not nudge during pointer gestures, while paused or from text controls.
- Browser rotation uses additive `hello.tokenRotation:true` and `command {cmdId,kind:"rotateToken",tokenId,mapId,levelId,rotationDeg}`. Show handles only on supporting hosts. Validate finite angles, current map/level, visibility, control ownership and pause on the host; apply through normal undoable token edits, persist and broadcast. Rejections leave the authoritative rotation intact. Older clients ignore the capability; newer clients retain selection/movement on older hosts. Online Play and DM Link compatibility versions remain unchanged.
- Browser-only door/window markers are light gray in both states, change only their open/closed glyph and show no colored portal/wall segment. Preserve fog/lighting occlusion and all existing DM door/window rendering.
- Dice: parser on the host (`NdM(+|-)K`, multiple terms, optional `NdMkhK` keep-highest groups; `kl` not supported), cap `online.maxDice` counts discarded dice too; result broadcast to all or privately to the selected chat recipient. Additive `hello.keptDice` capability gates Powerful damage in new clients connected to older hosts. All rolled values are retained, but totals/breakdowns count kept values only.

### 3.41.5 Phases and status
- **Phase 0 (done):** this section; spikes. *Audio tap spike:* the app plays through JavaFX `MediaPlayer` (`JavaFxAudioOutput`), which exposes no PCM, and there is no application mixer. The only way to tap the final mix is a new `AudioOutput` implementation that decodes with jlayer/`javax.sound`, mixes in Java and renders to a `SourceDataLine`; the engine's `AudioOutput` seam makes that possible without touching playlist/crossfade logic, but it replaces the native pipeline (gapless loops, seek, loudness) and is a large, risky change. A cheaper alternative that satisfies "music is required" is syncing playback state (`audio` message: track hash/url, position, volume, loop) and letting browsers play the same hashed files from the host. Decided: the state-sync alternative (Phase 3, option B). *Vision/weather review:* `VisibilityService.compute` (ray casting, flat segment arrays) and `WeatherEffects` (`unit(index, salt)`, `wrap`, `particleCount`, `lightningFlash`, deterministic from index and time) are pure functions that port directly to TypeScript; shared test vectors live in `src/test/resources/online/vectors.json` and are checked by both Java and the web client tests.
- **Phase 1 (host server, done):** campaigns + CRUD UI, start/stop session, login with rate limits and cookies, DM roster (connected, kick, reset password), snapshot/delta pipeline, asset server, clock sync, pause.
- **Phase 2 (client rendering, done except the golden-scene comparison):** Vite + TypeScript under `web/` built into `src/main/resources/web`; Canvas 2D renderer (map, grid, walls, doors, tokens, actor tokens); vision and fog with throttled compressed explored-cell deltas; weather port (including indoor handling and lightning); effects port. Open: automated golden-scene comparison between JavaFX and the browser, and verification in real browsers.
- **Phase 3 (music, done, option B):** the host publishes an `audio` message (`enabled`, `serverTime`, `panic`, `effectsPaused`, `music{id,url,gainDb,durationMs,playing,positionMs,loop}`, `effects[{id,url,gainDb,count,loop}]`) on change and every 5 s, replayed to late joiners. `gainDb` is `AudioTrack.effectiveGainDb()` (loudness matching, relative to the original file, clamped -48..+24). Browsers fetch the hashed original (`/a/<sha>.mp3|wav`, HTTP Range, 8 MB cap per response) and play via Web Audio: per-track gain, music/effects buses, master, limiter. Effective level = the player's own slider (curve x^2.2); the DM's volume sliders and mute are deliberately NOT sent or applied, so DM and player volumes are fully independent (only panic and paused effects still silence browsers). Music is positioned from the host clock and re-seeked on >1.5 s drift; one-shots start when the host count rises; looping effects fade in/out. Players have master/music/effects sliders and a mute button; audio starts by default (no opt-in) and the first click or key press anywhere satisfies the browser autoplay rule (a "Click to enable sound" hint shows until then). Muting stops all voices, drops decoded audio and closes the AudioContext, so a muted player uses no audio resources; unmuting re-applies the latest host message and joins at the DM's current position. Setting `online.audioStreaming` (no DM button; players mute themselves). Only originals are served, never `files/playback`. No transcoding (large WAVs are heavy). DM Link is unaffected, so no compatibility bump.
- **Phase 4 (interaction, done):** movement, actor placement by the DM, actor artwork upload, chat with whispers, dice, persisted history.
- **Phase 5 (sheet, done):** mock sheet at `/sheet/:actorId`, pop-out via `window.open` using the session cookie and its own WebSocket.
- **Phase 6 (hardening/docs):** rate limits, message size limits, graceful stop, tunnel documentation (Cloudflare Tunnel, Tailscale), tests, load measurement with 5 simulated clients.

### 3.41.6 Settings
`online.port` (default 7080), `online.sessionName`, `online.maxPlayers`, `online.audioStreaming`, `online.campaignsDirectory`, `online.pauseKey` (default `SPACE`), `online.maxImageEdge`, `online.maxMessageBytes`, `online.changeLogSize`, `online.maxDice`, `online.loginMaxFailures`, `online.loginLockoutSeconds`; all documented in `docs/SETTINGS.md`. Local API controls: `online.browser`, `online.start`, `online.stop`, `online.pause`, `online.copyLink`, `online.fearDown`, `online.fearUp`, `online.endScene`, `online.newSession`.

**Scene and session resets:** the DM decides when a scene ends and when a new play session begins; starting, stopping or restarting hosting no longer resets anything. While a campaign is hosted, the DM Fear overlay shows two small buttons directly left of the Fear card: **End scene** and **New session** (hideable controls and local API controls `online.endScene`, `online.newSession`). End scene clears every `per: "scene"` usage counter and removes every `until: "scene"` effect on all campaign actors' sheets, online or offline. New session additionally clears every `per: "session"` counter, removes `until: "session"` effects and empties session resource pools (`session: true`, e.g. Know the Tide); a new session also ends the scene. Rests continue to reset scene uses and scene effects as before. Each reset bumps `featureRevision`, persists the sheets, pushes `sheetFeature` updates to connected owners and posts a special public chat message from the Gamemaster (`"system":"sceneEnd"` / `"system":"newSession"`), rendered as a banner card in browsers and in the DM chat. Session resource pools are now saved with the sheet like any other resource. Per-scene usage counters are tracked by both browser and host (`uses.per = "scene"`); the browser blocks a spent feature with "No uses left this scene". The hosting-run `sessionId` (current-session claims for reports and rerolls) remains tied to the hosting run. Additive message fields: `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` unchanged.

**Fear counter:** each campaign stores the GM's Fear tokens (`Campaign.fear`, 0..12, persisted in `campaign.json`; older files load as 0). It is published as `state.fear` (a diffed `set` category, so changes arrive as deltas). Browsers show it permanently as a bottom-right overlay of up to 12 tokens (hidden when the host omits `fear`; shifted left while the sidebar is open). The DM view shows a map overlay at the bottom right with −/+ buttons only while a campaign is hosted; both buttons are hideable DM controls and local API controls (`online.fearDown`, `online.fearUp`). Additive: `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` unchanged.

### 3.41.6a Visual parity with the desktop renderer
Light colour tint uses the DM renderer as the reference: accumulate light contributions with the desktop bright/dim falloff, clamp total illumination before applying the map's tint strength once, and blend overlapping colours by contribution rather than stacking translucent tints. Tint uses the host's `lighting.dmDarknessFactor` (optional browser snapshot field `lighting.tintDarknessFactor`); player ambient darkness remains unchanged. Outside Night, outdoor sources cast neither tint nor bright cores, and tint is zero on outdoor pixels even when the map contains dark rooms. Indoor lights remain active with indoor darkness enabled. This is a backward-compatible Online Play rendering fix, not a DM Link contract change.

The browser mirrors `CanvasMapRenderer` for: rotated image layers, token shape/border/colour/crop/default artwork, door/window markers (online-only addition), light flicker (`flicker.ts` ports `LightFlicker` with Java's `String.hashCode` phase; time quantised to 30 fps; radius x(1-0.25f), brightness x(1-0.6f); honours `ui.lightFlicker`/performance mode through `lighting.flicker`), time-of-day multiply tint (`lighting.tint`), bright core with radius setting, grid opacity (`view.gridOpacity`; the grid is only drawn when `view.showGrid` is true, mirroring the DM's player-grid toggle), player-visible overlay shapes (`overlays`) with the real animated textures (host-generated tile served as a hashed asset; scrolling/pulsing layers; soft textures approximated with a blurred mask), emissive overlays, player-visible text boxes (`texts`), fog softness and fade (`view.fogSoftness`/`fogFade`; blur is an approximation), pings (`pings`), indoor/daylight lighting (`indoor`, always sent when rooms exist; `indoor.lighting` is false at night/with indoor darkness off), host-configured weather style (`weather.particles/color/opacity/flash`), outdoor-only weather clipped to the outdoor area (`weather.indoors`), lightning exposure through open exterior doors/windows and the ambient darkness lift by lightning and the light tint glow. The lightning flash is drawn below text and fog (like the desktop), so it cannot reveal unexplored walls or windows. Known approximations: ARCANE wander (linear scroll), fog blur, light glow, soft overlay textures. In PERSISTENT fog mode, token-attached lights write into the shared DM fog, so all players see what they reveal. Remembered fog uses a fixed alpha of 140. The chasm texture was removed entirely (maps that used it fall back to a flat colour).

**Effect layer (`aboveTokens`).** Each effect has an optional `aboveTokens` flag (null = texture default via `OverlayTextures.isAboveTokens`; floor textures water, lava, acid, ice, blood, web, grease, swamp, rubble, thorns, arcane are below, everything else above). Below-token effects are drawn under the tokens and the ambient multiply/bright core, so lighting and darkness affect them; above-token effects are drawn after the lighting and are not affected. Desktop uses extra floor-effect canvases; the browser draws floor overlays before tokens and sends `above` per overlay. Emitters light regardless of layer. Hideable control `effects.layer`. The field is optional and ignored by older builds' project loader; DM Link (§3.39) strictly rejects unknown properties, so `RemoteCompatibility.VERSION` was bumped to 5.

### 3.41.7 Remaining work
- Playtest in real browsers (desktop and mobile) and fix what turns up; measure load with several clients.
- Automated golden-scene comparison between JavaFX and the browser renderer.
- Optional audio transcoding or a size warning for very large originals (WAV).

### 3.41.7a Daggerheart character sheet (mock-up done, client implementation with mock data)
**Character Quick Access:** Persist character-specific favorites as additive sheet metadata. Star any Character-tab feature (including grouped children), domain card, weapon or loot item in the full sheet. Minimized floating sheets offer a compact Quick Access tray ordered Weapons, Domain Cards, Character Features, Items, omitting empty sections. Within sections: weapons are Primary then Secondary; domain cards by level ascending (ties keep loadout order); character features keep the Character-tab order; items are alphabetical (case-insensitive, stable). Only currently equipped/active weapons and active-loadout domain cards appear; carried weapons and vaulted cards keep their favorite status and reappear automatically when activated. Keep descriptions independently expandable and all feature/card actions, usage and token controls visible (including grouped subfeatures and grimoire spells). Reuse normal gameplay controls and restrictions for weapons/items; omit equipment/loadout movement, transfer, deletion and other management controls. Preserve expansion and scroll across updates, support keyboard dismissal and outside clicks, and honor read-only sheets. Desktop placement chooses above/below by available viewport space, clamps horizontally and scrolls within available height; update on dragging/resizing. Narrow screens use a bottom drawer. Favorites are saved with the character through existing sheet persistence; no new settings, DM controls or incompatible remote contracts. Rebuild browser assets and refresh classpath resources. The favorite star is a subtle icon button placed immediately left of the post-to-chat button wherever both appear: in card/slot headers, list summaries, and at the far left of the link/transfer/delete group in carried-equipment and loot rows. The loot tools column must fit all four icons without overlapping the usage/action column. The tray uses a calm, consistent layout: each entry is one card with a header line (expand chevron + name, meta on its own muted line, star at the right), an optional description, then a single wrapping action row; usage counters and tokens are compact inline controls; nested grouped features, grimoire spells and copied cards are indented sub-entries without extra card chrome; sections have clear headings and spacing. The left map-tool rail ends with a star **Quick Access** button that toggles the same tray for the player's first non-disabled owned character, whether its sheet is closed, open or minimized; it is disabled while the player has no such character. Opened from the rail, the tray sits right of the rail, bottom-aligned with the star and growing upward (bottom drawer on narrow screens), stays open while the sheet is opened, closed or minimized, and returns focus to the star when dismissed with Escape or its close button. It is a player-browser control only, not a DM control, so it needs no setting or local API.
The Quick Access rail star uses the same neutral color and outlined icon styling as the other tool buttons, including while active; no gold accent or filled-star override.
Minimized resource counters use a low-profile, single-line layout: each label sits beside its blocks rather than above them, with no top margin on the blocks and only 2 px vertical padding.
The minimized lower row retains stacked trait names/modifiers but uses 1 px vertical tile padding, no internal gap, and explicit compact line heights; Evasion and Quick Access match its reduced height.
**Minimized resource counters:** Above Evasion, traits and Quick Access, show HP, Stress, Armor and Hope together in one horizontal row. Render only unlocked capacity (no unavailable advancement/scar slots), retaining permanently/temporarily marked HP and their healing restrictions. Reuse normal contiguous mark/unmark behavior, never capacity editing even when sheet edit mode is enabled. Save changes and refresh all counters/actions through the sheet's normal render/update flow; external changes and capacity changes refresh automatically. Disable marking for read-only characters. Keep the row unwrapped and horizontally scrollable when necessary. No new settings or remote contracts.
**Pronouns:** The sheet header displays Background pronouns in parentheses only when present and updates while editing. Keep the existing top-level and Background pronoun fields synchronized on edit; for older mismatched sheets, prefer nonempty Background pronouns, falling back to the top-level value. No settings or remote compatibility-version changes.
**Sheet edit mode:** Default each open sheet to edit mode off. Show a labeled accessible on/off switch beside the Daggerheart sheet title in floating and standalone sheets; hide it on minimized windows. Preserve the mode across refreshes, without persisting it as a character setting or bypassing disabled-actor read-only access. Gate Proficiency pip edits, transformation controls (hidden when off), base trait numeric inputs (-99 through +99, never displaying buff-adjusted totals), character name, portrait upload with a hover/focus edit indicator, Experiences (name/bonus/add/delete), and all Background fields. Normal rolls, Hope and marked resource pips remain usable outside edit mode. In edit mode, HP/Stress/Armor pip clicks change the contiguous usable maximum: a locked pip enables slots through itself; an enabled pip disables itself and later slots. Preserve permanent/temporary HP locks and clamp marked values to the new capacity. Store HP/Stress capacity in existing tracks; represent manual Armor additions/removals as a persistent manual-duration armorScore effect, preserving equipment/buff contributions and showing "Manual override" separately in the Armor tooltip. Show character-local domain-card deletion beside chat links in Card and List views, only in edit mode; never delete catalog entries or other characters' cards. Reuse existing sheet, effect and artwork contracts; no settings, DM toolbar tool or remote compatibility-version change. Rebuild the browser bundle.
**Grouped class features:** Present Gifted Performer's songs and Warden of the Elements' Incarnation, Aura and Dominion elements as named sections within their parent feature cards, like grimoire spells. Each section retains its own existing actions, source keys, counters and chat sharing; parent actions and limits remain on the parent. Use explicit catalog metadata to opt in rather than grouping all granted features: Martial Artist's Focus is a separate feature. Show the same hierarchy in character creation and the browser compendium, while retaining flat learned features and catalog entries for old sheets, hosts and clients. Missing or ambiguous children remain visible independently. No compatibility version or settings changes.
Saved testing sheets refresh automation on load without rebuilding their setup, so manual traits, capacities, Proficiency and removed domain cards persist. Explicitly applying a new testing build still recalculates its build-dependent values and cards.
**Combined manual override:** Track edit-mode HP/Stress capacity, Proficiency, base traits and base Evasion changes together with Armor in one persistent Manual override effect. Evasion becomes a base-value numeric input in edit mode, excluding other buffs. Keep direct base/track changes authoritative for existing hosts; record their signed accumulated adjustments as additive effect metadata (do not apply them a second time as modifiers). Armor retains its armorScore modifier. Returning a value to its original base clears that adjustment; remove an empty override. Removing the effect in edit mode reverses tracked adjustments without undoing subsequent advancements, respecting HP locks and track limits. Hide this effect from the map's left-side effects overlay, not from the sheet Effects tab or stat breakdowns. Recognize existing Armor-only overrides. No settings or compatibility-version bump.
Rise to the Challenge's roll-option label explicitly states "Hope d20" so its die replacement is visible before rolling.
Manual override's Effects card shows "from sheet edit mode", never its internal source ID, and omits its redundant Armor modifier badge. Other effects retain their modifier badges, and Armor calculations/tooltips are unchanged.
Separate the sheet title and Edit mode control by 24 px in floating and standalone headers. Show the Edit mode label in muted gray when off and normal text color when on.
Witch token pools and the Lunar Phases die are tracked only on their respective feature cards, never as class resources in the sheet header. Linked talisman token mirrors are internal mitigation state, not header resources.
**Witch automation:** Commune rolls Spellcast-trait d6s in chat and includes the sensory-result chart. Enchanted Talisman asks how much Hope to spend once per rest, tracks that many tokens on the witch's feature and creates one transferable loot item. Its holder gains a mitigation buff; spending tokens reduces marked HP and atomically spends the creator's tokens. Depletion or the creator's rest removes the item and buff wherever transferred. Walk Between Worlds rolls Spellcast against 13, offers its once-per-rest Stress-cost token grant only on success, tracks Spellcast-trait tokens, and has a token-spend chat action; End scene clears them. Vexing Malison offers optional advantage against Hexed targets. Circle of Power tracks Spellcast-trait tokens with a manual spend button on the witch; it grants every campaign character passive +2 thresholds/Evasion and a preselected +2 attack option. The last token or ending the source removes all grants; recipients can dismiss out-of-range buffs without automatic reapplication. Night's Glamour rolls Spellcast against 13 and grants its appearance-based optional trait advantage only on success. Its damage mitigation offers Stress to maintain the buff; otherwise marking HP ends it. Dealing damage also requires Stress to maintain it. Moonbeam grants everyone a scene-duration, preselected +1 Spellcast option. Lunar Phases rolls and tracks one d6 per session, cycles it by +1 (6 wraps to 1) for one Hope once per rest, and replaces the witch's session-duration buff: 1 = Hope-cost Minor-damage negation, 2-3 = +2 damage, 4 = +3 thresholds, 5-6 = +1 Evasion. Feature descriptions include both result charts. Range/narrative prerequisites remain table-confirmed. Contracts are additive, with no DM Link version or settings changes; rebuild the browser bundle.
**Warrior automation:** Combat Training ignores weapon burden in creation and equipment, retaining one primary and one secondary slot. Courage offers a player-confirmed failed-Fear-roll chat action, never on reactions. Rise to the Challenge preselects a d20 Hope Die when at most two HP are unmarked. Slayer has no feature buttons or duplicate counter: Hope rolls offer a mutually exclusive Slayer Die reward, attack/damage options select how many d6s to spend, and New session converts unspent dice to Hope and clears them. Weapon Specialist offers an unlimited Hope-cost secondary-weapon damage die option and a separate once-per-long-rest chat action rerolling only Slayer dice showing 1; its feature displays only the usage counter. Martial Preparation grants every campaign character its downtime move (including offline actors), without adding slots or a feature counter. Choosing the move adds a die to an existing Slayer pool, otherwise a removable single-use d6 buff shared between attack/damage options. Additive capability-gated Online Play changes; no settings or compatibility-version changes. Rebuild the web bundle.
Sorcerer follow-up: Volatile Magic rerolls and Manipulate Magic damage-die doubling publish explanatory chat reports listing each selected die's old/new face and the old/new damage total, while still updating the original roll and preserving its audience. Flat post-roll bonuses report only the amount (for example, "Added 2 to the original roll"), retaining formula/result detail for rolled dice. Enchanted Aid keeps an unlimited Help action and a manual swap action sharing the chat swap's long-rest counter; visually group that counter with the swap, not Help. Rebuild the web bundle.
**Sorcerer automation:** Volatile Magic retains its manual feature action and offers a magic-damage chat action with individually selectable, visually rendered dice; applying rerolls the selected dice and updates the original damage card. Minor Illusion rolls Spellcast against 10. Channel Raw Power selects an active domain card, moves it to the vault, and either grants its level in Hope or a consumable damage-option buff worth twice its level. Elementalist selects air/earth/fire/lightning/water during creation and displays the saved element on the feature; optional action +2 / damage +3 bonuses each cost 1 Hope. Natural Evasion rolls its d6 in chat. Transcendence selects exactly two distinct benefits (including a trait picker) as removable rest-duration buffs. Manipulate Magic retains its manual feature action; a single chat action on eligible attacks/Spellcast rolls selects extended reach, +2 to the result, or an additional target, while damage rolls select one die to double in the original result. Each use costs Stress; narrative eligibility stays player-confirmed. Enchanted Aid's unlimited d8 Help roll and once-per-long-rest ally Spellcast Duality swap are independent. Arcane Charge has Hope-cost and free magic-damage buttons plus a default-on magic-damage mitigation choice; all grant the same removable long-rest Charged buff, consumed by an optional +10 magic-damage bonus. Players resolve the alternative +3 reaction Difficulty manually. Host-authoritative roll edits validate die selection, eligibility, costs and repeat use, preserve breakdowns and visibility, and update chat history. Additive capability-gated Online Play changes; no compatibility-version or settings changes. Rebuild the browser bundle.
**Rogue automation:** Dark Cloud offers a Spellcast roll against difficulty 15. Contacts Everywhere grants a removable session-duration buff whose mutually exclusive benefits are +3 to either die on a finished action roll or an auto-selected 2d8 damage option; spending any benefit ends the buff. Equal adjusted Hope and Fear results are a critical success. Reliable Backup raises Contacts Everywhere's session uses to three without resetting spent uses and, while learned, adds options to reduce marked HP by one or use a d20 Hope Die on a Presence roll in conversation, each consuming the same buff. Narrative prerequisites remain player-confirmed. Preserve existing remote compatibility and use additive, capability-gated Online Play changes where needed; rebuild the browser bundle.
**Druid automation:** Beastform and Evolution select a category of the character's tier or lower; Evolution also selects its +1 trait. A removable `Beastform: <category>` effect supplies additive traits/Evasion/thresholds, replaces the active attack without destroying equipment, and exposes temporary features in a Beastform tab between Character and Domain Cards. Legendary/Mythic Beast select eligible base forms; hybrids select distinct ordinary categories (no nested Legendary Beast/Hybrid), then their stated number of distinct advantages/features and pay additional Stress. Narrative prerequisites remain explicit player-selected roll options; enforce unconditional bonuses, resource costs and drop conditions automatically. Each form's listed advantages share one action/reaction roll option. Venomous Bite/Strike poison and Elemental Incarnation Fire retaliation use direct, unmodified 1d10 action buttons without optional bonuses; poison is rolled separately when the poisoned creature acts, not added to the initial hit. Removing/replacing a form removes its temporary features and dependent effects.
Beastform feature audit: Companion's Help an Ally costs 1 Hope; Unyielding is passive; Defender stays cost-only. On-hit features are roll-card triggers on the form's Melee attack (Hobbling/Snapping Strike, Venomous Bite Poisoned, Ocean Master, Devastating Strikes on damage) or on their own feature roll (Trample/Demolish Vulnerable, Venomous Strike Poisoned; its damage stays manual). Rampage uses host `damage.onesBonus: 10` copied onto the form effect. Host trigger `weaponRange` checks the active form weapon. Items: `beastformFree` (Puddleduck's Fins: Aquatic Scout costs no Stress, Evolution excluded) and action `assumeBeastform` (Yeti Coat: any character becomes the catalog Powerful Beast for the scene). Backward compatible; no remote version bump.
Clarity of Nature reports the total Stress available; Regeneration selects a target and rolls healing; Warden's Protection rolls and reports its ally count. Split Incarnation, Aura and Dominion into uniquely named per-element features. Channeling costs Stress, is exclusive, ends on Severe damage/rest, and automatically grants the matching owned Dominion benefit. Switching elements ends any aura; activating another requires an available once-per-rest use. Aura actions require their matching active aura. Earth aura publishes an optional, explicitly selected +1 Strength action/reaction bonus to every player character (no range inference), removed when the aura ends. Fire Dominion adds one Proficiency damage die, Air adds Evasion, and Earth participates in damage mitigation; conditional Water/Fire/Air buttons pay their costs and perform their configured rolls. Preserve existing clients/hosts with additive contracts and capability checks; no DM Link version or settings changes. Rebuild the Online Play browser bundle, not the Windows application.
**Blood Hunter automation:** Blood Maledict is a removable rest-duration advantage buff ending on Severe damage or reuse. Crimson Rite is a rest-duration damage-option buff with level-scaled extra dice, no weapon/damage-type picker; Chasing Death automatically waives its HP cost at 1-3 unmarked HP and changes its dice to d8 at exactly one unmarked HP. Grim Psychometry rolls Spellcast against 12 and offers its Stress-cost vision buff only on success. Veilwalker suggests advantage on the next attack in the scene. Werewolf suggests hearing/scent advantage on trait rolls without automatically selecting a transformation; Control the Beast and Feral Hide require the Wolf Form buff.
Mutagen downtime choices show each benefit's description. Expanded mutagen effects show only their individual benefit, not the entire Mutagens feature; Volatile Toxins shows its own feature text, including for previously brewed effects. Center crossed-out HP markers inside their pips. Shadowed Grit is unavailable on reaction rolls, which do not generate GM Fear; enforce this in both browser and host.
Mutagens is a free optional additional move at either rest, restricted to brewing mutagens (never an unrestricted third move). Select distinct increased/decreased traits and one benefit, or two with Volatile Toxins; Improved/Mastered Mutagens unlock their benefits automatically and mastery changes the trait adjustments to +2/-2. Benefits become rest-duration buffs with their configured actions, optional bonuses and passive stats. Selecting two benefits marks and locks one HP until the next rest of either kind; healing and pip edits cannot clear that HP before then. Previous locks release before recovery during that rest; newly brewed locks and buffs survive it. Keep narrative prerequisites player-confirmed. Extend additive data-driven automation and host enforcement without changing DM Link compatibility or settings; rebuild the browser bundle.
**Bard automation:** Rally grants the bard and every other player-owned campaign actor with a sheet one session-duration Rally Die effect, including offline actors. Its optional action/reaction/damage roll bonuses and clear-Stress action share one consumable die; resolve d6/d8 by the granting bard's level and d10 with Epic Poetry. The DM's New session button removes unspent dice; reconnects and hosting restarts do not. Gifted Performer has separate Relaxing, Epic and Heartbreaking Song features, buttons and long-rest counters; Virtuoso raises each maximum to two without resetting spent uses. Relaxing/Heartbreaking apply HP/Hope to the bard and publish chat buttons for other players to claim once per player per performance on an owned actor. Rousing Speech offers the same ally claim for clearing 2 Stress. Maestro adds mutually exclusive gain-Hope/clear-Stress claims to the Rally report, once per other player. Claims are host-authoritative, persist with sheet/report changes and are limited to current-session reports and eligible actors; range and narrative prerequisites stay table-confirmed. Heart of a Poet offers a Hope-cost d4 reaction on finished action rolls and updates their total; Epic Poetry has a d10 Help-an-Ally feature action. New metadata/messages are additive and capability-gated where required; Online Play and DM Link compatibility versions and settings remain unchanged.
Performance claim controls appear directly on action-used chat cards, including song recovery/Hope, Maestro and Rousing Speech reports; render them from the message metadata rather than relying on rest or roll rendering.
**Ancestry automation review:** "once per scene" features carry `uses {max, per: "scene"}` and scene-duration buffs `until: "scene"` (still removable manually); the DM's End scene / New session buttons reset them (see §3.41.6). Session usage resets only when the DM clicks New session, not on reconnects or hosting restarts. Hallowed Aura and Luckbender offer reactions on other players' action rolls as well as sheet buttons. Fearless/Hallowed Aura change the original roll's Hope/Fear outcome without changing dice, refresh outcome-dependent reactions, and report a formatted feature use. Rerolls produce only their roll card, not a second feature-use message.
Ancestry weapon/action buttons must perform the described rolls: Elemental Breath and Long Tongue attack and Proficiency damage, Retracting Claws Agility, Charge Agility-roll reactions and fixed 1d12 damage, Unshakable d6 (mark Stress unless 6), and Fungril Network Instinct against 12. Purposeful Design selects the permanent +1 Experience during creation. Ignition is a primary-weapon damage option that starts a buff and pays Stress only on activation; active buffs preselect matching options for free. Retract starts a persistent removable effect, preselects physical resistance and action-roll disadvantage (overridable), and prevents player token movement, not DM movement. Surefooted defaults on for Agility and suppresses disadvantage with an explicit breakdown; Natural Climber and Hunter's Nose optionally preselect advantage on their applicable traits. Luckbringer grants Hope to all actors of connected players once per session. Eye of the Storm selects a target for +1 Evasion, replacing the caster's previous grant and ending when the caster takes Severe damage; Lifespring selects a target and clears HP. Audit matching patterns across all Daggerheart categories, retaining narrative/range requirements rather than guessing them. Extend existing data-driven mechanisms and document additive Online Play contracts; do not change unrelated DM Link compatibility or settings.
The catalog audit also covers Unbound, Stealth Expertise and Red Nose of Hope outcome conversion; simple situational community/gear advantages; Nature's Tongue, Goad Them On and Woven Net trait rolls; Life Support, Healing Strike, Sparing Touch and Restoration target healing; and Patron's Mantle's intimidation advantage and expiry on taking Severe damage. Damage-linked expiry uses the resolved severity, not the number of HP actually added (which can be capped).
Charge chat reactions execute their configured fixed damage on the host, charge Stress once, and publish one damage roll rather than a separate action-used message. Repeated use on the same original roll is rejected; the original Agility dice are unchanged.
Feature-check roll cards show only the consequence below the dice (for Unshakable: "Marked 1 Stress." or "No Stress marked."), without repeating the feature name or result. Host-generated feature rolls include the full feature text and source attribution in their Source disclosure, matching sheet-generated rolls; browser and native DM chat both show the consequence.

**Splendor online automation:** Mending Touch's shared long-rest limit applies only to its two-point heals. Reassurance is a reaction on other players' action rolls. Healing Hands offers result-dependent healing on its Spellcast chat roll (crit succeeds), selects another target and marks them unavailable until their long rest. Second Wind has one card/attack-roll action with a benefit picker and a Hope/critical target picker defaulting to self. Life Ward picks an ally without confirmation and automatically saves them with a chat message. Smite doubles the whole damage total and attributes the extra damage to Smite in the breakdown. Zone of Protection activates only from a successful Spellcast roll; everyone, including the caster, can join from the action report. Track its shared d6 value as card tokens, preselect its damage mitigation, advance after use and expire all linked buffs above six. Restoration prepares tokens automatically on long rest, without clear/prepare buttons. Healing Strike heals a selected ally from damage-roll chat actions for two Hope. Stunning Sunlight has only Spellcast on the card; its chat action selects Hope and rolls both reaction-success and reaction-failure damage. Salvation Beam's successful-cast action distributes HP with ally sliders and a live Stress cost capped to available slots. Invigoration offers a searchable character/feature picker on its Hope-dice roll when any die is six and restores one exhausted use. Resurrection offers a successful-cast d6 action, reports the result and permanently vaults the card on 1-5. Costs, eligibility, shared effects, claims and roll reuse are host-authoritative; use additive, capability-gated Online Play contracts without changing DM Link compatibility or settings.

Smite also offers an optional weapon-attack roll option to spend 3 Hope and activate the same charge as its card action. Both entry points share the once-per-rest use; do not offer charging while already charged. The charge survives a failed attack and is consumed on the next successful weapon attack's damage roll. Spellcast rolls cannot charge Smite.

Splendor regression requirements: healing reports use "HP" and "Stress". Every ordinary damage breakdown row retains its single, undoubled result; one Smite row adds the sum of all other damage, including weapon flat bonuses, critical damage and host-generated bonuses. Zone mitigation must commit through the host even when no other host-only mitigation is selected, advancing the shared counter and updating/removing linked buffs; never silently apply it locally if host support is unavailable.

**DM compendium:** while hosting a campaign, show a Compendium book icon immediately below Chat in the right DM rail. It opens an in-app, resizable embedded window using the exact browser `CompendiumPanel` and styles, without minimize or pop-out controls. The DM can link any entry into public chat and add supported equipment, loot and domain cards to any player-owned campaign character, whether its owner is online or offline. Resolve entries from host data and persist additions atomically against the latest sheet, enforcing quantity caps and notifying live sheets/pop-outs; reject stale saves rather than overwriting additions. A character must have a created sheet before it can receive entries. Close and discard the embedded session when hosting ends/changes. Use a host-only, loopback-restricted capability, never player credentials or publicly accessible DM endpoints. Hide via `ui.controls.hidden=online.compendium`; retain local API access to `online.compendium`. Additive Online Play functionality; no incompatible protocol or DM Link changes.

**DM character sheets:** while hosting Daggerheart, double-click a player-owned actor in the Actors tree or its map token in Select mode to open the actual browser sheet in a non-modal, draggable, resizable WebView inside the DM map. An actor context-menu "Open character sheet" action provides keyboard access; placement remains available through drag/drop and "Place at view centre". Keep one window per actor and bring an already-open window forward. Offline owners are supported. DM edits/actions reuse the host's rules and are performed on behalf of the character's owner, without a player login or an extra roster connection. Use revocable, actor-scoped loopback capabilities; close all sheets when hosting ends/changes and prevent map shortcuts while a sheet has focus. Normal window closing flushes pending edits first and leaves the window open on save failure. Synchronize complete saved sheets in both directions, including notes, equipment and identity, using additive WebSocket notifications and an optional content-version precondition on saves. Refresh idle sheets automatically; reject concurrent stale edits explicitly rather than overwriting newer saves. Legacy clients retain existing revision checks. No new rail controls/settings or incompatible Online Play/DM Link contracts.

**Testing characters:** the DM can create a permanently marked testing actor from the Actors tree and assign it to any player; that owner may freely use its testing controls. Normal actors cannot opt into testing through sheet edits. The host rejects testing-marker/build spoofing, unrestricted identity changes on established normal sheets, extra multiclasses outside normal advancement limits, and normal loadouts above their rules-derived capacity.
A visible testing badge and dedicated editor allow changing level, primary class/subclass and subclass stage, any number of multiclasses (each with its subclass, stage and chosen domain), ancestry/mixed ancestry, community and transformation without creation/advancement prerequisites. Applying changes replaces class/heritage features, spellcast traits and accessible domains, removes obsolete resource pools and source-bound effects/usage, and initializes newly available resources without resetting retained pools or equipment/background. Every compendium card in the accessible domains, at every level, belongs to the active loadout; no five-card cap or vault moves for testing actors. Reloading refreshes this build from the current compendium. The testing marker is campaign-owned, persistent and immutable through the browser sheet API; missing markers mean normal characters. Normal creation, advancement and loadout limits remain unchanged. This is additive Online Play functionality, not a DM Link contract change; compatibility versions stay unchanged.

Tab order: Character, Beastform (only while transformed), Domain Cards, Equipment, Loot, Background.
The floating sheet defaults to 900 px wide, clamped to available space. Below the shared header, a persistent left sidebar stacks only the six compact trait cards vertically on every tab. Experiences belong in the Background tab, above the character description, not in the sidebar. The sidebar is a size container: trait cards keep their full size when there is room and otherwise shrink (padding, gaps, names and values scale with the sidebar height) so all six traits stay visible and readable on short screens or when extra class resources make the header taller; below about 470 px of sidebar height the subtle example actions are hidden first, and below about 250 px the tiles switch to compact one-line rows (name left, value right). Only when even the minimum readable size does not fit does the sidebar scroll independently, preserving its position across sheet refreshes and tab changes. Evasion, Armor, Thresholds, HP and Stress are permanent header vitals, beginning below the portrait and character info in the left header column, remaining visible on every tab, in one horizontal row. They never extend under the right header column; if needed, their row scrolls horizontally within the left column. Hope, Proficiency and additional class resources stay vertically stacked in the right header column. Short Rest, Long Rest and Death Move buttons sit in a compact action row to the right of the portrait, between the character details and the vitals row. Center the Evasion number and the dot inside marked-trait indicators. The main area contains the tabs and scrollable tab content. Character features use two equal columns: class/subclass on the left, heritage on the right; stack these columns on narrow sheets. Floating and pop-out sheets share this layout. No settings or compatibility contracts change.
Domain cards in both the loadout and vault are displayed in ascending level order without changing their saved order. Domain-card token counters appear in the loadout card header between the domain and recall cost, labeled "Tokens" rather than the full resource/card name, using the existing editable resource pools. Domain-card action groups have no enclosing border or panel padding; individual buttons retain their normal styling. Other sheet action groups are unchanged. This is browser-only presentation, with no settings, persistence or compatibility contract changes.
Evasion, Armor and Thresholds source tooltips open on hover or keyboard focus as overlays outside the vitals scrolling container, above tabs and sheet content. Keep them inside the sheet bounds without adding scrollbars; dismiss on leaving the stat, Escape, scrolling or sheet refresh. Floating and pop-out sheets share this behavior; no compatibility or settings changes.
Inventory quantities use the normal text color, including capped consumables; the gold counter retains its gold styling.
Changing a resource on the sheet (Hope, Stress, HP, Armor and Proficiency pips, gold, or resource pools) immediately re-evaluates the availability of every feature/item/card action button on the current tab, without requiring a tab switch. Browser-only; no settings or compatibility changes.
Armor, HP and Stress slots use identical 14 px square pips, including locked slots. Armor has a 114 px container for two rows of six slots, taking the extra width from the flexible HP/Stress columns. HP/Stress tracks wrap rather than shrink their pips when space is limited.
Trait tiles have a teal border highlight and subtle inner glow to distinguish them from the other vitals cards, with a brighter highlight on hover or keyboard focus.
Each trait tile shows a read-only circular mark indicator fully inside its top-left corner, separate from the trait name: an 8 px empty ring when unmarked and a centered 3 px dot when marked. Reserve horizontal space beside the name to avoid overlap, not a negative top offset. The indicator must preserve the original compact tile dimensions (6 px vertical padding), without adding a row above the name. Users cannot toggle it; only level-up rules mark traits or clear their marks.
Implementation status: the approved mock-up is ported to the web client (`web/src/daggerheart/`: `data.ts` types + mock character + rule helpers, `view.ts` sheet DOM, `window.ts` floating window, `sheet.css`). The sheet opens from the Actors section ("Open sheet") and from a right-rail button as a floating window, and as the pop-out (`/sheet/:actorId`). Data is a built-in mock character kept in memory only (window and pop-out do not share state); real SRD import, persistence and edit permissions come later. Rolls are sent over the existing `roll` message with additive optional fields `label`, `kind: "duality"` and `difficulty`; the host validates the duality expression shape and echoes the fields, so history keeps them, and the chat log renders Hope/Fear dice, outcome and verdict. Old clients ignore the extra fields, so `OnlineProtocol.VERSION` stays unchanged; DM Link / `RemoteCompatibility.VERSION` are unaffected.

Goal: the *Daggerheart* game system gets a real character sheet in Online Play. First step is a static HTML mock-up (`web/mockups/daggerheart-sheet.html`, standalone, no build, not bundled) to agree on content and layout before any host model, protocol or persistence work. Derived from the Daggerheart SRD 1.0 (Darrington Press Community Gaming License).
- **Window behaviour:** the sheet is a draggable window over the normal browser map view (minimise, close, pop out) and the same content opens in its own browser window (`/sheet/:actorId`, shared cookie and WebSocket). When minimised, the bar keeps a compact strip with the Evasion value (separated, not clickable) and the six traits as name + modifier buttons that open the same roll dialog as the sheet's traits. A shield icon button directly right of Evasion opens the same damage mitigation dialog as the Thresholds vital (disabled on read-only sheets). Double-clicking empty space or the title text on the sheet window's title bar toggles minimise/restore, exactly like the Minimise button (closing the Quick Access tray); double-clicks on title-bar buttons and the Edit mode switch are ignored. Opened from the Actors section. The floating sheet defaults to the available viewport height with 30 px above and below, remains resizable, and the pop-out has no window chrome. Owner and DM can edit; other players view read-only.
- **Header:** large character image (left), identity (middle), Level / Tier / Level-up (top right) with Hope and Proficiency directly below.
- **Traits and vitals:** all six traits stack vertically in the persistent left sidebar on every tab (click = roll, read-only top-left circle with a dot = marked by level-up, star = Spellcast trait, subtle example actions under each trait). Experiences appear in Background. Evasion, Armor, Thresholds, HP and Stress are permanent header vitals, beginning below the portrait and character info in the left header column, remaining visible on every tab, in one horizontal row. They never extend under the right header column; their row scrolls horizontally within the left column when needed. The three 12-slot tracks have compact 114 px cards and each uses two rows of six pips. Hope, Proficiency and additional class resources stay vertically stacked in the right header column. Center the Evasion number and marked-trait dot. Conditions are not part of the sheet. Short Rest, Long Rest and Death Move actions sit in the header row to the right of the portrait, between character details and the vitals row. This is browser-only presentation; settings and online/DM Link compatibility versions are unchanged.
- **Tabs (under the header, in the main area):** the header (image, identity, Level/Tier/Level-up, Hope, Proficiency, header rest/death actions, and permanent vitals below the portrait) and left trait-only sidebar are shown on every tab. Experiences remain a slim list without buttons in Background - the experience is chosen in the roll popup, costs 1 Hope, +modifier. *Character* shows two columns (class, Hope and subclass features on the left, ancestry and community features on the right; only learned features are listed, locked specialization/mastery are not shown). *Equipment* (active weapons and active armor side by side, then a full-width "carried, not equipped" list with name with badges on a second line below it and feature name + short text; compact active weapon cards with badges below the name: category, damage type, short trait e.g. Agi, range, damage, burden, tier, plus Attack/Damage buttons and Swap/Unequip; active armor shows name, base score, tier and feature only, no armor slots and no repair - repair becomes a downtime action in the rest menu; carried but unequipped weapons/armor), *Loot* (small gold-styled coin counter (gradient, gold border, coin icon) at the top; full-width inventory list with quantities, consumable cap 5, class items, loot), *Domain Cards* (loadout max 5, vault, level/domain/type/recall cost; each loadout card has a three-dot menu with "Move to vault", vault cards keep their "Recall to loadout" button; both panel headers have a search box (name, domain, type and text, all words must match; filters in place, the query survives moves); cards can be dragged between the Loadout and Vault panels (into the loadout only while a slot is free); the page auto-scrolls while dragging near its top/bottom edge), *Background* (Experiences, description, background questions, connections, notes).
- **Rules the sheet must respect:** traits start +2/+1/+1/+0/+0/-1; HP/Stress slots max 12 (Stress start 6); Hope 0-6; Armor Score max 12; max burden 2 hands; one primary and one secondary weapon; damage dice = Proficiency; thresholds = armor base + level (unarmored: level and 2x level); gold 10 handfuls = 1 bag, 10 bags = 1 chest, max 1 chest; last Stress marked = Vulnerable; last HP marked = death move; tier 1 = level 1, 2 = 2-4, 3 = 5-7, 4 = 8-10.
- **Rolls from the sheet:** duality roll (Hope die + Fear die, advantage/disadvantage, Experience spend, result Hope/Fear/critical on matching dice) per trait and Experience, attack and damage rolls from the active weapon. There is no roll bar: a roll opens a small popup (advantage/disadvantage, difficulty) and the result is posted to the shared chat/roll log (mock-up shows a toast).
- **Chat and roll presentation:** each browser chat message is a separate dark, bordered card with sender and timestamp in its header; whispers retain a purple recipient badge. Roll cards show styled dice, a summed modifier and a prominent total, with Hope/Fear/critical and difficulty outcomes where applicable. A keyboard-accessible, initially collapsed breakdown lists every applied trait, Experience, advantage/disadvantage, passive feature, weapon modifier and buff/debuff by name and actual impact, including individual dice. Damage separates fixed weapon bonuses from dice and includes per-source Proficiency dice-pool notes (`notes: [{label, impact}]`, contextual rather than added to the total). Preserve expanded cards when messages arrive. Sheet rolls send optional named expression contributions; the host checks that these describe exactly the rolled dice and modifier and resolves their values from its own rolls, then broadcasts/persists an additive `breakdown` field. Older clients/hosts and historical rolls without contributions retain their existing contract, with a formula/dice fallback instead of invented source names. Only mechanically applied influences are listed, not unautomated conditional feature text. No settings or DM tools are added; compatibility versions stay unchanged.
- **Roll source context:** feature/action, equipment and Spellcast rolls include an optional description-only `rollSource: {name, text, source}` snapshot. Show it in a subtle, keyboard-accessible, initially collapsed Source disclosure, independently of the mechanical breakdown; no sheet controls or inventory grabs. Spellcast titles identify the card or individual grimoire spell. Include source context on local action-result chat messages, follow-up spell damage and rerolls, including sheet pop-outs. Persist/broadcast bounded descriptive fields only, preserving whispers and expanded messages; do not reconstruct historical sources from changed sheets. Older hosts/clients/messages work without the optional field. Online Play and DM Link compatibility versions remain unchanged; no settings or DM controls added.
- **Shared chat cards and transfer offers:** chat icons and inventory-to-chat drags post description-only links that can never be grabbed, including historical links. A separate subtle Transfer control is available only for carried, unequipped weapons/armor and loot. For multiple identical units, ask how many to offer (positive integer, default all available). Offers explicitly identify the offering player/character and offered quantity, with live remaining quantity and compact Grab / Grab all controls. The quantity is prominent: a large accent-bordered remaining count (greyed when exhausted) with "remaining" and a muted "of N offered" beside it, on its own row inside the card above the Grab controls (so large numbers never crowd the buttons), in both the browser and DM-view chat. Grab opens a small quantity dialog (positive integer, default 1, at most the remaining amount; skipped when only 1 remains). Each offer has a persistent, host-enforced quantity budget and draws only from live unequipped stock; claiming never touches equipped gear. Grab all transfers the entire remaining offer atomically or explains a recipient quantity-cap failure without moving anything. Choose an owned receiving character when several exist. Posting does not remove or reserve stock; duplicate offers share live stock but retain independent offer limits. Persist both sheets and claimed quantities together before success; protect budgets and inventory against stale or forged sheet saves. Update floating sheets, pop-outs and late-join history. Advertise additive `hello.transferOffers` support; new clients refuse inventory sharing with older hosts whose links were claimable. Existing clients can still post safe links and claim one from explicit offers on new hosts; Online Play and DM Link compatibility versions stay unchanged. No new settings or DM controls.
- **Gold transfers, DM gold and custom loot:** the Loot gold counter's number is centered (native number spinner arrows hidden) and has a subtle Transfer icon (disabled at 0 gold) that posts a gold offer exactly like loot offers (category `gold`, quantity dialog defaulting to all carried gold; the host checks live gold). Grabbing gold moves it between sheets and bumps the inventory revision so the receiving sheet updates and stale saves are refused. The DM can type `/gold <amount>` (whole number 1-999999999) in the native chat to post a DM gold offer to the selected recipient; DM offers have no source sheet, and their persisted budget (`claimed`) is enforced by the host. Grab requests accept an optional `quantity`; the host advertises additive `hello.goldTransfers` and `hello.grabQuantity`, and new clients refuse gold offers or multi-unit Grab on older hosts instead of silently grabbing one. A subtle + icon to the right of the Inventory search box (with clear spacing) opens a dialog to add a custom item: name (required), optional description, type (loot default, gear, consumable, class item) and quantity (default 1, respecting the normal caps). Custom items are marked `custom: true`, have no Use or automation action buttons, never receive compendium automation or rest metadata, are never merged with compendium additions and never appear in the compendium; they can still be shared, transferred, edited and deleted like other loot. Chat message action buttons (Grab, Grab all, roll/rest/action follow-ups) use the inventory Drink-button look: light blue fill and border, accent border on hover, muted grey when disabled; primary actions (Gain Hope, Damage, recovery) keep a stronger solid blue, bold style. Online Play and DM Link compatibility versions stay unchanged; no new settings or DM tools.
- **Equipment and loot presentation:** group equipment table-row controls at the trailing edge; Loot separates usage/actions from its far-right Tools column. Action buttons (Attack, Damage, Equip, Unequip, Use, consumable actions) use a blue-tinted border/fill so they read as enabled buttons, while the subtle chat/transfer/delete icon buttons stay transparent. Active equipment retains its cards; the chat icon stays in the top-right of the card header. A plain Delete icon button removes carried equipment and loot. Items whose quantity reaches 0 (use, action, or a grabbed transfer on the host) are removed from the inventory; the quantity stepper may rest at 0 so accidental clicks lose nothing. The offer-quantity dialog is padded, stacked and themed. Loot descriptions start hidden; clicking a row's non-interactive area or accessible item-name disclosure button toggles the full text, preserving expansion across refreshes and allowing read-only inspection. Inventory and carried-equipment header search boxes filter name and description in place, case-insensitively with every search word required, preserving queries across sheet refreshes like domain-card searches.
- Out of scope for the mock-up: persistence, host/protocol messages, validation, automatic rules and the SRD content library (classes, cards, equipment tables). The real implementation will add messages/fields additively and must re-check `OnlineProtocol.VERSION`; DM Link is unaffected.
- **Compendium and automation (done):** `dmmap-daggerheart/*.json` is the maintained Daggerheart catalog and source of truth (folder: `online.daggerheartDirectory`, default beside the settings file); its format is documented in `docs/DAGGERHEART_DATA.md`. "Excluded From Loot" is dropped. The host serves `GET /api/compendium` (category index) and `GET /api/compendium/{category}` (raw file) to logged-in players. The web client has a **Compendium** button on the right rail (below Actors) that toggles a floating, draggable window like the character sheet (no sidebar content switch). The window has a vertical category navigation on the left and a table with a search bar and content-dependent filters on the right (e.g. domain/level/type for domain cards, tier/slot/trait/range/damage type for weapons, rarity for items, class/stage/domain for classes). Tables never limit the entry count; each row shows the entry's key facts per category (`LAYOUTS` in `web/src/compendium.ts`) and expands to the full detail (features of ancestries, classes, etc.). Armor, weapons, consumables, items and domain cards have a subtle add icon that asks which owned actor receives it when the player has several (`web/src/actor-picker.ts`, also used when grabbing from a chat transfer offer), placed in the matching tab. Entry drag/drop is disabled; every category also offers Link in chat as specified in 3.41.3. Entries carry an optional `automation` block (passive `modifiers`, `uses`, `actions` with steps `clear`/`gain`/`mark`/`effect`) interpreted by `web/src/daggerheart/engine.ts`: armor sets evasion modifiers, thresholds and armor slots; features, items, weapons and cards show action buttons; effects appear in a new **Effects** tab; roll-targeted modifiers (e.g. Control Potion: next Finesse roll +1) are added to matching rolls and removed when `consume` is set; rests expire effects and reset uses. Healing/action dice are rolled locally and announced in chat. Conditional text effects are intentionally left as text. Sheet state is still in memory only. `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` are unchanged (additive HTTP routes only).
- **Rest dialog (SRD downtime):** the Short rest / Long rest buttons open a dialog (`web/src/daggerheart/rest.ts` logic, `view.ts` UI) instead of resting immediately. Each character has 2 downtime moves per rest; the same move cannot be chosen twice (Prepare alone/with others count as one move) and a short rest allows at most one long-rest move (Clank). Short rest: Tend to Wounds, Clear Stress, Repair Armor (each 1d4+Tier, self or ally), Prepare. Long rest: Tend to All Wounds, Clear All Stress, Repair All Armor, Prepare, Work on a Project (narrative). Prepare is split into "Prepare (alone)" = +1 Hope and "Prepare (with others)" = +2 Hope; there is no cross-player coordination. Ally move targets are chat-only; Oasis reactions to another character's own recovery report update that character's sheet on the host. Extra moves/permissions from the sheet: Elf Celestial Trance, Timekeeper's Pendant add a move; Potion of Stability (consumed) and Safe Haven (costs 2 Hope) are passive checkboxes that add a move; Forager and Potioncraft Kit grant restricted additional forage moves; Clank Efficient or the Recovery card allow long-rest moves during a short rest; downtime actions of features/cards/items (e.g. Warlock tribute, Alconite recharge) and other narrative downtime moves are offered; Martial Preparation grants a Slayer Die. There are no ally checkboxes. The character's own passive benefits (Hardy, Premium Bedroll, Blessed Anlace, Trollhide Cuirass, Patchwork Bastion, Pipeweed, Insomniac's Periapt effect) are inline checkboxes like the roll dialog's damage modifiers (not collapsible); defaults are on where the benefit is automatic, and players can untick benefits they don't use. Oasis instead offers explicit per-die buttons on short-rest chat reports, never automatic rerolls. Soothing Speech applies to ally Tend to Wounds. Three short rests in a row force a long rest (`shortRestsInRow` on the sheet, optional). Confirming applies the moves, `applyRest`, and posts one chat report (`rest-summary.ts`: `SHORT/LONG REST` header line without emoji (the legacy campfire-prefixed header still parses), subtitle line, `•` bullet lines, under 1000 chars) which `chat.ts` renders as a distinct green rest-report card ("Short/Long rest" banner); other clients show readable plain text. Cancelling changes nothing. GM Fear gain and domain-card swapping stay manual. Additive browser/host support: `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` unchanged, no new settings.

- **Config-driven downtime:** content-specific rest behavior is explicit automation metadata maintained in the relevant `dmmap-daggerheart/*.json` entries. Action `timing` distinguishes normal actions, downtime moves and rest benefits; `downtime` describes rest-kind restrictions and roll tables. Additional downtime options (Forager, Potioncraft Kit) join the existing move choices and consume a normal move slot; they never grant a third slot. Only explicit `rest.extraMoves` or passive `extraMoves` grants increase the slot count (Celestial Trance, Timekeeper's Pendant, Potion of Stability, Safe Haven). `rest` also describes long-move permission, passive benefits and move modifiers. No runtime feature/item-name exceptions or prose/label inference. The same metadata controls sheet buttons, rest choices and execution guards. Retain base SRD rest moves/validation as engine rules. Refresh missing rest metadata on saved equipment without overwriting GM-customized mechanics. Rest effects last until the next rest; consumables and costs are applied only when used. Preserve unrelated actions on mixed-use sources. Additive compendium metadata, no message/snapshot or settings changes; compatibility versions unchanged.
- **Action report:** using an item or feature action from a sheet posts plain chat text (`action-summary.ts`: `ACTION USED` header, title line `<source> - <action>`, character name line and `•` bullets for each effect, e.g. `cleared 2 HP (1d4)`) instead of `Name: Title: effects`. Feature titles include their originating community, ancestry, class or subclass and deduplicate adjacent names (for example `Hearthborne - Close-Knit`, never `Close-Knit - Close-Knit`), consistently for browser and host-generated reports. `chat.ts` and the DM `ChatCards` render it as a distinct blue-accented card (the roll source disclosure still attaches). Older clients show the readable text. Browser/DM chat styling only: no settings, protocol or compatibility version changes.
- **Action recovery details:** omit redundant parenthesized calculations for fixed numeric HP recovery amounts in both host and browser reports, including numeric strings; retain dice and variable breakdowns and the actual capped recovery.
- **Damage calculation dialog:** a subtle icon button in the Thresholds vital (owner/DM editable sheets only) opens a dialog styled like the roll dialogs (`web/src/daggerheart/damage.ts` logic, `damage-view.ts` UI). The player enters incoming damage and picks Physical or Magic. A live preview shows damage after reductions, severity and the HP to mark: 0 or less = none, below Major = Minor (1 HP), below Severe = Major (2 HP), Severe = 3 HP, and the optional massive-damage rule (at least twice the Severe threshold = 4 HP). The base "Mark an Armor Slot" option reduces severity by one threshold per slot (disabled when no slot is free or an armor restriction applies). Feature mitigations come only from explicit `automation.mitigation` metadata in the relevant `dmmap-daggerheart/*.json` entries; the browser never infers mitigation from prose. Options may halve damage (resistance, never stacking), reduce it by a flat value or rolled dice, negate it, reduce severity, change marked HP, replace the HP with their cost, allow extra Armor Slots (or any number), raise thresholds, make each Armor Slot reduce extra thresholds, charge a cost per Armor Slot, block Armor Slots, or roll dice whose successes reduce severity, HP or the Armor Slots marked. Conditions: damage type, incoming severity, using an Armor Slot, marking the last Armor Slot or the last HP, all Armor Slots marked, at most N HP unmarked, and a minimum `@` value. Passive options apply automatically; others are checkboxes. Dice are rolled once when the option is first ticked and kept for the dialog. Order: halve, then reductions/negation, severity from thresholds, severity reductions, HP adjustments, HP replacement; HP never exceeds the remaining slots. Apply validates the combined costs, marks HP and Armor Slots, pays costs, counts uses (own limit or the source limit with `uses: "source"`), consumes items and posts one damage report; Cancel changes nothing. The report is plain chat text (`damage-summary.ts`: `DAMAGE TAKEN` header, no emoji, character name, a `<input> <type> damage [→ <reduced>] · <severity> · <n> HP marked` line and `•` bullets for mitigations, costs and follow-up rolls) that `chat.ts` renders as a distinct card with a large HP-marked badge, severity chip and damage-type tag, like rest reports. Saved equipment gets missing mitigation metadata on load without overwriting existing data. Browser-only and additive compendium metadata: no settings, DM tools, protocol or compatibility version changes.

- **Community automation corrections:** Oasis is an explicit per-die action on any current-session short-rest report, available to an owned Duneborne character, keeping the higher result and updating the original report and recipient recovery by the improvement only. Its normal once-per-rest character usage is shared across every player's reports: after use, hide that character's Oasis buttons everywhere until their next rest. Button labels use HP/Stress/Armor capitalization and omit the previous result. Publish a formatted action report naming the recipient, original/rerolled/kept results and actual additional recovery (including no improvement). Unbound has a sheet action and shared session-use counter alongside its Fear-roll reaction. Close-Knit selects a connected ally and a positive Hope amount, paying and granting that amount atomically. Dedicated offers a once-per-rest action-roll option replacing the Hope d12 with d20; Found Family offers a once-per-rest option costing 1 Hope with an editable Experience bonus (default +2). Know the Tide displays its level-capped token pool on the feature, offers a once-per-Fear-roll gain button, and lets the player spend multiple tokens for matching bonuses; tokens survive reconnects and hosting restarts (saved with the sheet) and are cleared when the DM clicks New session. Nomadic Pack and Brave Face cost 1 Hope. Maintain metadata in communities.json, refresh saved heritage automation, document schema additions and rebuild the browser bundle. New optional routes/fields retain older-client behavior; no settings or DM tools, no LAN compatibility change.

- **Seraph automation:** Prayer Dice has a Roll action and one compact class-resource group of individually removable rolled dice, persisted as session-duration pool effects. Each die offers its value separately in incoming-damage mitigation and on any finished chat roll (including allies); spending consumes that die, and manual removal supports table-side usage. Devout rolls one extra Prayer Die and drops the lowest, and raises Sparing Touch to two uses per long rest. Healing reports always identify the recipient, including self. Spirit Weapon offers a Stress-costed additional-target button on eligible weapon attack chat. Sacred Resonance is default-selected for Melee, Very Close and Close weapon damage, doubling each matching die across the entire kept damage pool (including bonus dice), never flat modifiers or discarded dice. Ethereal Visage offers a successful normal Presence-with-Hope reward alternative that removes one GM Fear instead of gaining Hope; claiming either reward excludes the other, and reaction rolls never qualify. Success remains player-confirmed when difficulty is hidden. Power of the Gods upgrades Wings of Light from d8 to d12. Rules live in classes.json; additive host capability gates prevent silent unsupported mechanics on older hosts. Compatibility versions remain unchanged; no settings or DM tools.
- **Guardian automation:** Unstoppable creates a scene buff with a tracked die starting at 1 (d4, d6 from level 5). Display its value and die size as a read-only class resource in the sheet header and feature controls, sourced directly from the active buff; show Inactive when the buff ends, with no duplicate saved counter. Its damage-chat button confirms dealing at least 1 HP and advances the die once per roll, removing the buff on overflow; scene end also removes it. While active, offer its current value as a default-on damage option and physical severity reduction in mitigation; the mitigation option explains Severe to Major, Major to Minor, Minor to None, and that magic damage is unaffected. Retain the Restrained/Vulnerable immunity reminder. Act of Reprisal offers situational +1 Proficiency on damage rolls. Nemesis costs 2 Hope and creates one rest-duration Prioritized buff; attack-chat buttons swap the actual Hope/Fear results once per roll, preserving totals and updating breakdown/outcome. Targets and qualifying damage remain player-confirmed. Metadata is additive; existing rolls/clients retain their contracts, and Online Play/DM Link compatibility versions are unchanged. No settings or DM controls.

- **Seraph presentation and session corrections:** Prayer Dice roll once per session with a persistent shared `uses` counter (reset only by New session), with no confirmation. Use a fresh independent local RNG sample for every die and publish all rolled and kept faces; legitimate repeats are never suppressed, and Devout deliberately biases kept faces upward by dropping the lowest. Show values only in the class-resource header, not duplicated on the feature, as compact chips sized for six dice (face plus small Hope/remove controls with accessible labels). Chat buttons say `Prayer die: +N`, never die indices; distinct IDs still permit spending equal-valued dice separately. Refresh legacy stored die labels without altering faces or IDs; existing unspent dice consume the new session use on migration. Roll-note labels and explanations wrap horizontally within the chat card, with no overflow or letter-by-letter columns. Sparing Touch always names both HP and Stress recipients. Ethereal Visage has no confirmation and publishes a persisted action report explaining the GM Fear removal instead of Hope; journal the report with the reward claim and Fear update. The reward button itself asserts success when difficulty is hidden, superseding the earlier success-confirmation wording. Never use native browser prompts anywhere in browser UI; replace existing prompts with themed accessible dialogs/inline notices and preserve catalog unsaved-change protection, including embedded WebView close. No settings or compatibility-version changes.

- **Prayer Dice Hope affordance:** emphasize the compact plus control with Hope-yellow text, a subtle yellow background and heavier, larger glyph; retain its Hope tooltip and accessible label, distinct manual-remove control, and compact six-die layout.
- **Sacred Resonance breakdown attribution:** preserve base totals on every weapon/bonus/Combo dice contribution and put the full matching-dice bonus on the existing Sacred Resonance contribution, replacing its zero placeholder; add that contribution when absent. For faces 4, 4, 3, 2, show the base dice total 13 and Sacred Resonance +8, totaling 21. Keep matching across sources, discarded-die exclusions and damage multipliers unchanged. Do not duplicate per-source doubling notes. This is additive report presentation only; no compatibility or settings changes.
- **DM chat breakdown wrapping:** native JavaFX roll notes stack their label and wrapping explanation within the available card width. Do not use the fixed-width numeric-total column for explanatory prose. Long Sacred Resonance notes, including saved history, must not widen or overflow narrow DM chat cards. Numeric contribution and result totals remain right-aligned. No protocol, settings or compatibility changes.

### 3.41.7b Character Mancer, level-up guide and sheet persistence
- **Consumed Luck Charm reactions:** resolve active consumable-granted roll reactions from canonical consumable definitions even after the inventory item is gone, including existing buffs without source keys. Never trust client-supplied reaction rules; require the active matching effect, reroll exactly one die and remove the buff atomically. Preserve stale-roll and duplicate rejection. No protocol, settings or compatibility-version changes.
- **Forager inventory stacking:** repeated identical foraged consumable rewards increment the existing inventory quantity, for both rolled rewards and result-of-6 choices. Preserve distinct/customized items, automation and the party-wide five-consumable limit. No protocol, settings or compatibility-version changes.
- **Forager rest-chat choices:** render attached reward actions on long- and short-rest reports even without recovery-die metadata. Preserve short-rest reroll buttons and render combined actions only once. Browser-only fix; no protocol, settings or compatibility-version changes.
- **Critical spell rewards:** `criticalOnly` validates matching positive Duality dice, not an implicit attack requirement. The configured trigger source still restricts attack rewards to attacks and Fane's reward to critical Sage Spellcast rolls; reject noncritical, non-Duality, other-domain and duplicate claims. No protocol, settings or compatibility-version changes.
- **Spell-result action routing:** validated roll-trigger requests, including both Vicious Entangle damage and additional restraint, bypass the sheet-only action guard. Keep roll ownership, source, success and repeat-use validation intact; ordinary sheet-only actions still require the sheet. No protocol, settings or compatibility-version changes.
- **Sage and Arcana online automation revision:** keep card controls minimal and put success-dependent costs, damage and activation on their own Spellcast chat results. Gifted Tracker retains only its Hope action. Unleash Chaos resets on session start, selects at least one token when casting and remembers the spent count for d10 damage. Rune Ward creates one transferable trinket once per rest; its holder gains a Hope-costed d8 mitigation buff, reports the roll and destroys the ward on 8. Vicious Entangle offers its extra Hope restraint; Cinder Grasp and Delerium Grasp retain separate 2d6 ongoing-damage controls, with Delerium granting GM Fear on initial damage. Wall Walk targets one creature, expires on scene end or recast by that caster. Conjure Swarm groups its two spells. Flight activates automatically on success with at least one Agility token, spends through a preselected action-roll option and ends when depleted. Chain Lightning pays Stress on casting. Thorn Skin is a token-bound buff. Wild Fortress, Rejuvenation Barrier, Earthquake and Plant Dominion activate from successful results; Sensory Patterns limits the cast itself. Forager creates transferable automated consumables, with a choice on a rest result of 6 and a party carry limit of five. Conjured Steeds selects Hope, grants the caster and chat-claiming allies a long-rest buff with optional preselected attack/damage adjustments. Telekinesis always offers result damage. Cloaking Blast has its Hope action and a successful-other-spell reaction, granting Cloaked. Wild Surge tracks 1-6, adds and advances through a preselected action option, and ends with Stress and a chat explanation on overflow or rest. Confusing Aura selects additional Stress after success, tracks one base layer plus paid layers, and rolls/reduces layers from its card. Forest Sprites selects Hope after success and grants every actor shared-pool +3 attack and additional-Armor options, ending for everyone at zero. Fane of the Wilds has no card actions, selects its post-roll token quantity and offers critical Sage-spell token gain. Force of Nature grants a buff with preselected +10 damage and Hope maintenance, drops when maintenance is unselected, and offers Armor clearing on damage results. Falling Sky selects Stress and rolls the corresponding damage from its successful result. Preserve existing client/host compatibility using additive metadata and existing feature transactions; no settings or new DM tools. Document the mechanics and rebuild the browser bundle and classpath resources.
- **Brawler presentation corrections:** Brawler's Strike attack sources include I Am The Weapon's rules. Show the shift dialog's trait picker only for Favored and retain the supplied stance descriptions verbatim in choices and effects. A separate Martial Artist foundation feature named Focus owns the Focus track and once-per-rest Refocus action, preserving existing resource values and spent uses when loading older sheets; Stance Fighter retains shifting and explicitly describes all stance-ending conditions. Highborne Privilege excludes attack rolls. Refresh affected saved feature text and active stance descriptions from the catalog, update documentation, and rebuild browser and host bundles. No settings or compatibility-version changes.
- **Brawler rules clarifications:** Aggressive adds an extra d8 and an extra d6 to Brawler's Strike, discarding the lowest independently in each pool. Refocusing with Instinct 0 or lower clears Focus to 0 without rolling; it still uses the once-per-rest recovery.
- **Brawler automation:** synthesize Brawler's Strike only with no equipped primary or secondary weapon, scale both damage dice with Proficiency, and show its +1 Evasion buff. Combo Strike is an optional Melee damage-roll cost and unmodified ascending Combo Die chain, including the final lower roll; permit once-per-tier Combo Die advancement. Offer Overwhelm on attack chat, Surrounded with an editable Hope cost defaulting to 1, Eye for an Eye in mitigation, critical Melee Pummeljoy rewards and attack-linked +1 Proficiency, and a Severe-damage Not Done Yet recovery choice. Martial Artists track up to 6 Focus, refocus once per rest with highest of Instinct d6s, select two tier-1 stances at foundation and another tier-eligible stance each level, and shift via a known-stance picker for 1 Focus (or 1 Stress with Flow State). Exactly one descriptive, automated stance buff persists until Severe damage, last HP, manual removal or scene end. Cover all supplied stances; positional/target and successful-hit conditions remain explicitly player-confirmed where the host lacks that information. Keen Defenses, Focus Cannon's costed Instinct roll and linked Proficiency d20+3 magic damage, and Flow State's alternate Combo Strike cost use feature/chat controls. Maintain catalog metadata and documentation, preserve existing client/host compatibility for additive changes, and rebuild the browser bundle. No new settings or DM controls.
- **Host-generated action sources:** chat-button feature reports (including Marked For Death and Death Strike) and downtime-reroll reports attach the activated feature's bounded description-only `rollSource` snapshot, not the original attack/weapon source. Preserve existing whisper recipients and persist the source with the chat message for history replay. Reuse the host feature-roll source construction; no protocol, settings or compatibility-version changes.
- **Reaction rolls:** non-attack trait roll dialogs offer Normal / Reaction below advantage/disadvantage, without explanatory text below the selected mode. Reaction replaces the `action` source target with `reaction`, retains the trait/spellcast targets, and is clearly identified in browser and DM chat, including rerolls/history. Reaction rolls never grant the standard Hope or critical Stress-clear reward; the host rejects reward claims. Recompute applicable bonuses on mode changes and discard hidden selections without spending costs. Quick Reactions and other reaction-only roll bonuses are unavailable on normal rolls; action-only bonuses/triggers do not apply to reaction rolls. Audit maintained reaction-related catalog metadata, preserving bonuses explicitly applying to both kinds and leaving narrative/target-dependent conditions player-confirmed. Use the existing additive source tags, not a new required wire field; Online and DM Link compatibility versions and settings remain unchanged.
- **Stealth Expertise:** exclude attack rolls from its Fear-to-Hope chat reaction, including attacks tagged with `action` and reactions offered to allies. Keep non-attack Fear action rolls eligible, with stealth/range conditions player-confirmed. Declare `excludeRolls: ["attack"]` in the card JSON and enforce it in browser filtering and host validation. No settings or compatibility-version changes.
- **Underborne Low-Light Living:** exclude attack rolls from its advantage option using the existing roll-option `excludeRolls: ["attack"]` filter. Add regression coverage and document the catalog metadata. No settings, protocol or compatibility-version changes.
- **Attack-linked damage defaults:** clicking Damage on an attack chat card passes that roll's Fear Die result into the damage dialog. Inputs declaring `source: "attackFear"` (Fear Leaf) use it as their editable initial value; direct sheet damage or missing/invalid context retains the configured default. Do not select or spend the option automatically or reuse another attack's value. Browser-only additive metadata; no protocol, compatibility or settings changes.
- **Assassin automation:** Marked For Death has sheet and weapon-attack chat actions costing 1 Stress and starting a rest-duration effect; while active, any damage dialog offers optional tier-count d4 damage, upgraded to d6 by Ambush and d8 by Backstab. Target defeat/GM Fear clearing remains manual in Effects, and players select the bonus only against their marked target. First Strike is an optional damage multiplier (double the rolled total, including bonuses and critical damage, not extra independent dice); its first-success-in-scene condition remains player-confirmed. Death Strike has both sheet and damage-result chat actions costing 1 Stress; Severe damage and target HP remain player-confirmed. True Strike is offered on attack results, sharing its Hope cost and long-rest use with the sheet action. Toxic Concoctions clears tokens automatically on long rests, and weapon-attack results offer Afflict with poison for 1 token. Venomancer adds a Fear Leaf damage option using an explicitly entered Fear Die result. Additive automation and dice-expression capability only; older hosts must reject/disable unsupported multipliers, existing clients retain ordinary rolls, and Online/DM Link compatibility versions and settings stay unchanged.
- **Data-driven rules expansion:** compendium automation declares custom resource pools (initial values, optional caps, gain/spend actions), creation bonuses and domain-card selection allowances, and optional roll bonuses with independent costs. The sheet displays active custom pools and offers applicable optional bonuses together in the roll dialog; validate combined costs and pay/consume only when the roll is sent successfully. Inventory bonuses require positive stock; carried, unequipped weapons/armor do not contribute. Warlock Favor and Patron Die, Charging Quiver and Powerful weapon damage must be covered. Powerful rolls an extra weapon damage die and discards the lowest on the host, with an accurate chat breakdown. Maintain rule metadata directly in the compendium JSON and document unsupported narrative/target-dependent mechanics explicitly rather than inventing automation. Persist additive fields with defaults for old sheets; preserve legacy automation and Online/DM Link compatibility.
- **Warlock automation:** Patron's Boon has the standard blue Hope feature action without changing its chat reroll. Favor appears only in class resources (tribute remains a downtime move); successful Hope action rolls offer a one-time host-authoritative Favor reward instead of Hope. Deathless Embrace asks how many Favor/Patron Dice to spend, rolls them, clears one HP per 4+, and reports dice and actual healing. Deadly Vengeance asks for HP marked, spends one Favor, rolls one Patron Die per HP and reports attacker HP marked per 4+; also offer it in mitigation using final HP marked. Menacing Reach grants an effect naming the primary weapon and its increased range (capped at Very Far); primary-weapon attack results offer an end-effect action after a successful attack. Diminish My Foes has feature and Hope-action chat buttons asking for Favor spent and reporting target Stress. Fearsome Attack retains its feature button and adds repeatable damage-chat selection/rerolls for one Favor per use. Otherworldly Ire is feature-only, asks for Favor spent, rolls Patron Dice and reports the highest result as the number of Close-range targets marking one HP each. Patron Dice are d6 below level 5 and d8 thereafter. Success, attack origin and target conditions remain player-confirmed where unknown. New host actions/reward support use additive `warlockAutomation` capability gating; no settings or Online/DM Link compatibility-version changes.
- **Option-dialog scrolling:** all dialogs presenting option lists (including roll bonuses, incoming-damage mitigation/Armor/passives, downtime benefits, feature benefits, damage-die choices, Beastform parts, stance learning, ancestry and card selection, and death-move bonuses) scroll only the option container when it exceeds the available height. Keep headings, controls outside that container, previews, errors and action buttons visible. Incoming-damage recovery controls remain outside the mitigation list. Preserve empty-option and mode-switch behavior; no settings or remote compatibility changes.
- **Buffs and consumables in roll dialogs:** a roll option may carry an `effect` (Patron's Fury, No Mercy). The corresponding compendium JSON entry also defines an `activate` button that pays the cost and starts the effect; ticking the option in the dialog does the same, with the cost shown. While the effect is active the option is pre-ticked and free. "+1 to next <trait> roll" potions are `consumeItem` options (listed unticked, ticking uses one potion); a drunk potion's prepared `consume` modifier is listed pre-ticked and spent by that roll.
- **Elf Quick Reactions:** retain its sheet action and also offer an unticked action-roll option labeled for reaction rolls. Selecting it costs 1 Stress and suggests advantage through the existing roll-option rules, paid only after successful roll submission. Reaction eligibility remains the player's choice because there is no separate reaction-roll type. No settings or compatibility contract changes; rebuild the browser bundle.
- **Effect names in action reports:** effects granted by items/consumables use a concise name describing the actual buff or debuff (for example, `Finesse Roll +1`), not the source item's name. Keep the source item/action visible in the report and effect details; give all automated item effects clear labels while preserving their mechanics and expiry.
- **Roll-chat buttons:** your own duality roll cards in the chat get a .roll-actions box. Attack rolls show a Damage button that opens the same dialog as the sheet. Features declare rollTriggers (on = duality/attack/spellcast/trait:X, when = always/fear/hope/failure/hopeDie/dieMatches, optional cost, steps, reroll = hope/duality, advantage). Covered: Strange Patterns (number resource; Hope/clear Stress on a matching die), Ranger's Focus, Hawkeye, Nimble Fingers, Feline Instincts, Internal Compass, Luckbender, Adaptability, Fearless, Unbound, Shadowed Grit, Patron's Boon, Courage. Rerolls send a new roll (Hope-die rerolls a single 1d12). The Online host echoes optional ref/actorId/source/weapon hints on roll messages (additive, no version bump). Left manual: Slayer, Know the Tide, Nemesis, Enchanted Aid, ally-targeted features, damage-die rerolls.
- **Reload policy:** on every load of a character, the automation of class, subclass, ancestry, community, transformation features and domain cards is refreshed from the compendium (resources, spent tokens and HP/stress tracks are kept). Grimoire cards also refresh their text and `spells[]`. **Grimoires** (`cardType` Grimoire) are rendered as one block per spell separated by a divider line; each spell decides its own Spellcast roll and action buttons (own cost/usage, key `card:<Book>/<Spell>`). The markdown renderer supports `***bold italic***` (previously it left a stray `*` before spell names). Weapons, armor, items and consumables are never refreshed because the GM may have altered them for the player. The GM Fear counter feeds `@fear`; `@cards_<domain>` counts loadout + vault cards of a domain (Umbral Veil, Fane of the Wilds).
- **Domain-card automation:** spell cards only get a Spellcast button when their text asks for the roll (`asksSpellcastRoll`); the roll's source carries `card:<name>`. Triggers with `damage` (Blighting Strike Hope/Fear, Blood Spike, Bolt Beacon, Corrosive Projectile, Siphon Essence with per-action `uses` and +1 Proficiency on Fear, Preservation Blast, Rain of Blades, Towering Stalk, Cinder Grasp, Vicious Entangle, Conjure Swarm, Grisly Harpoon, Delerium Grasp) open the spell damage dialog from the chat and pay cost/steps only once the damage is sent. Actions may have their own `uses` and a `sendRoll` (I See It Coming posts 1d4 + Evasion without changing the sheet). Token pools: Umbral Veil, Flight, Fane of the Wilds, Never Upstaged, Strategic Approach. Roll dialog uses Advantage/Normal/Disadvantage buttons. Difficulty was removed from the dialog, Ranger's Focus is a buff-gated reroll, damage has a Critical checkbox, chat buttons are aligned full-width. Limits: token damage options are not offered in the spell damage dialog; Fear/Sage counts are typed in; Fane spends one token per roll.
- **Persistence:** `GET /api/sheet/{id}` returns `{actorId, name, sheet}` (`sheet` null when none); `PUT /api/sheet/{id}` stores the JSON object (owner only, 403 otherwise; 413 above 512 KB; 400 for non-objects) in `<campaign>/sheets/<actorId>.json`. The client loads on open and autosaves (debounced) after any edit. Additive; no version bump.
- **Empty sheet:** an actor without a stored sheet gets `emptyCharacter` (`mancer.done = false`); the sheet shows only a start prompt. Stored sheets without `mancer` count as finished.
- **Creation before advancement:** disable the normal sheet's Level up button until character creation is complete, with a tooltip explaining the prerequisite. Completed and legacy sheets retain normal level-up access below the maximum level; testing-build controls are unchanged. This client-only restriction adds no settings or compatibility-version changes.
- **Roll-option labels:** show the originating community, ancestry, class/subclass or transformation alongside its feature/option (for example, Highborne - Privilege), including mixed ancestry and multiclass sources. Collapse identical adjacent source/feature/option names for cards, equipment and consumables as well. When the following label repeats that name with a parenthetical qualifier, colon or spaced-dash suffix, retain the more descriptive label once (for example `Elf - Quick Reactions (reaction roll)` and `Orderborne - Dedicated (Hope d20)`). Apply the same convention to action reports on browser and host. Use the same formatter in roll dialogs and selected-option breakdowns; preserve usage keys, costs, effect source names and existing client/host contracts. No settings or compatibility-version changes.
- **Roll-option layout:** keep numeric bonus inputs and token-spend counts in the same row as their checkbox option, in a compact labeled control on the right. Descriptions, costs and blockers wrap with the option text on the left. Separate each option row into its own padded, rounded, bordered panel with a subtle background and space between panels, including options without inputs. Apply the same grouping to damage mitigation and Armor Slot counts, with separate labels for checkbox and number controls. Experience, Otherworldly damage type and automatic damage-recovery dropdowns stay right-aligned beside their labels and shrink within narrow dialogs. Preserve selection, validation, costs and keyboard behavior; no settings, API or compatibility-version changes.
- **Roll rewards:** new character-linked duality rolls offer the actual roller a host-authoritative, one-time chat claim: +1 Hope for Hope, or +1 Hope and clear 1 Stress for criticals. Fear-to-Hope conversions change only the outcome, never grant Hope automatically. Disable claims at maximum Hope, except a critical can still clear marked Stress; an entirely capped/no-benefit claim is rejected without consuming it. Persist the claim and sheet changes together, reject other players/non-owned actors, duplicate claims, Fear/non-duality/legacy rolls, and stale saves. Synchronize chat history, open sheets and pop-outs. Advertise additive `hello.rollRewards` support and an optional host-created `rewardClaimed` marker; older hosts/history never offer claims. Additive Online Play HTTP/fields only, with unchanged Online/DM Link compatibility versions and no settings or DM controls.
- **Testing editor layout:** the testing-build form fills the dialog's available height and scrolls internally. Validation errors belong in the footer beside Cancel/Apply, not in a separate expanding region; keep both actions visible at all viewport heights. No settings or compatibility contracts change.
- **Testing traits:** Edit testing build exposes all six base trait modifiers as unrestricted integer number spinners, including negative values, without creation-array or advancement limits. Changes remain draft-only until Apply, survive build changes and reloads, and preserve subclass Spellcast selection. Normal character rules are unchanged; no settings or compatibility contracts change.
- **Mancer steps (one small choice each, Back/Next, progress bar):** class, subclass (foundation), ancestry (or mixed), community, traits (tiles with a +2/+1/0/-1 selector, swapping on conflict), primary weapon, secondary weapon (only for one-handed primaries), armor, supplies (potion/class item), background questions, Experiences (two), domain cards (two, level 1 from the class domains), connections, identity, review. Class guidance comes from the SRD (`guide.ts`); classes without SRD guidance (homebrew) use generic guidance.
- **Level-up steps:** tier-milestone Experience (levels 2/5/8, +1 Proficiency, marked traits cleared at 5/8), two advancement points (Proficiency and Multiclass cost two), per-advancement detail, new domain card (loadout or vault), optional swap of one owned card, review. Thresholds follow the level automatically.
- **Memory:** `mancer.choices` stores every creation choice and `levelUps[]` every level's choices; creation can be redone only before the first level-up and keeps notes, gold and inventory.
- **Sheet header labels:** prefix the original class with "Class:" and additional classes with "Multiclass:".
- **Stable wizard detail layout:** steps offering hover/focus details reserve an empty side panel even before anything is selected. Hovering, focusing, selecting or clearing a choice changes only panel contents, never the layout. Reserve panel height in the stacked narrow-screen layout as well; steps without detail previews remain full-width. This is client-only UI behavior with no settings or compatibility-version changes.
- **Transformations (Hope & Fear rule):** optional, at most one per character, GM-granted or chosen at creation if the GM allows it. The mancer has an optional "Transformation" step (default None) after the community; the Heritage features card on the Character page can add, change or remove one at any time (`setTransformation` in rules.ts). Its features (benefit and drawback) are always-active heritage features with source `Transformation - <name>`, stored in `CharacterSheet.transformation` and `mancer.choices.transformation`; they never count against the loadout/vault.
- **Transformation automation:** Ghost's Ephemeral passively halves physical damage and doubles magic damage, with a 2-Stress pass-through action/report. Shapeshifter keeps exactly one explicitly selected ancestry feature; acquiring it and changing ancestry require that choice. Change Shape and Only Skin Deep are downtime actions (also feature buttons) to change ancestry plus feature, or only the current ancestry feature. Vampire Fangs provides normal attack/damage controls using the highest trait; its roll offers Feed (1 Stress, entered HP/token gain, capped at 6). Feed tokens persist, lose one per long rest, can pay for a Fear d20 action roll, and at zero offer a default-selected disadvantage suggestion for action/reaction rolls. Wolf Form is a rest-expiring effect activated for 1 Stress from its feature or HP-marking mitigation; it automatically adds 1d10 to attacks/damage and offers a Stress-marking button on Hope rolls. Howling Rampage rolls tier d20 physical damage and ends Wolf Form. Keep metadata in transformations.json and cover acquisition, actions, rolls, damage and rests; additive browser automation only, no settings or compatibility-version changes.
- **Feed input UX:** use the standard styled in-app feature popup, not a browser prompt. Show numeric HP/token input, inline validation/errors, prerequisite guidance and Cancel/Feed controls. Cancel/Escape spends nothing; submit once on confirmation, retain input on request failure and prevent duplicate submissions.
- **Wizard UX (feedback round):** fixed-height dialog with an inner scrolling body that keeps its scroll position on redraw (reset only on a step change); a side panel (hover/focus or current selection) shows descriptions and features of class, subclass, ancestry (incl. mixed second feature), community, transformation, gear and domain cards; mixed ancestry uses a local mode flag (not derived from ancestry2); weapons and armor are tables (physical/magic x one-/two-handed groups) limited to gear flagged `starter: true` in the maintained compendium JSON (`starterGear` in rules.ts); the level-up swap uses the same card grids. `rich.ts` renders the compendium markdown. The Character journey card is removed. Evasion/Armor/thresholds show their sources (`statBreakdown` in engine.ts) and the header lists multiclasses (`multiclassesOf`). No version bumps.
- **Assumption:** advancement slot counts per tier are the editable table `ADVANCEMENT_RULES` (rules.ts). Rules logic is covered by `rules.test.ts`, persistence by `OnlineSessionServerTest`.

Real character sheets and rules automation (other than the Daggerheart sheet above, which is tracked in 3.41.7a), anti-cheat, accounts beyond per-campaign players, TLS termination in the host (use a tunnel/reverse proxy), voice/video, mobile apps, general map editing from browsers (owned effect drawing is supported).

### 3.41.8 DM chat tab and secret rolls (implemented)
- A **Chat** tab sits at the top of the DM controls rail and exists only while a campaign is hosted (hideable control `online.chat`; local API control of the same id). Clicking it opens a wider flyout with the chat; closing the session removes the tab and clears the window. Native JavaFX (`ChatPanel`, `ChatCards`), DM palette for chrome, browser-identical card contents (rolls with breakdown/source, whispers, shared cards, rest and damage reports) but no item-grab or damage buttons.
- **DM Measure tool:** a hosting-only **Measure** rail icon directly below Chat (hideable `online.measure`; API controls `online.measure`, `.distance`, `.radius`, `.cone`, `.rect`, `.hint`) with the browser's four modes. Dragging on the DM map creates a DM-owned shared measurement (id prefix `dm-`, owner `dm`, broadcast at 10 Hz, final send on release via `OnlineSessionServer.dmMeasurementUpsert`, 500 limit); shapes stay until their x is clicked (works in the Measure tool and in Select; right-clicking a measurement in the DM view does not open a context menu), Esc or right-click cancels an in-progress gesture (deleting a published one) and then leaves the tool. Choosing another tool, ping or laser leaves it; stopping hosting or hiding it also does. Same session-only semantics as browser measurements; no remote-compat or online-protocol change.
- **Daggerheart ranges in measurements (DM and browser):** labels show tiles plus the SRD range for the measured length (cone/radius length, rectangle diagonal), using the SRD's 1-inch-grid suggestion: ≤1 tile Melee, ≤3 Very Close, ≤6 Close, ≤12 Far, otherwise Very Far (Out of Range cannot be determined on a map). Format `5.0 tiles · Close`, `r = 4.0 tiles · Close`, `3.0 x 4.0 tiles · Close`. Implemented in `OnlineMeasurement.label` (Java) and `measurementLabel` (`web/src/tools.ts`) with identical text.
- Unread indicator: an amber dot on the rail icon when live messages arrive while the chat is closed; cleared on open.
- Composer: recipient dropdown (Everyone + connected players), send, roll and **secret roll** buttons, and `/roll`/`/r`/`/secret`/`/sr` commands; the DM also has `/gold <amount>` to post a gold transfer offer. The DM id in chat is `dm`, name `DM`.
- **Secret-roll toggle:** the eye-off control is an initially off, visibly selected toggle, not a submit action. While enabled, all rolls from the DM chat composer (Roll button and `/roll`/`/r`) are secret; ordinary messages and gold offers are unchanged. `/secret` and `/sr` always roll secretly, including through the Roll button, without changing the toggle. Successful or failed submissions do not reset it. This is local composer state only; no settings, remote contracts or compatibility versions change.
- DM moderation: each message/roll card in the DM Chat tab has a subtle, keyboard-accessible delete icon. Only the desktop host can delete messages visible to the DM; player clients have no delete control or accepted delete command. Persist removal before updating views, report failures in the DM chat, and remove deleted shared cards from the transfer cache. Send additive `chatDeleted {id,ts}` events only to the original audience and remove the entry from DM/recent tails and browser logs without creating unread notifications. Match both id and timestamp because older sessions reused numeric ids; new ids start above persisted ids and the current epoch milliseconds. Deleted entries stay absent after reconnect/restart; older Online protocol 1 clients ignore the event and converge on their next snapshot. No DM Link contract or compatibility version changes.
- Secret roll: players see `?` on every die and total, no modifier, breakdown or outcome; dice type and count are unchanged (expression reduced to its dice terms). The message carries `secret:true`; the real values live in `secretReal`, kept in history and delivered only to the DM view (stripped for players in `deliver`, snapshots and the published `chatTail`). The DM sees real numbers plus a "secret" note.
- Look and behaviour polish: the flyout takes the full free height (below the top margin, above the Fear counter via `FearOverlay.reservedHeight()`); cards copy the browser styling (gradients, rest/damage accent borders, shared-card, transfer-offer and domain colouring, formatted roll breakdown/source); flat roll modifiers never truncate. Scrolling is pixel based: expanding a breakdown/source keeps the viewport put, new messages stick to the bottom only when already there, and the "Jump to latest" overlay appears once more than ~40px above the end. Transfer offers show a large remaining count with "of N offered", refreshed live through `OnlineSessionServer.Host.cardStockChanged` -> `OnlineSessionController.addCardStockListener`. The DM sender (`fromId` `dm`) is displayed as "Gamemaster" in both the DM view and the browser chat; the stored name stays `DM` for compatibility, and DM gold offers read "Gamemaster offers N gold".
- Compatibility: additive message fields only, so `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` are unchanged. Tests: `OnlineSessionServerTest` (masking), `ChatCardsTest`, `web/src/chat.test.ts`.

### 3.41.9 Handouts / image file share (implemented)
- **Storage:** `CampaignHandouts` keeps per-campaign `<campaigns>/<id>/handouts/index.json` (folders, files with id/name/folder/ext/size/`allPlayers`/`players`) plus blobs in `files/<id>.<ext>`. Names are unique case-insensitively within a folder (files and folders share the namespace); import and copy auto-suffix duplicates, rename/move conflicts are rejected (409). Images are validated by decoding; png/jpg/gif are stored as-is, other formats become PNG. Limits: 25 MB, 120M pixels, 2000 files, folder depth 12. Grants persist; "all players" also covers later joiners; copies do not inherit grants.
- **DM UI:** a **Handouts** rail button directly below the Compendium button, visible only while hosting (hideable control `online.files`; local API controls `online.files`, `online.files.import`, `.paste`, `.folder`, `.refresh`). Wide flyout (`HandoutsPanel`) with a tree (thumbnails, access label), search, add from disk (multi-select), paste from clipboard (asks for a name), new folder, context menus (open, show to all, share, duplicate, rename, delete), drag and drop between folders (Ctrl copies) and OS file drops, F2/Delete/Enter/Ctrl+V. Share dialog grants individual players or all players. Double-click opens `HandoutImageWindow` (floating in-app window); "show to all players" there or in the menu.
- **Players:** `GET /api/handouts` (list filtered to granted files; folders derived from granted files' paths) and `GET /api/handouts/{id}` (404 unless granted). The server pushes `handouts` after hello and on every change, `handoutShow {id,name}` when the DM shows an image (it also grants it to everyone). `hello.handouts` gates the **Files** rail icon below Compendium. Players are strictly read-only: list-style browser with breadcrumbs, thumbnails and search; double-click opens a draggable image window. The Notes tree shares the same row styling.
- **Window stacking (player browser):** sheet, compendium, note and handout windows share one stack (`bringToFront` in `web/src/daggerheart/window.ts`, inline z-index from 4 upwards). Opening or re-opening a window (including a DM-shown handout) raises it; clicking any window raises it, even above handouts.
- **Compatibility:** additive routes/messages/hello flag only; `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` unchanged. Tests: `CampaignHandoutsTest`, `OnlineSessionServerTest`, `web/src/handouts.test.ts`.

## 8) Open Decisions (Track Here)

- ~~Online Play music~~ Decided: sync playback state, browsers play hashed originals (3.41.5).

- ~~Exact tile-to-inch calibration UX.~~ Decided: manual screen-diagonal entry + test square (v1.1).
- ~~Whether to store fog as stroke history, bitmap mask, or hybrid representation.~~ Decided: bitmap mask (v0.8).
- Minimum supported GPU/OpenGL profile for JavaFX on older laptops.

## 9) Change Log

- **Handouts / image file share (3.41.9):** DM Handouts rail tab and player Files tab with folders, per-player/all-player grants, live propagation, image windows and show-to-all. Additive; no compatibility bumps.
- **Daggerheart Character Mancer, level-up guide and sheet persistence (3.41.7a):** new actors have an empty sheet (`mancer.done = false`) with a start prompt; wizards in `web/src/daggerheart/mancer.ts` on top of pure rules in `rules.ts` (class guide data in `guide.ts` derived from the SRD, built from the compendium). Choices are remembered (`mancer.choices`, `levelUps`). Sheets persist on the host via `GET/PUT /api/sheet/{id}` (owner only, 512 KB limit, `<campaign>/sheets/<actorId>.json`) with debounced client autosave. Additive routes; `OnlineProtocol.VERSION` and `RemoteCompatibility.VERSION` unchanged.
- **Character Mancer UX overhaul:** side panel with feature details, scroll preservation, working mixed ancestry, trait tiles, starter-gear tables with separate primary/secondary weapon steps, rendered markdown, stat sources, multiclass in the header, redesigned level-up card swap, journey card removed; `starter` flag added to the generated weapon/armor data.
- **Online Play (3.41):** opt-in DM-hosted browser clients with campaigns, players and actors, Javalin host, snapshot/delta protocol and TypeScript client; the Session tab is renamed DM Link.
- **Online Play UI overhaul (3.41.1):** subtabs (Campaigns, Players, Actors, Connected) with tables and context menus, campaign game system, actor folder tree with artwork and drag-and-drop onto the map, pause key (`online.pauseKey`) with DM and player banners, player ping, styled dialogs; campaigns now live in `dmmap-campaigns`. The web client is bundled with the host, so `OnlineProtocol.VERSION` is unchanged (ping is additive); DM Link is untouched, so `RemoteCompatibility.VERSION` is unchanged.
- **Online play map sharpness:** browsers stream large map images at full resolution as pyramid tiles (`/t/`), the downscaled fallback uses higher-quality resampling/JPEG, and the browser grid follows the DM's player-grid toggle (`view.showGrid`). Additive protocol fields; no compatibility bump.
- **Token lights (3.38):** per-instance light on placed tokens via the token context menu (default torch), with the map light options except removal; remote compatibility version 4.
- **Application logging (3.40):** consistent dmmt.log.Log logging with console + rotating file output in a `logs` folder, configurable level, replacing ad-hoc `System.err` prints.
- **LAN remote workspace control (3.39):** opt-in Session menu, pinned TLS pairing/approval, one-client
  authority, locally rendered DM replica, shared settings/history, host-backed map/token/audio libraries
  and imports/exports, multilevel management, handouts and host-only player/audio output. Standalone
  startup performs no remote synchronization, listener, discovery or artwork preparation work.
- **Character tokens (3.38):** mutually exclusive Maps/Tokens sidebar tabs, searchable folder-based token library,
  clipboard/file imports with circular pan/zoom editing, independent hidden-by-default map instances,
  half-grid/Shift-free movement, group editing, DM name tags and self-contained map export.
- **Import/loading responsiveness (3.37):** header-only map dimensions, input-signature thumbnail reuse,
  move-stable image pyramids and adjacent-floor prefetch; one-pass loudness with shared waveform/timbre
  analysis, persisted pending audio and a window-independent preparation queue; bounded priority resource
  scheduling with live session/preparation work modes.
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

- Regression coverage must retain the current portal menu's Locked toggle and type-selection behavior, allow actor-rename notifications without a saved sheet while checking save persistence at notification time, and exercise bad certificate pins and incompatible remote versions independently rather than racing the bounded listener's TCP backlog. These are test corrections, not remote-contract or settings changes.
- Embedded-sheet persistence checks must wait for the exact saved resource field and value, not a JSON substring that can match a different resource.
- Browser sheet-view test doubles must support both observing and disconnecting resize observers and supply realistic event targets; valid character-creation fixtures must supply required subclass choices such as an Elementalist element. Action assertions must follow the catalog labels. Run the separate browser suite as well as Maven when verifying Online Play releases.
- Tests must not read or modify project-root settings files. An automatically registered JUnit extension redirects `dmmt.settings` to a fresh temporary copy of `src/test/resources/dmmt/service/test-settings.ini` for each class and test, including IDE runs, and restores the property and removes its files afterwards. Test-specific settings overrides remain supported; settings fixtures belong in test resources.
- Frame-profiler accounting tests use an injected monotonic clock and exact durations rather than sleeps, parking guarantees or comparisons affected by runner scheduling.
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

- DM chat pop-out: the Chat section header has a pop-out button that moves the single ChatPanel into a separate Stage (placeholder with a dock button remains in the section); closing the window docks it back. No remote or settings change.

# Online Play web client

Enrapture and Hypnotic Shimmer keep only Spellcast on their cards; successful results offer the
once-per-rest Stress payment or activation. Target conditions remain table-resolved. Thought Delver
keeps only its Hope payment on the card, with an optional deeper-thought Spellcast on the payment report.
`chatOnly` hides an action from sheet controls; `followUpAction` references its ID on a host-owned
payment action. The host includes optional `actionFollowUp` metadata on the committed report.
The owner's browser resolves the action against the current active card and opens the standard roll
dialog, without paying Hope again or consuming the follow-up on cancellation. Older clients ignore
the additive report field; no settings or compatibility-version changes are required.

Dread cards keep follow-ups on their matching chat results: Terrify Stress, Wall of Hunger payment,
Dark Army summoning, Damnation's selected Stress damage, Siphon Essence's target-marked-HP healing,
Dire Strike on weapon damage (including automatic Fear removal), and Jump Scare on magic damage.
Blighting Strike criticals use Hope damage. Hideous Retribution has a Spellcast-trait reaction on
the card and allied incoming-damage reports, followed by Stress-paid damage on its own reaction.
Summon Horror rolls Spellcast-count damage directly; Darkfire first selects at least 1 Hope.
Spectral Mist applies physical immunity to the caster and offers allies the same buff in chat,
expiring on an action roll or scene end; remove it manually after passing through a solid object.
Umbral Veil tokens expire with their scene buff, Dark Army's buff ends at zero fiends, and
Avatar of Terror mirrors Force of Nature's preselected damage/maintenance options with live Fear d6s.
Spotlight Hope is manual. Invoke Torment's doubled portion is a separate breakdown entry without rerolling.
Umbral Veil's rest counter and Dark Army's long-rest counter track activation, not token spending.
Ordinary damage reports carry actor metadata for allied Hideous Retribution reactions in both browser sheet views.

Sheet catalogs share in-flight downloads and parsed data across owned actors, rules and compendium tools.
Reconnects and catalog edits invalidate that cache; custom fetchers and actor-scoped DM authorization stay isolated.
Hidden/minimized sheets retain live data, effects and chat damage handlers without building their full sheet DOM.
Layout and sheet notifications are coalesced, and hidden browser tabs skip map drawing until visible again.
Visible sheet rebuilds position feature/domain cards immediately before restoring scroll; only follow-up
resize measurements are deferred, preventing a flash of overlapping or full-width feature text.
Unchanged saved sheets no longer need an initial save; new characters and migrations still persist.
Hosts advertising `sheetActionResponses` can return committed sheet data after feature actions, avoiding a follow-up GET.
Hosts advertising `sheetPatches` receive top-level save patches only with a known baseline and exact `If-Match` version.
Old hosts retain full saves and GET refreshes. Pending local edits are never replaced by action responses; conflicts and
persistence errors remain explicit. Spending and rolls are never queued for automatic offline replay.

The browser map never displays token names, including when a DM enables a name label. Names are hidden by
default and can be shown to the DM only from the desktop map's token right-click menu.
Token condition menus open from the canceled browser `contextmenu` event, rather than on pointer press,
so displaying the in-app menu cannot redirect the native context-menu event to a new DOM element.
Hidden is a linked actor buff; Restrained, Vulnerable and custom conditions are debuffs.
The host automatically adds Vulnerable when all Stress slots are marked and removes that generated
effect when any Stress is cleared, including through actions and rests. Saved full-Stress provenance
keeps Vulnerable from another source intact across reloads. Explicit token-condition application
takes manual ownership, so clearing Stress will not remove that effect.
Existing linked Hidden effects are reclassified when synchronized with their token.
Effect removal flushes the sheet save before sending `setTokenCondition`, so the host's linked-effect
update does not race the client's versioned autosave. A failed save keeps the token unchanged and reports
the existing save error; edits in another window still receive normal conflict protection.
Linked actor conditions persist when their token is absent, hidden or on another map. Fresh tokens
inherit saved conditions on the host; synchronization matches actor effects by condition ID, preserving
legacy token linkage without duplicating effects when the token ID changes. Non-actor conditions remain local to the token.

Wizard automation is maintained in `dmmap-daggerheart/classes.json`. Adept is an optional duality-roll
choice requiring a selected Experience; it doubles that modifier and replaces the 1 Hope cost with
1 Stress. Honed Expertise automatically rolls a client-local d6 at submission, showing the result
and refund outcome in a zero-value breakdown entry (never added to the action total). A 5+ refunds
the Experience cost, including Adept's Stress; this intentionally extends the printed Hope-only refund.
Combined costs must be affordable before rolling, and cancellation/disconnected sends spend nothing.
Conjure Shield automatically adds Proficiency to Evasion at 2+ Hope, including the stat breakdown.
Manual Hope pip changes immediately refresh Evasion, its source tooltip and the mini strip.
Face Your Fear's optional magic damage upgrades to 2d10/3d10 with Fueled by Fear / Have No Fear.
Thrive in Chaos uses the existing host-validated damage-chat feature action: mark 1 Stress and report
the target's additional HP, with successful attacks player-confirmed. No new settings, wire fields
or compatibility-version changes.

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

Roll dialogs group each numeric bonus or token-spend input with its checkbox option, keeping the labeled
control on the right and wrapping descriptions/costs on the left. Each option, including those without inputs,
has its own padded, rounded, bordered panel with a subtle background and space between panels. Damage mitigation and Armor Slot counts
use the same layout with independently labeled controls. Experience, Otherworldly damage type and automatic
damage-recovery dropdowns remain beside their labels and shrink to fit narrow dialogs. Roll behavior,
settings and client/host compatibility contracts are unchanged.
Long option lists scroll independently across roll bonuses, damage mitigation (including Armor and passive
benefits), downtime, feature/damage-die/Beastform/stance/death-bonus choices, ancestry and card selection.
Headings, controls outside the list, previews, errors and action buttons remain visible. Damage recovery
controls stay outside the mitigation list. Switching roll modes resets the option list's scroll position.
`checks/roll-dialog-scroll.html` checks real-browser overflow, stationary controls, last-option access,
empty lists and mode changes for trait/damage rolls, mitigation, feature benefits and card/roll choices.

Actor snapshots and sheet responses include an additive `disabled` flag. Disabled actors remain listed for
their owner, but their sheets are read-only with a disabled indicator beside the name, and their tokens have no player controllers. Read-only navigation and background flushes do not save; pending edits remain until re-enabled. The host rejects
disabled-actor writes, actions, rolls and inventory claims even from older clients, and excludes them from
actor-based targeting, effects and Copycat candidates. Existing effects remain, and the DM's authenticated
sheet retains edit access. The flag defaults to false for older campaigns and hosts; DM Link compatibility is unchanged.

Daggerheart ancestry automation is maintained in `dmmap-daggerheart` with the schema in
`docs/DAGGERHEART_DATA.md`. `engine.ts` exposes configured trait/attack/damage actions, suggested roll modes,
effect-gated mitigation and explicit Hope/Fear outcome overrides; `view.ts` shares those dialogs with chat
follow-ups. Target healing/buffs and cross-player reactions execute on the host against current sheets.
Splendor's result actions use the additive `splendorAutomation` host capability. `splendor-view.ts`
shares Second Wind's benefit/target picker, Stunning Sunlight's Hope selection, Salvation Beam's
budgeted healing sliders and Invigoration's searchable character/feature cards across chat and sheet
windows. `/api/splendor/{actorId}` returns sanitized eligible targets and spent feature uses rather than
exposing other players' sheets. The host revalidates choices, resources, roll eligibility and repeat
claims before committing. Zone of Protection shares one advancing card counter across all joined
recipients; selecting its mitigation always commits incoming damage to the host, even without other
host-only options, to advance the counter, update everyone and remove expired buffs. Smite recognizes
both its short and descriptive browser labels, preserves undoubled contribution totals and adds one
separate bonus row after host-generated damage bonuses.
Smite's optional weapon-attack roll option spends 3 Hope and grants the same charge as its card action,
sharing the card's once-per-rest limit. It is unavailable on Spellcast rolls or while already charged.
No settings or DM Link compatibility changes are required.
Weapon features use the additive `weaponAutomation` host capability for host-rolled damage rules
(`damageRules.weaponRules`: Brutal/Recursive extra dice, Serrated/Self-Correcting face replacements),
Reloading checks on new attacks (rerolls carry a `reroll` tag) and success-check trigger steps.
`engine.ts` scopes weapon modifiers and triggers to their own weapon, resolves `weaponProfile:` alternate
statistics (Versatile/Stockpiled) and `damageDie:` attack tags for chat Damage buttons, and blocks attacks
with Pompous requirements or Unloaded weapons. Older hosts disable only the affected rolls and buttons.
Armor features add damage-dialog fields (`gainHope`, `report`, `markStress`, `storeRoll`, `when.avoidsAll`),
the `armorFull` modifier condition, the `noArmorMarked` rest condition and armor `overflowHope`/`equipEffect`.
The host's `ArmorFeatureRules` normalizes saved sheets for `armorFullEffect`, `armorHalfEffect`, `armorFullHeldEffect`,
`equipEffect` and `zeroHopeClearArmor`, and validates `avoidsAll`, `gainHope` and `markStress` on Bone/Valor damage.
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

The Character tab combines all features into one searchable block ordered Hope, class, subclass,
ancestry, community and transformation. Card view shares domain cards' natural-height, shorter-column
flow and narrow-sheet fallback. An independent compact Card/List switch lasts for the open sheet;
list rows show name, source and chat link and expand to the complete feature with actions and grouped
subfeatures. Search covers names, sources and full text (including grouped children), survives refreshes
and remains available on read-only sheets, as does row expansion. No settings or remote contracts change.

Star Character-tab features (including grouped children), domain cards, weapons and loot to bookmark
them for that character. Minimize the floating sheet and select **Quick Access** to open its compact
tray: Weapons, Domain Cards, Character Features, then Items; empty sections are omitted. Weapons are
Primary then Secondary, cards ascend by level, features keep Character-tab order, items are alphabetical. Only equipped
weapons and active-loadout cards appear, but unequipping/vaulting never clears their stars.
Click an entry's name to expand its description independently; gameplay actions, uses and token
controls remain visible, including grouped features and grimoire spells. Management actions such as
unequip, move to vault, transfer and delete stay in the full sheet. The tray opens above or below the
bar according to available space, follows dragging/resizing, and becomes a bottom drawer on narrow
screens. Escape, its close button or an outside click dismisses it. Scroll and description expansion
survive sheet updates; read-only sheets permit inspection, not gameplay or favorite changes.
The star button sits immediately left of the chat-link icon (for carried equipment and loot it is the
leftmost of the subtle star/link/transfer/delete icons; the Loot tools column fits all four). Tray entries
are calm cards: name with a chevron (no chevron when there is no description) and star, muted metadata,
an optional description panel, then left-aligned action rows with compact token inputs and usage chips.
The minimized bar shows a shield button directly right of Evasion that opens the damage mitigation
dialog (disabled on read-only sheets). Above the other controls, HP, Stress, Armor and Hope share one horizontally scrollable row,
showing only available slots with labels beside the blocks and minimal vertical padding. These reuse normal marking, HP healing locks and read-only restrictions,
save through the sheet render flow and refresh with sheet updates; edit mode never changes their capacity.
The lower Evasion/trait row uses compact line heights and minimal vertical padding while keeping trait names above modifiers.
Double-clicking empty title-bar space or the title text toggles
minimize/restore like the Minimize button; double-clicks on title buttons and the Edit mode switch are ignored.
The map-tool rail ends with a separated, neutral outlined star matching the other tools (`App.quickAccessButton`) that toggles the same tray for
the player's first non-disabled owned character via `SheetWindow.toggleQuickAccess`, whether its sheet is
closed, open or minimized (disabled when there is none). That tray is placed right of the rail with
`quickAccessSidePlacement` (bottom-aligned, growing upward; drawer on narrow screens), is rendered by
`view.ts` whenever the tray is open, survives sheet open/close/minimize, and returns focus to the star.
Favorites are additive `quickAccessFavorites` sheet metadata, saved with the character through existing
full/compact saves without changing settings or remote compatibility versions.

The Java host serves the build output from the classpath `/web` (`/` and `/sheet/<actorId>` return `index.html`).
Sheet edit mode is local to each open view and defaults off. Its labeled switch is next to the sheet title
and hidden when minimized. The title and switch have 24 px separation; the **Edit mode** label is muted gray
when off. It gates base traits (-99..99), Proficiency, transformation controls, character
name/image, Experiences and Background fields; normal play controls remain available. Background pronouns update
the header while typing; empty pronouns do not display parentheses. Legacy mismatches prefer nonempty Background
pronouns, falling back to the top-level value; edits synchronize both fields. Edit-mode HP/Stress/Armor pip clicks
set a contiguous capacity, preserving permanent and temporary HP locks. HP/Stress use existing
track fields; Armor uses an existing manual-duration `armorScore` effect with source `manual:armor-slots`.
The same **Manual override** effect records HP/Stress capacity, Proficiency, base trait and base Evasion
adjustments in `manualChanges`; these are already applied to base fields and must not be counted again
as modifiers. Evasion has a base-value input in edit mode and a separate manual contribution in its tooltip.
The effect is hidden from the left-side overlay but remains in the Effects tab; removing it in edit mode
reverses its recorded adjustments while retaining other buffs/advancements and respecting HP locks.
Its Effects card displays `from sheet edit mode` rather than its internal ID and hides only its
redundant Armor modifier badge; other effects' badges and all stat calculations remain unchanged.
Domain deletion removes only the character's
card in either view. Portrait uploads reuse the authenticated artwork endpoint, including DM sheet sessions.
No new settings, remote contracts or compatibility version are required.
Saved testing sheets refresh automation without rebuilding on load, preserving manual edits and card
removals; explicitly applying a new testing build still recalculates the build's stats and cards.

Bone/Valor automation uses contextual result actions instead of advance payments: Redirect requires
a rolled 6, Support Tank rerolls an ally's chosen Duality die, and Ground Pound offers fixed damage.
Cruel Precision is one preselected current-Agility/Finesse selector on both weapon-damage entry points.
Breaking Blow consumes its prepared 2d12 only on a committed selected roll. Deathrun rolls its
initial +1-Proficiency weapon pool once, then removes a chosen die per target, with tracked exhaustion
and End Run controls. The latest damage result offers Continue Run while another target can
receive damage, opening the existing damage dialog without rerolling the original dice.
The die-removal selector sits on the right inside the Deathrun option row,
defaults to the lowest-valued remaining die, and stays hidden until continuing a selected Deathrun
after its first damage roll. Recovery's short-rest ally choice and Armorer's named ally repairs commit with
the rest. Sheet-backed damage/rest requests use the host's character-sheet size allowance separately
from their bounded transaction metadata. Untouchable rounds half current Agility up. Rise Up, Octarine Brace and hidden Unyielding
Armor dice commit with incoming damage. The optional `boneValorAutomation` host capability gates
the new transactions; older clients remain connectable and no LAN compatibility bump is needed.

`src/vectors.test.ts` checks `../src/test/resources/online/vectors.json` (shared with the Java tests) when it exists.

Layout: `bitset.ts` (base64 + zlib bitset codec), `store.ts` (snapshot/delta), `clock.ts`, `vision.ts`, `weather.ts`,
`render.ts` (canvas), `net.ts` (WebSocket), `app.ts` (map UI), `sheet.ts` (mock sheet), `login.ts`.
`lighting.ts` mirrors desktop light-tint accumulation, falloff and indoor/daytime eligibility.
Tint uses the host's optional `lighting.tintDarknessFactor` to match the DM view without reducing player ambient darkness;
older hosts that omit it retain full-strength player tint. Overlapping lights share a single capped tint overlay.
Settings kept in localStorage: `dmmt.volume`, `dmmt.muted`, `dmmt.maxParticles` (default 1500).
The host resolves future message, roll, card and whisper-recipient names to the first enabled actor
owned by that player in campaign order, or the account name if none is enabled. Saved legacy identities
are ignored. The roster displays the actor name alongside the account name. Older clients cannot edit
the derived name; historical messages retain their original names.
Character-sheet name edits rename the campaign actor, and DM actor renames update the saved sheet and its
Background Name field. Legacy mismatches display the actor name until explicitly renamed. A pending rename
stays local to the header or Background Name text field until it loses focus; typing pauses and background saves
do not persist a partial name. A committed rename in the map tab is saved before sending a chat message.
New chat and roll cards capture the same actor's portrait without showing an initial alongside it, falling back to the sender's initial when
artwork is missing or unavailable; older messages keep their existing initial avatars.

The preview and saved token use the normal DM token border thickness: 5.5% of the diameter, fully contained inside the artwork.

My actors uses compact, button-free rows: circular portrait, prominent character name, smaller
class/subclass and a separated level column. Double-click the row (or press Enter/Space while focused)
to open/focus its sheet; row text is not selectable and single-click never moves the camera. Sheet loads and edits refresh the summary,
including level-ups/testing-build edits. Disabled/testing indicators remain visible and disabled sheets
remain readable. Token customization and pop-out controls remain inside the sheet.

Character-sheet portraits open the circular, mandatory-border token editor. Choose or paste
an image (Ctrl+V works without clipboard-read permission), pan by dragging, zoom, choose a border color
(default: the authenticated account's assigned color) and size (default: 1 tile; half-tile increments).
The host retains the source/crop and renders the finished token for sheets, actor lists and linked map tokens.
`GET /api/actors/{id}/artwork` returns `source`, `color`, `sizeTiles`, `zoom`, `centerX`, `centerY`.
JSON `PUT` accepts these crop fields plus an optional image data URL; without an image it reuses the saved source.
The existing binary image PUT remains supported. Both routes enforce ownership and disabled-player restrictions;
actor-scoped DM grants remain supported. Older hosts show an update-required error rather than accepting a raw
upload in place of token customization. These additions do not change LAN remote compatibility.

The Character Mancer loads the Items catalog for its starting Torch, 50 feet of rope, Basic supplies and
standard class-guide keepsakes. These are normal compendium-backed inventory entries with catalog
descriptions and automation; the selected potion and starting gold are unchanged. Custom class-item text
remains plain inventory. Missing or duplicate basic-supply entries show a creation error asking the DM to
restore the catalog, rather than silently granting incomplete gear. Existing characters are not re-granted supplies.

An uncreated actor offers **Create Character** or **Import from Demiplane**. Import opens a themed
link-entry dialog, with inline loading/failure messages and a separate result popup after the sheet is
successfully saved. The concise popup shows only unmatched feature/domain-card names, the item quantity
warning and an unverified active loadout. Unmatched loot and technical diagnostics are not listed.
Use a public `https://app.demiplane.com/nexus/daggerheart/character-sheet/<uuid>` link that opens without
signing in. The DM host needs internet access; neither Demiplane credentials nor cookies are used.

The importer reads saved creation traits, class/subclass, ancestry/community, Experiences, equipment and
per-level advancement records (including trait/Experience increases, HP/Stress, Evasion, Proficiency,
subclass upgrades, extra domain cards and supported multiclass selections). Demiplane's suggested-trait
toggle uses verified starting allocations for the standard SRD classes and Brawler; manual allocations
are still validated when that toggle is off. Martial Artist starting forms are matched to local stances.
Local catalogs supply
feature/card/item mechanics and automation; ambiguous names are never guessed. Custom or unmatched
equipment stays plain inventory without invented mechanics. Item quantities default to one, and where
the active loadout cannot be verified the first five acquired matched cards are active, with the rest in
the vault; both are reported for review. Portraits, active effects, usage counters, scars, manual overrides
and unsupported background fields are not transferred. Missing required creation data, including
unreadable companion/stance/element or starting Experience-bonus choices, blocks import rather than
inventing a character. Unknown progression records remain in internal reconstruction diagnostics; historical card
swaps/removals are not inferred from final cards.

Demiplane's `50ft of Rope` matches the `50 feet of rope` item when no exact catalog name exists.
Both official Brawler keepsakes (hand wraps from a mentor and a book about your secret hobby) are
included in Items. Absent saved gold fields preserve the normal starting handful (10 coins); explicit
saved amounts, including zero, replace that default.

`POST /api/sheet/{id}/demiplane` accepts `{ "url": "https://app.demiplane.com/..." }` for an owned,
enabled, uncreated actor, including actor-scoped DM sheet grants. It returns only the public character's
build selections, not the surrounding account data, and does not mutate the actor. Fetches use a fixed
official HTTPS host, no redirects, time/size limits and a bounded concurrent download count. Reconstruction
uses the local catalogs and then the existing version-checked sheet save. Import conflicts retain the
newer sheet; other failed saves restore the empty local sheet and never announce success. Older hosts
return an update-required message. This additive route changes no settings, saved sheet contract or
LAN compatibility version.

Daggerheart testing actors are created only by the desktop DM (Actors tree context menu) and may be used freely
by their assigned player. `GET /api/sheet/{id}` supplies the authoritative `testingCharacter` marker separately
from saved sheet data; players cannot promote ordinary actors through PUT. Marked sheets show a testing badge
and **Edit testing build** instead of normal level-up. `daggerheart/testing.ts` rebuilds class/subclass stages,
with unrestricted integer number spinners for all six base traits in the editor. Trait edits apply only on
Apply, accept negative values, and survive build changes and reloads without creation-array limits.
The builder also supports
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
and retained across roster refreshes and reconnects. Players are shown as `username (character [pronouns])`
when pronouns are available, without a self marker; the roster grows to fit up to the viewport width.
The optional roster pronouns field is backward-compatible with older hosts and clients; compatibility versions remain unchanged.
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

Sage and Arcana use host-owned feature transactions for variable Hope, Stress and token payments.
The themed amount dialog validates whole numbers against the current pool; cancellation spends nothing.
Unleash Chaos saves its cast's token count for later damage and refills on the host's New Session.
Flight activates only on a successful Spellcast. Wild Surge advances its shared card counter on selected
action rolls, ending with additional Stress and a chat explanation above 6. Force of Nature drops
when its preselected Hope-maintenance option is omitted. Conjured Steeds invitations cannot exceed
the mounts purchased, exclude the already-buffed caster, and expire with that casting.
Rune Ward reuses transferable talismans: its holder can spend Hope for a d8 mitigation roll, reported
separately in chat; an 8 destroys the item and benefit. Forest Sprites gives every character attack
and additional-Armor options backed by the caster's single shared pool, removing all benefits at zero.
Forager's host-rolled downtime adds one of five automated inventory consumables, or a one-choice
rest-chat reward on a 6; the party may carry five. Rest cards render these reward actions even
without recovery dice, including long rests. Its Luck Charm rerolls exactly one selected die,
including Duality, Disadvantage and Combo dice. Conjure Swarm renders two independent spells.
Success-dependent spells keep their payments, buffs and damage on their own chat results.
These are additive catalog and message fields on existing endpoints; compatibility versions and
settings are unchanged. Rebuild with `npm run build` in `web`, then `mvn process-resources` at the
project root; restart the Java host and reload browser pages to serve the new classpath bundle.
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

Blade/Midnight automation uses result-card actions for Not Good Enough, Whirlwind,
Glancing Blow (half current Proficiency rounded up) and successful-Spellcast Eclipse.
Versatile Fighter selects an attack trait (highest current trait by default) and can maximize
one selected damage die for Stress. Rage Up's attack choice follows only that attack's
linked damage, with one 1-2 use spinner, and Reaper's Strike is selected with an attack.
Glancing Blow opens the normal damage window at half Proficiency rounded up, including damage options.
Pick and Pull and Uncanny Disguise's avoid-scrutiny advantage exclude weapon attacks.
Uncanny Disguise has one Stress-paid activation replacing its tokens; its buff preselects
spending a token on action rolls, never reactions, and ends when tokens run out.
Deadly/Uncanny Focus end with the scene. Phantom Retreat toggles for Hope and ends at rest;
Vanishing Dodge applies linked Hidden for manual removal.
Dark Whispers pays Stress with its Spellcast roll. Mass Disguise creates a campaign Countdown (8).
Spellcharge automatically awards capped tokens for actual magic-damage HP, with the gain in
the damage report. Night Terror chooses Fear from the live pool and reports its d6 damage.
Vitality provides a one-time permanent two-benefit picker, also on testing sheets. Its buff is hidden
from the left sidebar and can only be removed in sheet edit mode, reversing its benefits.

Chat's recipient dropdown targets Everyone,
Gamemaster or another connected player; private messages and rolls are purple with a Whisper recipient label.
Messages use separate sender/timestamp cards. Roll cards have styled dice, a summed modifier, a large total and
an initially collapsed, keyboard-accessible **Roll breakdown**. Sheet rolls list named trait/passive/Experience,
advantage/disadvantage, weapon and effect contributions with individual dice and signed impacts.
Criticals stand out with a warm glowing card border and matching glowing Hope/Fear dice.
The host validates optional `contributions: [{label, expression}]` against the rolled formula and returns/persists
`breakdown: [{label, expression, rolls, total}]`, using its own dice rather than client-provided totals.
Contributions can include bounded `notes: [{label, impact}]` to explain dice-pool adjustments such as
Proficiency buffs; these are context, not additional terms in the total. Weapon damage dice and fixed bonuses
are separate breakdown rows.
Each damage-adding feature has one damage entry; Combo Strike replaces its selection placeholder and
First Strike has its own doubling contribution. Additive extra-die effects have separate damage rows,
while Proficiency and keep/discard pool explanations remain on the weapon.
Contributions and returned details may also include a bounded description-only
`rollSource: {name, text, source}`. Hover or focus the row to open its themed source preview; Escape dismisses it.
The desktop DM chat uses the same saved descriptions. Older messages without row descriptions remain usable.
These fields are additive; compatibility versions are unchanged. Older hosts/history fall back to formula
and dice details. Open breakdown and source sections survive new messages and updates to their own message,
independently for each viewer. The reader's scroll position survives new messages.
Action buttons under a roll are grouped into subtly separated sections: **roll results** (primary blue: Hope rewards,
weapon damage and damage caused by the rolling feature, such as a successful spell's damage), **feature effects**
(the standard secondary buttons for other effects of the feature that made the roll, such as marking Stress or clearing
HP) and **other features** (indigo buttons from other features that can modify or react to the roll, such as
rerolls or added dice). Content can set `rollSection` on a roll trigger when the derived section is not correct.
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
On the sheet, loadout and vault domain cards are displayed in ascending level order without changing their saved order.
Cards retain their natural heights in two height-balanced columns. In ascending level order, each card goes
into the shorter column (left on ties), so levels flow from top to bottom across both columns, not by fixed alternation.
Columns may contain different numbers of cards; narrow panels use one column. Searching and resizing rebalance
the visible cards without stretching cards to match their neighbors or splitting a card between columns.
Loadout domain-card resource counters use the label "Tokens" and sit in the card header between the domain and recall cost.
They retain their existing editable values, limits and autosave behavior. Domain-card action groups have no enclosing border or panel padding; individual buttons retain their normal styling.
The icon-only Card/List switch follows the active count at the far right of the Loadout header.
Its compact 28 px buttons use 18 px icons and leave extra spacing after the active count without increasing the header height.
Card view is the default; List view applies to Loadout and Vault with aligned level, name, domain,
Recall cost, action-menu and chat-link columns. Click or keyboard-activate a row to expand/collapse
the full card, including grimoire spells, tokens and actions; menu/chat controls do not toggle it.
Preferences are saved on the authenticated campaign account, with a separate campaign DM preference,
and reused across sheets and devices. Open same-origin sheets/pop-outs refresh through BroadcastChannel
and window focus. `GET /api/preferences` returns `{userId,domainCardView:"card"|"list"}`;
`PUT /api/preferences {domainCardView}` validates and persists only the authenticated user's preference.
Save failures retain the previous view and show an inline error. Older hosts show an update-required message;
older clients ignore the additive account fields. Online Play and DM Link versions remain unchanged.
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
Loot uses Item (including an inline type badge), Qty, Usage & actions and Tools columns. Usage counters stack above
full-width, wrapping action buttons; link-in-chat, transfer and delete icons form a separate top-aligned row
in the far-right Tools column. Equipment keeps its compact trailing action groups. This is presentation-only;
settings, sharing/transfer contracts and compatibility versions are unchanged. Search filters name/description in place and retains
queries across rerenders. Loot descriptions start fully hidden; clicking a row's non-interactive area or its
keyboard-accessible item-name disclosure button toggles the full text. Other controls never toggle expansion.
Expanded state survives sheet refreshes, and read-only viewers can also expand descriptions.
Loot rows use a subtle blue-tinted highlight on hover and focus within, including collapsed rows.
`cardStock {actorId}` invalidates availability; owner-only `sheetInventory {actorId,inventory}` updates open sheets.
Transfers increment the persisted `inventoryRevision`; stale PUTs receive 409 and refresh inventory without
overwriting pending non-inventory edits. Legacy saves still work before transfers; after a transfer they must
reload and preserve this additive field. Both sheets are saved through a replayable write-ahead record so an
interrupted write cannot leave only half a transfer. Online protocol and DM Link compatibility are unchanged.
Use `/roll 2d6+3` or `/r 2d6+3` in chat (no separate dice composer). Optional `toId` targets a player or `dm`;
legacy `whisper:true` remains DM-only. Recipient names are included as additive `to` fields by the host.
Player recipients require `hello.playerWhispers:true`; older hosts offer only Everyone/Gamemaster, with the
legacy whisper flag retained for DM chat and rolls so private rolls cannot accidentally become public.
With `hello.chatActionReports:true`, browsers coalesce changes to their rendered chat controls and send
`chatActions {id,ts,actions:[{label,disabled,detail}]}` over the authenticated socket. The host attributes
reports to the connected player and forwards them only to native DM chat for DM-visible messages; it never
broadcasts or persists them or uses them to authorize actions. Reports are bounded to 128 labels (200
characters each for label/detail) and the latest 300 reported messages per connection. Disconnect removes
that connection's reports; reconnect republishes controls. DM chat shows a single-line preview and an
accessible expandable, player-grouped list of passive labels. Unsupported clients/hosts keep working
without summaries; no settings or Online/DM Link compatibility-version changes.
Drawing commands carry map/level identity; the host assigns and enforces campaign/player ownership and publishes
the normal map overlays, including generated effect textures. Texture choices/defaults come from the host rather
than a separate browser list. Measurements are shared session-only annotations, replayed to late joiners and cleared
on map/level changes. Both features are advertised in `hello`, so older hosts keep their tools disabled.
Audio preferences and logout live in Settings; the roster reports host-measured round-trip ping.
Campaign account colors are host-persisted: eight distinct player colors are assigned automatically to new
and legacy accounts, while the DM defaults to red. Colors survive reconnects/renames/restarts; editing the
account color is reserved for a future feature. Drawing and measurement pickers default to `hello.accountColor`,
without resetting local overrides on same-account reconnects. The roster adds optional player `color` and
`dmColor` fields, displayed as dots to the left of the names. `hello.measurementColors:true` enables the
measurement picker; commands and broadcasts carry optional six-digit hex `color`, retained on moves.
Older hosts retain legacy defaults with the measurement picker disabled; older clients still work and the
host supplies their account color for new measurements. Online Play and DM Link versions remain unchanged.
**Ping (P)** in the left toolbar activates a one-shot target cursor without opening a flyout. Left-click
to send one ping and return to Select. In Select, holding left-click still for 600 ms also sends one ping;
moving more than 4 screen pixels cancels the hold and keeps normal dragging/box selection. Release, focus
loss, cancellation, map/tool changes and pause cancel pending holds. Everyone, including the DM, sees
player and DM pings using the host's existing animation. The additive `mapPings` hello capability disables
player pings on older hosts; LAN compatibility is unchanged. P does not intercept typing or open dialogs.

Right-click without dragging switches to Select without dismissing flyouts; right-drag pans without changing
the active tool. Empty left-drag box-selects
owned drawings, controlled tokens and measurements for group movement. Left-clicking a controlled token
also selects it. Both selection methods show dashed square token outlines; selected tokens expose a
rotation handle when the host advertises `hello.tokenRotation:true`. Dragging it previews rotation locally
and sends `command {cmdId,kind:"rotateToken",tokenId,mapId,levelId,rotationDeg}` on release. The host checks
current map/level, pause, visibility, ownership and finite angles, then applies an undoable persisted edit.
Double-clicking a controlled actor token opens its character sheet only for the actor’s assigned player;
other tokens and actors remain unaffected.
Rejected or cancelled rotation never replaces the authoritative token state. Older hosts retain selection
and movement without rotation handles; Online Play and DM Link compatibility versions are unchanged.
Arrow keys with the map focused move all selected controlled tokens by exactly half a grid tile per
press/repeat, through the existing move command and rollback path. They do nothing during pointer
gestures, while paused or in text controls. Door/window badges are always light gray; only their glyph
changes on opening/closing, and the browser draws no portal segments. DM rendering is unchanged.
Local moves stay at their latest position after release until acknowledged authoritative token state
catches up. An acknowledgement alone may precede publication, so stale state cannot rewind token vision.
Attached lights follow the reconciled token position, not lagging light coordinates. Superseded move
rejections cannot undo newer moves; current rejections, host corrections, removal, loss of control and
resync snapshots remain authoritative. Tentative vision is not committed to explored memory.
This uses existing messages and sequence numbers, with no settings or compatibility-version changes.
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
All 236 domain cards and every grimoire spell have an individual audit disposition and a complete
open-decision list in `../plan.md`. Nested grimoire target actions resolve their full book/spell key.
Audited host reactions and source-exclusive effects require the optional `domainCardAutomationV3`
hello capability; the original capability remains for older clients. Domain action/option source tags
link results to their chosen cast or bonus without changing class roll tags.
Non-fixed reaction damage and Hope/Stress quantity rolls validate pending costs and source availability,
and spend nothing on cancellation or disconnected sends. `domain-card-corrections.test.ts`,
`domain-card-view.test.ts`, `rollbuttons.test.ts` and `damage.test.ts` cover these paths.
Browser action confirmations and notices use the themed, keyboard-accessible dialogs in `src/dialogs.ts`,
never native `alert`, `confirm` or `prompt`. Prayer Dice rolls and Ethereal Visage rewards have no confirmation.
Blood spell automation uses damage-result Brand of Castigation and success-result payments for Vitality
Manipulation, Parasite of the Will, Glyph of Hemorrhaging and Sanguine Feast. Vitality reports 1 Stress
or 2 with Hope/critical; Feast's three exclusive payments report double target HP and publish a caster-only
one-time refund for the original payment if the target dies. Mutual Suffering defers its rest use until
the successful Reaction result is accepted. Lifeblood Talisman uses the unique transferable enchanted-item
inventory and bearer buff, expiring on recast or zero caster marked HP, not rest alone. Vital Ward's ally
entry claims are linked to that activation and end with the caster's circle. Bloodbath's single cast control
has host-paid Hope/use and puts paid ally recovery claims directly on the roll. These new host-dependent
semantics require the additive `bloodAutomation` capability; older hosts remain connectable.

Witch automation rolls Commune's Spellcast-trait d6s and includes the sensory chart. Walk Between Worlds
Witch token counters and the Lunar die appear only on their feature cards, never as header class resources. Walk Between Worlds
and Night's Glamour roll against 13; spirit entry has a success-only chat action, while Glamour is
automatically applied on success. Spirit/Circle tokens have manual
spend buttons; Circle grants party thresholds/Evasion and a preselected attack bonus, while Moonbeam
grants a preselected scene-duration Spellcast bonus. Range remains player-confirmed; recipients may
remove out-of-range buffs without automatic reapplication. Talisman creation asks for Hope and creates
transferable loot; the holder's incoming-damage mitigation atomically spends the creating witch's tokens.
Depletion or the creator's rest removes the item wherever it was transferred. Glamour offers optional
appearance-based advantage and Stress-cost maintenance when marking HP or dealing damage; otherwise it
drops. Lunar Phases tracks its session d6 and once-per-rest Hope-cost increase (6 wraps to 1), automatically
replacing its mitigation/damage/threshold/Evasion buff. Updated linked workflows and success actions
require `hello.witchAutomation`; older hosts show an update message. See `docs/DAGGERHEART_DATA.md`.

Warrior automation keeps Slayer Dice in the header resource, not feature buttons/counters. Hope action
rolls offer an exclusive Slayer Die reward; attack/damage roll options select a count and roll/spend that
many d6s. New session converts unspent pool dice into Hope. Weapon Specialist's Hope-cost secondary
weapon die has no use limit; a separate once-per-long-rest chat button rerolls only Slayer Dice showing 1,
preserving Duality dice, other damage dice, bonuses and the original audience/history.
These chat actions require `hello.warriorAutomation`; older hosts show an actionable update message.
Combat Training ignores burden in creation/equipment while preserving primary/secondary slots.
Courage requires a failed Fear action roll (player-confirmed when Difficulty is unknown), never a reaction.
Rise to the Challenge automatically ticks the d20 Hope option while at most two HP are unmarked.
Martial Preparation's downtime move is granted to every campaign sheet, including offline actors, with no
extra move slot. It adds a die to an existing Slayer pool, otherwise a single removable d6 buff with shared
attack/damage consumption. Its feature has no die counter. `daggerheart/warrior.test.ts` covers these rules.
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
Warlock Favor is shown only in class resources; the exclusive **Gain Favor instead of Hope** reward is
available on successful Hope action rolls, even at full Hope. Deathless Embrace and Otherworldly Ire ask
for Favor/Patron Dice; Deadly Vengeance asks for HP marked and also works from mitigation using final HP.
Patron Dice scale from d6 to d8 at level 5. The host resolves feature dice, costs, rest limits and Embrace
healing, with descriptive chat reports. Menacing Reach grants an explicit primary-weapon reach buff and
an attack-chat action to end it after success. Diminish My Foes has feature and Hope-action Favor-input
dialogs. Fearsome Attack retains its feature button and adds repeatable individual damage-die rerolls,
charging one Favor each time. Patron's Boon's standard blue feature button does not alter its chat reroll.
New host actions and rewards require `hello.warlockAutomation:true`; older hosts show an update hint.
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

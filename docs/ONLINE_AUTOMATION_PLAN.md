# Making Online Play automation configurable

**Assessment: yes for the shipped Daggerheart mechanics, but not by moving strings out of TypeScript.** The catalog must describe what makes a control available, what happens when it is used, what state survives it, and what people see. The Java host must understand the same rules: it already owns important parts of Deathrun, rewards, claims and cross-character effects. A browser-only refactor would let a copy look right while the host still executes the original rule or rejects it.

**Agreed direction: a full rewrite of the automation subsystem, not two systems maintained side by side.** Give authors short, editable JSON rules and reusable JSON recipes, backed by one generic browser renderer and one authoritative host interpreter. Replace all feature-specific automation paths and convert all shipped content before release. No legacy engine, runtime adapter, mixed-format execution or fallback. Existing generic infrastructure such as the dice roller, persistence journal and accessible controls can be reused; the rest of the application need not be rewritten.

Prove that a differently named and mechanically different Deathrun works alongside the original **with both expressed in the new format and executed by the new engine**. Build in stages, but release as one coordinated replacement. Existing saved games need a backed-up, one-time data conversion, not permanent support for executing their old rules. This is the approved target design; the live application does not yet accept the replacement format.

Start with the [Deathrun and homebrew example](#example-2-deathrun-and-a-mechanically-different-copy) for the author experience, the [migration map](#coverage-and-naming) for scope, or the [release gates](#delivery-plan-and-release-gates) for implementation order.

## Implementation status

**Development API compatibility.** The unfinished format-2 endpoint uses
`/api/automation/{version}/{actorId}`, with compatibility version 1 shared by the browser and host. An
authenticated request with an absent or different version receives a 409 with both versions; it cannot read
actions or mutate a sheet. The browser does not fall back to the unversioned endpoint, and an older host that
lacks the versioned route is treated as unavailable for optional action descriptions. This adds no request to a
profile roll and leaves existing format-1 gameplay and DM Link unchanged. Ordinary checks fetch their own
pre-roll offer. This does not negotiate all sheet and gameplay traffic; that remains a cutover prerequisite.

**Ordinary checks (format 2).** Trait tiles and prompted reactions fetch an owner-only
`GET /api/automation/{version}/{actorId}/check?kind=action|reaction&trait=...` offer, then submit
`request.checkRoll` with the selected host-described options. The host derives the profile and roll facts,
enforces prepare blocks, runs automatic finished reactions, journals the result and publishes it to chat.
Pending sheet edits are flushed first; trait rolls can switch between action and reaction offers, while prompted
reactions stay fixed. Prompted checks reference a public, matching DM history entry, from which the host derives
the committed label; exact retries remain valid after deletion. A shared ruleset option spends 1 Hope for a
selected Experience on duality rolls. The host checks the selected name and modifier against the current sheet,
including after ordinary sheet edits. Selected advantage/disadvantage suggestions cancel or coalesce; a manual
mode can override them, and disadvantage can be ignored. Converted Experience alternatives can replace Hope
with Stress and change the modifier while remaining exclusive with the ordinary option; refunds and other non-profile
roll origins remain incomplete. Format-1 hosts keep the legacy dialog.

**Record inputs.** `record.select` takes a projected scalar-record collection in `from` and a `choiceLabel`
template using `input.option`. The host offers those records, then matches the submitted record against the
current collection before any cost; duplicate records are one choice and ordering does not affect validity.
Automatic `roll.prepared` rules run after selected prepare options and before `roll.finished`; `roll.contribute`
can insert a non-scale contribution `beforeScales`. Explicit scale dependencies on discarded mode dice are
rejected rather than silently rebound.

Legacy Experience cost replacements become exclusive `record.select` options with the configured Hope or
Stress cost and modifier multiplier. They share the `core-experience` group with the standard ruleset option.

**Profile rolls (format 2).** The automation endpoint accepts an owner-only `request.profileRoll` with
`sourceInstanceId`, `profileId` and `kind` (`attack`, `damage`, `action`, `reaction` or `spellcast`);
`selections` names any chosen `roll.prepare`
options. The host determines contributions, traits, thresholds and Fear from its sheet, catalog and campaign.
It runs prepare edits and automatic finished reactions in the same request. A blocked prepare discards the
transaction. Roll history stores the final structured event; retrying returns the journaled response without
publishing or spending again. Profile attacks include action and attack modifiers, and profile damage includes
damage modifiers; eligible passive and active-effect modifiers become labelled, source-identified contributions.
Native source profiles can instead specify `rollKind: "action"`, `"reaction"` or `"spellcast"` with an
optional `trait` (a named trait or `"spellcast"`), numeric `difficulty` and explicit `origin`. Their only
accepted roll kind is the declared one; attack profiles remain attack/damage only. The Spellcast trait resolves
to the current derived total. Action and spellcast profiles apply action modifiers; spellcast profiles also
apply spellcast modifiers, and reaction profiles apply reaction modifiers. Their known facts are supplied to
pre-roll choices without sampling; difficulty and outcome are recomputed from the edited roll. A source profile can
declare `limitRef` and `consumeOn: "attempt" | "success"`. The host checks the same overridden use budget as
ordinary actions before sampling, refunds failures for success-only casts, and reconciles automatic finished-roll
edits; an indeterminate outcome rejects the transaction. Exhausted offers are disabled. The converter emits
these profiles for top-level cards and nested grimoire spells with written-difficulty casts, preserving automatic
use policies. Nested spell origins use `card:<spell name>` to match existing triggers. The projection derives
nested source identities from the active parent instance, child path and catalog ID; newly copied cards receive
independent parent identities. Existing sheets still require identity migration, and other feature-action roll
origins still need conversion.
Legacy `action` selectors include attack, reaction and spellcast profiles, and Duality selectors use the
known pre-roll fact rather than waiting for the dice.
An equipped weapon's passive roll modifiers apply only to its own profile, while effect modifiers apply to the
holder. `set`, `add` and bounds are combined per statistic using the derived-stat ordering. Trait bonuses
are already included in the projected trait total. When a passive value, condition or label reads a derived
trait or total Spellcast, the host first projects trait-targeting passive and active-effect modifiers, then
evaluates passives against those totals. Trait-targeting passive values and conditions cannot depend on derived
trait totals. Other derived-stat reads and derived-effect activation still use the unmodified baseline; this
is not general dependency ordering. The preliminary projection evaluates only trait/Spellcast statistics and
their ordered prerequisites; if no active source reads derived traits, it is skipped altogether. This covers
declared source profiles, not every feature action that rolls dice,
damage application or interactive post-roll claims. Equipped weapons that already
have stable source and instance IDs also receive a base profile even without an automation program. The
host reads their saved customized trait, range, damage type, dice and flat bonus. Multiple dice pools each
scale with Proficiency unless `fixedDice` is set. Unsupported damage expressions reject the damage roll;
identity migration for existing sheets remains outstanding. The generated profile ID avoids declared IDs.

A stored effect whose source is active on its holder also offers its frozen attack profile. Its descriptor
and `request.profileRoll` include `effectInstanceId` and the reserved profile ID `effect.profile`; the host
resolves and validates the stored instance, not submitted damage facts. The activation must still exist when
using an offer or a result button, including after ending and recasting the effect. If a roll itself ends the
effect, it completes without creating a result offer for that activation. A derived effect on its own active
source also offers its current profile while its condition holds, without persisting the effect. Host sheet
transactions prune result controls when the condition stops holding, so they cannot reappear when it becomes
true again. If the current pack is unavailable during a sheet save, the host logs the problem and discards
the ephemeral controls rather than allowing an old activation to revive. Profiles on another character's
effect remain pending cross-holder integration.

Weapon damage records its base contribution IDs in roll metadata. Converted weapon-dice edits use those IDs
and the source ID so a bonus contribution from the same source is not mistaken for a base pool.
`roll.addDicePerPool` adds dice to each selected contribution and `roll.dropLowestPerPool` drops the lowest
selected dice in each contribution. They retain the existing single-pool operations and validate the
whole edit and dice budget before sampling.

A player chooses `roll.prepare` options before seeing the new dice. Their availability, inputs, prices and labels may use saved dice and known roll facts (`event.check.duality` identifies a planned Hope/Fear roll), but not the new faces or result. The compiler directs such choices to a `roll.finished` action instead. The selected option's `do` steps may still edit the sampled dice.

The owner's sheet description embeds matching option descriptors under `profiles[].options` for each available kind when the loadout has roll options. The host constructs these from the same profile facts used for execution, without rolling, spending, or making a second request at roll time.

A scoped loopback DM sheet grant uses the `dm` audience for automation rather than impersonating the owner.
`any` bindings include the DM; `self`, `others` and `party` are player audiences. Receipts distinguish the
campaign DM from a player. The grant still authorizes only its original sheet.

Started `whileEffect` pools are holder-local: ending, replacing or expiring an effect clears the numeric
actor pool only when no recognized supporting instance on that holder remains, including instances from
another program or an inactive sheet source. Self-targets use one holder; an unrelated recipient pool is
not touched. If an unresolved automation effect could change the answer, or an affected pool is malformed,
the staged transaction fails without persisting any participant. Unrelated manual effects have no automation
identity and do not support pools. Host dispatch uses the full compiled pack; isolated runtime and dispatcher
callers can pass that registry, and incomplete registries fail safely when support cannot be established.
Conditional ending currently checks only the executing source's actor effects; other sources and recipients,
derived-condition recomputation and cross-holder source-pool lifetimes remain pending.
New started and derived effects record `sourceActorId` alongside `sourceInstanceId` when the trusted actor ID is
available. Local counts, source-scoped replacement and endings, effect profiles and result guards distinguish
another character's identically named instance. Existing effects without that field keep their local legacy
behavior pending migration; their source must not be guessed for a cross-holder removal. A `singleHolder`
start requires source and holder IDs. The host applies its `effect.endElsewhere` request to all saved characters,
including offline and disabled holders, using the last start for each source actor, source instance and effect
within the transaction. It reconciles pools and result windows, compares every read sheet, and commits all
changes, reports and host commands together. A revised result's receipt and retry journal retain only buttons
that survive the transfer; a completely revoked offer is also removed from its chat event. Ambiguous pre-migration
identities and unsafe pool state reject the transaction without spending costs. Roster scanning is skipped when
no single-holder start occurs. For the standard engine-owned effects field, unrelated sheets are inspected
without building complete actor projections; other projection mappings retain the full-projection path.
`table.gain` and `table.spend` settle Fear on the private host event as each operation executes. Gains are capped
at 12; an unaffordable spend rejects the entire transaction. Later operations, rule bodies, target recipients
and roll phases see the settled value. Automatic eligibility, selected inputs and combined costs still use the
event's initial snapshot, before any rule bodies run. The host checks each ordered before/after transition against
the campaign before committing it with the sheets, history and retry receipt; rules cannot create other table
resources. No additional request is needed for a roll.
Lifecycle resets carry the settled Fear through saved actor order and scene/session phase order. Limit and pool
resets receive the resulting host event, retaining actor and session identity; a failure leaves the full reset
unchanged. The staged rest coordinator also carries Fear through preparation, each move and deferred benefit,
the pre-reset completion phase, reset expressions and the post-reset phase. It returns the ordered commands for
the future live host commit and leaves the original event untouched.
Numeric pool reset expressions evaluate against initialized declared state and pools from one pre-reset snapshot
per source, independently of declaration order; unchanged default pools are not persisted by the reset.

Ordinary self-actions dispatch owner `track.changed` reactions within the same private transaction, before later
steps; source costs, reports and retries remain atomic. Simple consumed items without projection dependencies
can heal and trigger reactions. A track change following an unsettled inventory, effect-transfer, limit-refresh
or damage command is rejected until ordered settlement is available. Direct sheet edits and other roll origins
still need track-event integration.

Started effects have persistent, non-reused instance IDs, separate from their source and effect definition. Ending and recasting the same effect must not make an earlier result refer to the new activation. Existing saved instance IDs need no rewrite.

An owner profile roll with matching result buttons returns `resultActions` and an opaque `event.resultId`. The owner can reopen the buttons with `GET /api/automation/{version}/{actorId}?resultId=…`; the host uses the saved dice, current resources and current revision. A later match supersedes that button only. The owner can use targeted result actions and `roll.finished` claim groups whose scope includes the source actor; other players' claims are not offered here. Claim groups default to source scope; `claimGroupScope: "result"` on a `roll.finished` roll/chat button shares that group across sources for that result, separately from any source-scoped group with the same name. The chosen group stays spent across revisions, including alternatives newly eligible after an edit. The base Hope/critical reward and converted alternative Hope and critical-melee rewards use result-wide `roll-reward`. Results do not carry over to a new session or a changed catalog.

`POST /api/automation/{version}/{actorId}` accepts `request.resultId` and one offered selection. It stages only the selected rule, not automatic reactions to the already-finished roll. A successful ordinary use consumes that button unless the binding is explicitly `repeatable: true`; each distinct repeat pays and rechecks its costs. An `exclusiveGroup` on a result binding consumes the other ordinary alternatives in that group from the same source instance, even if a member is repeatable. A self-claim consumes its source instance's claim group for this result. Both kinds of group also cover alternatives that become eligible only after a revision; identically named source-scoped groups on other instances remain independent. A `roll.publish` step adds a revised result to history rather than changing the original; spent buttons and groups remain spent on the revision. A failed cost or campaign command leaves the offer available. The original request ID replays a successful receipt without another charge or publication.

The sheet renders ordinary result actions from the private roll receipt. If a result has a target action, it fetches fresh recipient offers after the roll is saved; the roll itself still needs only one POST. A chat card loads actions only when its owner opens **Result actions**; restoring history does not issue a request per card. The host resolves current single-target or whole-party choices, stages all sheets and consumes the button atomically, without repeating finished-roll reactions. A targeted result can publish one roll revision; multiple publications are rejected without spending anything. Owner-only descriptors are not mirrored into chat action reports. An uncertain POST can be retried with the original intent; a rejected stale action refreshes without submitting again.

Shared Healing Hands fixtures run through the converted card and HTTP host as well as the legacy path. They
cover successful HP and clamped Stress recovery, failed HP and Stress recovery, and a critical below the written
difficulty. Both observe the seeded casts, choice eligibility, actual recovery, reports and recipient locks.
The converted path also covers engine-owned effect protection, version conflicts, duplicate and exclusive
choices and transaction replay. Recipient long-rest expiry is exercised in isolation; complete cross-holder
effect lifecycles remain a release gap.

The staged rest-reset primitive snapshots the original stored effect IDs and expires only those IDs, preserving
effects started or replaced during the same downtime and reconciling surviving effect-bound pools. It is used by
the live self-rest workflow; legacy effect locks still need conversion.

The versioned `/api/automation/{version}/{actorId}/rest` endpoint offers and commits owner-only self rests.
The host derives descriptors and facts, signs the offer, stages the complete rest, and atomically saves the sheet,
supported commands, reports and retry receipt. The browser flushes pending edits and renders preparation, move
and completion controls. Authored targeted ally moves and party recovery commit recipient sheets, reactions and
reports with the rest. A two-phase signed choice flow presents completion benefits after the actual move outcomes
and again after pre-reset benefits and the reset; the private staged rest is committed only after both phases.
Pending choices live in the running host and expire on restart or cache eviction; committed retries use the saved journal.
`POST .../rest/choices` first accepts `{request}` with no completion selections, then
`{requestId, choiceVersion, selections}` for before-reset choices. `POST .../rest/complete` accepts
`{choiceVersion, request}` with both phases' selections in order. Each response supplies the next signed
choice version and host-derived descriptors; exact retries reuse the staged outcomes. If automatic preparation
needs a roll or cross-holder effect or inventory settlement, `{request, prepare: true}` stages it before move selection and `{request, preparedVersion}`
continues with the chosen moves without rerolling. Older direct requests cannot skip this preparation.
Selected `rest.prepare` options may declare `restRollPolicy: "keepHigher"`; the host applies it before automatic
preparation and through completion, including nested track reactions. Each rest-context dice expression is sampled
twice and keeps the higher result; records retain the other sample. Unselected options leave rolling unchanged.
Legacy reroll passives combined with a move, no-recovery condition or deferred-only benefits/costs are diagnosed
rather than reordered.
Converted companions use their current sheet Stress track as the source of their unmarked-slot pool. They return
before long-rest moves and clear the owner's net Stress recovered by each completed move; unavailable companions
do not recover during short rests. `CompanionRestRecoveryTest` and the live HTTP fixture cover this ordering.
Single-action, claim and rest `inventory.grant` commands settle catalog-linked item/consumable stacks and actor
or party caps atomically. Actor grants settle immediately during preparation, downtime moves and both completion
phases; recipient grants settle during move adjustments, actions and deferred benefits. Later reads and track
reactions see new items and active sources, and party limits include the other staged holder. Unsupported dynamic
projections still reject.
`limit.restore` creates a saved recipient-specific claim instead of changing a use immediately; its owner selects
one current spent source limit through the generic chat controls. The claim ledger, sheet, report and exact-retry
receipt commit atomically. Offers to characters without a player owner are rejected; more than 64 spent limits
must be reduced before choosing.
Self-targeted single-holder effects transfer after preparation and between moves. Ally move adjustments,
selected moves and deferred benefits settle recipient track changes and effect starts and ends immediately,
including label-based endings of other sources' effects and transfers from offline or disabled holders, so later
operations and reactions see updated effects and effect-bound pools.
Adjustments and deferred benefits can also change recipient actor pools, writable collections and boolean facts;
later reactions use the updated recipient and active sources.
The host provides target-specific move and adjustment descriptors when availability, labels or inputs depend on
an ally; the browser switches them with the recipient and the transaction rechecks the choices. Before selection,
recipient-dependent text uses `...`, and a move is enabled when at least one offered recipient can use it.
Short-rest recovery dice can publish generic chat offers; an owned source may edit one die through
`rest.roll`. The host commits its use, the holder's bounded recovery and reactions, the updated offer and
the exact-retry receipt atomically. Converted downtime tables also offer each result die and repeat-count die;
converted downtime report expressions offer their complete signed roll and keep the higher total. Rerolling
updates the saved roll, not already settled table outcomes, counts or report effects. An action offering more
than 32 dice is rejected. Converted downtime action, sent, damage and resource rolls, checks, outcome tables,
action-step rolls and rest-passive rolls also offer their full expression, including preparation, move and
completion passives. Short-rest track-change reactions can publish their own generic roll offers for self and
ally holders, including when a later recovery reroll triggers another reaction; completed-move reactions can
publish them too. Recovery rerolls account for signed
offsets, the original remaining allowance and the holder's current healing floor. Rolls in other reaction phases
still need live offers.

The live host rejects legacy client-supplied rests and downtime rerolls when any category declares format 2,
including an incomplete pack. It cannot classify malformed, unreadable or unknown-version categories as legacy.
The shared rules directory alongside valid format-1 catalogs is not a cutover marker; a standalone ruleset is a
replacement candidate. Full and compact owner or DM sheet saves cannot change the rest counters: omitted fields
are preserved, and explicit changes or compact removals are rejected. Legacy incoming-damage snapshots preserve
the host's automation state and cannot change those counters under the replacement policy. These guards do not
affect ordinary edits and valid format-1 rests.

Scene and session resets use the same fail-closed catalog classification. Malformed, unreadable or
unknown-version categories and incomplete replacement packs reject the reset without changing sheets, campaign
state or history; valid format-1 catalogs continue to use legacy resets even when shared rules are present.

Selected `downtime.move` buttons may declare `restMove: {id, kind, group?, ally?, narrative?}`. `kind` is `any`,
`short`, `long`, or `longMove` (a long-rest move that an allowance may permit during a short rest). The optional
group defaults to the move ID and identifies mutually exclusive variants within a source instance; `ally` defaults
to false. `narrative: true` requires `ally: true` and forbids a `targetSelection`: it reports the move without
selecting or changing an ally. Other ally moves still require an authoritative recipient transaction. The compiler
validates this metadata and exposes it in descriptors, and the converter emits it for legacy timed actions.
The rest host derives move facts from the compiled binding, not a client request. These descriptors alone do
not establish general parity.
The shared ruleset may also declare `rest.short` (`moves`, `maxMoves`, `maxLongMoves`, `consecutiveLimit`) and
`rest.long` (`moves`, `maxMoves`). Counts are validated against a 32-move safety bound. Daggerheart starts with
two moves, permits at most one granted long-rest move in a short rest, and requires a long rest after three
short rests. Older development fixtures may omit this configuration, but cannot supply an authoritative rest
plan until it is present.
The staged preparation primitive constructs its facts from those limits and the stored short-rest count,
checks the expected revision, and applies authorized selected `rest.prepare` options and automatic adjustments
on a copy. Invalid choices, an exhausted short-rest allowance and invalid plans leave the sheet unchanged.
The self-rest endpoint composes this stage with downtime, effect expiry, resets, receipts and the supported outbox.
An isolated move primitive derives its event identity and target flag from compiled metadata, applies automatic
and selected move adjustments before the chosen button, and tracks source-scoped duplicate groups, move slots
and short-rest long-move allowances. It returns dice, reports and commands without persisting. A targeted
`downtime.move` button must declare `restMove.ally: true` and select exactly one other character. The host resolves
the versioned roster offer before adjustments so their conditions can read that recipient, rechecks eligibility
after adjustments, and applies source costs once against the chosen recipient's current protected tracks.
The bundled Tend and Repair ally variants target an active character's saved sheet. They report the actual recovery
after the recipient's healing floor and clamp negative short-rest recovery to zero.
Deferred bodies see a freshly reprojected recipient, including derived statistics; only after they succeed is the recipient update staged. Commands
needing interstage settlement are rejected. The caller must apply that update to a private sheet and construct
a fresh request-local roster before a later target move; reusing a roster with an unapplied update is rejected.
The live transaction commits these recipient updates together with post-outcome choices.
An automatic or option `downtime.move` rule may declare `after: {costs?, do?, report?}`. Its pre-move
operations set the move plan; after the button resolves, the host executes deferred bodies of rules that actually
ran, in their original order. Deferred costs are checked initially and paid against the post-move sheet. Results
and reports are local to the deferred body; it cannot change the completed plan or read the pre-move rule's
inputs/results. Ordinary dispatch rejects these two-phase rules, and a missing source or failed deferred cost
rejects the staged move. Converted move modifiers and move-specific passives place their bonuses before the move
and their side effects and costs afterward. Sources consumed by the move cannot yet supply a deferred benefit.
The shared rest configuration may also declare up to 32 `startFacts`, mapping names to scalar paths on the
projected actor. Preparation captures these before any option costs or moves, and the staged move progress
retains an immutable prepared plan. The bundled ruleset captures starting HP marks, Stress marks, Armor marks
and Hope. Missing or non-scalar configured facts reject preparation. Converted passives use starting Hope for
`minHope` and starting Armor for `noArmorMarked`; `armorMarked` reads current Armor marks. A `noRecovery` gate
requires both HP and Stress changes to be zero separately.
`rest.short` and `rest.long` bindings may set `restPhase` to `beforeReset` (the default) or `afterReset`.
Descriptions expose the phase, and dispatch rejects a selected binding or skips an automatic binding in the
other phase. A missing phase means `beforeReset`; a malformed phase is rejected. Converted `noRecovery`
passives run after the snapshot reset so their new effects survive, while ordinary rest passives run before it.
Started effects may declare `locks`, for example `"locks": {"hp": {"read": "actor.hope.value"}}`.
Each lock name is one projection-path segment, and its expression may read source, actor, effects or declared
state. The host freezes 1–8 integer values from 0 through 1,000 at activation; derived `while` effects cannot
declare locks. Locks only protect tracks whose projection explicitly includes them: the bundled Daggerheart HP
floor sums `locks.hp` on stored effects for both actor and recipient healing.
An effect with a short- or long-rest `until` event can also declare `"restExpiry": "beforeMoves"`. The
staged host removes matching effects from the initial validated snapshot before preparation and moves, without
refreshing uses or removing other effects. It reconciles supported pools and rejects invalid or unresolved
effects atomically. Effects started or replaced after that snapshot survive the current rest; effects without
early expiry retain normal reset ordering. Conversion of legacy locks and multi-character rests are pending.
Staged completion derives `event.rest.cleared` for numeric start facts as starting minus current values,
dispatches both phases around the snapshot reset, and advances the rest counters on a private copy.
Conditional options that no longer apply are reported as skipped; invalid choices, inputs, costs and blocked
outcomes reject the stage. The live self-rest transaction publishes reports and persists supported host requests.
Moves without the required recipient transaction reject targeted bindings rather than applying them to the wrong
character. The live coordinator handles the supported recipient mutations and reactions described above.
The internal rest coordinator composes the initial effect snapshot, early expiry, preparation, ordered moves and
completion on private source and recipient sheets. Recipient offers retain their original host versions while later
moves reproject earlier staged changes; every changed sheet advances once, and failure discards the sequence.
Recipient sheets are loaded only when selected or needed for eligibility. It accepts ordered Fear changes,
countdowns and already-applied limit refresh commands; supported single-holder cleanup settles between stages.
Other commands that could change facts read by later stages are rejected. The result separately identifies every
recipient sheet read and its original host version, including unchanged sheets used by eligibility predicates.
The live party-recovery workflow compares read sheet bytes and scope membership and host order under the store
lock before journaling all changed sheets, Fear, reports and the retry receipt. Additional ordered multi-holder
command settlement remains necessary.
A staged rest also retains the sorted members of every inspected host scope, including unloaded characters,
and rejects a membership change between moves. The rest result and its receipt wrapper also retain any host-order
dependencies. The live commit rechecks membership and order from one roster snapshot per scope and compares all
read sheet bytes under the same store lock.
Exact receipt replays return no recipient dependencies and do not reload scopes.
A host-signed offer binds the owner principal, actor, session and other host event facts, rest kind, pack revision,
source sheet revision and original stored-sheet version. A fresh request must present the offer before staging;
the caller must independently verify ownership and derive all event facts. Signing keys are local to the host
instance, so outstanding offers expire on restart. The staged receipt fingerprints the complete intent, including
the offer, and is checked before offer freshness. Thus an exact owner retry still replays a committed response
after a restart without rolling, reading a roster or staging another recipient write; changed intents, other
principals, DM receipts and older un-fingerprinted receipts cannot be reused as a rest. It shares the ordinary
bounded request-ID journal. The live self-rest API authenticates its owner before using this offer.
Sheet-backed owner recovery now reprojects the holder's statistics, derived effects and computed actor pools
before nested track reactions and after projection-relevant state or pool changes. It uses the staged state of all
active sources, not another host request. Explicit, derived and holder-owned conditional effect endings reconcile
effect-bound actor pools and reproject until stable before later reaction steps, with a bounded pass limit.
`limits.refresh` settles other active sources' uses before nested reactions, including sources without observers;
newly spent uses are not refreshed a second time. Reactions that change source activation, need cross-holder effect
cleanup, involve a computed source pool, request deferred inventory or damage changes, or cannot resolve a
conditional effect's source fail atomically
instead of reading stale facts. The fixed-source isolated dispatcher also
rejects projected snapshots when it cannot reproject them. Nested reactions propagate shared event changes back to
the parent while retaining each event's own type, actor and track-change details.
Private self downtime moves now use this projection during automatic adjustments, the selected move and authorized
deferred benefits. Converted Armor overflow fires for over-repair and positive clears of an empty track; later
steps see the resulting state, derived facts and Fear. Active event bindings and computed-pool dependencies limit
dispatch, while passive sources and conditional-effect state remain available to projection. A move without
listeners, projection dependencies, collection additions or mutable derived-stat reads skips the observer.
Reusing validation patterns and building diagnostic paths only on failure preserves validation while reducing
temporary allocations. In one opt-in, single-thread 32-idle-source private-rest sample, mean allocation fell
from 38.78 to 27.30 MiB without reactions and from 38.70 to 25.15 MiB with one Armor reaction; timings varied
substantially between runs. This is not a live persistence, concurrency or release benchmark.
Targeted-move adjustments, costs, buttons and deferred benefits settle recipient track reactions, actor-scoped
pools, writable collections and boolean facts; later operations see the reprojected holder and Fear. Unsupported
recipient commands and failed nested reactions abort the rest.
Converted `repairAllies` adjustments use live party recovery and recipient track-change reactions, including
Armor overflow. Live self and ally recovery also exercise converted Overflowing Armor. `plan.adjust` records
each requested and applied delta, before/after values, source instance, executing rule and binding, phase and
expanded step index in order, even when clamped. Nested reactions retain their own
operation identities. A move adjustment may declare `partyRecovery: {track, scope}`; conversion uses
`armorTrack` and `activePlayerCharacters` for `repairAllies`. The host copies this validated, authored metadata
into the adjustment record, rather than deriving recovery mechanics from a client-supplied amount. The ordinary
staging API without recovery still rejects these requests. Narrative ally moves remain report-only.
A recovery may additionally declare `afterRest: {sourcePath, move}`. It must have a fixed positive integer
`by` of at most 1,000, a literal label and exactly one automatic `downtime.move`/`downtime` binding, without a
conditional step or use budget. The compiler indexes these declarations by move. For matching self moves the
host schedules the compiled request for sources in the named initial projection location even when the ordinary
rule's condition was false or the source subsequently disappeared. Its fixed amount and label are checked
against any recorded adjustment; `adjustmentIndex: -1` means no matching adjustment was recorded. Sources at
other locations and ally moves do not schedule it. Converted top-level `repairAllies` declarations use `loadout`
and bypass rest, target and effect filters just as the old host loop did; effect-carried rest blocks are not
searched by that loop. Nonpositive legacy integers are ignored and unsupported amounts are diagnosed.
The internal whole-rest ordering primitive validates recorded source, rule, binding and step against the initial
sheet and compiled pack, then orders by source, rule, step and move instead of dispatch order. It accepts only
private host-produced events; provenance does not authenticate client data. A separate party stage can use the
host roster's original character order rather than sorted IDs, recording both membership and order for comparison
before commit without loading sheets. Existing sorted targeting and recovery remain unchanged. A whole-rest
recovery stage combines these primitives after a completed private rest, supplies each reaction with its
originating move facts, carries the post-rest table state, and rejects attempts to change completed event facts.
The live coordinator shares lazy recipient sheets across moves and recovery, advances each changed sheet once,
and atomically checks its dependencies; exact retries do not reload recipients. Per-card ally passives publish
independent claim offers. Unannotated recovery, changed scopes and unsettled commands fail the whole rest;
additional command settlement remains unfinished.
The shared Daggerheart ruleset now supplies self-targeted short recovery (1d4 plus tier and move bonus), the two
mutually exclusive preparation variants, long recovery and work on a project. Reports use the amount actually
cleared or gained, including permanent and locked HP and capped Hope; a short rest needs a granted allowance for
a long move. The ally variants update the selected recipient. Recovery, downtime-table, report, action-step and
rest-passive roll offers use the live reroll flow described above; other reaction-phase dice remain unfinished.
The sheet projection supplies permanent and temporary marked-HP floors to generic track clearing. The
engine recomputes locks held in its effect list as operations run, so ending a lock before healing in the same
action allows the newly recoverable HP to clear. Recipient healing uses the recipient's floor, and capacity
cannot be reduced below permanent marks. Malformed floor data rejects the staged action.

Linked capacity reserves use `track.capacity` with a same-owner `counter`. A signed `by` or a nonnegative
`amount` with `direction: reserve|release` changes capacity and counter together, bounded by the available
slots or reserve; later steps see the updated values. A projection's `capacityReserve` names the writable
capacity and counter and may name a read-only base total. Writes reject unpaired changes, malformed values
and inconsistent explicit totals. For Daggerheart, scars reserve Hope slots, lost slots clamp current Hope,
and healed scars restore slots without granting Hope. Missing legacy totals may use capacity plus scars.
Converted scar gains, marks and healing, including conditional outcomes and recipient actions, use this
operation. The shipped catalogs remain format 1.

Ordinary and targeted sheet requests accept only `sheet` bindings, even when another surface uses the same event.
Roll options and saved-result actions use their own validated surfaces; authoritative rests use separate
rest and downtime descriptors rather than sheet requests.

**Started, not cut over.** With a format-2 pack, the sheet panel and host endpoint support versioned single-target and whole-party sheet and owner result actions, plus owner-initiated profile rolls with optional selections. Claims, catalog editing and migration remain incomplete. All nine shipped catalogs still use format 1; the replacement endpoint returns 409 for them. There is no live second-engine selector or legacy adapter.

Profile-roll chat reads its Hope and Fear faces, critical/dominant result and any difficulty outcome from
the persisted host check rather than guessing from dice order. Breakdown labels follow the final roles after
a swap; a revised result creates a new history entry and leaves the original intact. Damage and legacy rolls
retain their existing presentation, and owner result controls remain separate from legacy roll buttons.
Kept profile dice include their contribution sign in the chat equation and breakdown. For earlier format-2
messages with unsigned flattened faces, the browser uses the saved structured contribution sign without
rewriting history; discarded and excluded dice remain absent from the flattened display.

Unused recipe bodies also validate concrete binding IDs, event/surface/mode names, audience, defaults, priority, result groups, static conditions, target and claim scopes, UI templates, complete input definitions and operation semantics without substitutions. Duplicate concrete input IDs and automatic rules with concrete inputs or costs are rejected as well. Local argument, input and result references are checked against their availability and operation order; scopes or expressions affected by substitutions are deferred to expansion. Input and operation details containing substitutions and checks depending on the eventual source's declarations still run at expansion, including pool costs supplied as recipe arguments; expanded pool operations reject undeclared pools even when the source has none. Full unused-body semantic linking is not yet complete. Expanded rules also validate references in target filters, roll plans and dice-table outcome templates.

The host validates each present format-2 catalog's declared category against its filename, as the full
candidate-pack validator does. A mismatched category makes the automation pack unavailable rather than
silently producing source IDs that do not match the sheet. The separate nine-category cutover gate remains.

`AutomationHost.compile` can additionally take a version-2 `dmmap-daggerheart-ruleset` document with
`name` and `automation`. It compiles that automation with the pack's recipes and projects the reserved
`ruleset:base` source onto each character without adding an editable sheet element. Its state and limits are
character-local; ordinary discovery, events, profiles, result guards and lifecycle resets include it.
Sheets cannot impersonate the reserved source. The host loads `rules\recipes.json` and `rules\ruleset.json`
together, includes both content revisions and the layout in the compiled-pack key, and excludes them from
compendium categories and legacy name-based lookup. A present but incomplete or malformed `rules` directory
is rejected rather than falling back; incomplete development fixtures without that directory can still use
root `rules.json`. Packaging copies and verifies the reserved documents, including their bundled converter
defaults. The shared ruleset offers 1 Hope for a non-reaction, non-death Duality result with Hope even on
failure; a critical also clears 1 Stress. It offers no action when neither can change. Profile rolls are
integrated; other roll origins and shared-rules editor support remain pending.

**Performance baseline.** Run `mvn "-Ddmmt.benchmark.automation=true" "-Dtest=OnlineSessionServerTest#measurePersistedProfileAndPartyActions" test` separately from other tests. It uses loopback HTTP and a temporary on-disk campaign, six sheets with 12 KB of notes each, a profile attack with conditional and unconditional passives, a stored-effect modifier, a selected option and a finished reaction, and versioned result and sheet actions with a recipient filter and five recipients each. It checks the applied modifiers and total. After 20 warm-up cycles the retry journal is full. The next 40 cycles measure each request to receipt of its response, including persistence on POST. The result GET fetches a post-roll target offer; neither GET is required to resolve the roll.

| Request | Median | p95 | Mean allocated per request |
|---|---:|---:|---:|
| Profile POST | 17.32 ms | 20.82 ms | 8.28 MiB |
| Result GET | 8.55 ms | 10.57 ms | 6.49 MiB |
| Result POST | 38.52 ms | 46.90 ms | 12.86 MiB |
| Party GET | 8.67 ms | 12.15 ms | 6.78 MiB |
| Party POST | 38.56 ms | 48.23 ms | 13.50 MiB |

After adding effect-bound pool reconciliation, another run of this same sample measured profile POST
14.07 ms median / 15.54 ms p95 / 9.13 MiB, result POST 31.67 / 33.95 ms / 13.71 MiB, and party POST
31.45 / 34.61 ms / 13.50 MiB. Actions without bound pools or effect changes avoid the new effect-support scan; this
single-client sample is not a concurrency guarantee.

For an unfiltered single-target action, the request-local roster now reads each stored revision and sheet
digest without refreshing every candidate through the catalog. Only a selected recipient is hydrated for
execution; an authored `where` predicate still projects every candidate, and whole-party execution hydrates
every selected recipient. The original stored sheets remain compare-and-set dependencies. Malformed stored
JSON is rejected, and hydration verifies that catalog refresh did not change the stored automation revision.
Run `mvn "-Ddmmt.benchmark.automation=true" "-Dtest=OnlineSessionServerTest#measurePersistedSingleRecipientAction" test`
for a loopback sample with 12 saved recipients, about 12 KB of notes per sheet, 10 warmups and 24 persisted
actions. One run before this change measured GET 8.53 ms median / 12.43 ms p95 / 1.89 MiB allocation and
POST 20.81 ms / 27.05 ms / 3.63 MiB; one afterward measured GET 8.05 ms / 10.56 ms / 1.65 MiB and POST
20.25 ms / 24.37 ms / 3.41 MiB. These small single runs show reduced allocation, not a reliable latency
guarantee; the journal fills during the sample.

Run `mvn "-Ddmmt.benchmark.automation=true" "-Dtest=OnlineSessionServerTest#measureSingleHolderRosterScans" test`
for a separate loopback sample of single-holder starts with 32 saved, unrelated offline or disabled sheets.
After 10 warmups, 30 POSTs measured 34.82 ms median, 43.71 ms p95 and 2.79 MiB mean allocation in one local run.
This measures roster inspection and persistence without matching prior effects, not concurrent clients or a
worst-case sheet size.
Another same-workload run measured 34.97 ms median, 44.84 ms p95 and 2.79 MiB before the targeted effect read,
and 35.30 ms, 41.24 ms and 2.69 MiB afterward. The sample shows lower allocation, not a reliable latency improvement.

The separate `mvn "-Ddmmt.benchmark.automation=true" "-Dtest=OnlineSessionServerTest#measurePersistedMixedWeaponDamage" test` samples a single sheet with 12 KB of notes and a full retry journal. Its weapon damage roll has two proficiency-scaled pools, conditional and unconditional passives, a stored effect, a selected option, and an automatic reaction adding and dropping one die per pool. After 20 warm-ups, 40 complete persisted POSTs measured 37.22 ms median, 51.55 ms p95, and 11.82 MiB mean allocation per request.

`OnlineSessionServerTest#measurePersistedDerivedActivationDamage` uses the same workload with an active
derived effect and a result button, including projection and persisted guard pruning. One run measured
29.57 ms median, 44.88 ms p95, and 14.56 MiB mean allocation over 40 POSTs after 20 warm-ups; the
ordinary mixed-weapon run in the same invocation measured 19.82 ms, 22.02 ms, and 11.85 MiB. The
derived scenario also creates result controls, so the difference is not solely derived-effect overhead.

`OnlineSessionServerTest#measurePersistedTraitDependentDamage` adds a trait bonus and a passive damage
contribution based on the modified total to that mixed-weapon workload. One run measured 30.32 ms median,
41.44 ms p95 and 13.11 MiB mean allocation; the ordinary workload in the same invocation measured
24.60 ms, 31.18 ms and 11.78 MiB. This loopback sample includes persistence, is not a concurrent-client
guarantee, and shows the cost of the additional projection rather than an extra network request.

`RuleCatalogTest#measureColdPackCompilation` measures pack compilation on the calling thread: nine
catalog envelopes (three populated fixtures) and eight recipe definitions. After 10 warm-ups, 20 runs
before sharing recipe validation measured 38.23 ms median, 60.14 ms p95 and 51.58 MiB mean allocation;
one run after sharing it measured 8.93 ms, 14.68 ms and 7.77 MiB. This synthetic cold-load sample does
not measure play-time requests or a full converted catalog. Validation still checks every recipe, including
unused ones, before expanding it for any source.

`OwnerTrackReactionsTest#measureManySourceTrackReactions` measures an isolated staged transaction with one
reacting and 64 unrelated sources, four track clears, 10 warmups and 20 measurements. One run before indexing
measured 14.91 ms median, 22.58 ms p95 and 19.75 MiB mean calling-thread allocation; one indexed run measured
5.49 ms, 7.44 ms and 2.78 MiB. Sources with computed actor pools remain in the nested dispatch so those pools
still clamp in source order. This synthetic sample excludes the host, storage and recipient reactions.

`HolderReactionProjectionTest#measureSheetBackedRecovery` measures one private holder recovery with a reacting
source and 32 passive sources, including reprojection after the clear and its state mutation. After 10 warmups,
20 runs measured 10.83 ms median, 12.96 ms p95 and 12.06 MiB mean calling-thread allocation before eliminating
redundant projection-input copies; a later run measured 6.99 ms, 9.82 ms and 10.49 MiB. After adding effect
settlement checks, another run measured 8.37 ms, 12.03 ms and 10.67 MiB. These single runs are not latency
guarantees and exclude storage, network traffic and concurrent play.

These are separate single-run development measurements on Windows / Temurin 25, not release thresholds or directly comparable workloads. The HTTP allocation figures are JVM-wide (host and test client); the cold-load and isolated-track figures are calling-thread allocation. None is retained heap. The runs exclude browser rendering, WAN latency, connected-client broadcasts and unrelated concurrent play.

**Host-intent hardening (2026-11-22):** The automation POST now accepts only a rule request; browser-supplied event facts are rejected, and the initial sheet-action event is created by the host. Journal replays are bound to the authenticated player principal. This closes event-type/payload forgery for this endpoint, but authoritative roll and lifecycle event construction and their integration with the existing roll pipeline remain outstanding. Keep previews local and pure, reuse host-computed roll facts, and avoid additional network round trips. Compiled programs now index visible buttons/options by event and surface, avoiding a scan of unrelated rules for each action description; the host pack is reused by compendium revision. Any actor-projection cache must include every relevant sheet, catalog and event dependency, and must be justified by benchmarks before adding complexity.

| Implemented building block | Scope |
|---|---|
| `dmmt.online.automation.RuleCompiler` / `RuleCatalog` | One replacement format (`version: 2`); strict JSON, known operation/input fields, stable IDs, bounded recipe expansion, state/input/result references and templates. Recursively find nested automation outside editor metadata; require stable IDs for automated array members rather than deriving them from names or positions. Candidate-pack validation requires nine matching category envelopes and rejects old/mixed versions. It does **not** prove content coverage or conversion parity. |
| `RuleExpression` / `RuleTemplate`, `web/src/automation/values.ts` | Shared pure arithmetic, comparisons, booleans, conditionals, collection-size/membership, explicit reads and text formatting. No eval, numeric coercion or HTML. Shared fixtures cover host/browser results and invalid values. |
| `StructuredRoll`, `web/src/automation/roll.ts` | Identified dice/pools, signed dice, fixed bonuses and dependency-based multipliers; original kept/dropped/maximized provenance and cumulative saved-roll derivation. Host plan resolution supports mixed counts/sides, keep-highest and maximization. Renaming a label cannot change arithmetic. |
| `StructuredDamage`, `damage.reduce`, `damage.increase`, `damage.resolve` | Validates ordered pre-severity snapshots and adjustment provenance; `damage.increase` records an `increase: true` adjustment that raises the amount. Resolution thresholds, armor-track fields/spend, severity reduction per slot and HP marks are configured in JSON; changes are bounded and apply to a copied actor snapshot. |
| `RuleRuntime` | Pure single-rule resolution on copies, descriptors, revision/input/eligibility checks, cumulative costs, inventory consumption, capped recovery, named use limits, numeric pools, individually identified stored-die pools, state operations, bounded rolls and configured reports/previews. Target descriptors include only host-derived `{id, label, revision}` options for the configured scope; they never contain recipient state. Pools can be source-scoped or actor-scoped in the isolated context; dice are rolled, kept by rule, independently enumerated, spent by stable ID, added with provenance to derived roll results, and reset by declared events. Failure leaves the caller's snapshot unchanged. Random previews fail explicitly rather than sampling. |
| `RuleDispatcher` | Deterministically discovers bindings and executes grouped event transactions on copies. Automatic rules run before selected actions; priority descends with stable source/rule/binding/item IDs as tie-breakers. It permits independent selected buttons, enforces configured exclusive option groups, preflights aggregate costs/use limits and returns shared actor state at one revision. Cross-actor claim transactions validate recipient eligibility/revisions, enforce one claim per group per recipient, apply recipient-targeted track/resource mutations and return a versioned claim ledger. Repeatable target actions additionally support one recipient or the complete host-supplied eligible set (up to 128), reject client-chosen subsets for fan-out, revalidate recipient IDs/revisions, and charge source costs/limits once while applying recipient operations per target. The caller must still authorize and atomically persist all source, recipient and claim updates; no HTTP authorization, idempotency journal or concurrent storage/CAS integration exists. |
| `web/src/automation/controls.ts` | Standalone button descriptors with text-safe labels, themed integer/die-choice dialogs, validation, cancellation and pending-click protection. Retries preserve the same request ID and inputs; IDs work over ordinary HTTP LAN origins. Per-die descriptors include the stable item ID, not its face or array index. Option bindings cannot be independently submitted or paid. This renderer drives format-2 sheet and owner-result controls; shipped catalogs are not yet converted. |

The runtime also exposes an optional transaction-local track observer for ordered resource, track, capacity and internal damage mutations. It preserves the host-derived request and before/after snapshots, including positive Armor-clear requests that clear nothing; callbacks run between mutations and may affect later steps. Previews and existing unobserved executions do not invoke it. An isolated grouped-event dispatcher can use it to settle the supplied owner's automatic track rules, with bounded recursion, source-instance state, ordered Fear/host requests and one enclosing revision. Isolated selected and deferred move executions can use the same reactions without repeating parent-event automatic rules or rechecking the deferred benefit's original eligibility. Existing computed actor pools are clamped in stable source-instance order before host action descriptions, target eligibility and dispatch. When clamping changes a pool, the host reprojects its effects and stats, including result-window activations, and rejects capacities that are still unstable; previews never write the adjustment. Isolated dispatch cannot reproject a previously projected actor and rejects pool changes that would leave its stats or derived effects stale. The observer also refreshes computed actor pools when no reaction is bound. The isolated host-owned clear uses the same protected floor and reactions, even for an empty source list or a positive request clearing no marks; it returns the actual clear, copied holder and source states, reports and commands without changing caller snapshots. A sheet-backed stage projects the affected holder's own sources, effects and identity independently of the parent event, then writes its reaction state to a private sheet at one revision. An isolated party stage extracts separately authored positive requests and applies them to the other members of each host-selected scope, preserving request and stable recipient order even when scopes alternate. It inspects each scope once and enforces a combined 1,024-application limit before loading sheets. It reprojects between requests, carries Fear, records each inspected membership and every read sheet version, and advances each changed sheet once. The caller must compare membership and versions and commit all changes atomically. These primitives assume their source list remains active during each reaction and are not wired to live gameplay or downtime.

Recipe document validation runs once per catalog, including empty catalogs. It checks every recipe's argument declarations and defaults, UI templates, `$arg`/`$ui` substitution names, concrete input definitions, statically resolvable local references, and static rule, binding, UI and operation shapes, including unused recipes. Checks that depend on substitutions or source declarations run when a recipe is expanded at a use site; complete semantic validation of unused rule bodies is still pending.

Executable candidate configurations, rather than a partial replacement of the shipped pack:

- [recipes.json](../src/test/resources/dmmt/automation/rewrite/recipes.json): saved-weapon-roll sequence composed exclusively from generic rules and operations, including JSON-defined exhaustion cleanup.
- [domain-cards.json](../src/test/resources/dmmt/automation/rewrite/domain-cards.json): Deathrun (3 Hope, +1 first Proficiency, remove one die) and Splinter Trail (1 Stress, +2, remove two, different labels).
- [consumables.json](../src/test/resources/dmmt/automation/rewrite/consumables.json): Minor Health Potion consumes stock, rolls d4, clears actual marked HP and formats the actual result.
- [pools.json](../src/test/resources/dmmt/automation/rewrite/pools.json): a Prayer-Dice-shaped source pool rolls three, keeps two, generates a separate templated action for each die, and demonstrates consuming a stored die into a derived roll result; a numeric Favor pool is actor-scoped, so two independently compiled source programs can share it through the same host snapshot. These fixtures test generic mechanics only; real Prayer Dice ownership, audience, roll/transaction integration, damage-reduction and Hope interactions are not implemented.
- The same pool fixture applies stored-die and separately costed ward reductions, then uses a JSON-configured resolver for severity thresholds, optional armor spending and bounded HP marks. These operations are fixtures, not a live damage pipeline.
- [claims.json](../src/test/resources/dmmt/automation/rewrite/claims.json): alternative recipient-targeted resource/track rewards in a shared per-recipient claim group, independent of the shipped catalog.
- [targets.json](../src/test/resources/dmmt/automation/rewrite/targets.json): generic single-target and party fan-out actions, source inclusion/exclusion, once-per-action costs/limits, recipient-scoped changes, trusted target options and atomic failure behavior.

The sequence fixtures supply a synthetic host damage plan and source state; they are not production effect instances or committed chat messages. The isolated resolver demonstrates `46 -> 40 -> 36` with the original dice and no rerolls. Browser/Java tests consume the same expression, template and saved-roll fixtures. Browser control tests additionally exercise cancellation, disabled reasons, exact selections, integer boundaries and uncertain retries.

Current supported automation fields are `state`, `limits`, `pools`, `effects`, `modifiers`, `profiles`, `rules`, `attachment` and `grants` (`rules` may be omitted when only passive modifiers, pools, profiles, derived effects, grants or an attachment exist). `grants` (`features`: 1-16 unique feature names, `display`: `grouped` or `separate`) tells the sheet builder which catalog features the source adds; the host does not change collections from it. An effect with a `while` condition is *derived*: it is never started, ended or persisted; the host evaluates `while` (which may read only `source.*`, `actor.*` and declared `state.*`) against the actor's persisted effects and adds an instance `{instanceId}/{effectId}/derived` with `derived: true` to the projected `actor.effects`, so its modifiers, label checks and `effects.<id>` counts behave like a started effect. `describe` lists these as `derivedEffects`; `while` cannot be combined with `until`, `stacking` or `exclusiveGroup`. An effect `exclusiveGroup` makes `effect.start` remove every stored effect in that group on the target, from any source, before adding the new instance (which records `group`). An `attachment` (`to` gear kind, up to 16 `requires` entries of `{when, reason}`, optional granted `feature` `{name, text}` and weapon attack `trait`) makes the source attachable: its modifiers, profiles and rules apply only while it is attached, those rules may read the host gear as `source.attachedTo.{id, instanceId, kind, name}`, and `Program.attachmentBlockers(gear, source)` returns each failed requirement's rendered reason. Requirement conditions and reason templates may only read `source.{id, name}` and the host-supplied gear facts `gear.{kind, name, range, damageType, hasFeature, generated, hasTraitAttachment}`; the `gear` root is unavailable to rules. The `containsText` expression tests case-insensitive substring containment; `count: [array, value]` returns how many elements equal the value. The Daggerheart projection exposes the active loadout as the read-only collection `actor.loadout` (`id`, `domain`). `profiles` lists 1-8 additional attack profiles (`id`, `label`, optional `trait`, `range`, `damage` with optional `count` defaulting to Proficiency, `sides`, `flat`, `type` defaulting to the source's damage type); the host lists active sources' profiles in `describe` as `profiles` with `sourceId`, `sourceInstanceId` and `sourceName`. Declared `effects` have a required `label`, optional `text`, `kind` (`buff`/`debuff`), `until` reset events, `stacking` (`replace`, `stack`, `unique`) and modifiers whose values are frozen when `effect.start` applies them to the actor or a claimed recipient; `effect.end` removes this source instance's copies and `effects.<id>` reads their count. An `endWhen` condition (same read restrictions as `while`, not combinable with it) is checked after every rule of the source runs and ends the source's actor instances once it holds (for example a token effect ending when its pool is empty). Modifiers name a `stat`, an unambiguous `op` (`add`, `set`, `atLeast`, `atMost`), a `value`, optional `label` and, for passive source modifiers only, a `when` condition. `limit.reset` restores a declared use limit. A `choice` input lists 1-64 labelled options (each optionally gated by `when`) and requires exactly `count` distinct selections: one ID for `count: 1`, otherwise an array. Instead of static `options`, `choice` and `allocation` inputs may declare one `optionsFrom` expression that yields up to 64 `{id, label}` host facts (allies, traits, catalog entries, active effect instances); a dedicated trait, actor, catalog or effect picker kind is therefore unnecessary, and only IDs offered from the pre-action state are accepted. An `allocation` input distributes exactly `total` across its options, each from 0 to `max` (default `total`); the accepted value is normalized to include every offered option. A quantity is an `integer` input. Tests: `InputRuntimeTest` with [inputs.json](../src/test/resources/dmmt/automation/rewrite/inputs.json). Tests: `EffectRuntimeTest` with [effects.json](../src/test/resources/dmmt/automation/rewrite/effects.json). Roll edits use the `die` root inside per-die expressions (`where`, `face`, `when`; die fields include `face`, `sides`, `role`, `contributionId`, `sourceId`, `previous` and `editedBy`): `roll.selectDice` returns the IDs of kept, unexcluded dice from editable contributions; `roll.reroll` (`choose: new|higher`), `roll.setFaces` and `roll.explode` (`maxDice` 1-32, chaining) record each prior face and editing source (at most 32 edits per die) with the provenance `rerolled`, `set` or `exploded`; `roll.addDice` rolls `count` (0-1024) more dice of the first selected die's size into its contribution (origin `added`), `roll.resize` rerolls each selected die with a new `sides` (2-1000; origin `resized`, prior faces kept in history), `roll.dropLowest` excludes the lowest `count` selected dice, and `roll.contribute` appends a labelled `dice` (`count`, `sides`, optional `keepHighest`, `maximize`, `sign`, `editable`), `flat` (`value`) or `scale` (`of` as a list of earlier contribution IDs or `"all"` to multiply the whole earlier roll including earlier scale adjustments, `factor`) contribution; a zero-dice or zero-value contribution is skipped. Contributions with `editable: false` are never edited. Deliberately no separate maximize, face-map, flat-bonus or advantage operations: use `roll.setFaces` with `die.sides` or an `if` expression, a `flat` contribution, and signed dice contributions with `roll.derive` exclusion. A reroll-once rule tests `includes(die.editedBy, ...)` in `where`. Random edits are unavailable in previews (`randomPreview`). Tests: `RollEditRuntimeTest` with [roll-edits.json](../src/test/resources/dmmt/automation/rewrite/roll-edits.json). pool kinds are `number` and `dice`, with `source` or `actor` scope. In addition to existing pool/roll operations, `damage.reduce` composes bounded pre-severity reductions and `damage.resolve` applies configurable threshold, armor and HP policies. Bindings may use `forEach` over declared dice pools, `exclusiveGroup` for selected options, `claimGroup` for recipient claims and `targetSelection` for repeatable recipient actions. Input kinds are `integer`, `die.select`, `choice` and `allocation`; binding modes are `button`, `option` and `automatic`. Claim-group bindings enforce one claim per recipient; repeatable target actions use separate target metadata and do not consume the claim ledger. The compiler performs programmatic validation; a published JSON Schema and fully typed actor/event/recipient/state contracts are still pending. Unused recipe bodies do not yet receive complete semantic linking. Lifecycle events add `rest.prepare`, `downtime.move`, `death.options`, `track.changed`, `pool.changed`, `equipment.changed`, `effect.started`, `effect.ended` and `creation.apply`, with `death`, `downtime`, `creation` and `companion` surfaces; rest and death moves are ordinary rules bound to these events rather than special offer operations. Bindings may declare an `audience` (`self`, `others`, `party`, `any`; default `self`) that the host enforces. State operations: `limit.modify` refunds `by` uses (clamped), `track.capacity` changes a track's `capacity` (or `max`) by `by` within 0-1000 and clamps its value, `collection.add`/`collection.remove` change `inventory`, `features`, `loadout`, `vault`, `conditions` or `companions` (added entries record `grantedBy`, matching `quantity` entries merge, duplicates and missing IDs are unavailable). `damage.deal` (`to`: `actor`, `recipient`, `attacker`) and `countdown.start` queue bounded host requests in the context `outbox` instead of mutating other actors directly; redirects combine `damage.reduce` with `damage.deal`, and vaulting a card combines remove and add. An effect may carry a frozen alternate attack `profile` (label, trait, range, damage dice/flat/type). `plan.adjust` changes one numeric field of a host-published adjustable plan (`plan` is an `event.*` path, e.g. `event.rest` or `event.move`): the plan must list the field under `adjustable` with optional `min` (default 0)/`max` (default 1000), otherwise the step is unavailable (`planField`); `by` (-1000..1000) is clamped and each change is appended to the plan's `adjustments` (`field`, applied `by`, `sourceId`, `label`). Because the dispatcher shares the event between invocations, adjustments from several sources accumulate. The `object` expression builds dynamic objects from 1-16 field expressions; `at: [array, index]` reads a 0-based element (out of range is an error) and the `|list` template formatter joins scalar arrays with commas. `dice.table` rolls `count` (0-100) dice with one side per listed outcome template (2-100) and stores `{faces, outcomes, count}`; it is unavailable in previews. `inventory.grant` (`catalog`, `entry`, `quantity` 1-100, `label`, optional `to`, `tag`, `max` 1-1000 and `scope` `actor`/`party`) queues a host inventory request. Tests: `LifecycleRuntimeTest` with [lifecycle.json](../src/test/resources/dmmt/automation/rewrite/lifecycle.json). Other constructs below describe the **target**, not available runtime capabilities.

### Target actions

Target and claim dispatch return a separate ordered `outbox`. Each command carries the host-selected `recipientId`; do not recover commands from the last source context, which may represent only the last recipient.

**Sheet endpoint:** On a format-2 pack, a sheet or owner-result button's descriptor lists eligible saved characters by ID and label. A single-target button submits `request.target: {recipientId, version}` using that option's version. A party button submits `{version}` using the selection's version, never a list of IDs. The host rebuilds the eligible set, checks the catalog and saved sheet versions, and journals all changes together. A stale offer returns 409 without a charge or report; a retry of an already committed request returns its original receipt. Scoped DM sheet grants cannot invoke cross-character actions. Claim acceptance is a separate integration.

New journal entries bind the caller and a SHA-256 fingerprint of the complete submitted intent to the request ID. Object-key order does not affect the fingerprint, but changed values or array order do; conflicting reuse returns 409 before applying anything. Older entries without a fingerprint remain replayable until evicted, preserving saved retries across the upgrade.

A target-selecting binding declares this JSON contract:

```json
"targetSelection": {
  "cardinality": "one",
  "scope": "connectedPlayerCharacters",
  "excludeSource": true
}
```

`cardinality` is `one` or `all`; `scope` is `connectedPlayerCharacters`, `activeCharacters` or `activePlayerCharacters`; and `excludeSource` controls whether the source actor may be a recipient. An optional `where` condition over `recipient.*` filters eligibility: the dispatcher drops (`all`) or rejects (`one`) ineligible recipients, and options carrying an optional `snapshot` are filtered at describe time. Target selections are valid on sheet action buttons and on `roll.finished` roll-trigger buttons (Healing Hands uses this with a recovery-debuff gate). Stunning Sunlight converts to a Hope-amount input plus two generic dice rolls, and Resurrection converts to a d6 that moves the card from loadout to vault (or back) with conditional steps. Second Wind is a self choice (1 HP or 3 Stress) plus an ally roll-trigger variant. Salvation Beam is a one-ally target action with an integer Stress input (bounded by unmarked Stress) paid by `track.mark` and healed with `track.clear`, filtered by a `where` on wounded allies and repeatable while Stress remains. Invigoration targets any active character and emits the host `limit.restore` request (`to`: `actor` or `recipient`), which the host resolves by letting the recipient pick one spent limited feature use. The host supplies `targetOptions[scope]` from its authoritative roster as `{id, label, revision[, snapshot]}` records. These bounded records are display/selection facts only; recipient state is loaded and validated again by the host dispatcher at execution. A `one` action submits a selected ID and its expected revision. An `all` action submits no target ID and always uses the complete host-filtered eligible set, never a browser-selected subset. Target actions reserve source costs and use limits once, apply recipient-scoped operations independently to every selected recipient, and fail without returning partial updates if any recipient is stale or ineligible. A transaction accepts at most 128 recipients. `actor`, `anyActor`, `party` and `allActors` convert respectively to one connected player character, one active character, all connected player characters and all active player characters, including offline player-sheet characters. These actions are repeatable and remain distinct from one-time `claimGroup` rewards. The sheet panel renders single-target radios with the rule inputs and lists whole-party recipients inline. A stale action reloads the choices without applying; an uncertain response retries the identical intent. Owner result controls are available on the sheet and roll chat; saved cross-player offers expose eligible claim actions in chat.


Expressions may read `sourcePools.<id>.value` (and `.max`) for pools declared with `scope: source`. Zone of Protection converts to a source-scoped `protection` pool, a derived marker effect that lasts while the pool is above 0, and a claimable "Zone of Protection" effect whose mitigation reduces damage by the shared pool value and advances it (spending it back to 0 at capacity). The host must dispatch the effect-source program for the holder with the caster's source pools and write the pool state back to the caster.

`roll.resolve` accepts an optional `metadata` object of scalar expressions merged into the resolved roll's metadata. Legacy `castTokens` becomes an integer `amount` input (a spent cost) whose value is recorded as `spentTokens` roll metadata; `spentTokenDamage` becomes a roll trigger that requires `event.roll.metadata.spentTokens >= 1` and rolls that many fixed magic dice. `hostRollAction`, `bloodAutomation` and `otherPlayerOnly` were host gating flags: the converted rule already pays its own cost and limit and filters by binding audience (`ally` → `others`), so they are validated and dropped.

Grouped execution preflights every selected rule's inputs, limits and costs on copied snapshots before running effects. Automatic rules run first, then selected actions in descending priority with stable-ID tie-breakers; all returned contexts share one incremented revision. Explicit `exclusiveGroup` values, rather than a hard-coded one-button limit, define mutually exclusive options. Claim execution also returns a claim-ledger revision. A production host must atomically compare-and-set that ledger together with source and recipient snapshots; the isolated resolver alone cannot prevent two concurrent host requests from committing the same old ledger.

### Host integration

Format-2 sheet and lifecycle commits check the original stored sheets under the campaign store lock before writing the journal. An intervening edit, even one that does not change `automationState.revision`, rejects the staged sheets, campaign changes and chat together. This commit-time check is separate from checking the revision the player saw when opening an action.

`claim.offer` publishes a separate saved chat offer for its source instance. Versioned authenticated GET/POST
claim endpoints describe an eligible owned character's current choices, preserve the originating event, enforce
the saved group's limit, and atomically compare the complete offer and read sheets before journaling the replacement,
changes and reports. The browser loads descriptors on demand and retries an uncertain response with the same intent.
Replays require the same caller and request; changed catalogs or inactive sources invalidate an offer. Claim
inventory grants, recipient track reactions, cross-holder lifecycle behavior and `actionBenefits` injection remain
unfinished. Recovery rejects ambiguous or conflicting offer and report history before writing sheets.
Claim actions that publish or revise rolls are rejected until roll-result publication is connected.
`GET /api/automation/{version}/{sourceActorId}/claims/{messageId}/{timestamp}?recipientId=…` returns
the current source, recipient and claim revisions plus action descriptors. POST to the same path supplies
`request: {requestId, recipientId, expectedRevision, expectedRecipientRevision, expectedClaimRevision, selection}`.
The compiler links each `claim.offer` to a matching `claim.accept` chat binding in the same expanded source;
a roll-result claim with the same group is not an acceptance handler.

- **Sheet projection.** `SheetProjection` interprets the app-owned `src/main/resources/dmmt/online/automation/daggerheart-projection.json` (`format: dmmap-sheet-projection`, `version: 2`). It declares active source lists and bounded writable actor facts. `poolTracks` maps an actor numeric pool to the unmarked slots of a writable plain track; an optional writable boolean `fullFlag` becomes true when the pool is exhausted and stays set until a `flag.set` rule clears it. The sheet track is authoritative, including its current capacity. `downtime.completed` automatic rules receive `event.move.start` and nonnegative net `event.move.cleared` values for the ruleset's numeric `startFacts`, after the selected move and deferred benefits. Tests: `ProjectedPoolTrackTest` and `CompanionRestRecoveryTest`.
- **State.** `automationState` holds the actor `revision`, per-source-instance `state`, `usage` and `sourcePools`, and a 32-entry request journal. Duplicate source instance IDs are rejected. A consumed source whose quantity reaches 0 is removed from its list.
- **`AutomationHost`.** Compiles every present catalog and the effective shared rules into a pack keyed by their combined content revision (failures are cached per revision too); any format-1 catalog or missing required rules makes the pack unavailable. `describe` returns `{revision, actions[]}` filtered by audience; `execute` validates `{requestId, expectedRevision, selections[{sourceInstanceId, ruleId, bindingId, eachId?, inputs?}]}`, replays a journaled `requestId`, rejects stale revisions, runs automatic reactions before selections, writes through the projection and returns reports, rolls and an `outbox` of host requests. `resetLimits` applies lifecycle resets.
- **Endpoints.** `GET /api/automation/{version}/{actorId}?surface=&event=` and `POST /api/automation/{version}/{actorId}` with `{request}`. Event facts are host-generated. The actor's owner has relation `self`, other session players have `other`, and a scoped DM sheet grant has `dm`. Audience checks and owner-specific receipts still apply. Errors: 409 for a compatibility mismatch, unavailable automation or a stale/spent request, 403 for an audience violation, and 400 for invalid input. A successful change bumps `featureRevision`, commits the staged sheets and supported host commands, pushes feature state and publishes action reports.
- Tests: `AutomationHostTest` (fixture [host-classes.json](../src/test/resources/dmmt/automation/rewrite/host-classes.json)) and `OnlineSessionServerTest#automationEndpointsDescribeExecuteJournalAndRejectStaleOrForeignUse`, both using fixtures independent of `dmmap-daggerheart`.
- **Saved-state protection.** A full sheet save cannot forge `automationState`; the server keeps the stored revision, usage and journal.
- **Sheet panel.** `web/src/automation/panel.ts` renders an *Automation* card at the top of an editable player sheet with one button per `mode: button` action from `GET /api/automation/{version}/{actorId}`, sends the selection with the current revision, reloads after every action and shows server errors inline. It stays hidden while the pack is unavailable, but displays a compatibility mismatch instead of hiding it. Tests: `web/src/automation/panel.test.ts`.

### Format converter (one-time tool)

`dmmt.online.automation.LegacyCatalogConverter <legacy-catalog-dir> <staging-dir>` converts format-1 catalogs into format-2 copies in a separate staging directory, writes `rules\recipes.json`, `rules\ruleset.json` and a `conversion-report.json` (per-catalog block counts, path-specific diagnostics, grouped reasons and IDs assigned to nested automated members), and validates the staged pack. It preserves and validates shared documents from the source directory when present; otherwise it uses the bundled defaults. It refuses a staging directory inside or containing the source and never runs at application runtime. Each block converts completely or is removed from the staged copy and reported; nothing is guessed.

Supported legacy families:

- **Action rolls.** Fixed-trait, highest-trait, traitless and spellcast-trait selectors convert to generic `roll.resolve` plans. Spellcast uses the first trait marked `spellcast` in the declarative sheet projection, and its button is unavailable when no such trait exists. Beastform-selected traits still need an active-form projection/selection contract.
- **Action reports.** A legacy `report.dice` expression converts to a host-resolved generic roll followed by a nonnegative captured report amount, after costs and before ordinary action steps. Signed dice and flat terms retain their order and the actual faces appear in the receipt. Computed amounts are also captured before the steps; exact trait names use the derived trait total. Invalid or out-of-bounds expressions, combinations with other independently ordered rolls, and derived-stat reports after payments (which require stat recomputation) are diagnosed instead of guessed. Computed reports outside actions remain unsupported until their ordering is wired. The report remains narrative and does not choose or heal recipients.
- **Damage actions and roll triggers.** Direct action damage converts to `roll.resolve` with fixed dice, proficiency-scaled dice or spellcast-trait-scaled dice, optional signed flat damage, and `physical`/`magic` metadata. Spellcast scaling multiplies any written dice count by the nonnegative projected spellcast modifier; the corresponding action is unavailable when no spellcast trait is projected. `rollTriggers` and `conditionalRollTriggers` can contribute spellcast-scaled damage to an existing roll. These operations produce typed roll results; they do not select targets or apply damage.
- **Limits and actions.** `uses` → limit `uses` (`rest` → both rests, `shortRest`, `longRest`, `scene` → `scene.end`, `session` → `session.start`), referenced by every rule unless the rule sets `usesSource: false`; a rule-level `uses` must be `"source"`. `actions` → sheet `button` rules with `label`, `description`, `cost`, `steps`, `report`, `requiresEffect`, `excludesEffect` and `endEffect`. Hope costs → `resource.spend actor.hope`; HP/Stress/Armor → `track.mark`; other costs → `pool.spend`. Steps: `clear`/`mark` → `track.clear`/`track.mark` (`"all"` reads the marked count, or spends the current balance of a declared pool), `gain` → `resource.gain`/`pool.gain`; amounts may be whole numbers, dice strings (`1d4+1` → a `dice.roll` step whose total feeds the amount, floored at 0) or computed `@tokens` (`@tier`, `@level`, `@hope`, `@proficiency`, `@total_proficiency`, `@armor_score`, `@armor_available`, `@spellcast`, `@total_spellcast`, `@<trait>`, `@total_<trait>`, `@amount`) read from projected facts and `actor.stats`; unsigned amounts are floored at 0. A cost of `@hope` spends all Hope. `selectAmount` → an `amount` integer input (`min` default 1, `max` = available Hope, free track slots, the pool balance, or unbounded for `heal`); the button is unavailable when the minimum cannot be paid and `@amount` reads the choice. As in the legacy runtime, the amount is paid from its resource (unless `heal`, or a cost already reads `@amount`); `store` replaces a declared pool with the amount plus `base` before the other steps; `heal` clears that many marked Hit Points after them; `addToRoll` (roll triggers only) adds the amount as a flat contribution and republishes the roll. `selectAmount` is accepted on actions and roll triggers.
- Legacy `hostOwned` flags on actions, options and trigger/mitigation entries are consumed during conversion. The replacement runtime is authoritative by default; no feature-specific TypeScript or Java `hostOwned` branch survives in converted JSON.
- **Resources.** `resources` → `pools` with `scope: "actor"`, `label`, `max`, `initial`, `resetOn`; `resetValue`/`minimumReset` become the pool's `resetTo` expression (evaluated against the actor at reset and clamped to the pool range; `@cards_<domain>` counts loadout and vault cards of that domain). The converter collects every declaration first, so a cost or gain in another catalog copies the same declaration into its own source (for example a class's Favor pool spent by a domain card). Duplicate declarations of one pool must agree. A `max` may be a positive integer or a computed token (`@level`, `@proficiency`, `@spellcast`, `@total_spellcast`); a computed capacity is a pool `max` expression evaluated against the owning actor whenever the pool is loaded, and stored values are clamped to it. `session: true` resets the pool on `session.start` (to `sessionReset`, if given, else 0); `sessionHope` instead adds an automatic `session.start` rule that gains Hope equal to the balance and spends the pool. A source may declare only pools.
- **Effects.** `effect` steps → declared effects started on the actor. Rules an effect grants (`actions`, `rollOptions`, `rollTriggers`, `mitigation` inside the effect) become rules of the same source gated by `includes [pluck actor.effects label] <effect name>`; `endEffect` resolves to an effect declared by the same source.
- **Spellcast difficulty.** `spellcastDifficulty` → a `spellcast-roll` button that resolves a spellcast-trait roll with that difficulty and summarises it with `roll.check` (total, difficulty, kept hope/fear faces, `dominant`, `critical`, `outcome`). `automaticRestUse` reserves the source `uses` budget and refunds it with `limit.modify` when the outcome is a failure; `automaticRestUseOnAttempt` keeps the use on every attempt.
- **Modifiers.** Signed whole-number or computed-token `modifiers` (`thresholds` → both thresholds, `traits` → all six, `roll: X` → `roll.X`). Passive `traitValue` reads the modified trait before applying its divisor and rounding; this covers Untouchable. Legacy effect `traitValue` stays dynamic, whereas started format-2 effect values freeze, so the converter diagnoses that effect form rather than changing its behavior. Marked-HP divisors and marked-HP/Stress multipliers become computed values, `min`/`max` clamp them, and `modifiers`/`conditionalModifiers` conditions (`hopeMin`, `requiresArmor`, `armorFull`, `requiresEffect`, `loadoutDomain` via `count` over `actor.loadout`) become the passive modifier's `when`. `conditionalActions`/`conditionalRollOptions` convert exactly like `actions`/`rollOptions`; their `loadoutDomain`, `requiresZeroHope` and roll-option `condition.{markedHp, stressFull, unmarkedHp}` gates become rule `when` terms.
- **Roll options.** `roll.prepare` `option` rules with `roll.contribute` chained into `roll.publish`: `value`, `dice`, `advantage`, `exclusive` (? `exclusiveGroup`), `defaultOn`, `weapon` and roll filters (`attack`, `damage`, `spellcast`, `action`, `reaction`, `trait:<Trait>`, `duality`, `companion`, `spelldamage`, `card:`/`feat:`/`featureAction:` origins, `any`), `excludeRolls`, `weaponRange`. `condition.unmarkedHp` (`min`/`max`) compares HP capacity minus marked HP. `quantity` becomes a bounded integer input `quantity` (1 to the available resource, capped by the legacy `max`) multiplying cost amounts, flat values and dice counts. `diceResource` becomes the dice contribution's `role` so later rules can select those dice by `die.role`. `hopeDie` resizes the Hope die (`roll.selectDice` on `die.role = hope` then `roll.resize`). Damage-roll `proficiency` adds that many of the attacking weapon's base dice (`die.sourceId = event.check.weaponSourceId`, unavailable for fixed-dice weapons); attack-roll `proficiency` starts a declared pending buff that an `automatic` rule spends on the next eligible weapon damage roll, and a failed attack ends it unused. Attack-roll `damageDie` uses the same pending pattern to `roll.resize` the next damage roll's weapon dice. `traitValue` adds a flat contribution reading `actor.stats.trait.<Trait>`. `multiplier` (2-10) adds a `scale` contribution with `of: "all"` at binding `priority` -100 so it multiplies the finished roll, in the `roll-multiplier` exclusive group unless `exclusive` is set. `required` makes the option `automatic` (no choice, costs paid as its first steps) and, on weapon sources, limited to rolls whose `event.check.weaponSource` is that instance. Roll triggers' `primaryWeapon` requires `event.check.weapon = primary`; `anyWeapon` adds no weapon restriction. Roll triggers also convert `advantage` (with a duality reroll: an added d6 advantage contribution), `giveFear` (a `table.gain` host request for the `fear` table resource, also on mitigation) and one-die `check`s (`dice.roll` plus outcome steps guarded by `if` on `success`/`unless`; `choices` become a `choice` input whose option steps are additionally guarded on the selection; actions convert `check` the same way). Scaled option dice (`diceByLevel`, `diceByFeature`, `diceConditions`, `@tierdN`) become `if` expressions over `actor.stats.level`/`actor.stats.tier` and the `actor.features`/`actor.heritage` labels for the contribution's `count` and `sides`; `magicOnly` gates on the optional `event.check.damageType` (`magic`) or `event.check.spell`; `weaponRange`/`weaponRanges` compare `event.check.range` against profile range IDs (`melee`, `veryClose`, `close`, `far`, `veryFar`) and exclude spells; `ignoreDisadvantage` runs late (priority -90) and uses the generic `roll.discard {from, role}` op to drop `disadvantage` contributions. `roll.annotate {from, field, value}` sets a scalar roll metadata field. A weapon's `damageTypeChoice` becomes a `magic-damage` option on that weapon's damage rolls that annotates `metadata.damageType = magic`; the host must treat roll `metadata.damageType` as the dealt damage type. `allResourceDice {resource, sides}` adds one die per point in a declared pool and is unavailable while the pool is empty; option `steps` (for example clearing that pool) run after the contribution is published. `matchingDice` selects kept damage dice whose generic `die.matches` (other kept dice of the same role sharing the face, exposed in `roll.selectDice` predicates) is above 0 and contributes their face total at priority -80, after other options add dice. `resourceMultiplier {resource, value}` contributes the pool value times `value` as a flat bonus and is offered only while the pool is above 0. A trigger's `swapDuality` uses the generic `roll.swapRoles {from, first, second}` op (exchanging the Hope and Fear roles) and is gated on a non-damage roll whose Hope and Fear dice differ. `repeatable` is accepted metadata: once-per-roll offering is the host's per-roll claim, not a rule field. `excludeRolls` and roll filters accept `card:`/`feat:`/`featureAction:`/`option:` origins, and `hideSpellcast` is accepted display metadata.
- **Consumables and limits.** Top-level consumable sheet actions use up one unit (`inventory.consume` cost) unless `keepItem` is set; `portions: <pool>` instead consumes a unit and refills the pool to its `initial` only when the pool reaches 0. `minimumHope` becomes `gte(actor.hope.value, n)`. `refreshUses` (`short`/`long`) adds the generic `limits.refresh {on, label}` outbox op: the host clears used limits whose `resetOn` matches those reset events on every other source of the actor (pools are untouched). `@<pool>dN+M` damage dice read the pool value as the dice count and require it above 0. `transferHope` (one-ally target only) adds an `amount` Hope input, spends it from the actor and gains it on the recipient.
- **Effect endings and stacking.** Legacy `unique` becomes effect `stacking: replaceAnySource` (starting it removes the same effect from every source instance; legacy also limited the effect's roll options to the first matching one, which is not replicated). `exclusiveSource` becomes `singleHolder: true`: starting the effect appends an `effect.endElsewhere {sourceActorId, sourceInstanceId, effect, holder, label}` outbox request, and the host removes that source's effect from every other saved actor. `endOnMarkedHp` ends the effect on `damage.applied` with at least one Hit Point marked; `endOnDealtDamage` ends it when its holder finishes a damage roll. Effect roll modifiers that are used up (`consume`), add dice, or filter a trait roll (`trait:Agility`) become automatic roll options gated on the effect (and ending it when used up); a used-up bonus requires every modifier of that effect to be used up. Such effect-granted options are never restricted to the granting weapon, because the effect holder may be an ally.
- **Roll triggers** and **conditional roll triggers** — `roll.finished` `button` rules: `reroll` (`hope`/`duality`), extra `damage`, `steps`, `when` (`hope`/`fear`, `hopeOrCritical`, `success`/`failure`, `hopeDie` with `die`, `always`), `successIfKnown`, explicit `difficulty` for known-success checks, `outcome`, `failedOnly`, `criticalOnly`, `weaponAttack`, generic `on: trait`, `audience`; `exclusiveRollGroup` maps to a binding exclusive group so only one selected button in that group executes. Trigger `addDice` (`NdM`, `@tierdM` or a flat number) becomes an added `roll.contribute` (damage role on damage rolls, modifier otherwise). `damageDice` (damage rolls only) becomes a `die.select` input over the non-duality dice of `event.roll` (selected with a per-die `where` predicate): `reroll` takes 1..32 dice (`min: 1`) and rerolls them with `roll.reroll`; `double` takes one die and adds its face as a flat damage contribution via the `roll.diceTotal` op. `die.select` inputs accept `where` instead of `eligible` and an optional `min`, so the player may pick between `min` and `count` dice. Block `hopeReward` (`resource`/`favor`/`removeFear` with an `amount` and a legacy `roll` filter) becomes a `roll.finished` self-claim button in claim group `roll-reward` (recipient `sourceActor`), available on non-critical Hope-dominant rolls that match the filter, excluding reactions and known failures; `removeFear` emits the generic `table.spend` host request for the `fear` table resource and the others `pool.gain`. The ruleset's base Hope reward must share the `roll-reward` group so only one reward is claimed. `classResourceOnly` sets pool `section: "class"` (pools accept `section` `feature` or `class`, a display hint for the class-resources section). `cardOnly` sets `section: "feature"`. `whileEffect` resolves the legacy effect label to a declared effect id and sets the pool field `whileEffect`; the pool is cleared when its last supporting instance on the holder ends. `usesOverrides` (`feat:<Name>`) become top-level `limitOverrides` (`{feature, limit, max}`); the host exposes `actor.limitOverrides` and the runtime uses the highest max across active sources. `patronDice` (`heal`/`ire`/`vengeance`) maps to `dice.hits` (4+) or the new `dice.highest` op over `6 + 2*min(1, floor(level/5))`-sided dice. Witch grants/actions remain unconverted. Cross-source `endEffect`/`requiresEffect` labels that no local effect declares compile to the generic `effect.endLabeled` op (`{label, target}`), which removes every effect on the target with that label regardless of source. `@fear` compiles to the host-supplied read-only fact `event.table.fear`; the live host must supply it on every event.
- **Armor slot states** — `armorHalfEffect`/`armorFullHeldEffect` become derived effects whose `while` compares `actor.armorTrack.marked` with its capacity; `armorFullEffect`, `zeroHopeClearArmor` and `overflowHope` become automatic `track.changed` rules. The `track.changed` payload is `event.track` (actor field), `event.before`/`event.after` snapshots, `event.change` (`mark`/`clear`) and `event.requested`; the host does not dispatch it yet. `criticalMelee` becomes a `roll-reward` self claim plus carried Proficiency damage.
- **Weapon damage handling** (block `damage`) — one `roll.finished` `automatic` rule filtered on `event.check.kind = damage` and `event.check.weaponSource = source.instanceId`, editing dice whose source and contribution ID match the host-recorded weapon base pools: `extraDice` → `roll.addDicePerPool`, `dropLowest` → `roll.dropLowestPerPool`, `explode` `once`/`repeat` → `roll.explode` (once skips exploded dice), `onesAs` → a flat contribution of `(N-1) times ones`, `maximumOnes` → `roll.setFaces` to `die.sides`, `onesBonus` → a dN dice contribution per one, `proficiencyOnly` → `event.check.fixedDice = false`, `label` → the contribution/rule label. **Alternate profiles** → source `profiles` (legacy ranges become `melee`/`veryClose`/`close`/`far`/`veryFar`; `1d8+3` becomes `{sides:8, flat:3}` with Proficiency dice; `phys`/`mag` become `physical`/`magic`).
- **Rest.** Rest allowances, passives and move modifiers convert to generic preparation, move and completion
  rules, preserving initial and cleared facts, filters, costs and ordering around the reset. Selected `reroll`
  passives become `restRollPolicy: "keepHigher"` preparation options; unusual deferred combinations are diagnosed.
  `chooseAlly` emits a claim, move modifiers adjust the plan, and effect-carried rules apply while active.
  Self and targeted rests, party recovery, post-outcome choices and supported host commands are live; see the
  implementation status for remaining phase and cross-holder restrictions.
- **Attach.** A legacy `attach` block becomes a top-level `attachment`: legacy blockers become ordered requirements with the same reasons (generated weapons, `noFeature` as `not gear.hasFeature`, `range`/`damageType` equality, a second trait attachment, `nameIncludes` as `containsText` alternatives). Nested automation converts normally, and its weapon identity reads (`source.id`/`source.instanceId`) are retargeted to `source.attachedTo.*`. Gap: the host does not yet move items onto gear, supply gear facts, show the granted feature, apply the trait or activate attached programs.
- **Channels and auras.** `channelBenefit` becomes a derived effect whose `while` tests that the required channel effect label is active (aura benefits named "Aura: Element" also require "Channeling: Element"); a legacy effect `group` becomes `exclusiveGroup`; `endOnDamage: N` becomes an automatic `damage.applied` rule that ends the effect when `event.applied.severity >= N` (`endOnSourceSevere` uses severity 3, `endAfterIncomingDamage` uses `event.applied.incoming >= 1`); a mitigation option with `preserveAfterIncomingDamage` becomes a `damage.prepare` button that sets a declared `keep-<effect>` state slot, and the damage ending skips `effect.end` while that slot is set and then clears it (prepare-time choice because automatic rules run before selected options); effect `damage` (one extra Proficiency die) becomes an automatic `roll.finished` weapon-damage rule gated by the effect; an `aura` action is available only while an effect in the `channel` group is active and starts a marker effect lasting until the next rest labelled with the action; `grantsFeatures`/`groupGrantedFeatures` become `grants`. Known fidelity gap: the aura marker lasts until rest, so switching channels within the same rest shows the aura for the new element, whereas legacy ended the aura with its channel.
- **Reloading.** A `reloading` block (`dice` `1dN`, `fail` face) becomes one declared debuff effect "{source.name}: Unloaded" and three rules: an automatic `roll.prepare` rule that blocks attacks with this weapon while the effect is active, an automatic `roll.finished` rule that rolls the reload die after each non-reroll attack with the weapon and starts the effect when the face equals `fail`, and a sheet **Reload** button that costs 1 Stress and ends the effect. Two generic constructs support it: the `event.block` operation (`reason` template) appends `{sourceId, sourceInstanceId, reason}` to `event.blocked`, and any non-cost `do` step may carry an `if` expression that skips it when false (a conditional step may not name a result with `as`). Host contract: attack roll events publish `event.check.weaponSource` (the weapon instance) and `event.check.reroll` on rerolls, and the host refuses a `roll.prepare` whose `event.blocked` is non-empty. Gap: the host does not yet dispatch `roll.prepare` or enforce `event.blocked`.
- **Action timing.** `timing: downtime` or `rest` makes the action a `downtime.move` button on the `downtime` surface; the editable sheet renders a generic *Downtime* automation panel next to the *Automation* panel. Other timings produce a diagnostic.
- **Sent rolls** (`sendRoll` on actions, effect-granted actions and conditional roll triggers) — one `roll.resolve` step (`as: sentRoll`) with a literal plan: a fixed (`1d6`) or computed (`@tierd20`, `@spellcastd6`, `@total_proficiencyd6`, `@Agilityd6kh1`) dice count that never goes below zero, optional keep-highest, a bare computed value (`@tier`, `@armor_available`, `@armor_score`) as a flat contribution, and `base: evasion` as a flat contribution from `actor.stats.evasion`. Trait counts read trait totals; `@spellcast` dice also require a positive projected Spellcast trait. The report starts with the roll label and total; a legacy `{result}` placeholder in the authored report becomes `{result.sentRoll.total}`. Keep-highest is clamped so zero dice keep zero.
- **Mitigation.** `damage.prepare` rules using `damage.reduce` and `damage.publish`: `negate`, `halve` (floor) or `reduce`; `when.type`, `when.severity` (minor/major/severe/massive from `event.thresholds`; massive is at least twice the severe threshold), `when.hpUnmarkedMax`; `passive` → `automatic`, `defaultOn` → `option`; `effect` starts a declared effect.
- **Death moves.** A `deathMove` declaration converts by `kind`: `hopeBonus` becomes a required roll option on `death`/`death:risk`/`death:avoid` rolls (`event.check.kind == "death"`, `event.check.move`), `mask` a paid optional death-roll option, and `hopeSpend` actions a Hope-quantity death-roll option. Alternative moves (`battleHardened`, `unbreakable`, `egg`, `spectral`) and effects marked `lifeWard` become `death.options` buttons on the `death` surface built from ordinary operations (`resource.spend`, `track.clear`, `dice.roll`, `collection.remove`/`collection.add` from loadout to vault, `inventory.consume`, `effect.start`/`effect.end`). Spectral Form records the HP marked when it starts in a state slot; `track.changed` rules on `hp` end it when HP is cleared further or the last HP is marked, block the button at the last HP and reopen it after healing. `undying` (Won't Stay Dead) is a self-scoped `roll.finished` claim on a failed non-critical Risk It All that lowers HP capacity by one and annotates the roll with `deathOutcome: "hope"`. `manual` notes are appended to the source's button and option descriptions. Host contract: core death rolls dispatch `roll.prepare`/`roll.finished` with `check.kind: "death"` and `check.move`; flat modifier contributions adjust the Hope result; `deathOutcome: "hope"` overrides a failed Risk It All; `death.options` buttons replace the core moves; loadout entries are identified by source instance ID; and `track.changed` for `hp` is dispatched after each committed HP change.
- **Creation allowances and downtime moves.** A block-level `creation` object (`hp`, `stress`, `evasion`, `domainCards`, `experienceBonus`; whole numbers from -8 to 8) becomes one automatic `creation.apply` rule of `plan.adjust` steps on `event.creation`; the character builder publishes `event.creation` with the base values and an `adjustable` entry per field. An action `downtime` body converts on a `downtime`/`rest` timing: `rest` filters on `event.move.rest`, `additional` lowers `event.move.slots` by one (the host publishes `slots`, default 1, adjustable from 0) so the move does not use a downtime slot, `consumeItem` becomes an `inventory.consume` cost, and `craft` becomes an `inventory.grant` of that consumable (at most 5 carried by the actor). `table` (one die with one outcome per face, optional `repeat` summing dice, integers and traits, clamped 0-100) becomes a `dice.table` step reported as `rolled {result.table.faces|list}: {result.table.outcomes|list}`; `forage` tables grant the foraged consumable of faces 1-5 (tag `foraged`, at most 5 across the party) and on a 6 offer a one-claim group of five `claim.accept` rules, one per foraged consumable. Host contract: `inventory.grant` requests resolve `catalog`/`entry`, add `quantity` to the actor's or recipient's inventory (merging stacks) and, when `max` is present, reject the transaction if the carried quantity of items with the same `tag` (or the same entry without a tag) across `scope` (`actor` or `party`) would exceed it.
- **Token effects.** An effect `tokenResource` (a declared resource ID) becomes `endWhen: {"lte":[{"read":"actor.pools.<id>.value"},0]}`, so the effect ends with its last token; an unknown resource is a diagnostic.
- **Damage option extras.** `double` becomes a prepare-stage `damage.increase` by the current amount. `recoveryChoice` becomes an applied-stage button (even when `passive`) with a `recovery` choice input (`hope` → gain 1 Hope, `stress` → clear 1 Stress). `reflectHp` reports "{source.name}: the attacker marks {event.applied.hitPoints} Hit Points." for the table to apply. `quantity: true` needs exactly one resource cost: it adds an integer `quantity` input bounded by the affordable units, multiplies the cost and supplies `roll.count: "quantity"`. A paid option with no mechanical change reports its label so the table resolves it.
- **Action outcome tables.** An action `table` (`dice` `1dN` with 2-100 sides; ordered `outcomes` of `{min, max?, text, steps?}` covering every face without gaps) becomes one `dice.table` step (`as: "table"`) whose outcomes repeat each text once per covered face; each outcome's converted steps are gated with `if` on `at[result.table.faces, 0]` (`eq` for one face, `and[gte, lte]` for a range), and outcome steps cannot roll amounts. The default report is `{source.name}: rolled {result.table.faces|list}. {result.table.outcomes|list}`. `gain scars` becomes `resource.gain` on `actor.scars` capped by `min[amount, actor.hope.max]`; the Daggerheart projection maps the top-level sheet `scars` as a writable value fact (default 0; value facts may declare a plain `default` used when the sheet field is missing). Host contract: after writing `scars`, the host recomputes the available Hope slots (total minus scars) as for death-move scars.
- **Slot modifiers, action endings and trigger extras.** A passive modifier with a `weapon` slot becomes an automatic roll option filtered by `event.check.weapon` (the equipped weapon in that slot, not this source's weapon) and labelled `{source.name}`. `excludeRolls: spelldamage` excludes spell damage rolls. Effect `endOnAttack`/`endOnAction` become `roll.finished` endings for the holder's attack rolls, or for its attack, action and spellcast rolls (reactions excluded). Roll triggers support `disadvantage` rerolls (a d6 contribution with sign −1), `removeFear` (a `table.spend` of GM Fear reported by default) and `weaponDamage` (non-spell weapon damage only). A `gold` cost becomes `resource.spend` on `actor.gold`: `resource.spend`/`resource.gain` accept a plain numeric actor fact as an uncapped pool that is written back in place, and the projection still enforces writability and bounds.
- **Resource rolls.** `resourceRoll` (actions and roll triggers) becomes a bounded integer `amount` input (max: available Hope, gold or free track slots), a `track.mark`/`resource.spend` cost of `input.amount`, and a `roll.resolve` of `input.amount` dice with an optional `multiply(input.amount, bonusPerDie)` flat contribution. A typed roll is a fixed-dice damage roll stored as `resourceDamage`; an untyped one is a plain roll stored as `resourceRoll`. A generated report line is prepended to any authored report.
- **Roll sections and die faces.** `rollSection` becomes the binding `group` (`result`, `sourceFeature`, `otherFeatures`); `containsDie: N` becomes `includes(pluck(flatten(pluck(event.roll.contributions, "dice")), "face"), N)`. The unary `flatten` expression concatenates array elements one level.

Duplicate rule IDs within a block are made unique (`use`, `use-2`, ...). Template braces in legacy text are escaped. A rule may omit `do` when it only pays costs, consumes a limit or reports. Tests: `LegacyCatalogConverterTest` compiles inline fixtures and the shipped Warden's Protection block, then executes them through `RuleDispatcher`; the latter also has a persisted HTTP transaction test. The browser and replacement host share a deterministic Warden report-and-reset fixture, but live multi-character rest integration remains unfinished.

**Event context contract.** Converted rules read these facts, which the host must supply with every key present (`null` when not applicable), because reading an absent value is an error: `event.roll`; `event.check.{kind, trait, weapon, range, dominant, outcome, critical, hopeDie, spell, origin, total, difficulty, weaponSource, weaponSourceId, fixedDice}` (`weaponSource` is the attacking weapon's source instance ID and `weaponSourceId` its source ID, which base weapon damage dice carry as `sourceId`); `event.damage` (structured damage) and `event.damageType`; `event.thresholds.{major, severe}`; actor `effects` instances carry `label`. Selected options run in dispatcher order and chain: each `roll.publish`/`damage.publish` replaces `event.roll`/`event.damage` for the next rule.

Dry run on the shipped pack: 1,013 of 1,087 automation blocks (93.2%) convert; the target is 100%. Duality-die rerolls (`rerollDualityDie`: hope or fear) now convert to a `roll.reroll` on that die role. A report `amount` that is a trait or pool reference is evaluated by a leading `value` step and shown from `{result.reportAmount}`. Deathrun-style repeated damage converts to the shipped recipe `core:repeat-saved-weapon-damage` (`rules\recipes.json`), and `mark all` on a track converts to capacity minus marked. Trait-derived modifiers (`traitValue` with `divisor` and `round`), `toggleEffect` (end when present, start when absent), `damageDiceBelow` reroll limits and `spendHopeAmount` convert to ordinary expressions, conditional steps and inputs. Multi-source families convert too: typed rollOption input (integer input with optional constant default), specialAction (mass disguise, night terror and vitality as ordinary countdown/table/choice/capacity steps), conditionalDamageRewards (automatic damage.applied rules), @fear dice (offered only while table Fear is at least 1) and maintainOnAction (an automatic `roll.prepare` rule that ends the effect when Hope is 0 at roll time). Still unconverted: `ancestryChoice` (needs a catalog-backed ancestry picker that writes sheet data) and the remaining singletons. Fixed-trait, highest-trait, traitless and spellcast-trait action rolls; direct fixed/proficiency-/spellcast-scaled damage actions; spellcast-scaled damage contributions to roll triggers; and legacy target modes now convert. Target modes become JSON target scopes/cardinality and recipient-scoped operations; the isolated dispatcher executes single-target and all-eligible-target transactions. Replacement rules are host-authoritative by default, so the converter consumes `hostOwned` from actions, options, roll triggers, conditional triggers and mitigation rather than preserving a runtime flag. Spellcast actions use a generic first-match projection and are unavailable if no spellcast trait is projected. Remaining action-roll gaps include `conditionalRollTriggers.actionRoll` and beastform selection/behavior; beastform-specific actions/fields, separate spellcast-difficulty rules and rest rerolls remain open. Damage actions and trigger contributions produce typed rolls only and do not apply damage to targets. Conditional triggers convert when their individual fields are supported; domain-specific features such as `loadoutDomain` and damage type still produce diagnostics. Projection `stats` now supply tier, trait totals, spellcast, Proficiency, evasion, armor score/availability and thresholds. Severity-stage mitigation still needs a post-severity damage event; target execution outside the sheet action endpoint remains outstanding. Chat benefits become one `claim.accept` rule per benefit plus a `claim.offer` step on the offering rule: bindings declare `claimScope.recipients` (`otherPlayersCharacters`, `playerCharacters` or `sourceActor`) and optional `excludeSource`, and offers may publish a per-group claim limit (exclusive benefits limit the offer to one claim; per-amount offers allow the selected amount minus one). Offers are scoped to the offering source instance; cross-source benefit injection (`actionBenefits`) and follow-up feature actions remain diagnostics. The live host records and enforces offer limits; unsupported claim operations still reject the claim.

Witch family: `witchGrant` markers for Circle of Power, Moonbeam and Forest Sprites become plain marker effects plus a `join` claim that starts a benefit effect on the claimer; the sprite pool becomes a shared `scope:source` pool read through `sourcePools` (effect `endWhen` may read `sourcePools`). The Enchanted Talisman, Rune Ward and Lifeblood Talisman are real inventory items (as in legacy) so players can hand them over like any item: the witch action emits `inventory.grant` into the `items` catalog for the actor (talisman quantity = tokens spent), and the items carry their own mitigation in `items.json`. The pseudo-resource `@item` in a cost or `destroyResourceOnMax` means the carried item: it becomes `inventory.consume` and reads `source.quantity`; rest `expireItem` consumes the whole stack on the holder's next rest. Known gaps: the talisman expires on the holder's rest, not the creator's; Lifeblood's end-on-no-marked-HP is not modeled; moving item state between sheets needs live-server wiring. `effectCounters` + `lunarPhases` become a 0-6 die pool with one derived (`while`) effect per phase; `lunarRoll`/`lunarAdvance` only change that pool. Mitigation `destroyResourceOnMax` and `reportRoll` convert to a conditional `pool.spend` and a roll report.

Companions: a companion declaration becomes a generic pool of unmarked companion Stress slots (companion-stress-slots, marking spends it, zero means unavailable), a long-rest rule that returns an unavailable companion and clears 1 Stress, and one counter pool companion-<option id> per plain training option (for example companion-armored). `requiresCompanion` and `companionIntercept` become when gates on the slot pool; an intercept pays one slot as its cost and the Armored variant pays an owner Armor Slot instead (the marks-stage restriction is lifted for it). Stat and choice training options (Resilient, Aware, Vicious, Intelligent, Light in the Dark), companionTraining entitlements, the Bonded rescue and Creature Comfort remain sheet data or unconverted: the level-up sheet must raise the slot pool itself.

Run the focused checks from the repository root with `mvn -q "-Dtest=RuleConformanceTest,StructuredRollTest,StructuredDamageTest,RuleRuntimeTest,RuleCatalogTest,PoolRuntimeTest,RuleDispatcherTest,CrossActorClaimsTest,EffectRuntimeTest,RollEditRuntimeTest,InputRuntimeTest,LifecycleRuntimeTest,TargetActionsTest,LegacyCatalogConverterTest,AutomationHostTest" test`; run `npm test -- --run src\automation` from `web`. `RuleCatalog.main` also accepts `<catalog.json> <recipes.json>` with the project's Java dependency classpath for read-only candidate validation. It does not convert or overwrite files.

**Still required:** behavior-level baseline/parity fixtures; all catalog families and shared ruleset; authoritative HTTP intents, multi-actor persistence and protected sheet writes; live roll/lifecycle event wiring, effects, passive rules, general roll calculation/edit phases, shared actor-pool persistence/concurrency and remaining inputs/operations; all UI/editor surfaces; one-time backed-up catalog/saved-game conversion; compatibility negotiation and coordinated removal of old handlers. `docs/automation-parity-ledger.csv` inventories all 1,087 converter-recognized blocks with source pointers, fields and dry-run diagnostics; `docs/automation-parity-baselines.md` maps legacy tests and captured scenarios. The 1,013 `converted-candidate` rows indicate conversion acceptance only. No row has verified replacement parity.

## What exists today

A recursive count of `automation` objects in the nine maintained catalogs gives **1,126 blocks across 1,219 entries** (1,125 nonempty blocks), excluding keys starting with `_` such as `_editorOriginal`. The converter ledger counts **1,087**: it counts an outer automation block once, including when its owner is a feature or grimoire spell. Inside those blocks are another 39 automation objects: 29 on beastform features, two on features of effects granted by those features, one on a companion option and seven on attachments. They are part of the parent block's conversion and parity obligation, not 39 extra converter successes. The recursive inventory has 58 distinct direct keys, including lists with 620 actions, 210 roll options, 103 result triggers and 82 mitigation options. Those list totals exclude conditional lists and controls created at runtime.

| What the implementation shows | Why it matters for authoring |
|---|---|
| [Deathrun's catalog entry](../dmmap-daggerheart/domain-cards.json#L1843-L1865) specifies the price and some labels, repeats the same effect under the sheet action and attack option, and sets `deathrun` / `deathrunResult`. | Copying it copies a switch into a built-in algorithm. The author cannot choose a different initial bonus, continuation policy, or continuation label there. |
| [Result buttons](../web/src/daggerheart/rollbuttons.ts#L542-L559) construct **Continue Deathrun**. The [damage dialog](../web/src/daggerheart/view.ts#L2434-L2446) builds the die selector; its [preview](../web/src/daggerheart/view.ts#L2533-L2562) recognizes multiplier rows by the display names `Smite`, `Invoke Torment` and `First Strike`. | This is not just a translation problem. Changing the label of an unrelated bonus can change the preview and the math. Buttons, previews and execution need structured roll provenance. |
| [OnlineSessionServer](../src/main/java/dmmt/online/OnlineSessionServer.java#L3237-L3248) checks the active effect and counter. [BoneValorRules](../src/main/java/dmmt/online/BoneValorRules.java#L78-L155) saves mixed-pool dice, handles maximized faces and recomputes damage; [the server](../src/main/java/dmmt/online/OnlineSessionServer.java#L3358-L3378) stamps chat and commits the sheet. | The original dice, activation identity, costs, retries and resulting chat cannot be left to client configuration or reconstructed from text. |
| [Prayer Dice](../dmmap-daggerheart/classes.json#L3879-L3887) declares a pool, but [the browser](../web/src/daggerheart/engine.ts#L622-L645) invents its per-die actions and labels, [sheet loading](../web/src/daggerheart/persist.ts#L131-L132) rewrites labels, and [the host](../src/main/java/dmmt/online/OnlineSessionServer.java#L4225-L4239) synthesizes the spend trigger. | A second rolled pool needs its **own** controls and lifetime. Two equal faces must remain different spendable dice, not two indistinguishable `+3` actions. |
| [`ActionDef`](../web/src/daggerheart/engine.ts#L144-L249) mixes costs and general steps with `splendorAction`, `witchAction`, `patronDice`, `copycat` and similar fields. [SpecialCardActions](../src/main/java/dmmt/online/SpecialCardActions.java#L14-L76) dispatches on named workflows. Some [spell controls](../web/src/daggerheart/view.ts#L2041) still depend on prose. | Neither adding more flags nor renaming them to `handler: "deathrun"` achieves the goal. Explicit events, operations and placement must replace all three forms of dispatch. |
| [Result matching and grouping](../web/src/daggerheart/engine.ts#L1825-L1889) interpret comma-separated source tags and sometimes names. [HostCatalog](../src/main/java/dmmt/online/HostCatalog.java#L96-L142) indexes names; [the editor and sheet links](DAGGERHEART_DATA.md#guided-dm-editor-and-live-catalog-links) already maintain entry IDs, feature identities, revisions and override baselines. | Build on existing identities, revision checks and the guided editor. Don't make visible names, button order, array positions or localized text into foreign keys. |

### What “fully configurable” should mean

An author can add or duplicate a feature, change its costs, triggers, available choices, calculations, state, expiry, placements and **all feature-specific text** by editing the configured catalog. No browser build, Java change, capability named after the feature, or database migration for a new combination of supported operations. One declarative definition drives sheet, roll dialog, chat, quick access, resource chips, effect rows and the DM's read-only chat action summary where applicable.

The engine still owns *how* to roll and identify dice, render an accessible picker, validate ownership, apply a transaction and synchronize it. Put **game policy** (including base reward, rest and death-move definitions when migrating those systems) in ruleset JSON; do not make persistence, visibility/authorization, or dice limits editable game rules. An entirely new kind of input, die operation, or world fact may require one new generic capability in code. Narrative prerequisites such as range, a defeated adversary, or an unknown hit are not facts the engine can invent. Model them as a visible instruction, an explicit choice, or an assertion on the existing action, without an extra confirmation by default.

## Proposed authoring format

Keep the nine category files and keep rules next to the entry, feature or spell that owns them. **Remove per-block `schemaVersion`: there is only one supported automation format.** Use one format version for the complete catalog/rules pack, declared in its file headers: the rewritten catalogs use the existing envelope's `version: 2`, and shared rule files declare that same format version. This is a validation/migration marker, not a choice between runtime parsers. The normal loader rejects older or mixed-format packs with instructions to convert them; only a separate one-time converter understands the old data. Changing a label or price does not change the format version.

Add a reserved `rules/` subdirectory under `online.daggerheartDirectory` for the shared `ruleset.json` (default rules, text, tracks, event phases) and `recipes.json` (optional compositions). These are not new compendium categories or executable plugins. Update the loader, editor, revision/invalidation and packaging explicitly before using them. Ordinary entries should need only the category file and inline labels. Provide an **Expand recipe** view so nobody has to guess what a preset does.

| Authoring concept | Meaning |
|---|---|
| `pools`, `limits`, `effects` | Declare state: numeric resources or collections of individually identified dice; a use budget with an owner and reset events; an effect definition with stacking and expiry. Separate from current values, which belong to instances on the host. |
| `rules` | Each has a stable local `id`, one or more `bindings`, optional `when`, `inputs`, `costs`, `limitRef`, ordered `do` operations and `ui`. A passive constant modifier can use a short form; the compiler expands it into a contribution in a defined phase. |
| `bindings` | Each says **when**, **where** and **how**: typed `event`, `surface`, `mode` (`button`, `option`, `automatic`, `passive`), optional selection default, actor/audience filter and chat `group`. A sheet button and a paid attack option can bind to the same rule; only the selected binding executes. |
| `when` / `inputs` / `do` | `when` reads facts without side effects. An input declares a themed picker: enum, quantity, eligible actor, allocation, trait, eligible die(s), catalog entry or effect instance. `do` changes state using registered, typed operations; it never runs client-supplied steps. Eligibility is checked again after selection. |
| `ui` | Plain-text templates for labels, descriptions, tooltips, accessible names, disabled reasons, input labels, state badges, previews and chat/reports. Binding-specific overrides take precedence over rule text, then ruleset defaults. Text is never an ID, a dice expression, a trigger or an input value. |
| `useRecipe`, `args` | Compile-time composition of the same fields, with typed required/default arguments and local IDs scoped to the use site. Recipes can be opened, copied or inlined. No Java/TS switch on recipe ID; no implicit deep merges of action arrays. |

**Identity.** A definition reference is `(category, entryId, nested feature/spell ID, local ID)`; `self`, `owner` and local references are scoped shortcuts. Promote/reuse `_catalogFeatureId` where present; mint and persist IDs once for missing features and spells. A duplicate receives a fresh definition namespace, with local references rebound and explicit external references left intact. Resources intended to be shared, such as Warlock Favor, use explicit shared IDs. A *source instance* (equipped item, learned or borrowed card, etc.), an *activation* of its effect, a *roll*, and a *die* each have their own identities. Two casters or a copied card must not share state merely because they share a definition.

**Expressions and templates.** Use literals or typed JSON expressions, e.g. `{"read":"actor.derived.proficiency"}`, `{"add":[1,{"read":"input.amount"}]}`, `{"gte":[{"read":"event.finalHpMarked"},1]}`. Allow bounded arithmetic, min/max/clamp, explicit rounding, comparisons, boolean combinations and typed collection queries. Distinguish base/derived, marked/free, original/current roll, before/after and requested/applied values. Use a dice spec for random values, sampled at the declared resolution phase, never while rendering. No JavaScript, reflection, arbitrary sheet paths, remote `$ref`s or script escape hatch.

For text, use **one** syntax: `Continue {source.name} ({state.remainingCount} dice left)` or `{source.name}: {die.face|signed}`. Resolve only declared, audience-visible context (`source`, `actor`, `recipient`, `event`, `state`, `input`, `die`, `result`), plus a small formatter list (`signed`, number, singular/plural forms). Literal braces escape as `{{` / `}}`. Unknown tokens, wrong types and tokens unavailable on a surface are authoring errors, not `undefined` on a button. Optional values need an explicit fallback or separate template for that case. Render as text on browser and desktop; never interpret the result as HTML or mechanics. Snapshot the rendered report with its original audience at commit; derive live button labels from current state. Generic error codes and a safe default message remain available if a feature doesn't supply its own disabled reason.

### Example 1: a simple action needs no workflow

Proposed complete automation block for a one-Hope action clearing up to two marked Stress on the acting character. It has an independent limit, not a feature-wide one. `result.cleared` is the **actual** amount after capping.

```json
{
  "limits": { "recover": { "max": 1, "resetOn": ["rest.short", "rest.long"] } },
  "rules": [{
    "id": "steady",
    "bindings": [{ "id": "sheet", "event": "action.request", "surface": "sheet", "mode": "button" }],
    "when": { "gte": [{ "read": "actor.stress.marked" }, 1] },
    "limitRef": "recover",
    "costs": [{ "op": "resource.spend", "pool": "actor.hope", "amount": 1 }],
    "do": [{ "op": "track.clear", "track": "actor.stress", "amount": 2, "as": "cleared" }],
    "ui": { "label": "Steady {actor.name}", "report": "{actor.name} cleared {result.cleared} Stress." }
  }]
}
```

### Example 2: Deathrun and a mechanically different copy

What a catalog author should write for the original card (the repeating sequence is a **JSON recipe**, specified below):

```json
{
  "state": {
    "phase": "inactive", "activation": 0, "uses": 0, "weaponId": null,
    "firstDamage": null, "originalEligibleDice": [], "remainingDice": []
  },
  "rules": [{
    "id": "run",
    "useRecipe": "core:repeat-saved-weapon-damage",
    "args": {
      "startCosts": [{ "op": "resource.spend", "pool": "actor.hope", "amount": 3 }],
      "firstProficiencyBonus": 1,
      "removeEachTime": 1,
      "dieRole": "weaponBase",
      "minDiceLeftToDealDamage": 1
    },
    "ui": {
      "start": "{source.name}",
      "damage": "{source.name} damage",
      "continue": "Continue {source.name}",
      "end": "End {source.name}",
      "chooseDice": "Choose a weapon die to remove",
      "dieChoice": "d{die.sides}: {die.face}",
      "preview": "Remaining damage: {result.damage.total}. No new dice are rolled."
    }
  }]
}
```

For a homebrew **Splinter Trail**, duplicate the card with a new entry ID, keep the same recipe, and edit these fields:

| Path relative to the rule | Deathrun | Splinter Trail |
|---|---|---|
| `args.startCosts` | Spend 3 Hope | `[{"op":"track.mark","track":"actor.stress","amount":1}]` |
| `args.firstProficiencyBonus` | `1` | `2` |
| `args.removeEachTime` | `1` | `2` distinct original kept dice |
| `ui.continue` / `ui.chooseDice` | `Continue {source.name}` / choose one die | `Strike again ({state.uses} hits so far)` / `Choose two dice to remove` |

The new rule must **not** create an effect named Deathrun, look for its card ID, modify its counter, or remove its dice. Both may be learned or active at once. If several sequences qualify for the same damage roll, offer a mutually exclusive choice for that roll, not “first matching effect wins.” A continuation opened from chat is already bound to the exact activation.

**Production recipe expansion contract** (the names in the first column are recipe-local IDs, not engine operations). The executable fixture currently uses source-local slots and a supplied damage plan to exercise the calculations; effect instances, the ordinary-roll pipeline and transaction integration remain to be implemented:

| Rule | Bindings and guards | Ordered behavior |
|---|---|---|
| `start` | Sheet button, **or** optional weapon attack roll option. No live instance for this rule on this source. | Pay `startCosts` once, then `effect.start` on self, with manual expiry. Sheet action commits immediately; selecting the attack option commits with the attack, not when ticking the box. Both reference one effect definition. |
| `firstDamage` | Default-selected option on weapon damage while this instance has no snapshot; not spell damage. | `roll.contribute` the configured Proficiency delta to the ordinary weapon calculation (per proficiency-scaled pool; respect fixed dice). Resolve normal modifiers once. `roll.capture` final breakdown, weapon/profile identity, original kept die IDs matching `dieRole`, and dependency links. Start the sequence revision and publish a bound result. |
| `nextDamage` | Result-group button on the roller's latest matching result, **also** reachable via the active option in the ordinary damage dialog. Instance/revision and weapon must match. Require `remainingCount - removeEachTime >= minDiceLeftToDealDamage`. | Show `die.select` on remaining original IDs (Deathrun defaults to a lowest-valued die). No new bonuses or rolls. Apply `roll.derive` from the saved roll minus the **cumulative** selected IDs; publish a *new* result, keeping earlier messages immutable. Advance state once in the same commit. |
| `end` | Effect button or feature-group action on a result from this activation. | `effect.end` the referenced instance only. Stop offering Continue on exhaustion; release the spent instance before the next ordinary weapon damage dialog. A historical End action must never end a new activation. When targets run out first, End remains a player decision. |

For clarity, this is the proposed JSON *inside* the `nextDamage` rule, after binding `state` to the activation and `args` to the recipe arguments. `roll.derive` has **no knowledge of sequences**: it derives a roll from a snapshot with named dice omitted. It also works without effects or chat.

```json
{
  "inputs": [{
    "id": "remove", "kind": "die.select", "from": { "read": "state.firstDamage" },
    "eligible": { "read": "state.remainingDice" },
    "count": { "read": "args.removeEachTime" }, "default": "lowest",
    "label": { "$ui": "chooseDice" }, "choiceLabel": { "$ui": "dieChoice" }
  }],
  "do": [
    { "op": "set.subtract", "from": { "read": "state.remainingDice" }, "items": { "read": "input.remove" }, "as": "kept" },
    { "op": "roll.derive", "from": { "read": "state.firstDamage" }, "keepSelectedWithin": { "read": "state.originalEligibleDice" }, "keep": { "read": "result.kept" }, "as": "damage" },
    { "op": "state.set", "slot": "remainingDice", "value": { "read": "result.kept" } },
    { "op": "state.increment", "slot": "uses", "by": 1 },
    { "op": "roll.publish", "roll": { "read": "result.damage" } }
  ]
}
```

The preview evaluates `set.subtract` and `roll.derive`; it reads `result.damage.total` without publishing or advancing state. Each contribution records its stable ID, role, source, pool, sides, original face, kept/dropped/maximized status and whether a multiplier applies to a pool or the whole total. Recompute dependent contributions only; keep independent bonus dice and flats. Do not regex the displayed expression or search for a word like “Smite.” Example parity fixture: kept d8 faces `8,3`, d6 faces `6,2`, flat `+4`, whole-roll x2 gives `46`. Removing the d8 showing `3` gives `40`, regardless of how the multiplier row is labeled.

### Example 3: generated controls and claims

Prayer Dice is a different test: **one rule per stored die**, not one counter and not a special `prayer` widget. Declare a face pool with session lifetime, a once-per-session roll rule, independent samples equal to the configured count plus extras and `keepHighest` equal to the base count. Devout modifies the draw count by stable feature reference. Instantiate the following binding for each kept die; this fragment shows the chat use:

```json
{
  "id": "aid-roll",
  "bindings": [{ "id": "result", "event": "roll.finished", "surface": "chat", "mode": "button", "forEach": "pool.dice", "group": "otherFeatures", "visibleTo": "messageAudience", "invoker": "dieOwner" }],
  "ui": { "label": "Prayer die: {die.face|signed}", "description": "Spend this die to aid a roll within Far range." },
  "do": [
    { "op": "roll.addFlat", "roll": { "read": "event.rollId" }, "amount": { "read": "die.face" } },
    { "op": "pool.consumeDie", "die": { "read": "die.id" } }
  ]
}
```

The binding intersects the message audience with ownership of the die (self or ally's roll, including plain rolls). The header's **gain Hope** and **remove**, plus the incoming-damage **reduce by {die.face}** option, are other rules consuming the *same instance* after a successful operation. IDs disambiguate equal faces; indices need not appear on the buttons. Keep the roll and spend controls confirmation-free. A homebrew pool could instead expire on rest and offer only a damage reduction, with no Prayer text left over.

For an alternative Hope reward such as Ethereal Visage, bind to the owner's normal Presence roll with Hope, exclude reactions and criticals, require success if known and available GM Fear. Use a **shared claim group** with the base reward, `campaign.fear.spend` and a configured report. The button itself asserts success if the difficulty is hidden; it does not ask again. A party invitation is different: snapshot eligible recipients, configure one claim per player/actor as appropriate and bind to the source activation if required. Neither is the same thing as a once-per-rest use.

## Coverage and naming

This is the migration map, not a claim that the proposed operations have already been implemented. Use the existing [catalog field guide](DAGGERHEART_DATA.md#automation-block) and [living specification](../plan.md), but resolve differences against code, shipped data and characterization fixtures. Keep a field- and behavior-level ledger; top-level key coverage cannot prove runtime parity.

| Family to migrate | Reusable concepts needed | Cases that must survive |
|---|---|---|
| Source eligibility and passives | Source activation policies, filtered contributions, calculated stats, overrides and dependency order | Learned vs merely granted, equipped vs carried, positive stock, active loadout vs vault, nested spell identity, four-domain conditions, Bare Bones unarmored base; do not apply creation grants every load. |
| Actions, costs and limits | Explicit button/roll bindings; mark/clear tracks vs spend/gain pools; atomic aggregate cost; independent or shared use budgets and recharge rules | Simple potions, item quantities/portions, source use vs action/option use, deferred use on successful cast, Hope/Stress plus Experience and options, alternative costs. Cancel or no legal benefit must not spend. |
| Rolls and result edits | Typed attack/reaction/Spellcast/weapon/spell-damage contexts; optional/required/default contributions; explicit result origin; die selection, keep/drop, matching, exploding, reroll, replace, multiply and result claims | Powerful, mixed Brawler pools/Combo chain, Sacred Resonance, rerolling Duality, saved-roll continuation, extra target damage, preselected form maintenance. Distinguish changing a historic roll from publishing a new one. |
| Pools, equipment and effects | Numeric, rolled-face and stored-roll state; scoped instances, stacking, replace/refresh; link holder/caster/recipient; inventory grant, consume, attach and transfer | Prayer Dice, Favor/Patron Die, tokens, reloading, armor thresholds, charged items, transferable talismans, end-on-zero and manual expiry. Copy/transfer definitions and state under the correct ownership policy. |
| Incoming damage, reactions and rewards | Ordered calculation stages and “after commit” events; claimant and target filters, exclusivity and no-benefit rejection | Preserve thresholds → multiplication/resistance → reduction → severity/negation → armor → HP adjustment/replacement; distinguish would-mark from actually marked HP. Cover mitigation, armor wear, shared shields, target healing, alternative Hope claims, GM Fear and hidden rolls. |
| Lifecycle, rest and downtime | Explicit acquisition, equipment, scene/session/rest and source-removal events; expiry/reset policies; bounded tables, crafting, rest slots/moves and extra/alternative moves | Once per scene vs session, rest-granted effects not immediately expiring, short vs long rest, Recovery, Forager, ally repairs, permanent vault and death moves. A reconnect is not a new session. |
| Cross-actor and stateful workflows | Recipient selectors, bounded allocations, mirrored pools/activation links, borrowed-source namespaces, structured choices, roll/commit-or-cancel flows | Offline vs connected allies, per-player claims across tabs/actors, Copycat and nested spells, Zone of Protection, Salvation Beam, exclusivity on recast; never run one client write per target. |
| Class subsystems and custom content | Declarative selection lists/requirements, acquisition grants, form/profile replacement, companion entity/track operations, conditional actions and UI descriptors | Druid forms/elements, Brawler stances, ranger training/interception, lunar phases, Warlock outcomes, transformation choices. Mancer/import, DM-authored effects and customized equipment must resolve the same definitions without losing player or DM choices. Keep unsupported custom-effect presets explicitly manual. |

**Index of the 58 shipped top-level fields.** This is where each existing field enters the conversion, not a proposal for 58 new operations. Most of the per-card switches are *below* this level.

| First migration destination | Current fields |
|---|---|
| Rules, bindings and plain instructions | `actions`, `conditionalActions`, `rollOptions`, `conditionalRollOptions`, `rollTriggers`, `conditionalRollTriggers`, `manual`, `hideSpellcast`, `spellcastDifficulty`, `spellcastSuccessSteps` |
| Contributions, eligibility and roll calculations | `modifiers`, `conditionalModifiers`, `requirement`, `damage`, `damageTypeChoice`, `alternateProfiles`, `channelBenefit`, `ignoreWeaponBurden`, `unarmoredBase` |
| Named pools, budgets and display | `resources`, `dicePool`, `effectCounters`, `classResourceOnly`, `uses`, `usesOverrides`, `experienceRefund` |
| Damage, claims and linked effects | `mitigation`, `conditionalMitigation`, `hopeReward`, `conditionalDamageRewards`, `actionBenefits`, `criticalMelee`, `overflowHope`, `equipEffect`, `armorFullEffect`, `armorHalfEffect`, `armorFullHeldEffect`, `zeroHopeClearArmor` |
| Rest, inventory and lifecycle events | `rest`, `restReroll`, `automaticRestUse`, `automaticRestUseOnAttempt`, `partyDowntime`, `attach`, `reloading`, `deathMove`, `banishDifficulty` |
| Acquisition, forms and companion recipes | `creation`, `grantsFeatures`, `groupGrantedFeatures`, `elementChoice`, `unarmedWeapon`, `stances`, `lunarPhases`, `beastforms`, `beastformFree`, `companion`, `companionTraining` |

**Rename concepts, not just keys:**

| Ambiguous current vocabulary | Proposed distinction |
|---|---|
| `on`, `roll`, `when`, `timing`, `chatOnly`, `automatic` | `bindings.event` = what happened; `when` = predicates on facts; `bindings.surface` = where offered; `bindings.mode` = player choice or automatic. Explicit binding replaces text-derived Spellcast. |
| `hostOwned`, `hostRollAction`, `*Automation` | Not author switches. Compiler infers required capabilities and execution/commit phase from the operations and binding. |
| `uses`, `usesSource`, `deferSourceUse`, `expendOn`, `repeatable` | Named `limitRef` plus explicit spend event; claim policy separately defines per-roll/per-player exclusivity. Reset events are neither costs nor effect expiry. |
| `target`, `audience`, `otherPlayerOnly`, `allowSourceOwner`, `allActors`, `party` | Separate **who sees the message**, **who may invoke**, **which actors are eligible**, and **who receives the change**. Give selectors explicit cardinality and scope: one selected saved actor, connected players' actors, or all saved player actors. |
| `requiresEffect`, `excludesEffect`, `endEffect`, `unique`, `exclusiveRollGroup` | Typed instance predicates, `effect.end`, a stacking key/policy, and a separate claim-group key. Explicit source/recipient/activation scope; never match display names. |
| `resource`, `counter`, `dicePool`, `@hp`, `@proficiency` | Distinguish `track.mark` from `resource.spend`, use budgets from counters, numeric pools from face collections; use phase-qualified and base/derived reads. |
| `actionRoll`, `sendRoll`, `resourceRoll`, `damage`, `damageDice`, `rollTriggers` | Specify roll kind and calculation, a binding to a finished result, and whether to add a contribution, edit that result, or publish a derived one. Use result groups `result`, `sourceFeature`, `otherFeatures` independently of the mechanic. |

## Runtime, persistence and compatibility

1. **Compile once per catalog revision.** Strict JSON parsing (including duplicate keys), JSON Schema for tagged unions, then a semantic linker: unique IDs, reference existence/types, allowed inputs/outputs by event, valid cost directions and roll phases, template variables, private facts, cycles and bounds. Compile shorthand and JSON recipes to one typed rule representation. Reject unknown executable fields with `category / entry / rule / JSON pointer` and a fix; preserve unrelated metadata. Never silently run half a feature. Index by source, event and surface; don't walk every catalog entry on each roll. Retain the last good revision on a failed live edit; if none exists, explain why the ruleset cannot be activated.
2. **Interpret once for authority.** Java owns eligibility, spend/claim checks, rolling for authoritative commands, and multi-sheet, campaign and chat mutations. Extract generic primitives from existing `DiceRoller`, feature steps, mitigation/rest logic and campaign journal instead of implementing nine independent engines. TypeScript renders action descriptors and inputs and evaluates only pure previews for responsiveness; desktop renders the same audience-appropriate text/sections. Use shared schemas, generated bindings and identical cross-language fixtures for the pure subset. Do not ship host secrets (e.g. hidden Difficulty) in a preview. Replace locally resolved automation flows too, explicitly testing the authority change against captured baseline behavior. An unconverted flow blocks release rather than falling back to the old engine.
3. **Define deterministic dispatch.** One event has typed origin, owner, phase and visible facts. Collect candidates from active sources only. Resolve passives, optional selections, combined costs and mutually exclusive groups in documented phases; use explicit priority and stable ID as a tie-breaker within a phase. Fully resolve input/eligibility before mutation. Stage bounded follow-ups after commit; reject cycles, recursion without a bound, excessive dice, targets or steps. Never permit arbitrary sheet patches or network/file access from a rule.
4. **Send intent, not mechanics.** Example intent fields: `actorId`, `sourceInstanceId`, `ruleId`, `bindingId`, `activationId?`, `messageRef?`, `expectedStateRevision`, `ruleRevision`, `inputs`, `requestId`. Host looks up the definition from the trusted catalog or validated DM-owned snapshot, verifies ownership/active loadout, target scope, current session, message visibility, allowed die IDs, cost, claim group and revision; never trusts claimed faces, totals, labels or supplied operations. Use one journaled transaction for cost, use, roll, effect, recipients, campaign Fear and chat as applicable. Retry the *same* request idempotently; return the original result, or an actionable stale rejection with refreshed choices. Do not replay a possibly committed operation with a fresh ID. Ordinary sheet PUTs must not fabricate or overwrite protected state.
5. **Keep definitions separate from facts.** An activation stores its definition/source ID and rule revision, actor/recipient, instance ID, monotonic revision, selected inputs, counters and typed slots (including immutable roll snapshot + remaining die IDs). Retain revisioned definitions or a bounded validated snapshot until live activations/claims no longer need them. New activations use the current catalog. Cosmetic edits may refresh live labels; old chat keeps its text and audience. Reject stale actions, and never reinterpret an old die selection against a changed definition.
6. **Authoring is part of the feature.** Extend the existing loopback-only DM editor (`web/src/catalog-editor.ts`, `catalog-fields.ts`, Java `CatalogEditor`) rather than creating a second editor. Present *When / Where / Who / Cost & limits / What happens / What players see*. Offer field pickers, template autocomplete with visible sample values, expanded recipe, a “why unavailable?” preview and dry-run sheet/chat examples. Keep plain JSON round-trippable, immutable IDs, atomic save, optimistic revision, unknown non-rule fields, embedded-WebView close protection and `catalogChanged` cache invalidation. Do not infer rules from descriptions, labels or filenames.
7. **Make one coordinated cutover.** Ship the new engine, all converted catalogs, shared rules, editor and browser assets together. Delete the old execution paths, card-specific capability flags and incompatible command handlers; retain no legacy interpreter or down-conversion for old clients. Negotiate the new Online Play contract before loading editable sheets or accepting gameplay commands, and enforce it on every mutation endpoint so stale tabs cannot bypass the check. Reject mismatches with the supported and received versions and an update/reload instruction; a new browser must likewise refuse an old host rather than executing old automation. Increment the Online Play compatibility contract as required by the breaking rewrite. DM Link's `RemoteCompatibility.VERSION` is a separate assessment: increment it if its messages, snapshots, settings or asset-transfer semantics change incompatibly, never merely for new application content. A format version and a connection compatibility version protect different boundaries; neither implies two runnable engines.
8. **Convert data once, outside normal execution.** Stop hosting and back up the original catalog pack and affected campaign data before conversion. Map `feat:<name>`, `card:<book>/<spell>`, option/usage keys, custom resource IDs, `_catalogFeatureId`, effect-name links and legacy dice indices to IDs in a staged copy. Respect catalog-link baselines and edited gear, custom inventory, earned choices, spent limits and offline actors. Do not re-grant creation benefits. For active Deathruns preserve first roll, weapon, original indices, remaining dice and count; for Prayer Dice preserve IDs, values and spent session use. Convert executable effect/claim snapshots to the new format too. Use exact, unambiguous aliases; unresolved mechanics or references produce actionable diagnostics and block activation of the converted data, not a legacy fallback or silent loss. Validate the entire staged pack and affected campaigns before a recoverable, journaled cutover; record completion so retries never reapply grants or conversion. Existing chat stays readable and keeps order, whispers and claim status; never make a historic message newly executable by matching its label. Rollback restores the old application and the corresponding pre-conversion data together, not old code over new saves.

## Delivery plan and release gates

| Stage | Deliverable | Exit gate |
|---|---|---|
| 1. Baseline before designing more primitives | Inventory each live field **and** runtime-generated action/label. Record source path, binding/surface, authority, condition, inputs, cost timing, result, persistence and representative fixture. Classify as direct, recipe, new primitive, intentionally manual or rewrite pending. Include base rules, DM custom effects and saved-sheet-only behavior. | All nine categories and nested automation owners counted; every discovered dispatch/field assigned. Existing manual boundaries and known parity gaps called out, not promoted to supported. Capture current event traces and golden chat/sheet outcomes before replacing the implementation. |
| 2. Language and authoring prototype | Single-format identity model, schema, expression/template checker, compiler, host descriptor query, generic controls/preview and CLI/DM-editor validation. Start with one consumable and one independent-use feature. Build the one-time converter separately from the runtime loader. | Changing a label, cost, amount, placement or name updates the UI and execution without a build. Bad reference/token fails at the exact path; known non-rule fields survive editing. Runtime rejects unconverted or mixed-format data. |
| 3. Prove the hardest vertical slice early | Roll provenance, snapshot/derive operations, revisioned activation state, intent/commit path, first Deathrun recipe. Add Splinter Trail in JSON only. | Both work together from sheet, attack option, damage dialog, effect and result chat; checks include the numeric fixture above, fixed/mixed/kept/dropped/maximized dice, multipliers, spent bonuses, last die, wrong weapon, stale result and reconnect. Rename the multiplier row and every feature label; totals must not change. |
| 4. Prove the model is not a Deathrun engine | Migrate Prayer Dice/Devout and one alternative reward; then a grimoire spell, one mitigation/reaction and a cross-actor invitation or transfer. | One generic per-die renderer/command; equal faces consumed independently; no new confirmation. Verify one-claim exclusivity, real healing, canceled input, offline recipients, simultaneous tabs and transaction recovery. Add a second pool/claim from JSON without code. |
| 5. Complete every family before release | Passives, costs, resources, simple actions/options first; then results, effects, damage/rest, gear, acquisition/forms/companions, and linked or borrowed multi-actor workflows. Mechanically convert only unambiguous old data with a dry run and reviewable diff; resolve every outstanding conversion explicitly. Add a primitive only with a documented contract and a second independent use/fixture. | Each batch matches captured baseline outcomes, has author documentation and editor presets, and accounts for every field. All shipped executable behavior runs on the new engine. No legacy adapters or formerly supported mechanics downgraded to manual fallbacks. |
| 6. Release the complete replacement | Remove old feature-specific runtime handlers, label/name checks and compatibility fallbacks. Ship the complete rules/assets in local and CI packages; rehearse conversion on copied catalogs/sheets/history, mismatch rejection, interrupted cutover and coordinated rollback. | One runtime and one supported format. No shipped executable mechanic requires a feature/class name, text pattern or card-specific flag; old-format knowledge exists only in the one-time converter. All shipped behaviors have a passing parity fixture or an explicit pre-existing manual disposition. Publish the author guide, schema and conversion report. |

**Test the contract, not just JSON parsing.** Run each fixture through eligibility, displayed controls/placeholders and pure preview, then through the host with deterministic dice. Compare committed sheets, resources, uses, roll contributions, IDs, revisions, chat audience and message order against captured baseline fixtures, without retaining the old engine in the shipped application. Include unknown/hidden facts, stale or duplicate requests, cancel, insufficient combined costs, full tracks, recast/expiry, actor switching, cold load, catalog hot reload, rejection of old clients/hosts and restart after an interrupted journal. Cover conversion reruns, ambiguous references, preserved custom gear and active state, interrupted cutover and rollback. Test desktop and browser output, not merely that the rule appears in a list. Extend [the browser Deathrun tests](../web/src/daggerheart/bone-valor.test.ts), [result-button tests](../web/src/daggerheart/rollbuttons.test.ts), [Java dice tests](../src/test/java/dmmt/online/BoneValorRulesTest.java) and [server integration tests](../src/test/java/dmmt/online/OnlineSessionServerTest.java); use the existing catalog editor/cache, persistence, Seraph, damage, rest and class suites as characterization fixtures.

**Completion criterion:** stages are an implementation order, not permission to ship a partial rewrite. Simple one-shot rules should become shorter, not longer. The real cost is replacing stateful dice, event ordering and cross-actor transactions *without losing saved games*. If a card still needs a named handler, implement the missing generic operation before release. Do not hide the exception behind JSON, retain a fallback engine, or mark the rewrite complete with a supported feature still unconverted.

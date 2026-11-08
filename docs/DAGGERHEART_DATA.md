# Daggerheart data format

`dmmap-daggerheart/*.json` is the maintained Daggerheart catalog and source of truth. Edit these files directly
when correcting data or adding entries, or use **DM Compendium > Edit JSON catalogs**; there is no converter or source-export dependency. The folder is configured
with `online.daggerheartDirectory`. The host serves it at `GET /api/compendium` and
`GET /api/compendium/{category}`.

Each file: `{ "format": "dmmap-daggerheart", "version": 1, "category", "label", "entries": [] }`.

| File | Notable entry fields |
|---|---|
| armor | `tier`, `armor {score, major, severe}`, `feature`, `automation`, `starter` |
| weapons | `weapon {slot, trait, range, damage {dice, bonus, type}, burden}`, `feature`, `automation`, `starter` |
| domain-cards | `cardType`, `domain`, `level`, `recall`, `text`, `automation`; grimoires also `spells[] {name, text, automation}` |
| classes | `kind` class/subclass, `domains`, `features[]`; classes: `startingHp`, `startingEvasion`; subclasses: `parent`, `stage`, `spellcastTrait` |
| ancestries, communities, transformations | `features[]` |
| consumables, items | `rarity`, `text`, `automation` |

`starter: true` (weapons, armor) marks the basic gear the Character Mancer offers.

## Guided DM editor and live catalog links

While hosting, the DM compendium's **Edit JSON catalogs** view edits all nine files above, including automation,
features and grimoire spells. Select an entry or use **New entry** / **Duplicate as new**. Plain-language field
help explains scope, costs and limits. **Overview** holds identity, category statistics and a live formatted preview;
**Features** presents named features/spells in a list with a dedicated editor. **Automation** selects entry-wide
or feature/spell-owned rules and presents cards with focused activation, conditions, effects and costs/limits.
**Advanced** holds uncommon entry fields and custom keys. Deeper groups open breadcrumb-addressable panels,
not nested accordions. Save/draft status remain outside the scrollable content, and same-panel structural edits
retain scroll position. Typed inputs, optional-field pickers and ordered-list controls edit the existing structure
without requiring JSON syntax. List templates come from maintained rules;
review their IDs, labels, prerequisites and costs rather than treating a copied example as a new mechanic.
Action effects also offer **Add effect** presets for restoring tracks, gaining/spending Hope, changing a custom
resource or applying a modifier effect; edit their amounts, pool IDs and durations to match the intended rule.
Custom resource-map keys are supported, but an arbitrary field name does not invent runtime behavior.

The compendium and editor explicitly theme all input types, feature text areas, dropdowns and placeholders,
including disabled fields, with contrasting light text on dark backgrounds in both browsers and the embedded DM view.

**Save to JSON** updates the actual configured file with validation, a content-revision check and atomic replacement.
It preserves other entries, file metadata and unknown fields. Existing entry IDs are immutable; new IDs must be
unique. Names must be unique within a category (subclass stages may share a name). Failed saves keep the draft.
**Reload file**, navigation and closing protect unsaved work. Player cookies and character-sheet DM grants cannot
authorize catalog edits: `GET/PUT /api/dm/catalog/{category}` requires the loopback DM-compendium capability.

Catalog writes emit an additive `catalogChanged` notification. Browser/DM compendiums reload their caches;
open sheets flush pending player edits and request current rules. The host also resolves rules on sheet reads,
saves and authoritative feature actions, so closed/offline sheets use current JSON after reopening/restarting.
Sheet reads project the latest rules without rewriting character files; the next sheet save persists that projection.
This is not a character rebuild: tracks, resource values, quantities, spent-use counters, choices, advancements
and notes remain intact. Class starting values and `automation.creation` grants are not reapplied.

Optional sheet `catalog: {category, id, values, featureId?}` links retain the last catalog-owned field values.
Only unchanged catalog-owned values are replaced, preserving manual overrides. Legacy sheets match exact
equipment/card names or learned feature names and sources. Custom inventory is excluded. The editor retains
an internal `_editorOriginal` entry snapshot to refresh legacy copies and resolve renames, and assigns hidden
`_catalogFeatureId` identities to feature/spell blocks so ordered-list edits do not change a learned block's identity.
Do not edit these internal fields by hand. Entry/feature renames keep linked counters; fields such as
`requiresFeature` that explicitly reference a feature name still need their exact references updated.

No new setting or rail tool is introduced: hide/open access remains `online.compendium`, including the local API.
The optional notification and sheet metadata are backward-compatible; neither Online Play nor DM Link compatibility
versions change. Older clients can continue reading catalog files and saved sheets.

## Automation block

### Active-loadout conditional modifiers

`conditionalModifiers` contains passive modifiers with `loadoutDomain: {domain, min}`.
The domain comparison is case-insensitive and counts only cards in the active loadout, including the
source card when it matches; vaulted cards do not count. These modifiers affect sheet statistics, their
source breakdowns, roll bonuses and the host's Spellcast trait calculation. Older clients ignore the
separate `conditionalModifiers` field rather than applying the bonuses without the condition. This
currently covers the passive bonuses on Arcana-, Blade-, Bone-, Splendor- and Valor-Touched.
`conditionalActions` and `conditionalMitigation` use the same gate for Bone-Touched's attack avoidance
and Splendor-Touched's shared-use damage replacements. Older clients ignore these gated actions rather
than applying them without the loadout requirement. Grace-Touched's explicit substitution controls are
gated too; automatic substitution across every Stress-cost workflow remains an open decision.

`conditionalRollOptions` similarly gates optional roll choices by `loadoutDomain`. Codex-Touched's
`derivedProficiency` uses current Proficiency including bonuses; Sage-Touched's `traitValue` uses the
current total trait and its natural-environment bonus is player-selected. Sage's Agility and Instinct
choices share its rest limit, while its natural-environment option is unlimited. Dread-Touched's
optional roll bonus uses the GM's current Fear pool. Older clients ignore these options.

### Audited domain-card metadata

The individual disposition of all 236 cards and every nested spell is in
[`plan.md`](../plan.md#individual-domain-card-disposition-ledger). Supported sheet mechanics and
deliberately manual narrative consequences are distinguished from the complete open-decision list.

- `unarmoredBase` specifies `{score, trait, thresholdsByTier}`. Bare Bones replaces the unarmored base,
  adds current Strength to its Armor Score, and adds level once to each threshold. Equipped armor and
  vaulting disable this base; stat breakdowns use the same calculation.
- `@total_<trait>`, `@total_spellcast` and `@total_proficiency` use current derived totals. Existing
  `@<trait>`, `@spellcast` and `@proficiency` expressions keep their base-value semantics.
  Resource `resetValue`/`minimumReset` define rest regeneration after expiring effects have been removed.
  Step `minimumAmount` clamps an award such as Flight's current-Agility tokens to at least one.
- Nested actions/reactions use `card:<book>/<spell>` keys. Both host catalog lookup and browser target
  selection resolve the exact nested spell; the host requires its book in the active loadout.
  `actionRoll.reaction` opens Reaction mode. Action rolls include a
  `featureAction:<source-key>:<action-id>` source tag for domain-card actions; selected domain-card options include
  `option:<source-key>:<option-id>` so a result action can require the specific cast or option.
- Trigger `successIfKnown` rejects a known failed roll, while hidden Difficulty remains player-confirmed.
  `criticalOnly` requires equal positive Duality dice on an eligible attack. `failedOnly` excludes
  critical successes. `exclusiveRollGroup` gives alternative reactions a shared per-roll exclusion;
  Blighting Strike cannot claim both failed-cast payments.
- Non-fixed damage reactions pass a pending action into the sheet damage dialog. Recheck the active
  source, reaction eligibility, use and aggregate costs before sending; pay and consume the use only
  after sending. Canceled/disconnected rolls spend nothing. This follows the client-side sheet damage
  pattern, not a host-acknowledged per-roll transaction across reloads.
- `resourceRoll: {resource, sides, bonusPerDie?, type?}` opens a Hope-spend or Stress-mark quantity
  dialog. A damage `type` labels the result as physical/magic; omitting it produces a utility roll
  (Arcane Reflection/Invigoration). `hopeDice` remains supported for Arcane Barrage.
- Roll options can use `resourceMultiplier` for an all-token numerical bonus or `allResourceDice`
  for an all-dice pool. Their clear-all steps run only on successful sending. `advantageDie: 8` changes
  advantage, never disadvantage; `condition.markedHp` supplies Runic Adrenaline's minimum marked HP.
  `magicDamage` changes weapon damage tags/type, `multiplier: 2` doubles the total, and `rerollBelow: 3`
  rerolls all eligible 1s/2s once through the existing host damage reroller.
- `damage.count: "spellcast"` uses the current total Spellcast trait instead of Proficiency.
  `vaultSource` is a host reaction operation that moves the active source card to the vault on validated
  success and increments the compendium revision; Counterspell uses it.
- Effect `exclusiveSource` replaces that caster/source's previous recipient effects on recast.
  Shield Aura's passive mitigation has `endEffectIfNoHp`: it expires only when its extra reduction
  caused HP to become zero, not when ordinary Armor or negation already prevented damage.
  `endOnMarkedHpCount` expires Vital Ward after actual HP marked by incoming damage reaches the
  specified threshold; non-damage HP changes and leaving the circle are explicitly manual.

The host advertises optional `domainCardAutomationV2` alongside the retained original capability.
Current clients require V2 for host-dependent audited actions; older hosts disable those controls
without blocking connection. LAN compatibility version and settings contracts are unchanged.

### Witch metadata

Commune's `sendRoll.dice: "@spellcastd6"` resolves the current total Spellcast trait as the dice count;
the long-rest use is spent only after the roll is sent. Its description includes the sensory-result chart.
Walk Between Worlds and Night's Glamour use `actionRoll {trait: "spellcast", difficulty: 13}` with
feature-source-specific `when: "success"` triggers. The Walk roll has `usesSource: false`; only its
chat action entering beyond the veil spends Stress and the rest use. Glamour's free `automatic: true`
trigger is applied by the host on success without an additional button.

Token resources declare `whileEffect` with their source effect's name and `resetOn: "rest"`.
Witch resources and Lunar `effectCounters` declare `cardOnly: true`: counters appear only on their
own feature cards, not as class resources in the header. Linked talisman mirrors are also excluded
from the header; their values remain available internally for damage mitigation.
An effect's `tokenResource` removes it when its pool reaches zero. Together these clear Walk tokens on
End scene and Circle/Talisman tokens on expiry; token-spend actions use `usesSource: false`.
Circle and Moonbeam source effects declare `witchGrant: "circle"|"moonbeam"`; the host distributes
source-linked benefits to campaign characters, including offline owners. Circle bonuses are passive
thresholds/Evasion and a preselected attack option; Moonbeam preselects +1 Spellcast until scene end.
Recipients may dismiss out-of-range grants without having them reapplied during that activation.

`witchAction: "talisman"` asks for Hope and uses the authoritative feature endpoint to create a
transferable, creator-linked loot item. Its holder's mitigation spends the original witch's tokens,
not an independent copy. Transfer, token spending and expiry reconcile inventories and effects on the
host. Depletion or the creator's rest removes the item from its current holder.
Inventory stores `witchTalisman {sourceActorId, activationId}`; linked buffs carry `witchLinked`,
`sourceActorId` and `activationId`. Each held talisman has an independent host-mirrored
`talisman-tokens:<sourceActorId>:<activationId>` resource, so spending one cannot debit another creator.
Source marker metadata `witchRecipients` retains manual party-grant dismissals for that activation;
`witchTalismanCreated` prevents recreating a moved item. These host-maintained fields are not catalog rules.

Glamour uses `endOnMarkedHp` and `endOnDealtDamage`. A mitigation/roll option's `preserveEffect` pays
Stress to keep the named buff. No incoming HP means no maintenance cost. `roll: "trait"` matches
trait-bearing action/reaction rolls; appearance-based advantage stays optional and player-confirmed.
The runtime effect's `hpMaintenance` counter records paid incoming-damage maintenance independently
of simultaneous Stress recovery; an unchanged receipt cannot preserve it through a later hit.

Lunar Phases declares `lunarPhases[] {faces, effect}` plus `witchAction: "lunarRoll"|"lunarAdvance"`.
Its session roll and rest-limited Hope-cost increase have independent action limits. The active effect's
`counter` stores the d6 face, displayed by `effectCounters`; increasing 6 wraps to 1 and replaces the buff.
Face 1 supplies Hope-cost Minor-damage negation, 2-3 passive +2 damage, 4 passive +3 thresholds, and
5-6 passive +1 Evasion. Only New session refreshes the session roll; rests preserve the face.

The additive `hello.witchAutomation` capability gates the linked workflows and success actions on
older hosts. No settings or DM Link compatibility version changes are needed.

### Warrior metadata

Combat Training declares `ignoreWeaponBurden: true`, applied by creation and equipment while retaining
one primary and one secondary slot. Rise to the Challenge uses `condition: {unmarkedHp: {min: 0, max: 2}}`
with `hopeDie: 20` and `defaultOn: true`. Courage uses a Fear `rollTrigger` with `failedOnly: true` and
`excludeRolls: ["reaction"]`; an unknown Difficulty leaves failure player-confirmed.

Slayer declares `classResourceOnly: true` and no actions, plus
`hopeReward: {resource: "slayer-dice", amount: 1, roll: "action"}`. Its `session: true, sessionHope: true`
resource converts unspent dice into capped Hope before New session clears the pool. Scene endings,
reconnects and hosting restarts do not convert dice. Attack/damage options declare
`quantity: {resource: "slayer-dice", label: "Slayer Dice to spend"}` and `diceResource: "slayer-dice"`.
The roll breakdown retains a `Dice resource` note identifying these dice independently of source labels.
Weapon Specialist's `secondaryWeaponDie: true` damage option adds exactly one equipped secondary weapon
die (not its flat bonus), costs 1 Hope and uses `usesSource: false`. Its independent source-wide
`uses: {max: 1, per: "longRest"}` limits `rerollResourceDice: "slayer-dice"` on finished attacks/damage
to rerolling only those dice showing 1. The original roll, total, breakdown and history update atomically
with the spent use; duplicate requests and rolls without eligible dice are rejected.
Slayer reward/reroll chat actions require the additive `hello.warriorAutomation` capability.

Martial Preparation declares `partyDowntime: true`: the host maintains a `partyDowntimeGrant` effect
with the granting feature's downtime actions on every other campaign sheet, including offline actors.
It does not add slots or a resource counter. Its gain step's `fallbackEffect` is applied only if the target
does not define that resource; otherwise the normal pool gain is used. The fallback's attack/damage
options share `endEffect`, consuming the one d6 buff. Grants are reconciled on sheet reads and changes;
removing the granting feature removes access, not previously earned dice. This is additive Online Play
behavior, not a settings or DM Link compatibility change.

### Ranger companion metadata

Beastbound's foundation feature declares `automation.companion`: starting `evasion`, `stress`, `maxStress`,
`damage`, `range`, ordered `dice`/`ranges`, and `options[] {id, name, text, max, choose?, stat?, value?, automation?}`.
The official companion sheet allows three selections each of Intelligent, Vicious, Resilient and Aware;
other options have one. `choose: "experience"` increases a selected Experience; `choose: "attack"` raises
the die or range by one step. `stat: "hope"` marks Light in the Dark's additional owner Hope slot;
`stress` and `evasion` increase companion stats. Names/prose are not used to discover the companion subclass.

Expert Training and Advanced Training declare `companionTraining: 1` and `2`: additional choices **when
acquired**, not on every subsequent level. Creation stores optional `mancer.choices.companion`; the owner
sheet stores optional `companion` state and level records store `companionTraining`/`companionExperience`.
Deferred setup catches up training and tier-milestone Experiences since subclass acquisition.
Multiclass acquisition itself counts as a companion level-up; there is no retrospective pre-acquisition training.

Creature Comfort uses shared once-per-rest actions with `companion: "comfortHope"|"comfortStress"`.
Loyal Friend's sheet action uses `requiresCompanion` and the standard action announcement, without a confirmation, to spend
its long-rest use without changing damage tracks. Battle-Bonded has no activation; players check its
conditional Evasion passively. Hold Them Off retains its normal action and also uses a damage `rollOptions` entry costing 3 Hope.
Loyal Friend's `companionIntercept` mitigation uses `when.lastHp`, `replaceHp`, `insteadOfArmor` and the
same source-wide long-rest use for the other direction. These are local sheet/damage actions, like existing
damage mitigation. `companionArmored` requires learned Armored and uses an `armor: 1` cost instead of companion
Stress; it is mutually exclusive with the regular companion interception. Positional prerequisites
are confirmed explicitly. `requiresCompanion` gates an action
or effect on companion availability; `confirm` supplies a table-prerequisite prompt.

Command rolls include the additive `companion` source target, use companion Experiences instead of owner
Experiences, and otherwise reuse existing Spellcast/attack/damage infrastructure. The `on: "companion"`
Hope trigger offers the conditional next-action advantage through existing chat feature reactions.
Weapon-range and critical melee benefits recognize a companion attack on the owner's saved sheet.
Chat sharing reconstructs the companion weapon from that same saved state for identity validation;
the generated attack is not transferable inventory.
No new required messages, settings or compatibility-version changes.

Rules reference: [official SRD](https://www.daggerheart.com/srd/), Ranger/companion sections, and
[official character sheets](https://www.daggerheart.com/downloads/), Ranger Companion sheet.

### Brawler and martial stance metadata

`classes.json` maintains Brawler mechanics without inferring rules from feature prose:

- `unarmedWeapon` is a weapon template without a trait. When no real primary or secondary is equipped,
  the sheet synthesizes it with `generated: "brawler"`, remembers `brawlerTrait`, and shows its Evasion buff.
  Generated weapons are never added to carried equipment.
- `criticalMelee: {hope, stress, proficiency}` adds to the standard critical rewards and attack-linked
  damage Proficiency only for Melee weapon attacks. Rewards remain a single host-authoritative claim.
- Roll options support `weaponRange`, `requiresFeature`, `excludesFeature`, `exclusive` (mutually exclusive
  cost group), `combo: true`, and `proficiency` (attack-only bonus carried to that attack's Damage button).
  `quantity.resource: "hope"` uses the current Hope track, initially selects a quantity of 1, and scales costs.
  Selecting Combo Strike does not add ordinary dice: the optional `damageRules.comboDie` request asks
  the host to roll an unmodified nondecreasing chain, including the final lower face, after normal damage.
  This extra damage is neither maximized for a critical nor multiplied. Excessive chains explicitly fail
  at the host's dice limit instead of returning a partial result.
- `stances: [{name, tier, chooseTrait?, effect}]` declares the known-stance choices. Creation persists
  `stances` in its choices and `knownStances` on the sheet; level history records newly learned `stances`.
  Two distinct tier-1 choices are required at foundation (including multiclass acquisition), then one
  unknown stance from the current tier or below per level. `shiftStance` actions pay their configured cost
  after a known-stance picker; `refocus` actions replace Focus with the highest of Instinct d6s, once per
  rest using their own `uses`. At Instinct 0 or lower, clear Focus to 0 without rolling.
  Martial Artist's separate `Focus` foundation feature owns the resource and Refocus action.
  Stance Fighter declares `grantsFeatures: ["Focus"]` to upgrade existing sheets without resetting
  Focus or its spent Refocus use. It retains shifting, stance definitions and their exact rules text.
  Only a selected stance with `chooseTrait: true` shows a trait picker (Favored).
- A stance effect has `stance: true`, `until: "scene"` and descriptive `text`. Exactly one is active;
  Severe final damage and marking the last HP drop it. End-of-scene removal uses the Effects tab.
  `chooseTrait` adds a `roll: "damage"` modifier with `traitValue` set to the selected trait, including
  its current passive bonuses. Effects can supply `rollOptions`, `actions`,
  `mitigation` and `damage: {extraDice, dropLowest, maximumOnes?}`. Extra/kept dice apply independently
  to each base weapon pool (including both Brawler's Strike pools). `maximumOnes` becomes the optional
  `damageRules.maximumOnes` request, resolved before keeping dice on the host, never on Combo Dice.
  `hello.brawlerRolls` gates these optional damage rules; older clients retain ordinary rolls.
  Loading a sheet refreshes its active stance descriptions without replacing chosen-trait modifiers.
  Generated weapon roll sources include the granting feature's rules when the weapon has no feature text.
- Stance Fighter's `rollTriggers` use `requiresEffect` and optional `weaponRange`. Invigorating uses
  `check: {dice: "1d4", success: 4, gain: "focus"}` for a capped, once-per-attack host check; successful-hit
  and positional prerequisites stay player-confirmed. Non-fixed trigger `damage` opens the normal
  Proficiency damage dialog (Focus Cannon), without paying the originating roll's cost again.
- Mitigation can use `reflectHp` (report the actual HP for Eye for an Eye), `finalSevere` plus
  `recoveryChoice` (automatic Hope-or-Stress recovery after applying damage), and `insteadOfArmor`
  (Stable's Focus-funded severity reduction, mutually exclusive with marking Armor).
  Target HP/Stress and forced movement stay table-managed because attack chat has no target actor.

No settings, required protocol fields or LAN compatibility versions change.

### General automation fields

```json
{
  "modifiers": [ { "stat": "evasion", "value": -2 }, { "roll": "trait:Finesse", "value": 1, "consume": true } ],
  "resources": [ { "id": "favor", "label": "Favor", "initial": 3 } ],
  "creation": { "hp": 1, "domainCards": 1 },
  "rollOptions": [ { "id": "patron-die", "label": "Patron Die", "roll": "action",
                     "dice": "1d6", "diceByLevel": { "5": "1d8" }, "cost": { "favor": 1 },
                     "description": "Only actions related to your patron's sphere of influence." } ],
  "uses": { "max": 1, "per": "longRest" },
  "actions": [ { "id": "drink", "label": "Drink", "cost": { "hope": 1 },
                 "steps": [ { "op": "clear", "resource": "hp", "amount": "1d4" } ] } ],
  "manual": [ "Resolve targeting and situational prerequisites using the source description." ]
}
```

The example illustrates independent fields, not one exported game feature. Do not copy its shared `uses` onto a
feature with independently limited sub-abilities: a source-wide limit applies to **all** its actions/options.

- **Passive modifiers** (`stat`): `evasion`, `armorScore`, `majorThreshold`, `severeThreshold`, `thresholds`,
  `proficiency`, `loadoutMax`, `traits`, `trait.<Name>`. Values are numbers or `@tier`, `@level`, `@proficiency`,
  `@spellcast`, `@<Trait>` (optionally prefixed with `-`). They apply while the source is active (equipped armor and
  weapons, learned features, loadout cards, or an active effect).
- **Roll modifiers** (`roll`): `action`, `attack`, `damage`, `spellcast`, `trait:<Name>`; `value` is a flat bonus,
  `dice` extra dice. `action` matches trait, attack and Spellcast rolls, **not damage**.
  `consume: true` removes the modifier after the next matching roll; an effect without modifiers left
  is removed. Prepared consume modifiers (a drunk potion) are offered in the matching roll dialog as pre-ticked
  options instead of being applied silently; unticking keeps them for a later roll.
- **resources**: optional array of `{id, label, initial, max?, session?}` for numeric custom pools. `initial` and `max`
  are numbers or supported formula strings. Omit `max` for an uncapped pool; never infer a cap from Hope or a
  description. Resource ids are stable, shared sheet keys, so a Warlock subclass can spend the `favor` declared
  by its class's Favor feature. Initial values are applied once, not every render/re-equip. Resource counters
  are not rolled-die collections: use `dicePool` for distinct rolled values.
  Rally Dice use consumable effects instead of numeric resource pools.
  `session: true` declares a session pool (initially 0): it is saved with the sheet like other pools and
  reset to 0 when the DM clicks **New session**.
  Know the Tide uses this with `max: "@level"`; its Fear-roll trigger gains tokens once per roll.
  `longRestLoss` subtracts that many tokens automatically on each long rest, floored at zero (Feed).
  `resetOn: "longRest"|"shortRest"|"rest"` restores the pool to `initial` on that rest;
  a long rest also counts as a short rest. Toxic Concoctions uses `initial: 0, resetOn: "longRest"`.
- **dicePool**: `{id, label, count, sides, extraByFeature?: {"Feature": number}}` declares one grouped class
  resource. An action with `rollDicePool: true` replaces its dice, rolling `count` (a supported value formula)
  plus feature-granted extras and keeping the highest `count`. Nonpositive count produces no dice.
  Each kept face is a session-duration effect with `dicePool: id` and a `counter` carrying its value.
  Prayer Dice declares `uses: {max: 1, per: "session"}`; `runActionDetailed` counts the use before rolling,
  without confirmation. Rests do not refresh it; New session does. Each die takes an independent RNG sample;
  repeated faces remain valid. The feature displays its session counter but no duplicate dice.
  The header groups values as compact dice chips, not individual resource/effect rows; each chip supports manual
  removal (**x**) and spending for Hope (**+**). Per-die mitigation reduces damage by that value and consumes the effect.
  Per-die `on: "any", audience: "any"` chat triggers add the face as a flat `addDice` expression and consume
  the source die, including on plain, damage and ally rolls. The host resolves these dynamic triggers from the
  stored die and catalog pool, not client-supplied actions. Buttons use `Prayer die: +N`, without indices.
  New session removes every unspent die. Loading older dice refreshes labels while preserving IDs and values,
  and marks the session use spent if unspent dice already exist without a counter.
- **hopeReward**: `{removeFear: 1, roll: "trait:Presence"}` offers an alternative to the
  normal Hope reward on a noncritical normal roll with Hope. The host validates ownership, trait, outcome,
  known difficulty and available Fear, and journals the Fear change, explanatory chat report and shared one-time
  reward claim together. No confirmation dialog is shown. With hidden difficulty the reward button asserts success.
  Reaction rolls never qualify; reports retain the roll's whisper audience.
- **matchingDice**: a roll option with `matchingDice: true` requests Sacred Resonance damage rules.
  `weaponRanges: ["Melee", "Very Close", "Close"]` filters eligible weapon roll options/triggers.
  Each matching kept face counts double across all damage sources, including bonus and Combo Dice;
  discarded dice and flat/critical modifiers do not participate. Original faces and base contribution totals
  remain unchanged; the full additional damage appears on the Sacred Resonance row instead of its zero
  placeholder (the host adds a row when absent). Combined total multipliers also multiply the matching-die bonus.
  The additive `hello.seraphAutomation` capability gates new host rules and alternative rewards; older clients
  retain ordinary rolls/rewards. Online Play and DM Link compatibility versions are unchanged.
- **uses**: `max` per `shortRest`, `longRest`, `rest`, `scene` or `session`. Scene uses reset when the DM clicks
  **End scene** or **New session** and on any rest; session uses reset only on **New session**. Reconnecting or
  restarting hosting resets nothing. Exhausted uses show "No uses left this scene/this session/until a rest".
  Scene-duration effects are also removable in Effects. Source-wide uses are shared by its actions/options; a roll option's own `uses` is an independent
  limit. A rest limit is not a custom resource recharge rule.
  `usesOverrides: {"feat:<Feature>": number}` raises another source's maximum while the granting feature
  is learned (Virtuoso), without resetting its usage. `grantsFeatures: ["<Feature>", ...]` refreshes linked
  catalog features on existing sheets (the Gifted Performer songs), retaining their counters.
  `groupGrantedFeatures: true` presents unambiguous linked features from the same source as sections of
  their parent on the sheet, in creation and in the browser compendium. The features remain separate
  automation sources, with independent actions, counters and chat-sharing controls. Missing or ambiguous
  children are not hidden. Leave it unset when a grant is a separate rules feature, such as Focus.
- **Sorcerer selections:** `elementChoice: ["Air", ...]` on a foundation feature adds a required creation
  step, persisted as `element` on the choices and sheet. Existing sheets without a choice can set it once
  on the feature. Action `chooseEffects: {count, options: [{id, label, effect, chooseTrait?}]}` requires
  exactly `count` distinct choices; `chooseTrait` replaces `@choice` in a modifier's stat path with the
  selected trait (Transcendence). These are normal removable effects with configured rest expiry.
  Action `channelRawPower: <EffectDef>` chooses an active domain card and vaults it. The player chooses
  capped Hope equal to its level or the defined effect, whose roll-option values resolve to twice that
  level. The catalog's `@cardLevel` value is a selection placeholder, not a general modifier expression.
  `magicOnly: true` restricts an optional roll bonus or chat trigger to magic damage; weapon metadata and
  additive `magicdamage` / `spelldamage` source tags identify it. `magicAction: true` restricts a chat
  action to Spellcast or a magic-weapon attack. Narrative spell/target prerequisites remain table-managed.
  A trigger's `choices: [{id, label, addDice?}]` offers one popup choice and sends `choiceId` to
  `/api/feature`; the host resolves its definition, adds any configured bonus to the original result, and
  shares one per-roll claim across all choices. `damageDice: "reroll"|"double"` selects global zero-based
  `dice` indexes and sends `expectedDice` (the displayed roll snapshot) with the normal roll reference.
  The host rejects empty, duplicated, stale, out-of-range, negative or unmodifiable Combo Dice selections.
  Double requires exactly one die. Selected faces update in place with provenance notes; unaffected
  dice, flat bonuses, keep-highest rules and whole-roll multipliers are preserved. Dice edits also emit
  an action-used report with each selected face and the total before/after, respecting the original
  roll's audience; they do not create a second damage roll. Flat `addDice` bonuses report just the total,
  while actual dice retain formula/result detail. `hello.sorcererAutomation` gates these new chat dialogs;
  existing clients ignore the additive fields. Online Play and DM Link compatibility versions are unchanged.
  Sheet actions with `usesSource: false` bypass their source's shared counter (their own `uses`, if any,
  still apply); mixed action rows group the source counter with its limited actions, not bypass actions.
  Enchanted Aid's manual and chat swaps share the source's long-rest counter without limiting its d8 Help roll.
  Arcane Charge uses existing
  mitigation `defaultOn`, magic `when`, effect steps and `requiresEffect`/`endEffect` damage options.
- **actions**: normally a button on the feature/item/card. `timing: "downtime"` offers the action only as a rest
  move; `timing: "rest"` hides standalone controls for benefits handled by `rest` metadata. Missing `timing`
  retains normal behavior (`timing: "action"` explicitly preserves a GM's normal-action override);
  names, descriptions and labels do not control runtime timing.
  `downtime: {rest?: "short"|"long", consumeItem?: boolean, table?: {expression, outcomes, repeat?}}`
  restricts rest kinds or describes a dice table of text outcomes. All downtime actions add options to the existing
  slots; "additional downtime move" options such as Forager and Potioncraft Kit do not grant extra slots.
  Only explicit `rest.extraMoves` or passive `extraMoves` grants increase the number of moves.
  `repeat` rolls the number of table draws (e.g. `1d4+@instinct`). Foraged consumables are reported for GM inventory
  handling, not immediately eaten/applied. `cost` is a map of `hp`, `stress`, `hope`, `armor` or declared
  custom ids to number/formula amounts (for example `{"favor":"@spellcast"}`). HP/Stress/Armor costs mark slots;
  Hope/custom costs spend available points. Steps:
  - `clear` (`hp`, `stress`, `armor` or a custom id; `amount` number, dice string, supported formula or `all`)
  - `gain` (`hope` or custom ids), `mark` (`hp`, `stress`, `armor` or custom ids), with numeric/dice/formula amounts
  - `effect` (`{name, kind: buff|debuff, until: manual|rest|shortRest|longRest|scene|session, text, modifiers}`) adds
    an entry to the Effects tab. `name` is the concise effect label shown in action reports and the tab, not the
    item/card/feature that granted it; the granting source is tracked separately. Use `text` for the effect details.
  `actionRoll: {trait?, attack?, difficulty?}` opens the standard Duality dialog (without a trait it rolls just
  Duality Dice). `damage: {dice, fixed?, type?: "physical"|"magic"}` opens damage using Proficiency unless fixed.
  Both pay action costs only when the roll is sent and validate combined action, Experience and option costs.
  `check: {dice, unless, mark: "stress"}` is a host-rolled conditional check: mark Stress unless the die equals
  the configured face (Unshakable). Its roll card includes the consequence.
  `target: "actor"` selects a campaign character and applies configured steps there, keeping costs and usage
  on the source; `"party"` applies to every actor of connected players. Target actions run on the host against
  current sheets, not client copies. An effect's `endOnSourceSevere: true` links its duration to its caster's
  Severe damage and replaces that caster's previous grant on recast. `blocksMovement: true` prevents player
  movement of that actor; DM movement is unaffected.
  `"allActors"` targets every player-owned campaign actor with a saved sheet, including the source and
  offline actors, without changing the connected-player meaning of `"party"`.
  `chatBenefits: [{id, label, steps}]` applies the action's ordinary steps to its owner and publishes
  optional ally claims in the chat report. `automation.actionBenefits: {"feat:<Feature>": [...]}` adds
  choices to that feature's report while the granting feature is learned (Maestro on Rally).
  A player chooses an eligible owned actor and may claim only one option per report, across all their
  actors/tabs. Claims at full Hope or with nothing recoverable are rejected without consuming the claim.
  Report options/eligible actors are snapshotted by the host; costs, uses, benefits and claims are journaled
  with their sheets. `actionBenefits` chat metadata includes `options`, `actorIds`, `claimedBy` and `sessionId`;
  `/api/feature` accepts `{actorId, benefitId, roll: {id, ts}}`. Historical-session claims are unavailable.
  `hello.partyFeatures` capability gates these actions and after-roll additions on older hosts.
  Effects may carry `rollOptions` and `actions` with `endEffect` to share a single consumable across
  roll types and recovery. `unique: true` replaces all previous effects with that name and offers only
  one matching option per roll dialog. Optional `die: {dice, diceByLevel?, diceByFeature?}` resolves from
  the granting actor at grant time, replacing `@die` in the effect's actions/options, never from its recipient.
  Scene-duration effects expire on **End scene**, **New session** or any rest; session-duration effects expire
  only on **New session**, not on rests, reconnects or hosting restarts.
  `transferHope: true` with `target: "actor"` asks for a positive integer `amount` and an ally (Close-Knit).
  The host validates both balances and the shared use limit, then spends and grants the entire amount atomically;
  insufficient Hope or recipient capacity leaves both sheets and the use counter unchanged.
  `actionRoll.trait: "highest"` selects the trait with the highest current total modifier (Fangs; ties use
  standard trait order). `requiresEffect` gates an action and `endEffect` removes the named effect after use.
  `sendRoll.base` is optional; its `dice` may use `@tier` as the dice count (`"@tierd20"` for Howling Rampage).
  `ancestryChoice: "ancestry"|"feature"` declares a Shapeshifter downtime choice and also exposes a sheet
  button for that downtime move. An ancestry change requires a new feature choice; feature-only changes
  retain the current ancestry. `ancestryFeature` on the sheet records the retained feature; creation choices
  and testing builds also carry it. Only that feature contributes ancestry automation and creation stats.
- **Death-move actions:** optional `actions[].deathMove` is a string (`risk`, `avoid`, `spectral`, `egg`,
  `battleHardened` or `unbreakable`) that redirects the button into the host-validated death-move workflow.
  It is not a local healing/spending action. Blessed redirects to `risk`; its optional Hope spend and source
  use are paid only when the death-move dialog is confirmed. Battle-Hardened's 1 Hope cost and Unbreakable's
  d6 recovery/card vaulting are likewise host-owned, not duplicate `cost`/`clear` steps on the button.
  Cancellation, unavailable sources and rejected requests spend nothing.
- **Death-move metadata:** optional `automation.deathMove` is
  `{kind: "hopeBonus"|"hopeSpend"|"mask"|"undying"|"spectral"|"egg"|"battleHardened"|"lifeWard"|"unbreakable",
  moves?: ["risk"|"avoid"], bonus?: number}`. `moves` restricts a Hope adjustment to the listed basic moves;
  `bonus` is its numeric Hope-die adjustment. Alternative/prevention kinds do not need either field.
  Source ids/provenance and metadata determine behavior; entry/feature names are display text, not routing keys.
  Availability is checked against the current saved sheet: owned items/consumables need positive quantity,
  armor must be equipped, cards/spells must be in the active loadout, and features must be learned/selected.
  Remaining source uses and resource balances are validated by the host at execution, not just when offered.

  | Catalog source | Metadata and behavior |
  |---|---|
  | Reliquary of the Sightless Saint | `hopeBonus`, `moves: ["risk"]`, `bonus: 1`; +1 Risk Hope. |
  | Phoenix Feather | `hopeBonus`, `moves: ["avoid"]`, `bonus: 1`; +1 Avoid Death scar check. Multiple copies do not stack. |
  | Hallowed Heroplate / Blessed | `hopeSpend`, `moves: ["risk"]`; optional chosen Hope spend adds that amount to Risk Hope, once per long rest while equipped. |
  | Mask of the Dance Master | `mask`, `moves: ["risk","avoid"]`, `bonus: 1`; optional one-token payment from `death-mask` grants +1 Hope after wearing confirmation. Persistent pool starts at 0 and caps at 4. |
  | Reanimated / Won't Stay Dead | `undying`; a failed Risk result may be converted to success after confirming one permanently marked HP. |
  | Order of the Ghost Slayer / Spectral Form | `spectral`; alternate death move clears 1 HP and creates the host-owned spectral effect. Physical mitigation requires that effect. |
  | Cathartoid Egg | `egg`; consumes one egg and persists the narrative transformation, without inventing HP recovery or changing the character build. |
  | Battle-Hardened | `battleHardened`; active card, 1 Hope, once per long rest, clears 1 HP instead of a death move. |
  | Life Ward | `lifeWard`; the normal targeted action spends 3 Hope to grant an ally the protected effect described below. |
  | Unbreakable | `unbreakable`; active card, host rolls d6 recovery when the last available HP is marked, then vaults that card. |

  Mask token gain is deliberately manual: confirm a living creature died within Melee range, then use Gain
  Mask token. GM-approved counter adjustments correct the 0–4 pool. Other health-related Mask rolls and token
  spending remain manual; the general action-roll dialog must not silently offer/spend these death-move tokens.
  Wearing the Mask and Life Ward's ally/Close-range requirement are table-confirmed, not inferred from coordinates.

  Risk uses host-owned raw Hope/Fear d12s. **Raw matching dice are critical**, regardless of Hope adjustments;
  otherwise **adjusted Hope >= Fear** is ordinary success. Recovery uses adjusted Hope, split between HP and
  Stress by a sender-only, one-time chat claim; critical recovery clears all recoverable HP and Stress.
  Death rolls do not offer ordinary action-roll Hope/Stress rewards or reactions. Avoid Death compares adjusted
  Hope to level for its scar check. Won't Stay Dead preserves the raw dice and adjusted recovery value; converting
  the last available HP to permanent HP instead ends in death and offers no recovery.
  Permanent HP markers persist separately from normal marked HP and cannot be cleared by healing or rests.
  Scar markers separately reduce Hope capacity and persist through rests/reloads; their adjustment is GM-approved.
- **Death-related sheet fields:** `CharacterSheet.longRestRevision` is a nonnegative, monotonically increasing
  integer incremented on each completed long rest. The host uses the caster's revision change to remove all
  wards granted by that caster, including wards on offline/cross-owner targets; the target's long rest does not
  expire another character's ward.
  `pendingDeathRoll: {id, ts}` identifies the pending Risk It All roll and its timestamp for a one-time recovery
  claim. Ordinary manual HP or Stress recovery through a sheet save invalidates that pending claim, preventing
  duplicate recovery when older clients use manual controls instead of the chat claim.
  `permanentHp` is the number of permanently marked HP slots, **not a reduction of maximum HP**; those slots
  remain marked and unavailable for recovery. `spectralBlocked` records Spectral Form's trigger-specific
  exclusion when the form ends from marking the last available HP, so the ensuing death move must use another
  option. `deathNarrative` is a persistent string recording narrative death/transformation outcomes such as
  Cathartoid Egg; it does not itself heal HP or alter the character build.
- **Death-related effect fields:** effect definitions optionally carry `lifeWard: boolean`, `spectralHp: number`
  and `phaseMovement: boolean`. Absent/false flags have no special behavior.
  `lifeWard: true` identifies a ward owned by its **caster**, even though the effect is stored on its target.
  Configure it as `kind: "buff", until: "manual"` so the target's rest does not expire it; the host records caster
  ownership and ends the caster's previous ward on recast or the caster's long rest. Offline/cross-owner targets
  are supported. At the target's full-HP death trigger (damage or manual sheet save), the ward clears 1
  recoverable HP instead of a death move and consumes itself. Costs, target effect and chat are saved atomically.
  `spectralHp` records the host's marked-HP baseline immediately after Spectral Form's initial 1 HP recovery;
  further HP clearing ends the form. Marking all available HP also ends it and requires a different death move,
  excluding Spectral Form for that trigger. `phaseMovement: true` permits actor-token movement through walls
  and closed doors, while pause, ownership, finite-position and map-limit checks remain enforced. These fields
  are set on the host-created Spectral Form effect, not a separate local action effect. Shunting out of solid
  objects when the form ends remains GM-controlled; wall-segment maps cannot establish solid interiors.
  The extension advertises optional `hello.deathMoveOptions`; the basic `hello.deathMoves` endpoint remains
  available to older clients. All fields are additive; neither Online nor DM Link compatibility versions change.
- **rest**: optional `{extraMoves, longMovesInShort, passives, modifiers}` on any automation source.
  A downtime action with `downtime.additional: true` gets its own optional restricted slot, containing only that
  action, rather than consuming a normal move or granting an unrestricted extra move.
  `actions[].mutagens` declares `{traitBonus, traitBonusByFeature?, maxBenefitsByFeature?, extraBenefitCost?,
  benefits: [{id, requiresFeature?, effect}]}`. Its rest pick adds
  `mutagens: {increase, decrease, benefits: [id]}`. The two traits must differ; benefit ids must be distinct,
  unlocked and within the feature-derived limit. Brewing executes after rest refresh/recovery; selecting more
  than one benefit pays `extraBenefitCost`, and its HP cost becomes an `until: "rest"` effect with `lockedHp`.
  That effect cannot be manually dismissed. Permanent HP plus active `lockedHp` sets the healing/pip floor,
  including host-applied healing, death recovery and downtime rerolls. Old rest-duration locks release before
  that rest's recovery; newly brewed buffs and locks last until the following rest. Skipping brewing still
  expires old buffs. Sheet saves retain locks unless a short/long rest was completed.
  Testing-build changes also retain HP locks until rest, even when their originating feature is removed.
  `extraMoves` grants unrestricted additional slots; `longMovesInShort` allows one long-rest move in a short rest.
  `passives` contain `{id, label, defaultOn, rest?, cost?, steps?, extraMoves?, reroll?, consumeItem?, move?, bonus?, condition?}`.
  These are inline opt-in/out checkboxes with source-scoped ids. Extra-move costs are paid when confirming;
  move-specific benefits/consumption apply only when that move is selected. `condition` is `armorMarked` or
  `noRecovery`; `reroll` keeps the higher of two downtime results. `modifiers` contain
  `{move, rest?, target?: "ally", bonus?, steps?, note?}` for benefits triggered by base rest moves.
  Only owned inventory, equipped weapons/armor, active domain cards/spells and learned features contribute.
- **restReroll**: `true` offers explicit per-die buttons on current-session short-rest chat reports (Oasis),
  including other characters' reports. New reports carry optional `actorId` and `restDice` metadata with the
  expression, result and report-line index; recovery dice also record the affected resource and remaining recovery.
  The host keeps the higher roll, applies only the additional recovery (respecting the original recovery cap),
  and journals the changed sheets, source usage and original report together. Oasis declares
  `uses: {max: 1, per: "rest"}`, shared across all reports for that character; its buttons disappear when spent
  and return after the character rests. Each die can be rerolled once. A formatted action report announces the
  original, rerolled and kept results and actual additional recovery. Report updates use
  additive `chatUpdate` messages; historical reports and older clients without metadata remain readable.
- **Action report titles:** feature reports use sheet feature `source` provenance to name the originating
  community, ancestry, class or subclass, followed by the feature and action, omitting adjacent duplicate names
  (for example `Hearthborne - Close-Knit`). Item/card names remain their own sources. Browser and host reports
  use the same convention; a repeated name followed by a parenthetical qualifier, colon or spaced-dash suffix
  replaces the shorter name rather than duplicating it. Roll-option labels use this convention too
  (for example `Orderborne - Dedicated (Hope d20)`). The readable `ACTION USED` chat format is unchanged.
  Host-generated reports from chat reactions and downtime rerolls also attach `rollSource: {name, text, source}`
  from the activated feature, not the original attack's weapon. Snapshots retain the feature description and
  origin in saved history even after later sheet edits; names/origins are bounded to 240 characters and text
  to 16,000. Reactions preserve the original roll's whisper recipient.
- **rollTriggers**: optional array of {id, label, on, when?, die?, matchResource?, cost?, steps?, reroll?, advantage?, description?} shown as buttons under rolls in chat. `audience` defaults to `"self"`; `"ally"` offers it on other players' rolls and `"any"` on both. `on` is `duality`, `action`, `attack`, `spellcast` or `trait:<Name>`; `when` is `always` (default), `fear`, `hope`, `failure` (shown on every roll, since players never know the difficulty), `hopeDie` (with `die`) or `dieMatches` (a Duality Die equals the number in the `matchResource` pool). `outcome: "hope"` changes the original roll's outcome without changing dice or total; Hope/Fear-dependent buttons and outcome badges read that override, while equal dice remain critical. `requiresEffect`/`endEffect` gate a trigger on, and end, an active effect (Ranger's Focus). `on` may also be `card:<Card Name>` for a domain card's own Spellcast roll. A trigger with `damage: {dice, fixed?, extraDice?, type?}` opens the damage dialog (`fixed` uses the dice count as written, otherwise it scales with Proficiency; `extraDice` adds Proficiency dice) and only pays its cost/steps when the damage is sent. Actions may carry their own `uses: {max, per}` (per-action limit) and `sendRoll: {dice, base: 'evasion', label}` which posts a plain roll to the chat without changing the sheet (I See It Coming). Spell cards only get a Spellcast button when their text asks for the roll. The roll dialog uses Advantage / Normal / Disadvantage buttons and a Critical checkbox for damage. `reroll` `hope` rolls a new Hope Die; `duality` repeats the roll (`advantage` adds a d6). Rerolls produce only a roll card, never a duplicate action message. Attack rolls also get a Damage button that behaves like the sheet's, including configured ancestry weapons.
- **Roll rewards:** `outcome: "hope"` never grants Hope automatically. The original roller claims +1 Hope
  from the updated chat card, once per roll. Critical rolls instead claim +1 Hope and clear 1 Stress.
  At maximum Hope, only criticals with marked Stress can still claim; no-benefit claims stay unconsumed.
  The host persists claims and character changes together; older hosts/history do not expose claim buttons.
  `rollTriggers[].addDice` rolls additional dice on the host after the original action, paying costs once
  and updating the saved roll's expression, dice, total and breakdown through additive `rollUpdate` fields.
  Original Hope/Fear dice and outcome are unchanged. Heart of a Poet uses this for its 1-Hope d4.
  Effects may also carry `rollTriggers`; the host resolves their definitions from the learned granting
  feature, not from editable effect metadata. `dualityDieBonus: {die: "hope"|"fear", value: number}`
  adjusts that die and the total on the finished roll, preserving its original face in the host's saved roll
  and identifying the adjustment in the breakdown. Equal adjusted dice are a critical success. The
  `hello.dualityDieBonus` capability gates these buttons on older hosts. `requiresEffect` and `endEffect`
  make both choices share one consumable effect (Contacts Everywhere).
  A trigger's `weapon` restricts it to the named attack weapon. `tokenGain: {resource, max}` asks for a positive
  integer `amount` through a styled in-app popup with inline validation and Cancel/Feed controls (no browser
  prompt). Cancellation spends nothing; request errors stay inline with input retained for retry.
  The feature request adds it to the capped pool atomically with the cost and per-roll
  claim (Feed); hit success and ability to bleed are player-confirmed. `when: "hopeOrCritical"` includes equal
  Duality Dice (Wolf Form); existing `when: "hope"` continues to exclude criticals.
  `on: "damage"` offers a reaction on damage results, including single-die rolls, without inventing
  Hope/Fear dice or offering Duality-only reactions. Damage triggers use `when: "always"` (default);
  the host enforces the roll kind, ownership, costs and one use per roll (Death Strike).
  `weaponAttack: true` requires an attack source with a named weapon (Marked For Death, Toxic Concoctions).
  Success, target state and damage severity remain player-confirmed, not inferred from dice or prose.
  `excludeRolls: ["attack"]` suppresses a trigger whenever any excluded target appears in the roll source,
  even when its `on` target also matches. Both browser and host enforce this for Stealth Expertise,
  including reactions to allies' attacks; non-attack Fear action rolls remain eligible.
- **Reaction rolls:** the existing comma-separated roll `source` uses `reaction` instead of `action`,
  retaining trait/spellcast targets. `roll: "reaction"` gates optional bonuses and modifiers to reaction rolls;
  `on: "reaction"` gates chat triggers. Declare separate entries for bonuses explicitly applying to both kinds.
  The host never offers or accepts standard Hope/critical Stress-clear rewards for reaction rolls.
  Quick Reactions is a reaction-only option, not a standalone Stress-spend action; Anchoring Hook is an optional
  reaction advantage with its forced-movement condition player-confirmed. Missing reaction-only equipment options
  are refreshed from the catalog without overwriting existing customized option ids.
  Gifted applies to action/reaction/damage, and empty Feed tokens impose disadvantage on action/reaction;
  Feed's Fear d20 remains action-only. Other reaction-related content (Mucin of Slipperiness restraint resistance,
  Wraith Trap's stored spirits/psychic condition, Repudiate, Counterspell, Hideous Retribution and Attack of Opportunity)
  remains manual where no roll automation is declared; use the relevant trait's Reaction mode.
  No new required message field or compatibility-version change; old rolls without `reaction` retain their behavior.
- **rollOptions**: optional array of `{id, label, roll, value?, dice?, diceByLevel?, cost?, steps?, description?,
  uses?, consumeItem?, weapon?, effect?}`. `effect` (`{name, kind, until}`) is started by the first roll that ticks
  the option (paying its cost); while that effect is active the option is pre-ticked and free. Entries that support
  standalone activation define an `activate` action with the same cost and effect. `consumeItem` options on
  inventory items (the "+1 to next <trait> roll" potions) are listed unticked and use up one item when ticked. These are **opt-in**, situational additions offered together in the matching roll popup,
  not always-on modifiers or immediate resource-spending buttons. `value` is a numeric/formula flat bonus,
  `dice` extra dice, and `diceByLevel` supplies level-threshold overrides with `dice` as the fallback
  (Patron Die: d6 initially, d8 from level 5). Targets are the roll targets listed above.
  Validate combined selected costs/uses before sending; pay, consume stock and apply steps only after successful
  WebSocket send, never on cancellation or a failed/disconnected send. This confirms sending, not host acknowledgement;
  no rollback protocol is implied. `consumeItem: true` explicitly consumes a consumable;
  options on reusable items default to **not** consuming stock. A description is an applicability requirement,
  not a target/range detector. Choosing between mutually exclusive alternatives (Elementalist, Cruel Precision)
  remains the player's responsibility.
  `weapon` is `source`, `primary`, `secondary` or `any`; weapon-owned options default to `source` (the selected
  weapon must be the option's source). Paired/Double Duty/Focused explicitly use `primary`, so an equipped
  secondary weapon can grant its bonus to primary-weapon damage. Nonweapon sources have no weapon restriction
  by default; other weapon-local options retain the `source` default.
  `defaultOn` preselects an option. `advantage: "advantage"|"disadvantage"` suggests the corresponding roll mode
  when toggled; players may override it. `ignoreDisadvantage: true` omits a selected disadvantage die and explains
  this in the breakdown (Surefooted). `requiresEffect` only offers the option while the named effect is active.
  A primary/secondary restriction also applies to nonweapon sources such as Ignition, not to unrelated damage.
  `hopeDie: 20` replaces only the Hope d12 with a d20 (Dedicated), retaining the Fear d12, modifiers and normal
  Duality outcomes/rewards. `input: {label, default, min, max}` accepts an integer flat bonus (Found Family:
  ally Experience, default +2). `quantity: {resource, label}` accepts a positive integer up to the available pool,
  multiplying the option's flat bonus, added dice count and all costs by that quantity (Know the Tide, Slayer). Invalid quantities block
  sending; cancellation and failed sends spend nothing. Source-wide uses remain shared with sheet actions.
  `fearDie: 20` independently replaces the Fear d12 with a d20 (Feed); Hope and Fear can both be d20.
  `resourceEmpty: "<id>"` offers an option only at zero tokens. Combined with `defaultOn` and
  `advantage: "disadvantage"`, it suggests hunger disadvantage without removing the player's override.
  `dice` and its replacements support `@tier` as a dice count (`"@tierd4"`).
  `diceByFeature: {"Ambush":"@tierd6","Backstab":"@tierd8"}` replaces the dice when the character has
  that learned feature; the last matching entry wins. This keeps subclass upgrades in JSON.
  Wizard Face Your Fear uses the same mapping for Fueled by Fear (2d10) and Have No Fear (3d10).
  `experience: {resource: "stress", multiplier: 2}` on a duality option requires a selected Experience,
  replaces its 1 Hope cost with 1 Stress and doubles its modifier (Adept). This replacement cost is
  included in combined affordability checks, not duplicated in the option's `cost`.
  Automation-level `experienceRefund: {dice: "1d6", min: 5}` automatically rolls when submitting
  an Experience-backed roll (Honed Expertise). The client-local die and refund outcome appear in a
  zero-value breakdown entry, without affecting the action total. A qualifying result refunds the
  Experience cost only, including Adept's Stress; unrelated selected-option costs remain payable.
  Affordability is checked before the refund roll. Cancellation and failed sends pay nothing.
  Refunding Adept Stress is an intentional extension of Honed Expertise's printed Hope-only rule.
  Passive modifiers support `hopeMin: 2`, evaluated live for both derived stats and their breakdowns
  (Conjure Shield's `evasion` modifier with `value: "@proficiency"`).
  Thrive in Chaos uses an ordinary `on: "damage"` roll trigger costing 1 Stress; the existing host
  action validates the damage message, pays the cost and posts the feature report. Successful-attack
  and additional target HP conditions remain player-confirmed.
  `diceConditions: [{feature?, unmarkedHp?: {min,max}, dice?, dieSize?}]` applies matching overrides after
  level/feature scaling. `dieSize` changes the die faces without changing the scaled dice count.
  Actions/options support `costConditions: [{feature?, unmarkedHp?: {min,max}, cost}]`, merging matching
  resource overrides into the base cost. Bounds are inclusive and re-evaluated from current HP.
  Passive modifiers support `requiresEffect`, including stat breakdowns. Roll options also support
  `excludeRolls` to hide an option whenever an excluded roll target is present. This prevents applying Wolf Form's
  bonus twice on an attack already receiving its die and keeps Underborne Low-Light Living off attack rolls.
  `actionRoll.trait: "spellcast"` selects the character's Spellcast trait and includes `spellcast` plus the
  feature key in the roll targets. `rollTriggers.when: "success"` requires a known difficulty, supplied by
  `rollTriggers.difficulty` or the original roll; raw matching Duality dice also succeed. Browser and host
  both enforce the result, while existing `failure` triggers retain their table-confirmed semantics.
  `multiplier: 2` doubles the entire damage roll after bonuses and critical damage, never the number of dice.
  It does not stack with another doubling option. The host advertises `hello.rollMultipliers`; older hosts
  block sending this option with an update-required message. The bounded expression form is `(expression)*2`;
  named contributions use the same multiplier, which the host validates and applies to the actual rolled dice.
  Browser and DM chat show the multiplier separately from flat modifiers, preserving raw and discarded dice.
  Damage dialogs for spells also include ordinary `damage` options.
  Marked For Death gates its optional bonus with `requiresEffect`; its effect expires on a rest and must be
  removed manually when the adversary dies or the GM spends Fear. First Strike's first-success condition and
  Death Strike's Severe-damage condition remain manual. True Strike's chat and sheet actions share source uses.
  Fear Leaf uses `input` for the attack's Fear Die result (1-20), not the GM Fear pool or a new roll.
  `input.source: "attackFear"` prefills it from the attack chat card whose Damage button was clicked.
  The value remains editable and the option stays unticked; direct sheet damage or missing/invalid context
  uses `input.default`. Other numeric inputs are unaffected; context is not retained between dialogs.
  Fear Leaf and Leech Weed options each cost one poison token; Afflict with poison is an alternative way
  to spend a token for a poison, not an additional required payment.
- **damage**: `{extraDice: number, dropLowest: number}` on a weapon changes **only that source weapon's base
  damage dice**, never bonus dice from coatings/options or another equipped weapon. Powerful/Massive use
  `{extraDice:1, dropLowest:1}`. Roll one extra weapon die and discard the lowest before adding the unchanged
  flat bonus and independent bonus dice. The host's kept-dice expression is `NdSkhK` (for example `4d6kh3+6`);
  chat records all rolls and the kept total. An attachable Greatstone does not grant this to every carried weapon.
- **creation**: additive `{hp?, stress?, evasion?, domainCards?, domains?, experienceBonus?}`; numeric slot/evasion/card allowances
  and optional extra accessible domain names. Apply through the Character Mancer once, not as repeating resource
  gains. Whole/mixed-ancestry feature selection must respect which feature was chosen. Endurance, High Stamina,
  Nimble, Prepared, Battlemage and At Ease are data-driven; Stoneskin remains a passive Armor Score/threshold bonus.
  `experienceBonus` asks the player which creation Experience permanently receives the bonus (Purposeful Design).
- **manual**: optional array of guidance strings for table-confirmed prerequisites and manual adjustments.
  These notes do not execute operations or replace the original descriptions, which stay visible.
- Armor automatically sets damage thresholds (base major/severe + level; unarmored level and 2x level) and the armor
  slots from the armor score.

## Catalog coverage

The maintained catalog covers **1,187 entries / 1,296 rule sources**, including expansion content, not just core
classes or the named examples. A source is an individual class/heritage/transformation feature, or one gear/card/item
entry. The counts below describe the current catalog snapshot; edit them when catalog changes affect the totals.
“Executable” means at least one modifier, counter/usage upgrade, action/chat benefit, roll option, creation/rest grant, damage-mitigation option, weapon-damage operation or death-move metadata;
**it does not mean the whole feature is automated**. A cost/use-only button tracks the owner's spending, not the
target's outcome. “Plain gear” has no feature to automate; the domain-card column also includes
unresolved automation, not only narrative text. See the individual audit ledger before interpreting coverage
as complete automation.

| Category | Entries | Rule sources | Executable | Plain gear |
|---|---:|---:|---:|---:|
| Domain cards | 236 | 236 | 223 | 13 |
| Armor | 110 | 110 | 80 | 30 |
| Weapons | 336 | 336 | 144 | 192 |
| Consumables | 182 | 182 | 62 | 120 |
| Items | 174 | 174 | 84 | 90 |
| **All categories** | **1,187** | **1,296** | **777** | **519** |

Classes, ancestries, communities and transformations are included in the total.

### Curated mappings

- **Blood Hunter:** Blood Maledict grants a rest-duration action-advantage option against the chosen target;
  Severe damage or reuse ends/replaces it. Crimson Rite grants optional level-scaled damage without a weapon
  or damage-type selection; it expires on rest. Chasing Death waives the HP cost at 1-3 unmarked HP and uses
  d8s at exactly one unmarked HP, preserving level-scaled dice counts and changing back after healing.
  Grim Psychometry rolls the Spellcast trait against 12; only a successful feature roll offers the once-per-roll
  Stress-cost vision claim and lore-advantage buff. Veilwalker preselects next-attack advantage and removes its
  buff when that option is used (or on rest/scene expiry). Werewolf offers hearing/scent advantage, but never
  adds a transformation automatically. Control the Beast offers the Wolf Form d10 on non-attack Agility/Strength
  action rolls; Feral Hide adds Tier to both thresholds only with Wolf Form.
  Mutagens is an optional free additional brewing move at either rest. Choose distinct +1/-1 traits (+2/-2
  with Mastered Mutagens) and one unlocked benefit, or two with Volatile Toxins for one HP locked until the
  next rest of either kind. Improved and Mastered Mutagens unlock their extra choices automatically.
  Each benefit's `effect.text` describes both its downtime choice and its expanded buff. Saved mutagen buffs
  resolve descriptions from current definitions; Volatile Toxins uses its own feature text rather than Mutagens.
  Shadowed Grit uses `excludeRolls: ["reaction"]` to suppress and reject reaction-roll claims.
  Celerity pays Stress to remove Restrained/Vulnerable effects; Durable adds 2 Armor Score; Hunter's Senses
  suggests tracking advantage; Nerves of Steel has a 2-Hope replacement-cost button; Rapidity adds 1 Evasion;
  Ironskin/Steelflesh add Proficiency to Severe/Major thresholds; Fury is a Stress-cost attack bonus equal to
  Proficiency; Aetherblood remains a narrative buff. Range, chosen Maledict target, hearing/scent, tracking,
  vision content and replacing a Stress payment remain player-confirmed. New metadata is additive; no
  settings or DM Link compatibility change.

- **Bard:** Rally grants one d6/d8 (level 5) session effect to every player-owned actor with a sheet;
  Epic Poetry upgrades it to d10. Spend it optionally on action, reaction or damage rolls, or use its
  Effects-tab action to roll it and clear Stress. Each use removes the whole effect. Relaxing, Epic and
  Heartbreaking Songs are separate long-rest features/counters; Virtuoso raises each limit to two.
  Relaxing/Heartbreaking heal the bard or grant Hope and offer once-per-other-player chat claims;
  Rousing Speech offers clearing 2 Stress to allies. Maestro's Rally report offers one Hope or one Stress
  recovery, mutually exclusive per player. Range/narrative conditions remain table-confirmed; Epic Song's
  adversary Vulnerable condition remains manual. Heart of a Poet is a finished-action d4 button, not a
  pre-roll option. Epic Poetry's Help-an-Ally action spends the usual 1 Hope and rolls a d10 advantage die.
- **Guardian:** Unstoppable's scene effect has `counter: {value: 1, max: 4, maxByLevel: {"5": 6}}`.
  Feature `effectCounters: [{label: "Unstoppable Die", effect: "Unstoppable"}]` displays a read-only class
  resource in the header and feature controls, reading the active effect's value and level-scaled die size
  directly (Inactive without the buff). It does not create a second saved resource value.
  Effect roll options with `effectValue: true` add that effect's current counter value; its damage bonus and
  physical-severity mitigation are default-on options. The mitigation description explains the physical-only
  one-threshold reduction (Severe to Major, Major to Minor, Minor to None). Saved effects refresh mitigation
  descriptions from their feature's current action metadata without resetting the counter or mechanics.
  `advanceEffect` on a damage trigger advances the
  named counter once per roll and removes the effect on overflow. `usesSource: false` separates this reaction
  from the activation's long-rest limit; it does not refresh or spend that activation. Qualifying HP damage
  and immunity to Restrained/Vulnerable remain player-confirmed. Scene end removes the buff.
  Act of Reprisal uses a situational damage option with `proficiency: 1`.
  Nemesis grants one rest-duration Prioritized effect for 2 Hope; its `swapDuality: true` attack trigger
  exchanges the actual Hope/Fear results, updates breakdown/outcome and preserves the total. Matching dice
  cannot be swapped and each roll permits only one use. The prioritized adversary is table-confirmed.
  Additive `hello.guardianRolls` gates new chat actions on older hosts; no compatibility version changes.
- **Warlock:** initial 3 Favor, **uncapped**; downtime tribute gains `@spellcast`. `classResourceOnly: true`
  hides the duplicate feature resource controls. `hopeReward: {favor: 1, roll: "action"}` offers a
  host-authoritative exclusive Favor reward instead of Hope on successful Hope action rolls, including when
  Hope is full (not reactions, Fear or criticals). A roll cannot award both rewards.
  Patron's Pact is an optional action-roll Patron Die costing one Favor, d6/d8 at level 5.
  Patron's Boon has the normal Hope feature button and its unchanged chat reroll.
  `patronDice: "heal" | "vengeance" | "ire"` actions ask for a positive whole-number `amount`; the host
  rolls d6/d8 Patron Dice. Heal spends that much Favor and clears HP per 4+ with actual recovery reported;
  vengeance spends one Favor and takes HP marked as the dice count, reporting attacker HP per 4+;
  ire spends one Favor per die and reports highest-result Close-range targets, each marking one HP.
  Deathless Embrace and Otherworldly Ire share their feature's once-per-rest limit. Ire is not mitigation.
  Vengeance mitigation uses `patronDice: "vengeance"` and final HP marked after mitigation, spending nothing
  if no HP is marked. Attack origin remains player-confirmed. `menacingReach: true` spends the configured
  Favor cost to grant a buff naming the primary weapon and its one-step-higher reach (capped at Very Far).
  Its `primaryWeapon: true` attack trigger ends the effect after a player-confirmed successful attack.
  `spendFavor: true` feature/Hope-action triggers take Favor input and report equal target Stress
  (Diminish My Foes). Fearsome Attack uses `damageDice: "reroll", repeatable: true` on damage results,
  reusing the individual-die selector, stale-selection validation and original-roll updates; every use costs
  one Favor, and the original feature button remains. New actions/rewards require `hello.warlockAutomation`.
  Pact Mantle's thresholds and intimidation option end on taking Severe damage. Fury's tier-scaled Patron Dice
  (a damage-dialog option costing 1 Favor that starts a scene effect; while active it is pre-ticked and free;
  remove the effect manually after dealing Severe damage), and the fixed-Favor costs of Endless/Wrathful
  subfeatures are explicit. Adversary HP/Stress application and unknown success/target conditions remain
  player/GM-confirmed; no settings or compatibility-version changes.
- **Other class/heritage sources:** permanent Stalwart, Shell, Rugged, Ascendant and Fleeting Shadow bonuses;
  creation grants; Battle Ritual, Limit Breaker, Deadly Determination and Frontline Tank resource actions;
  Rogue's Dodge effect; No Mercy as an effect-based attack option (pre-ticked while active); optional Tusks, Kick, Elementalist, Wings of Light, Sneak Attack,
  Combat Training, Adrenaline and Face Your Fear. Later upgrades that replace another feature's die are manual
  where not encoded on the originating option.
- **Ancestry review and matching rules:** Elemental Breath/Long Tongue attacks and damage; Charge reactions and
  fixed damage; Fungril Network against 12; Retracting Claws; Unshakable; Purposeful Design; Ignition; Retract;
  Surefooted and situational advantage options. Hallowed Aura/Luckbender react to allies, Luckbringer grants party
  Hope, Eye of the Storm maintains a caster-linked target buff, and Lifespring heals a chosen target.
  The catalog-wide audit encodes "once per scene" limits as `per: "scene"` uses and also mapped Unbound, Stealth Expertise and Red Nose
  of Hope outcome conversion; simple situational community/gear advantages; Nature's Tongue, Goad Them On and
  Woven Net trait rolls; and Life Support, Healing Strike, Sparing Touch and Restoration target healing.
  Range, willingness, attack success and narrative prerequisites remain player/GM decisions. Complex healing
  choices such as Healing Hands (result-dependent amounts and per-target rest limits) and area/group healing
  still need their own workflows rather than guessed targets.
- **Pools:** Favor, Slayer Dice, Tide, poison tokens, Spellcharge, Inspiration, Restoration, thorns, Dark Army
  fiends, Twilight Toll, chaos, Invisibility/Disguise tokens and Alconite charge. Explicit gain/spend/clear buttons
  track **numeric tokens** only. Follow descriptions for start/end-session and rest preparation/clearing; rest
  buttons do not synthesize arbitrary pool refreshes. An option spends one token when the rule permits spending
  several; additional tokens/dice are manual. Negative trait/formula results must never grant negative tokens.
- **Gear/items:** all original unconditional armor/weapon penalties and bonuses retained; conditional primary
  damage from Paired/Double Duty/Focused is opt-in. Powerful/Massive cover 14 weapons. Charging Quiver is optional
  `@tier` damage **only for arrows stored in it**. Piercing Arrows is `@proficiency` damage with its own
  three-per-rest option limit. Six trait relics grant +1 while active; Lorekeeper/Cursed Carols bonuses are
  situational. Alconite recharge requires its explicit salt-water downtime action.
- **Domain cards:** optional Body Basher, Cruel Precision, Bold Presence, Boost, Forceful Push, Natural Familiar,
  Nature's Tongue and Runic Adrenaline; Rise Up/Notorious passives; Full Surge and explicit self-healing/recovery
  actions. Bold Presence's condition avoidance has an independent once-per-rest action limit; its Presence roll
  option remains unlimited. Astral Projection marks Stress; Bloodbath, Arcane Deflection and Shared Clarity
  spend Hope on their action buttons. Battle Cry offers other players a Bard-style chat claim for clearing
  1 Stress and gaining 1 Hope, once per player; hearing and advantage remain table-managed. A Soldier's Bond
  has no automation. Mending Touch offers unlimited base healing and a shared once-per-long-rest upgrade;
  its target and narrative prerequisite are player-confirmed. Summon Horror's action marks 1 Stress.
  Dark Army's summon and Thorn Skin's cast have independent long-rest and rest limits respectively;
  either rest clears their tokens, even if the cards are in the vault. Rest-time resource resets include
  vaulted card definitions, with active definitions taking precedence for duplicate resource IDs.
  Confirm Dark Army's Spellcast success at the table.
  Book of Illiat's Arcane Barrage prompts for Hope and sends that many d6 magic damage dice; it spends
  Hope and its rest use only after the send succeeds. Its `conditionalActions` metadata is ignored by
  older clients.
  Armorer and Fortified Armor use conditional modifiers that apply only while armor is equipped.
  Blink Out uses `spellcastDifficulty` and a host-validated `conditionalRollTriggers` Hope-quantity
  action; older clients ignore the trigger and newer clients require host capability support.
  Book of Homet's `automaticRestUse` consumes each spell's separate rest use on a successful
  host roll at `spellcastDifficulty`, not on failure. Travel is resolved at the table.
  Banish's `banishDifficulty` reaction rolls the current Spellcast trait's d20s on the host and stores
  their highest result on the card; players can manually edit or clear that value.
  Arcana-Touched uses a host-validated conditional chat reaction to swap Hope and Fear once per rest;
  vaulted Arcana cards do not count.
  Blood- and Valor-Touched use conditional modifiers and damage rewards, evaluated after mitigation
  when confirming damage. These metadata fields are ignored by older clients.
  Codex-Touched's conditional `codexSwap` action swaps the card with a chosen vault card and has its
  own rest limit, separate from its Stress-paid Spellcast option.
  Midnight-Touched offers a Stress-paid attack Fear die damage option and a zero-Hope, once-per-rest
  Hope action. Confirm attack success and the prevented GM Fear at the table.
  Frenzy's manually ended effect carries its damage/threshold bonuses and Armor restriction.
  Healing Field offers free and upgraded party healing with one shared long-rest use.
  Eldritch Flesh and Power Through Pain derive their bonuses from currently marked Stress and HP.
  Shadowhunter uses a player-managed lighting effect, while Voice of Reason offers its situational
  advantage and preselects its damage Proficiency bonus only with a full Stress track.
  Dread-Touched's Fear reaction rejects known failures; if Difficulty is hidden, the player confirms
  success and the GM handles the prevented Fear.
  Body Basher and Cruel Precision apply the selected current trait to appropriate weapon damage.
  Deadly Focus ends at player discretion, Critical Inspiration uses ally claims, and Magic Immunity
  negates magic damage until rest.
  Runic Lock uses the same successful-cast rest tracking as Book of Homet. Lean on Me uses an
  exclusive ally claim; the failed roll and proximity are player-confirmed.
  Conjure Swarm's beetles reduce the next non-negated incoming damage automatically and disperse
  unless the optional Hope payment retains the effect.
  Weave the Flesh offers separate base and upgraded ally benefits with a shared rest limit.
  Safe Haven's rest passive has no additional cost, and Specter of the Dark creates a manually ended physical-immunity effect.
  Other target effects remain table-managed. Separate healing choices never
  clear both resources unless the export says both. The individual domain-card review is not yet complete.
- **Consumables:** existing healing and next-roll trait potions retained; Sweet Moss, Self-Sewing Thread,
  Nursewood Sap, Godling's Pomelo, Votive Bloom Nectar and Tincture self-healing choices; Steelskin, Featherstep,
  Drakemantle, Vigilance, Lionheart, Demiurge and Needle Key temporary modifiers. Six weapon coatings are
  opt-in, consume one stock explicitly, and require the **coated weapon**; ongoing target damage/conditions stay manual.

### Druid forms and elements

`Beastform.automation.beastforms` contains the 24 categories with tier, examples, trait/bonus, Evasion,
attack damage/range, advantages and feature automation. `evolved` specifies eligible base tiers and additive
damage/trait/Evasion upgrades; `increaseDie` upgrades the main attack die. `hybrid` specifies category,
advantage/feature counts and extra Stress. Hybrid source categories are distinct ordinary categories, excluding
composite Legendary/Mythic entries; advantages and features are deduplicated by name. `beastform: true` and
`evolution: true` actions open the picker, validate every selection before paying, and Evolution selects its
additional trait. A form effect has `beastform`, `weapon`, and temporary `features`; equipment is never overwritten.
Temporary features participate in passive stats, roll options, actions and mitigation and appear only while
their effect is active. `actionRoll.trait: "beastform"` uses the active form's attack trait, including in hybrids.
Action-created effects from a Beastform feature inherit that form as their `requiresEffect` parent.

Effect `group` replaces other members of the group; `requiresEffect` expires a child when its named parent
ends. `endOnDamage` is a minimum resolved severity (2 Major, 3 Severe), including mitigated damage whose actual
HP marking is capped. Forms also drop on the last HP. Domain-card spells are blocked during Beastform; existing
spells remain active. Bird's-Eye View retains its per-rest counter across form changes. Unconditional thresholds
come from temporary features; situational advantages, Pack Hunting, Takedown, Rampage and Vicious Maul are
player-selected options. Each form's advantages use one `duality` option listing all applicable activities.
Venomous Bite/Strike poison and Elemental Incarnation Fire retaliation use `sendRoll` for an unmodified 1d10
without a damage-options dialog. Poison is a separate direct physical damage roll when the target acts,
not an optional bonus to the initial hit. Deadly Raptor sets additive `damageRules.rerollBelow` to Proficiency; the host rerolls
each eligible damage die once, counts rerolls against its dice cap and annotates the breakdown. It is gated by
`hello.druidAutomation`; unsupported hosts do not silently ignore the rule. The same capability gates form,
channel and aura actions. Compatibility version constants are unchanged.

`channel: "<Element>"` actions start exclusive rest-duration channel effects ending on Severe damage.
Owned `channelBenefit: {element, effect}` metadata grants the matching Dominion effect at channel time.
`aura: true` selects the matching `Elemental Aura: <Element>` feature's effect, requires channeling and spends
the once-per-rest use. Switching channeling ends an aura rather than transferring it. Effects and conditional
buttons have exact, unique names (`Channeling: Fire`, `Elemental Aura: Fire`, `Elemental Dominion: Fire`, etc.).
The host synchronizes Earth aura grants to every saved player-owned sheet, including offline actors and the
caster. Each grant offers an unchecked, non-stacking `trait:Strength` +1 option; players confirm Close range.
Grants end when their source aura ends, not when another druid's aura ends. Fire Dominion adds one damage die
to Proficiency-based damage pools (`damage.proficiencyOnly: true` excludes fixed-dice retaliation and poison).
Earth Dominion uses mitigation `markedHpOnly` to roll per HP actually about
to be marked (after the remaining-HP cap), subtracting one HP for each 6; Air grants +1 Evasion.

`report: {amount?, dice?, text}` posts a local action consequence with `{amount}` substitution: Clarity uses
the effective Instinct value and Warden's Protection rolls its ally count. Regeneration is a host-resolved
`target: "actor"` action with a `1d4` clear-HP step; reports include both the rolled healing and actual recovery.
Cannonball uses existing chat benefits with `exclusiveClaim: true` (only one throwing ally per invitation).
`allowSourceOwner: true` permits another actor owned by the initiating player, never the initiating actor.
The claim grants temporary Cannonball Throw features; `featureAction: {name, id}` opens the selected Agility
or Strength attack on the throwing ally's own sheet. Damage uses that ally's Proficiency; its temporary effect
ends after damage or at scene end. An interrupted throw remains available in Effects. Ordinary healing/song
claims retain their prior per-player behavior.

### Explicit unsupported boundaries

- **Cross-actor/GM state:** range/line of sight, companion stats, GM Fear,
  target Difficulty/thresholds/HP/Stress, resistance, damage prevention/reflection and forced movement. Examples:
  Healing Hands, Hex, Drain, unconfigured aura allies and target-dependent damage. Explicit `target` actions
  support character selection/healing and configured effects; this does not infer general target automation.
- **Roll-result-dependent rules:** trigger validation, changing Hope/Fear die size or values, reroll selection,
  exploding dice (Brutal/Recursive), special face replacements (Serrated/Self-Correcting), doubling matching dice,
  critical conversion, damage severity changes and arbitrary after-the-fact roll replacement. Explicit outcome
  overrides and configured rerolls are supported without inferring other replacements. Ordinary optional extra dice
  are supported; these algorithms are not inferred from prose.
- **Conditional passives and substitutions:** Wolf Form/Feral Hide, Conjure Shield, Irontree reinforcement,
  Defiant Bulwark, Vowkeeper and Heartsteel, four-domain “Touched” loadout conditions, resource-dependent
  thresholds, proficiency for only one attack, min/half/multiplied formulas, and effects ending on a damage/result
  trigger unless explicitly configured (`endOnSourceSevere`). Conditions never become always-on bonuses.
  Other early-expiring numeric effects have explicit removal notes.
- **Selections and permanent changes:** Bare Bones' armor replacement table, Vitality's choose-two bonuses,
  Mutagens/Transcendence choices, arbitrary chosen Experiences, scars, permanently lost slots, copied/attached/transferred
  item features, unconfigured transformation statistics and replacing upgraded feature dice. Beastform choices
  are explicitly supported as above; no choice is guessed.
- **Complex pools/multiple sub-abilities:** Prayer Dice keep distinct rolled face values;
  Wild Surge, Moon phases and Zone of Protection keep stateful die faces; variable token expenditure, rolled-table
  outcomes and automatic rest/session hooks stay manual unless an explicit button/option is listed. Grimoires
    (`Book of ...`) have no card-level automation; each spell in `spells[]` is its own feature with its own
    parsed cost/usage button, Spellcast roll button and usage counter (key `card:<Book>/<Spell>`), so spells never
    share one subspell's cost/use. The sheet draws a separator line between spells.
  Unconfigured usage upgrades such as Devout/Reliable Backup do not invent new once-per-rest actions.
- **Narrative/world/inventory:** crafting recipes, foraging, downtime projects, timers/countdowns, conjured objects,
  item attachment, form/appearance changes, portals, memory/information gathering, immunities, senses and travel.
  Descriptions and manual boundary notes remain available, even when there is no executable action.
- **Rests:** content-specific rules are configured directly in each relevant catalog entry's automation. The runtime
  reads metadata, never feature/item names or labels. Base SRD rest moves remain engine rules.
  Passive benefits are shown as inline checkboxes with configured defaults.
  Moves are validated: no duplicates, and at most one long-rest move in a short rest. The chat
  report is plain text (`rest-summary.ts`: `SHORT REST` header, subtitle, `•` bullets) that `chat.ts` renders as a card.
  Ally targets and the GM's Fear gain are not applied.
  `engine.ts` shares metadata-only rest classification between sheet controls and the rest dialog.
- **Damage mitigation:** configured directly in each relevant catalog entry's `automation.mitigation`: an array of
  options shown in the Thresholds damage dialog (`damage.ts`, `damage-view.ts`). Sources are the
  equipped armor and weapons, class/ancestry/community features, loadout cards and inventory items with quantity.
  Option fields:
  - `id`, `label`, `description`; `passive` (always applied when its conditions hold) or `defaultOn`.
    `requiresEffect` only offers the mitigation while the named effect is active (Retracted).
    `endEffect` consumes the named effect only when the mitigation actually applies (Reliable Backup).
    `excludesEffect` blocks activation while the named effect is already active (Wolf Form).
  - `when`: `type` (`physical`/`magic`), `severity` (list of `minor`/`major`/`severe`/`massive`, after reductions),
    `armor`, `lastArmor`, `armorFull`, `lastHp`, `hpUnmarkedMax`, `min: {value, at}` (e.g. `@cards_splendor` ≥ 4).
    `markingHp` requires at least one HP actually marked after armor and other mitigation.
  - `cost` (resource → value; `@hp` = HP the damage would mark), `uses` (`"source"` shares the source's `uses`,
    or its own `{max, per}`), `consumeItem`, `quantity` (player picks a count; cost and dice scale).
  - Effects: `thresholds` (bonus), `double`, `halve`, `reduce` (value), `negate`, `severity` (steps down), `hp` (+/- HP),
    `replaceHp` (pay `cost` instead of marking HP), `armorSlots` (number or `"any"`), `armorSeverity` (steps per slot),
    `armorCost`, `armorBlocked`, `saveArmor`, `roll: {count, sides, success, each}` (`count` may be a value, `"hp"` or
    `"quantity"`; without `success` the total reduces damage), and `followUp: {dice, text}` rolled on apply.
    `effect` adds a standard active effect only when the damage is applied (Wolf Form), never on preview/cancel.
    `markedHpOnly` applies an HP adjustment after capping damage to remaining HP slots.
  - Order: thresholds → double → halve (rounded up, resistance never stacks) → flat/rolled reductions → severity (massive = at least 2× Severe) →
    negate → Armor Slots → severity steps → HP adjustments → HP replacement. HP is capped at the unmarked slots.
    Values may use `@hp`, `@armorScore`, `@cards_<domain>` and the usual named values.
  Rest-only actions have no standalone buttons and
  `runAction` rejects them outside the rest context, except the explicit Shapeshifter `ancestryChoice` buttons;
  unrelated actions on mixed-use sources remain available.
  Automated downtime consumables are consumed when their selected move executes.
  Effects gained during downtime persist until the next rest. Failed resolution leaves the sheet unchanged.
  Existing character loads receive missing rest metadata for saved gear without replacing customized actions,
  modifiers, costs or existing rest definitions. Class/heritage/domain rules follow the compendium as before.

## Maintenance and validation

Maintain entries and automation directly in `dmmap-daggerheart/*.json`; do not infer conditional rules from prose.
Keep ids unique within each category, preserve the documented version-1 envelope, and ensure automation references
declared resources and uses supported field shapes. The catalog is application content, not campaign/user data.
Schema fields remain additive/optional in format version 1; old catalogs/clients retain their existing fields. This
is not a remote/online compatibility-version change. No new application setting is introduced.

The browser regression fixture `web\checks\automation-runtime.html` runs with the existing Vite dev server
(`npm run dev` in `web`, then open `/checks/automation-runtime.html`). It checks cancel/disconnected-send behavior,
combined costs and independent limits, Powerful capability gating, the Patron Die upgrade and editable Favor UI.

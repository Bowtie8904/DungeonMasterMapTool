# Daggerheart data format

`dmmap-daggerheart/*.json` is the maintained Daggerheart catalog and source of truth. Edit these files directly
when correcting data or adding entries; there is no converter or source-export dependency. The folder is configured
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

## Automation block

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
  are not rolled-die collections: Prayer Dice retain distinct rolled values and remain manual.
  Rally Dice use consumable effects instead of numeric resource pools.
  `session: true` declares a session pool (initially 0): it is saved with the sheet like other pools and
  reset to 0 when the DM clicks **New session**.
  Know the Tide uses this with `max: "@level"`; its Fear-roll trigger gains tokens once per roll.
  `longRestLoss` subtracts that many tokens automatically on each long rest, floored at zero (Feed).
  `resetOn: "longRest"|"shortRest"|"rest"` restores the pool to `initial` on that rest;
  a long rest also counts as a short rest. Toxic Concoctions uses `initial: 0, resetOn: "longRest"`.
- **uses**: `max` per `shortRest`, `longRest`, `rest`, `scene` or `session`. Scene uses reset when the DM clicks
  **End scene** or **New session** and on any rest; session uses reset only on **New session**. Reconnecting or
  restarting hosting resets nothing. Exhausted uses show "No uses left this scene/this session/until a rest".
  Scene-duration effects are also removable in Effects. Source-wide uses are shared by its actions/options; a roll option's own `uses` is an independent
  limit. A rest limit is not a custom resource recharge rule.
  `usesOverrides: {"feat:<Feature>": number}` raises another source's maximum while the granting feature
  is learned (Virtuoso), without resetting its usage. `grantsFeatures: ["<Feature>", ...]` refreshes linked
  catalog features on existing sheets (the split Gifted Performer songs), retaining their counters.
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
  multiplying the option's flat bonus and all costs by that quantity (Know the Tide). Invalid quantities block
  sending; cancellation and failed sends spend nothing. Source-wide uses remain shared with sheet actions.
  `fearDie: 20` independently replaces the Fear d12 with a d20 (Feed); Hope and Fear can both be d20.
  `resourceEmpty: "<id>"` offers an option only at zero tokens. Combined with `defaultOn` and
  `advantage: "disadvantage"`, it suggests hunger disadvantage without removing the player's override.
  `dice` and its replacements support `@tier` as a dice count (`"@tierd4"`).
  `diceByFeature: {"Ambush":"@tierd6","Backstab":"@tierd8"}` replaces the dice when the character has
  that learned feature; the last matching entry wins. This keeps subclass upgrades in JSON.
  `diceConditions: [{feature?, unmarkedHp?: {min,max}, dice?, dieSize?}]` applies matching overrides after
  level/feature scaling. `dieSize` changes the die faces without changing the scaled dice count.
  Actions/options support `costConditions: [{feature?, unmarkedHp?: {min,max}, cost}]`, merging matching
  resource overrides into the base cost. Bounds are inclusive and re-evaluated from current HP.
  Passive modifiers support `requiresEffect`, including stat breakdowns. Roll options also support
  `excludeRolls` to prevent applying Wolf Form's bonus twice on an attack already receiving its die.
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
target's outcome. “Plain gear” has no feature to automate. Sources without an executable operation are
purely narrative text.

| Category | Entries | Rule sources | Executable | Plain gear |
|---|---:|---:|---:|---:|
| Domain cards | 236 | 236 | 165 | 71 |
| Armor | 110 | 110 | 80 | 30 |
| Weapons | 336 | 336 | 144 | 192 |
| Consumables | 182 | 182 | 62 | 120 |
| Items | 174 | 174 | 84 | 90 |
| **All categories** | **1,187** | **1,296** | **719** | **577** |

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
- **Warlock:** initial 3 Favor, **uncapped**; downtime tribute gains `@spellcast`. After a **successful action roll
  with Hope**, choose **one** button: gain Favor instead without awarding Hope (also works when Hope is full),
  or replace Hope already awarded by spending it to gain Favor. Do not use these after other Hope gains.
  Patron's Pact is an optional action-roll Patron Die costing one Favor, d6/d8 at level 5.
  Pact Mantle's thresholds and intimidation option end on taking Severe damage. Fury's tier-scaled Patron Dice
  (a damage-dialog option costing 1 Favor that starts a scene effect; while active it is pre-ticked and free;
  remove the effect manually after dealing Severe damage), and the fixed-Favor costs of Endless/Wrathful
  subfeatures are explicit; damage prevention, reroll choice, range and target effects remain manual.
- **Other class/heritage sources:** permanent Stalwart, Shell, Rugged, Ascendant and Fleeting Shadow bonuses;
  creation grants; Battle Ritual, Courage, Limit Breaker, Deadly Determination and Frontline Tank resource actions;
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
  choices such as Healing Hands (result-dependent amounts and per-target rest limits), Mending Touch's special
  healing-time upgrade, and area/group healing still need their own workflows rather than guessed targets.
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
  actions. Separate healing choices never clear both resources unless the export says both.
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
- **Complex pools/multiple sub-abilities:** Prayer Dice keep distinct rolled face values; Unstoppable,
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

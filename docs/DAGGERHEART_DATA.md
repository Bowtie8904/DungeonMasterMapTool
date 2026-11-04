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
- **resources**: optional array of `{id, label, initial, max?}` for numeric custom pools. `initial` and `max`
  are numbers or supported formula strings. Omit `max` for an uncapped pool; never infer a cap from Hope or a
  description. Resource ids are stable, shared sheet keys, so a Warlock subclass can spend the `favor` declared
  by its class's Favor feature. Initial values are applied once, not every render/re-equip. Resource counters
  are not rolled-die collections: Prayer/Rally Dice retain distinct rolled values and remain manual.
- **uses**: `max` per `shortRest`, `longRest`, `rest` or `session`. Every hosting run starts a new session;
  reconnecting does not. Per-scene counters are deliberately not tracked, including old saved scene limits.
  Scene-duration effects still exist and are removable in Effects. Source-wide uses are shared by its actions/options; a roll option's own `uses` is an independent
  limit. A rest limit is not a custom resource recharge rule.
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
- **rest**: optional `{extraMoves, longMovesInShort, passives, modifiers}` on any automation source.
  `extraMoves` grants unrestricted additional slots; `longMovesInShort` allows one long-rest move in a short rest.
  `passives` contain `{id, label, defaultOn, rest?, cost?, steps?, extraMoves?, reroll?, consumeItem?, move?, bonus?, condition?}`.
  These are inline opt-in/out checkboxes with source-scoped ids. Extra-move costs are paid when confirming;
  move-specific benefits/consumption apply only when that move is selected. `condition` is `armorMarked` or
  `noRecovery`; `reroll` keeps the higher of two downtime results. `modifiers` contain
  `{move, rest?, target?: "ally", bonus?, steps?, note?}` for benefits triggered by base rest moves.
  Only owned inventory, equipped weapons/armor, active domain cards/spells and learned features contribute.
- **rollTriggers**: optional array of {id, label, on, when?, die?, matchResource?, cost?, steps?, reroll?, advantage?, description?} shown as buttons under rolls in chat. `audience` defaults to `"self"`; `"ally"` offers it on other players' rolls and `"any"` on both. `on` is `duality`, `action`, `attack`, `spellcast` or `trait:<Name>`; `when` is `always` (default), `fear`, `hope`, `failure` (shown on every roll, since players never know the difficulty), `hopeDie` (with `die`) or `dieMatches` (a Duality Die equals the number in the `matchResource` pool). `outcome: "hope"` changes the original roll's outcome without changing dice or total; Hope/Fear-dependent buttons and outcome badges read that override, while equal dice remain critical. `requiresEffect`/`endEffect` gate a trigger on, and end, an active effect (Ranger's Focus). `on` may also be `card:<Card Name>` for a domain card's own Spellcast roll. A trigger with `damage: {dice, fixed?, extraDice?, type?}` opens the damage dialog (`fixed` uses the dice count as written, otherwise it scales with Proficiency; `extraDice` adds Proficiency dice) and only pays its cost/steps when the damage is sent. Actions may carry their own `uses: {max, per}` (per-action limit) and `sendRoll: {dice, base: 'evasion', label}` which posts a plain roll to the chat without changing the sheet (I See It Coming). Spell cards only get a Spellcast button when their text asks for the roll. The roll dialog uses Advantage / Normal / Disadvantage buttons and a Critical checkbox for damage. `reroll` `hope` rolls a new Hope Die; `duality` repeats the roll (`advantage` adds a d6). Rerolls produce only a roll card, never a duplicate action message. Attack rolls also get a Damage button that behaves like the sheet's, including configured ancestry weapons.
- **Roll rewards:** `outcome: "hope"` never grants Hope automatically. The original roller claims +1 Hope
  from the updated chat card, once per roll. Critical rolls instead claim +1 Hope and clear 1 Stress.
  At maximum Hope, only criticals with marked Stress can still claim; no-benefit claims stay unconsumed.
  The host persists claims and character changes together; older hosts/history do not expose claim buttons.
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
- **manual**: not part of the maintained format. Original descriptions stay visible.
- Armor automatically sets damage thresholds (base major/severe + level; unarmored level and 2x level) and the armor
  slots from the armor score.

## Catalog coverage

The maintained catalog covers **1,187 entries / 1,294 rule sources**, including expansion content, not just core
classes or the named examples. A source is an individual class/heritage/transformation feature, or one gear/card/item
entry. The counts below describe the current catalog snapshot; edit them when catalog changes affect the totals.
“Executable” means at least one modifier, counter, action, roll option, creation/rest grant, damage-mitigation option or weapon-damage operation;
**it does not mean the whole feature is automated**. A cost/use-only button tracks the owner's spending, not the
target's outcome. “Plain gear” has no feature to automate. Sources without an executable operation are
purely narrative text.

| Category | Entries | Rule sources | Executable | Plain gear |
|---|---:|---:|---:|---:|
| Domain cards | 236 | 236 | 165 | 71 |
| Armor | 110 | 110 | 80 | 30 |
| Weapons | 336 | 336 | 144 | 192 |
| Consumables | 182 | 182 | 61 | 121 |
| Items | 174 | 174 | 81 | 93 |
| **All categories** | **1,187** | **1,294** | **705** | **589** |

Classes, ancestries, communities and transformations are included in the total.

### Curated mappings

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
  Rogue's Dodge effect; No Mercy as an effect-based attack option (pre-ticked while active); optional Tusks, Kick, Elementalist, Heart of a Poet, Wings of Light, Sneak Attack,
  Combat Training, Adrenaline and Face Your Fear. Later upgrades that replace another feature's die are manual
  where not encoded on the originating option.
- **Ancestry review and matching rules:** Elemental Breath/Long Tongue attacks and damage; Charge reactions and
  fixed damage; Fungril Network against 12; Retracting Claws; Unshakable; Purposeful Design; Ignition; Retract;
  Surefooted and situational advantage options. Hallowed Aura/Luckbender react to allies, Luckbringer grants party
  Hope, Eye of the Storm maintains a caster-linked target buff, and Lifespring heals a chosen target.
  The catalog-wide audit removed every scene-use limit and also mapped Unbound, Stealth Expertise and Red Nose
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
  item features, transformation/Beastform statistics and replacing upgraded feature dice. No choice is guessed.
- **Complex pools/multiple sub-abilities:** Prayer/Rally Dice keep distinct rolled face values; Unstoppable,
  Wild Surge, Moon phases and Zone of Protection keep stateful die faces; variable token expenditure, rolled-table
  outcomes and automatic rest/session hooks stay manual unless an explicit button/option is listed. Grimoires
    (`Book of ...`) have no card-level automation; each spell in `spells[]` is its own feature with its own
    parsed cost/usage button, Spellcast roll button and usage counter (key `card:<Book>/<Spell>`), so spells never
    share one subspell's cost/use. The sheet draws a separator line between spells.
  Usage upgrades such as Devout/Virtuoso/Reliable Backup do not invent new once-per-rest actions.
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
  - `when`: `type` (`physical`/`magic`), `severity` (list of `minor`/`major`/`severe`/`massive`, after reductions),
    `armor`, `lastArmor`, `armorFull`, `lastHp`, `hpUnmarkedMax`, `min: {value, at}` (e.g. `@cards_splendor` ≥ 4).
  - `cost` (resource → value; `@hp` = HP the damage would mark), `uses` (`"source"` shares the source's `uses`,
    or its own `{max, per}`), `consumeItem`, `quantity` (player picks a count; cost and dice scale).
  - Effects: `thresholds` (bonus), `halve`, `reduce` (value), `negate`, `severity` (steps down), `hp` (+/- HP),
    `replaceHp` (pay `cost` instead of marking HP), `armorSlots` (number or `"any"`), `armorSeverity` (steps per slot),
    `armorCost`, `armorBlocked`, `saveArmor`, `roll: {count, sides, success, each}` (`count` may be a value, `"hp"` or
    `"quantity"`; without `success` the total reduces damage), and `followUp: {dice, text}` rolled on apply.
  - Order: thresholds → halve (rounded up) → flat/rolled reductions → severity (massive = at least 2× Severe) →
    negate → Armor Slots → severity steps → HP adjustments → HP replacement. HP is capped at the unmarked slots.
    Values may use `@hp`, `@armorScore`, `@cards_<domain>` and the usual named values.
  Rest-only actions have no standalone buttons and
  `runAction` rejects them outside the rest context; unrelated actions on mixed-use sources remain available.
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

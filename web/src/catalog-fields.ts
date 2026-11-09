export type CatalogValue = string | number | boolean | null | CatalogObject | CatalogValue[];
export interface CatalogObject { [key: string]: CatalogValue }

export const CATALOG_LABELS: Record<string, string> = {
  ancestries: 'Ancestries', armor: 'Armor', classes: 'Classes & subclasses', communities: 'Communities',
  consumables: 'Consumables', 'domain-cards': 'Domain cards', items: 'Items', transformations: 'Transformations', weapons: 'Weapons',
};

export const FIELD_HELP: Record<string, string> = {
  id: 'Stable identifier, not a display name. References use this ID; do not reuse it for a different entry.',
  name: 'Display name in the compendium and linked sheets. Renaming keeps the same catalog identity.',
  label: 'Human-readable label shown on a button, counter or choice.',
  text: 'Rules or description. Supports **bold**, *italic*, paragraphs and bullet lists; prose does not create automation.',
  description: 'Explains a situational rule or prerequisite to the player. It does not automatically check position or targeting.',
  tier: 'Equipment/form/stance tier (1-4). Limits which characters can select it; does not level up the character.',
  rarity: 'Rarity label used for browsing and filtering; does not change mechanics.',
  starter: 'Offer this equipment in the Character Mancer at character creation.',
  armor: 'Base armor values. Character level and passive bonuses are added separately.',
  score: 'Base Armor Score: how many Armor Slots the armor provides.',
  major: 'Base Major damage threshold, before character level and passive bonuses.',
  severe: 'Base Severe damage threshold. Must not be lower than Major.',
  weapon: 'Weapon attack profile. These values drive the attack and damage dialogs.',
  slot: 'Primary or secondary weapon slot.',
  trait: 'Character trait used for this roll or weapon.',
  range: 'Daggerheart range band. Situational distance still needs table confirmation.',
  damage: 'Damage dice/rules. Weapon damage is multiplied by Proficiency unless a fixed pool is specified.',
  dice: 'Dice expression, for example 1d6 or 2d8. Some rules allow formulas such as @spellcast.',
  bonus: 'Flat bonus, added after the base dice.',
  type: 'Kind of feature, damage, inventory entry or effect; the available choices depend on this group.',
  burden: 'One-Handed or Two-Handed. Two-handed equipment uses both hands.',
  kind: 'Class/subclass for catalog entries; buff/debuff for effects.',
  domains: 'Domains available to this class/build. Creating a catalog domain name does not invent new rules.',
  startingHp: 'Starting HP slots for new characters only. Existing HP tracks and advancements are not rebuilt.',
  startingEvasion: 'Starting base Evasion for new characters only. Existing character bases are not rebuilt.',
  parent: 'Exact name of the class this subclass belongs to.',
  stage: 'Subclass progression stage: foundation, specialization or mastery.',
  spellcastTrait: 'Trait used for Spellcast rolls by this subclass.',
  features: 'Ordered feature blocks, each with its own name, rules text and optional automation. Existing selections remain selected.',
  feature: 'Equipment feature name and rules text. Put mechanics in automation, not in the description.',
  domain: 'Exact domain name for this card; used by creation and leveling choices.',
  cardType: 'Ability, Spell or Grimoire. A Grimoire contains independently automated spells.',
  level: 'Required card level (1-10), not a character-level edit.',
  recall: 'Stress cost to recall this domain card from the vault.',
  spells: 'Individual grimoire spells. Each has separate text, actions and automation.',
  automation: 'Machine-readable mechanics. Only supported fields are executed; changing prose alone does not change mechanics.',
  modifiers: 'Passive statistics or roll bonuses while this source is active (equipped/learned/in loadout/an active effect).',
  stat: 'Passive statistic: evasion, armorScore, thresholds, proficiency, loadoutMax, traits or trait.<Name>.',
  roll: 'Roll target: action, attack, damage, spellcast, reaction or trait:<Name>. Action includes attacks/Spellcast, not damage.',
  value: 'Numeric bonus or supported formula: @tier, @level, @proficiency, @spellcast or @<Trait>; a leading minus negates it.',
  traitValue: 'Use the current value of this chosen trait as the bonus.',
  consume: 'Remove this modifier after the next matching roll. Until then it remains available.',
  resources: 'Custom numeric pools, identified by stable shared IDs. Initial values are applied once, not on every refresh.',
  initial: 'Initial pool value, a number or supported formula. Does not reset a character’s current pool when edited.',
  max: 'Maximum value/uses/selections. Omit a resource maximum for an uncapped pool.',
  session: 'Session pool: starts at zero and resets on the DM’s New session command.',
  longRestLoss: 'Subtract this many resource points after a long rest, floored at zero.',
  resetOn: 'Rest that restores this pool to its initial value. A long rest also counts as a short rest.',
  uses: 'Limited-use counter. On automation it is shared by all source actions; on an action/option it is independent.',
  per: 'When spent uses recharge. Scene resets on scene/session/rest; session resets only on New session.',
  actions: 'Buttons on the sheet for this source. Define stable IDs, labels, costs and steps.',
  timing: 'action: normal sheet button; downtime: rest-move choice; rest: benefits handled by rest metadata.',
  cost: 'Resource costs paid on activation. HP/Stress/Armor mark slots; Hope/custom pools spend points.',
  costConditions: 'Conditional additional costs, evaluated against the character’s current sheet.',
  steps: 'Operations performed in order after paying the action’s cost.',
  op: 'clear heals/unmarks; gain adds points; mark spends/marks; effect creates an effect.',
  resource: 'Track/pool ID: hp, stress, armor, hope or a declared custom resource ID.',
  amount: 'Operation amount: number, supported formula, dice expression or all (where supported).',
  effect: 'Effect added to the character’s Effects tab, with its own name, text, duration and mechanics.',
  until: 'Effect lifetime. manual needs removal; rest durations expire on matching rests; scene/session use DM commands.',
  unique: 'Replace previous effects with this name rather than stack copies.',
  target: 'actor selects one connected character; anyActor selects one actor including offline sheets; party targets connected players’ characters; allActors includes offline player-owned sheets.',
  requiresFeature: 'Exact learned feature name required for this action or option.',
  excludesFeature: 'Hide this action or option while the named feature is learned.',
  requiresEffect: 'Exact active effect name required.',
  endEffect: 'Remove this named effect when the action, roll option, trigger or mitigation is used.',
  confirm: 'Table prerequisite the player must explicitly confirm (for example distance or a successful hit).',
  actionRoll: 'Open the Duality roll dialog; optionally select trait, attack and difficulty.',
  difficulty: 'Target number for a check/roll. Situational difficulty remains table-controlled.',
  fixed: 'Use a fixed dice pool rather than multiplying by Proficiency.',
  sendRoll: 'Send a simple die roll to chat rather than a Duality action roll.',
  check: 'Host-rolled conditional check. Configured success/failure applies its declared consequence.',
  unless: 'Only apply the marking consequence when the die does not equal this face.',
  success: 'Die face that counts as success for this conditional check.',
  mark: 'Track marked on a failed conditional check.',
  gain: 'Resource/pool granted on a successful conditional check.',
  creation: 'Permanent creation grants, applied by the Character Mancer only; catalog edits do not rebuild existing characters.',
  hp: 'HP amount/bonus. In a cost it marks HP; in a creation block it grants starting slots.',
  stress: 'Stress amount/bonus. Marked slots and current state are separate from catalog rules.',
  hope: 'Hope amount/bonus. A cost spends available Hope.',
  evasion: 'Evasion value/bonus. Passive additions are separate from a character’s base Evasion.',
  domainCards: 'Additional domain cards selected at character creation.',
  experienceBonus: 'Creation-time Experience bonus, not a retroactive change to existing Experiences.',
  rollOptions: 'Optional bonuses in roll dialogs. Each can have its own cost, use limit and prerequisite.',
  defaultOn: 'Preselect this option in matching roll dialogs; players can still untick it.',
  diceByLevel: 'Level-to-dice map. The highest applicable character-level threshold selects the dice.',
  diceByFeature: 'Feature-name-to-dice map; the matching learned feature changes the die.',
  diceConditions: 'Conditional dice replacements based on current sheet conditions.',
  excludeRolls: 'Roll targets excluded from this otherwise matching option/trigger.',
  weaponRange: 'Restrict to weapon attacks in this range band.',
  advantage: 'Offer/apply advantage or disadvantage using the standard roll system.',
  ignoreDisadvantage: 'Ignore disadvantage for the matching roll.',
  weaponAttack: 'Require a weapon attack rather than an arbitrary action.',
  proficiency: 'Proficiency bonus for the configured attack/damage mechanic, not a permanent advancement.',
  exclusive: 'Options with the same group name cannot be selected together.',
  combo: 'Use host-rolled Combo Dice after normal damage; not multiplied or maximized for criticals.',
  multiplier: 'Damage multiplier supported by the runtime (currently 2).',
  hopeDie: 'Replace the Hope die with the supported die size (currently d20).',
  fearDie: 'Replace the Fear die with the supported die size (currently d20).',
  input: 'Player-entered numeric bonus with a default and allowed bounds.',
  default: 'Initial value shown in the input; does not override a player’s selection.',
  min: 'Minimum allowed input value.',
  quantity: 'Let the player choose how many tokens to spend; scales the option’s bonus dice, flat bonus and costs.',
  ignoreWeaponBurden: 'Ignore weapon burden during creation/equipping; retain one primary and one secondary slot.',
  partyDowntime: 'Grant this feature’s downtime actions to every campaign character without adding move slots.',
  partyDowntimeGrant: 'Host-maintained access to a campaign-wide downtime move.',
  fallbackEffect: 'Apply this effect instead of gaining a resource when the character has no definition for that pool.',
  sessionHope: 'Convert unspent session resource tokens into Hope before New session clears the pool.',
  secondaryWeaponDie: 'Add one damage die from the equipped secondary weapon, without its flat bonus.',
  diceResource: 'Identify the option’s dice in chat breakdowns for resource-specific reroll actions.',
  rerollResourceDice: 'Reroll only dice showing 1 from this resource on an attack or damage roll.',
  failedOnly: 'Require a failed roll; unknown Difficulty remains player-confirmed.',
  resourceEmpty: 'Offer this option only when this named resource pool is empty.',
  consumeItem: 'Consume an inventory copy when this action/option is used.',
  downtime: 'Rest-move restrictions and optional dice-table outcomes.',
  table: 'Dice-table expression and ordered text outcomes; results are reported for table handling.',
  expression: 'Dice expression used to draw a table result.',
  outcomes: 'Ordered result descriptions for the dice table.',
  repeat: 'Number of table draws, expressed as supported dice/formula text.',
  rest: 'Automatic rest benefits and modifications to available rest moves.',
  extraMoves: 'Explicit extra downtime slots. Merely adding another rest action does not grant a slot.',
  longMovesInShort: 'Allow long-rest moves during a short rest.',
  passives: 'Automatic benefits applied on the declared rest.',
  restReroll: 'Offer a short-rest downtime die reroll in chat and keep the higher result.',
  rollTriggers: 'Buttons/reactions offered after a finished chat roll, with their own conditions and costs.',
  on: 'Roll source/target matched by this trigger.',
  when: 'Outcome/sheet condition required before this rule is available.',
  audience: 'Who may use this reaction: the roller, allies or anyone eligible.',
  outcome: 'Hope/Fear outcome required or substituted by this rule.',
  reroll: 'Reroll Hope or both Duality dice using host validation.',
  swapDuality: 'Swap Hope/Fear outcomes without inventing a new roll.',
  addDice: 'Extra host-rolled dice added by this after-roll reaction.',
  dualityDieBonus: 'Add a value to the chosen Hope or Fear die after an action roll; equal adjusted results are critical.',
  dicePool: 'Grouped rolled-die resource: id, label, count formula, sides, and optional extraByFeature dice; keep highest count.',
  rollDicePool: 'Roll and replace the granting feature’s dice pool.',
  extraByFeature: 'Extra dice granted by exact learned feature names, discarding the lowest extras.',
  hopeReward: 'Exclusive successful normal Hope-roll reward: remove GM Fear or gain Favor instead of Hope.',
  classResourceOnly: 'Show these resource pools in class resources, not again on the feature.',
  patronDice: 'Warlock Patron Dice workflow: heal (Favor per die), vengeance (one Favor, one die per HP marked), or ire (Favor per die, highest result targets).',
  spendFavor: 'Ask for Favor spent and report equal target Stress after a successful Hope action (Diminish My Foes).',
  menacingReach: 'Grant a buff describing the primary weapon reach increased one step, capped at Very Far.',
  primaryWeapon: 'Only offer this chat reaction on the equipped primary weapon.',
  repeatable: 'Allow this chat reaction repeatedly on the same roll, paying its cost every time.',
  removeFear: 'GM Fear removed by the alternative reward, sharing the normal reward’s one-time claim.',
  matchingDice: 'Double matching kept damage die values across the entire weapon damage pool, including bonus dice.',
  weaponRanges: 'Eligible weapon ranges; excludes spell damage from weapon roll options.',
  usesSource: 'Spend the granting feature’s shared use counter.',
  tokenGain: 'Gain points in the named pool once per eligible roll.',
  advanceEffect: 'Advance the named effect’s state after this trigger.',
  mitigation: 'Incoming-damage choices. Armor alternatives, costs and conditions remain explicit.',
  insteadOfArmor: 'This mitigation replaces marking Armor; it cannot be combined with ordinary Armor mitigation.',
  replaceHp: 'Replace the HP-marking consequence instead of merely reducing severity.',
  reflectHp: 'Report reflected damage based on the HP actually marked; the target remains table-managed.',
  finalSevere: 'Require the final damage severity to be Severe.',
  recoveryChoice: 'Offer Hope or Stress recovery after applying damage.',
  extraDice: 'Extra base damage dice.',
  dropLowest: 'How many low dice to discard from each affected base pool.',
  maximumOnes: 'Treat rolled ones as maximum faces before keeping base damage dice.',
  proficiencyOnly: 'Restrict the modifier to Proficiency-based damage pools.',
  deathMove: 'Supported death prevention/recovery rule; costs and HP prerequisites are host-validated.',
  moves: 'Death moves to which the bonus applies (risk/avoid).',
  grantsFeatures: 'Exact linked feature names added/refreshed while this feature is learned; does not reset counters.',
  groupGrantedFeatures: 'Display the granted features as sections of this feature; their actions and counters remain independent.',
  usesOverrides: 'Source-key-to-maximum map that raises another feature’s use limit without resetting it.',
  actionBenefits: 'Source-key-to-chat-benefit map added to another feature’s report.',
  chatBenefits: 'After an action, offer allies one of these benefits in chat. Claims are host-validated.',
  transferHope: 'Ask for a positive Hope amount to transfer to a selected ally.',
  allowSourceOwner: 'Allow another eligible character owned by the source player as a recipient.',
  blocksMovement: 'Block player token movement while this effect is active. DM movement is unaffected.',
  endOnSourceSevere: 'End this grant when its caster takes Severe damage; recasting replaces the previous grant.',
  requiresCompanion: 'Only available when the character has an available Ranger companion.',
  companion: 'Ranger companion definition, or comfortHope/comfortStress for companion actions.',
  companionTraining: 'Training selections granted when this subclass feature is acquired, not on every refresh.',
  options: 'Selectable companion training benefits, with limits and optional stat/choice effects.',
  choose: 'Choice the player must make: experience or attack (companion training).',
  ranges: 'Ordered attack range progression.',
  maxStress: 'Maximum companion Stress slots.',
  companionIntercept: 'Companion takes the attack instead, subject to positional/availability confirmation.',
  companionArmored: 'Companion Armored intercept costs owner Armor instead of companion Stress.',
  lastHp: 'Only when the attack would mark the owner’s last usable HP.',
  stances: 'Selectable martial stances; creation/leveling determines learned stances.',
  stance: 'Mark this effect as the one active martial stance.',
  chooseTrait: 'Ask which trait supplies this stance’s damage bonus.',
  shiftStance: 'Pay the action cost after selecting a learned stance.',
  refocus: 'Roll Instinct d6s and keep the highest as Focus, using this action’s own limited-use counter.',
  unarmedWeapon: 'Generated unarmed weapon while no real primary/secondary weapon is equipped; not carried inventory.',
  criticalMelee: 'Additional rewards and attack-linked damage Proficiency on critical Melee weapon attacks.',
  beastforms: 'Druid form definitions; tier gates and player selections still apply.',
  beastform: 'Mark this action/effect as a Druid beastform.',
  evolution: 'Enable the supported evolution choice for an active beastform.',
  evolved: 'Evolved form upgrades and maximum accessible form tier.',
  hybrid: 'Hybrid form component selection counts/limits.',
  examples: 'Descriptive examples of animals/forms; does not create mechanics.',
  advantages: 'Situational advantages of this form, confirmed at the table.',
  channel: 'Channel element used by the supported Druid action.',
  channelBenefit: 'Effect granted for the configured channel element.',
  element: 'Exact element name used by the channel feature.',
  aura: 'Use the configured Druid aura action.',
  earthAuraGrant: 'Mark the supported Earth Aura grant for its runtime handling.',
  witchGrant: 'Host-managed grant: circle, moonbeam, talisman or Lifeblood Talisman (lifeblood).',
  cardOnly: 'Show this pool or effect counter only on its feature card, not among header class resources.',
  witchAction: 'Supported action: talisman Hope picker, lifeblood enchanted-item creation, lunarRoll or lunarAdvance.',
  bloodAutomation: 'Require the host capability for Blood enchanted items, linked circle claims and paid roll benefits.',
  hostRollAction: 'Pay this action cost and source use on the host when its tagged action roll commits, not on the browser.',
  deferSourceUse: 'Check the source use limit but leave consumption to a successful-result action.',
  sourceOnly: 'Restrict this chat benefit to the original caster actor, not other actors owned by the same player.',
  requiresSourceEffect: 'Bind this recipient effect to the exact caster effect activation; stale claims fail and benefits end with it.',
  automatic: 'Apply this free source-specific success effect on the host without an extra chat button.',
  lunarPhases: 'Die faces and corresponding session-duration Lunar Phases effects.',
  faces: 'Lunar die results that grant this effect.',
  tokenResource: 'End this effect when its token pool is depleted.',
  whileEffect: 'Clear this resource when its named source effect is absent.',
  endOnMarkedHp: 'End this effect when incoming damage marks HP unless its preservation option is paid.',
  endOnDealtDamage: 'End this effect after a damage roll unless its preservation option is paid.',
  preserveEffect: 'Pay this option to maintain the named effect through damage.',
  endOnDamage: 'End this effect on the configured damage condition.',
  mutagens: 'Blood Hunter mutagen choices, limits, costs and benefit effects.',
  benefits: 'Selectable named effects/benefits.',
  traitBonus: 'Bonus to the trait selected by the mutagen benefit.',
  traitBonusByFeature: 'Feature-name-to-trait-bonus overrides.',
  maxBenefitsByFeature: 'Feature-name-to-maximum-benefits overrides.',
  extraBenefitCost: 'Extra resource cost for an additional selected benefit.',
  counter: 'Persistent effect counter, with value and maximum.',
  maxByLevel: 'Character-level-to-counter-maximum overrides.',
  effectCounters: 'Show counters for these named active effects on the granting feature.',
  report: 'Text/dice/amount to report in chat; does not silently change a target’s sheet.',
  manual: 'Rules that require table adjudication. They are described, not automatically executed.',
  additional: 'Offer this as an additional rest choice, not an additional downtime slot.',
  armorBlocked: 'Do not offer Armor as a mitigation for this damage.',
  armorCost: 'Number of Armor Slots marked by this mitigation.',
  armorFull: 'Require the configured full-armor condition before this rule is available.',
  armorSeverity: 'Severity reduction provided by marking Armor.',
  armorSlots: 'Number of Armor Slots affected by this rest benefit.',
  at: 'Character level at which this rest benefit or modifier becomes available.',
  attack: 'Treat the action roll as an attack rather than a general trait check.',
  base: 'Base value used by this simple roll, such as current Evasion.',
  condition: 'Sheet condition that gates the rest/damage benefit; it is not inferred from prose.',
  count: 'Number of component choices allowed by this form.',
  damageType: 'Damage category used by this generated weapon (phys or magic).',
  dieSize: 'Number of faces on the selected die.',
  double: 'Double the affected rest recovery amount.',
  each: 'Apply this recovery amount to each eligible rest die/move rather than once.',
  excludesEffect: 'Do not offer this option while the named effect is active.',
  exclusiveClaim: 'A chat recipient can claim only one of these mutually exclusive benefits.',
  featureAction: 'Named feature and action ID to use for this chat benefit.',
  followUp: 'Next supported recovery/damage step offered after applying this mitigation.',
  halve: 'Halve damage using the supported incoming-damage operation.',
  hands: 'Hands occupied by the generated weapon (1 or 2).',
  hpUnmarkedMax: 'Maximum unmarked HP required for this mitigation to be available.',
  increaseDie: 'Raise the evolved form damage die by one supported step.',
  lastArmor: 'Require that the mitigation would mark the last usable Armor Slot.',
  lifeWard: 'Mark this effect as a host-validated Life Ward.',
  markedHpOnly: 'Limit recovery to the HP marked by the qualifying event.',
  markingHp: 'Require that this incoming damage will actually mark HP.',
  minMarkedHp: 'Minimum actual HP to mark after Armor and remaining-HP limits, before HP-replacement mitigation.',
  maxTier: 'Highest form/component tier allowed by this selection.',
  move: 'Rest move or damage follow-up targeted by this rule.',
  negate: 'Negate the qualifying damage using this mitigation rather than reduce its severity.',
  note: 'Additional descriptive text; it does not create an automatic operation.',
  passive: 'Apply this modifier as a passive benefit rather than a selectable rest option.',
  reduce: 'Number of damage severity steps reduced by this mitigation.',
  saveArmor: 'Avoid marking Armor when the configured mitigation/check succeeds.',
  severity: 'Incoming-damage severity to which this option applies.',
  sides: 'Number of faces on the configured die.',
  source: 'Source identity for a linked rule, effect or input; use the exact supported source value.',
  thresholds: 'Combined Major and Severe threshold bonus.',
  unmarkedHp: 'Unmarked HP condition used to gate this rule.',
  group: 'Shared effect grouping key.',
  ancestryChoice: 'Open the supported ancestry/feature picker instead of inferring a choice from prose.',
  die: 'Die definition or trigger face; exact meaning depends on the enclosing group.',
  matchResource: 'Resource whose value must match the trigger’s die condition.',
  effectValue: 'Use the selected effect’s stored value for this option.',
  rerollBelowProficiency: 'Enable supported rerolls below the character’s Proficiency.',
};

export function fieldLabel(key: string): string {
  const labels: Record<string, string> = {
    actions: 'Activated actions', steps: 'Effects', modifiers: 'Passive bonuses', resources: 'Resource pools',
    uses: 'Use limit', rollOptions: 'Optional roll bonuses', rollTriggers: 'After-roll reactions',
    mitigation: 'Damage mitigation', cost: 'Activation cost', rest: 'Rest benefits',
    clear: 'Restore / unmark', mark: 'Spend / mark', effect: 'Apply effect',
  };
  return labels[key] ?? key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[-_]/g, ' ').replace(/^./, c => c.toUpperCase());
}

export const STEP_TEMPLATES: { label: string; value: CatalogObject }[] = [
  { label: 'Restore HP', value: { op: 'clear', resource: 'hp', amount: 1 } },
  { label: 'Clear Stress', value: { op: 'clear', resource: 'stress', amount: 1 } },
  { label: 'Restore Armor Slots', value: { op: 'clear', resource: 'armor', amount: 1 } },
  { label: 'Gain Hope', value: { op: 'gain', resource: 'hope', amount: 1 } },
  { label: 'Spend Hope', value: { op: 'mark', resource: 'hope', amount: 1 } },
  { label: 'Mark Stress', value: { op: 'mark', resource: 'stress', amount: 1 } },
  { label: 'Change a custom resource', value: { op: 'gain', resource: 'custom-pool', amount: 1 } },
  { label: 'Apply a modifier effect', value: { op: 'effect', effect: {
    name: 'New effect', text: '', until: 'rest', unique: true, automation: { modifiers: [{ stat: 'evasion', value: 1 }] },
  } } },
];

export type EditorTab = 'Overview' | 'Features' | 'Automation' | 'Advanced';
export const OVERVIEW_FIELDS = new Set([
  'id', 'name', 'text', 'description', 'tier', 'rarity', 'starter', 'armor', 'weapon',
  'kind', 'domains', 'startingHp', 'startingEvasion', 'parent', 'stage', 'spellcastTrait',
  'domain', 'level', 'cardType', 'recall',
]);

export function entryFieldTab(key: string): EditorTab {
  if (OVERVIEW_FIELDS.has(key)) return 'Overview';
  if (['features', 'feature', 'spells'].includes(key)) return 'Features';
  return key === 'automation' ? 'Automation' : 'Advanced';
}

export function ruleSection(key: string): string {
  if (['id', 'name', 'label', 'text', 'description', 'timing', 'on', 'roll', 'audience', 'target'].includes(key)) return 'When / activation';
  if (/^(requires|excludes)/.test(key) || ['when', 'condition', 'confirm', 'weaponRange', 'weaponAttack', 'resourceEmpty', 'costConditions', 'diceConditions'].includes(key)) return 'Conditions';
  if (['cost', 'uses', 'usesSource', 'consumeItem', 'quantity', 'extraBenefitCost', 'max', 'per'].includes(key)) return 'Costs & limits';
  return 'Effects / results';
}

export function ruleSummary(value: CatalogValue): string {
  if (Array.isArray(value)) return `${value.length} ${value.length === 1 ? 'item' : 'items'}`;
  if (!isCatalogObject(value)) return String(value ?? '(not set)');
  if (value.op) return `${fieldLabel(String(value.op))} ${String(value.amount ?? '')} ${String(value.resource ?? '')}`.trim();
  return String(value.label ?? value.name ?? value.id ?? value.stat ?? value.roll ?? value.kind ?? 'Configure fields');
}

export function fieldHelp(key: string, path = ''): string {
  if (/\.(cost|extraBenefitCost)\.[^.]+$/.test(path)) return `Cost in the ${key} resource pool. HP/Stress/Armor mark slots; Hope/custom points are spent. Use a number or supported formula.`;
  if (/\.diceByLevel\.[^.]+$/.test(path)) return `Dice used starting at character level ${key}; the highest applicable level threshold wins.`;
  if (/\.maxByLevel\.[^.]+$/.test(path)) return `Counter maximum starting at character level ${key}.`;
  if (/\.diceByFeature\.[^.]+$/.test(path)) return `Dice used while the exact feature "${key}" is learned.`;
  if (/\.usesOverrides\.[^.]+$/.test(path)) return `Raise the maximum uses of source "${key}" to this value; spent uses are not reset.`;
  if (/\.actionBenefits\.[^.]+$/.test(path)) return `Chat benefit choices added to source "${key}" while the granting feature is learned.`;
  return FIELD_HELP[key] ?? `Named ${fieldLabel(key).toLowerCase()} setting. Preserve the value type and consult the rule’s description; custom keys only execute if supported by the host.`;
}

const AUTOMATION: CatalogObject = {
  modifiers: [{ stat: 'evasion', value: 1 }],
  resources: [{ id: 'custom-pool', label: 'Custom pool', initial: 0 }],
  uses: { max: 1, per: 'longRest' },
  actions: [{ id: 'use', label: 'Use', steps: [{ op: 'clear', resource: 'stress', amount: 1 }] }],
  creation: { hp: 0, stress: 0, evasion: 0, domainCards: 0 },
  rollOptions: [{ id: 'bonus', label: 'Bonus', roll: 'action', value: 1 }],
  rollTriggers: [{ id: 'reaction', label: 'Reaction', on: 'action', when: 'hope', steps: [] }],
  damage: { extraDice: 1, dropLowest: 1 },
  mitigation: [{ id: 'reduce', label: 'Reduce damage', description: 'Confirm the prerequisite.', cost: { hope: 1 } }],
  rest: { extraMoves: 1 }, restReroll: true, deathMove: { kind: 'hopeBonus', bonus: 1, moves: ['risk'] },
  grantsFeatures: ['Feature name'], usesOverrides: { 'feat:Feature name': 2 },
  actionBenefits: {}, companion: {}, companionTraining: 1, effectCounters: [],
  stances: [], criticalMelee: { hope: 1, stress: 1, proficiency: 1 }, unarmedWeapon: {},
  beastforms: [], channelBenefit: {}, manual: ['Resolve situational prerequisites at the table.'],
};

export function entryTemplate(category: string): CatalogObject {
  const base: CatalogObject = { id: '', name: '', text: '' };
  if (category === 'armor') Object.assign(base, { tier: 1, armor: { score: 1, major: 5, severe: 10 }, starter: false });
  if (category === 'weapons') Object.assign(base, {
    tier: 1, weapon: { slot: 'primary', trait: 'Agility', range: 'Melee', damage: { dice: 'd6', bonus: 0, type: 'physical' }, burden: 'One-Handed' }, starter: false,
  });
  if (category === 'domain-cards') Object.assign(base, { domain: 'Arcana', level: 1, cardType: 'Ability', recall: 0 });
  if (category === 'classes') Object.assign(base, { kind: 'class', domains: ['Arcana', 'Sage'], startingHp: 6, startingEvasion: 10, features: [] });
  if (['ancestries', 'communities', 'transformations'].includes(category)) base.features = [];
  if (['items', 'consumables'].includes(category)) base.rarity = 'Common';
  return base;
}

export function isCatalogObject(value: CatalogValue | undefined): value is CatalogObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function cloneValue<T extends CatalogValue>(value: T): T { return structuredClone(value); }

/** Collect optional shapes from real rules, merging automation regardless of its owning category. */
export class CatalogShapes {
  private shapes = new Map<string, CatalogObject>();
  private examples = new Map<string, CatalogValue[]>();
  constructor(files: CatalogObject[]) {
    this.collect({ automation: AUTOMATION }, 'entry');
    for (const [category] of Object.entries(CATALOG_LABELS)) this.collect(entryTemplate(category), category);
    for (const file of files) {
      const entries = file.entries;
      if (Array.isArray(entries)) for (const entry of entries) this.collect(entry, String(file.category));
    }
  }
  private key(path: string): string {
    path = path.replace(/\[\d+\]/g, '[]');
    const automation = path.lastIndexOf('.automation');
    return automation < 0 ? path : path.slice(automation + 1);
  }
  private collect(value: CatalogValue, path: string): void {
    const key = this.key(path);
    if (Array.isArray(value)) {
      const samples = this.examples.get(key) ?? [];
      for (const child of value) {
        if (samples.length < 12 && !samples.some(sample => JSON.stringify(sample) === JSON.stringify(child))) samples.push(child);
        this.collect(child, `${path}[]`);
      }
      this.examples.set(key, samples);
    } else if (isCatalogObject(value)) {
      const shape = this.shapes.get(key) ?? {};
      for (const [name, child] of Object.entries(value)) {
        if (name.startsWith('_')) continue;
        if (!(name in shape)) shape[name] = child;
        this.collect(child, `${path}.${name}`);
      }
      this.shapes.set(key, shape);
    }
  }
  fields(path: string): CatalogObject {
    path = path.replace(/\[\d+\]/g, '[]');
    const fields = { ...this.shapes.get(this.key(path)) };
    if (Object.keys(CATALOG_LABELS).includes(path)) {
      fields.automation = {};
      fields.feature = { name: '', text: '' };
      if (path === 'classes') Object.assign(fields, { parent: '', stage: 'foundation', spellcastTrait: 'Agility' });
      if (path === 'domain-cards') fields.spells = [];
    }
    if (path.endsWith('.automation')) return { ...AUTOMATION, ...fields };
    if (/\.(features|spells)\[\]$/.test(path)) Object.assign(fields, { name: '', text: '', automation: {} });
    return fields;
  }
  samples(path: string): CatalogValue[] {
    const samples = this.examples.get(this.key(path));
    if (samples?.length) return samples;
    if (/\.(features|spells)$/.test(path)) return [{ name: '', text: '', automation: {} }];
    return [''];
  }
}

// Automation engine for the dmmap-daggerheart data format (see docs/DAGGERHEART_DATA.md).
// Pure functions over the CharacterSheet so they can be unit-tested without a DOM.

import { Advantage, CharacterSheet, Feature, Weapon, TraitName, TRAIT_ORDER, signed, tierOf } from './data';
import type { RollContribution } from '../protocol';
import type { MitigationOption } from './damage';
import { hpHealingFloor, syncDeathState } from './death';
import { syncBrawler } from './brawler';
import { clearCompanionStress, companionAvailable, companionFeatures, companionWeapon, returnCompanion, syncCompanion } from './companion';
import type { CompanionRules } from './companion';
import { activeCards, activeCardSources, actorAutomationAvailable, clearBorrowedCard } from './copycat';
import { migrateVitality } from './vitality';

export type Resource = string;
export type Until = 'manual' | 'rest' | 'shortRest' | 'longRest' | 'scene' | 'session';

/** Value of a modifier: a number or an expression such as "@spellcast", "@tier" or "-@level". */
export type ModValue = number | string;

export interface Modifier {
  /** Clamps the resolved value (Berserker's Brew: at least 1 / at most -1). */
  min?: number;
  max?: number;
  divisor?: number;
  round?: 'up' | 'down';
  hopeMin?: number;
  requiresArmor?: boolean;
  markedHpDivisor?: number;
  markedHpMultiplier?: number;
  markedStressMultiplier?: number;
  loadoutDomain?: { domain: string; min: number };
  traitValue?: TraitName;
  /** Passive stat: evasion, armorScore, majorThreshold, severeThreshold, thresholds, proficiency, loadoutMax, traits, trait.<Name> */
  stat?: string;
  /** Roll target: attack, damage, spellcast, trait:<Name> */
  roll?: string;
  value?: ModValue;
  /** Dice added to the roll (damage rolls), e.g. "d6" */
  dice?: string;
  /** Level-scaled replacement for dice, e.g. {"2": "2d6", "5": "3d8"} */
  diceByLevel?: Record<string, string>;
  /** Removed once it has been applied to a matching roll */
  consume?: boolean;
  requiresEffect?: string;
  /** Only while every Armor Slot is marked (Last Stand). */
  armorFull?: boolean;
  /** On a weapon: applies to rolls with the equipped weapon in this slot instead of the source weapon (Trusty). */
  weapon?: 'primary' | 'secondary';
}

export interface EffectDef {
  /** Host-created Reloading debuff: blocks attacks with this weapon until reloaded. */
  unloadedWeapon?: string;
  endOnAction?: boolean;
  surgeResource?: string;
  maintainOnAction?: string;
  endOnAttack?: boolean;
  deathrun?: boolean;
  deathrunRoll?: Pick<import('../protocol').RollMessage, 'expression' | 'total' | 'breakdown' | 'weapon'> & { rolls: number[] };
  deathrunRemaining?: number[];
  consumeLongRestMove?: boolean;
  vitality?: boolean;
  vitalityTotalChanges?: Partial<Record<'hp' | 'stress', number>>;
  onlineCondition?: { tokenId?: string; id: string; iconId?: string };
  /** Already applied to base fields; recorded for display and reversal, never added as stat modifiers. */
  manualChanges?: Partial<Record<ManualStat, number>>;
  rest?: Automation['rest'];
  exclusiveSource?: boolean;
  endOnMarkedHpCount?: number;
  witchGrant?: 'circle' | 'moonbeam' | 'talisman' | 'lifeblood' | 'runeWard' | 'sprites';
  witchLinked?: 'circle' | 'moonbeam' | 'talisman' | 'lifeblood' | 'runeWard' | 'sprites';
  activationId?: string;
  requiresSourceEffect?: string;
  resources?: ResourceDef[];
  tokenResource?: string;
  /** Host-owned tokens on sourceActorId's source card, not this recipient's resources. */
  linkedTokenResource?: string;
  endOnMarkedHp?: boolean;
  endOnDealtDamage?: boolean;
  partyDowntimeGrant?: boolean;
  dicePool?: string;
  requiresCompanion?: boolean;
  counter?: { value: number; max: number; maxByLevel?: Record<string, number> };
  earthAuraGrant?: boolean;
  group?: string;
  requiresEffect?: string;
  endOnDamage?: number;
  endAfterIncomingDamage?: boolean;
  beastform?: boolean;
  features?: Feature[];
  weapon?: Weapon;
  stance?: boolean;
  mitigation?: MitigationOption[];
  damage?: Automation['damage'];
  name: string;
  kind: 'buff' | 'debuff';
  until?: Until;
  text?: string;
  modifiers?: Modifier[];
  blocksMovement?: boolean;
  unconsciousHp?: number;
  spectralHp?: number;
  phaseMovement?: boolean;
  lifeWard?: boolean;
  endOnSourceSevere?: boolean;
  sourceActorId?: string;
  sourceKey?: string;
  unique?: boolean;
  lockedHp?: number;
  die?: Pick<RollOption, 'dice' | 'diceByLevel' | 'diceByFeature'>;
  rollOptions?: RollOption[];
  rollTriggers?: RollTrigger[];
  actions?: ActionDef[];
}

export interface ChatBenefit {
  cost?: ActionDef['cost'];
  sourceOnly?: boolean;
  report?: ActionDef['report'];
  transferStress?: boolean;
  id: string;
  label: string;
  steps: Step[];
  featureAction?: { name: string; id: string };
  exclusiveClaim?: boolean;
}

export interface Step {
  itemId?: string;
  minimumAmount?: number;
  fallbackEffect?: EffectDef;
  op: 'clear' | 'gain' | 'mark' | 'effect' | 'inventory';
  resource?: Resource;
  amount?: number | string;
  effect?: EffectDef;
}

export interface ActionDef {
  /** Rolls `dice` once; the first outcome whose inclusive range contains the total is reported and its steps run. */
  table?: { dice: string; outcomes: Array<{ min: number; max?: number; text: string; steps?: Step[] }> };
  /** Requires at least this much Hope (Backsies Stone: spend all, minimum 1). */
  minimumHope?: number;
  /** Refreshes rest-limited feature uses as if the given rest had been taken (Cockerel Claw Tea). */
  refreshUses?: RestKind;
  /** Consumable portion pool: the action keeps the unit until this resource is empty, then consumes it and refills the pool. */
  portions?: string;
  /** Consumable action that never uses up a unit by itself (multi-portion items, throwing on later portions). */
  keepItem?: boolean;
  /** Doomsday Potion: rolls each Hope slot, scarring each roll below 12, and grants `effect` on the first 12. */
  doomsday?: { effect: EffectDef };
  splendorAction?: 'healingHands' | 'secondWind' | 'zone' | 'sunlight' | 'salvation' | 'invigoration' | 'resurrection';
  otherPlayerOnly?: boolean;
  benefitsPerAmount?: boolean;
  excludeSourceActor?: boolean;
  selectAmount?: { resource: string; label: string; min?: number; store?: string; base?: number; addToRoll?: boolean; heal?: boolean };
  castTokens?: { resource: string; sides: number };
  selectedAmount?: number;
  spentTokenDamage?: { sides: number };
  layerRoll?: string;
  hostOwned?: boolean;
  removeFear?: number;
  bloodAutomation?: boolean;
  deferSourceUse?: boolean;
  hostRollAction?: boolean;
  specialAction?: 'vitality' | 'massDisguise' | 'nightTerror';
  toggleEffect?: EffectDef;
  copycat?: boolean;
  giveFear?: number;
  chatDamage?: ActionDef['damage'];
  excludesEffect?: string;
  vaultSource?: boolean;
  /** Spend a chosen amount and roll that many dice; `gold` counts handfuls of 10 coins (Gambler's Fallacy). */
  resourceRoll?: { resource: 'hope' | 'stress' | 'gold'; sides: number; bonusPerDie?: number; type?: 'magic' | 'physical' };
  codexSwap?: boolean;
  requiresZeroHope?: boolean;
  loadoutDomain?: { domain: string; min: number };
  hopeDice?: { sides: number; type: 'magic' | 'physical' };
  witchAction?: 'talisman' | 'lifeblood' | 'lunarRoll' | 'lunarAdvance';
  patronDice?: 'heal' | 'vengeance' | 'ire';
  spendFavor?: boolean;
  menacingReach?: boolean;
  usesSource?: boolean;
  magicOnly?: boolean;
  channelRawPower?: EffectDef;
  chooseEffects?: { count: number; options: Array<{ id: string; label: string; effect: EffectDef; chooseTrait?: boolean }> };
  rollDicePool?: boolean;
  weaponRanges?: string[];
  requiresCompanion?: boolean;
  confirm?: string;
  companion?: 'comfortHope' | 'comfortStress';
  beastform?: boolean;
  /** Item transformation into one fixed Beastform category from the Druid class catalog (Yeti Coat). Works for any class. */
  assumeBeastform?: { form: string; until?: Until };
  evolution?: boolean;
  channel?: string;
  aura?: boolean;
  report?: { amount?: ModValue; dice?: string; text: string };
  weaponRange?: string;
  shiftStance?: boolean;
  refocus?: boolean;
  requiresFeature?: string;
  excludesFeature?: string;
  deathMove?: string;
  id: string;
  label: string;
  timing?: 'action' | 'downtime' | 'rest';
  chatOnly?: boolean;
  followUpAction?: string;
  downtime?: {
    rest?: RestKind;
    additional?: boolean;
    consumeItem?: boolean;
    /** Recipe: the Online Play host adds this consumable (catalog id) to the inventory when the move is resolved. */
    craft?: string;
    table?: { expression: string; outcomes: string[]; repeat?: string; forage?: boolean };
  };
  cost?: Partial<Record<Resource, ModValue>>;
  costConditions?: Array<SheetCondition & { cost: NonNullable<ActionDef['cost']> }>;
  mutagens?: {
    traitBonus: number;
    traitBonusByFeature?: Record<string, number>;
    maxBenefitsByFeature?: Record<string, number>;
    extraBenefitCost?: NonNullable<ActionDef['cost']>;
    benefits: Array<{ id: string; requiresFeature?: string; effect: EffectDef }>;
  };
  steps?: Step[];
  /** Own use limit of this action, counted separately from the card's other actions. */
  uses?: { max: number; per: 'shortRest' | 'longRest' | 'rest' | 'scene' | 'session' };
  /** Sends a plain roll to the chat, for example the d4 of I See It Coming added to the Evasion against one attack. */
  sendRoll?: { dice: string; base?: 'evasion'; label: string };
  actionRoll?: { trait?: TraitName | 'highest' | 'spellcast' | 'beastform'; attack?: boolean; reaction?: boolean; difficulty?: number };
  ancestryChoice?: 'ancestry' | 'feature';
  endEffect?: string;
  requiresEffect?: string;
  damage?: { dice: string; fixed?: boolean; extraDice?: number; type?: 'physical' | 'magic'; count?: 'spellcast' };
  target?: 'actor' | 'anyActor' | 'party' | 'allActors';
  chatBenefits?: ChatBenefit[];
  allowSourceOwner?: boolean;
  transferHope?: boolean;
  check?: { dice: string; unless?: number; mark?: 'stress'; success?: number; gain?: string };
}

export interface ResourceDef {
  sessionReset?: ModValue;
  resetValue?: ModValue;
  minimumReset?: number;
  cardOnly?: boolean;
  whileEffect?: string;
  sessionHope?: boolean;
  id: string;
  label: string;
  initial: ModValue;
  max?: ModValue;
  session?: boolean;
  longRestLoss?: number;
  resetOn?: 'shortRest' | 'longRest' | 'rest';
}

export interface RollOption extends ActionDef {
  /** Die sizes the player picks from in the roll dialog (first is the default); replaces `dice` with one die of that size. */
  dieSizes?: number[];
  /** Offered only while this resource is above 0; it is not spent by selecting the option. */
  requiresResource?: string;
  /** Host: when this option's die shows this face, the `requiresResource` pool is emptied and reported (Superior Alconite Crystal). */
  expendOn?: number;
  deathrun?: boolean;
  traitChoices?: TraitName[];
  requiresRollOutcome?: 'hopeOrCritical';
  chooseTrait?: boolean;
  maximizeDie?: boolean;
  linkedDamageTrait?: TraitName;
  linkedDamageMultiplier?: number;
  allResourceDice?: { resource: string; sides: number };
  advantageDie?: 8;
  magicDamage?: boolean;
  rerollBelow?: number;
  resourceMultiplier?: { resource: string; value: number };
  traitValue?: TraitName;
  derivedProficiency?: boolean;
  experience?: { resource: 'stress'; multiplier: number };
  preserveEffect?: string;
  condition?: SheetCondition;
  secondaryWeaponDie?: boolean;
  diceResource?: string;
  matchingDice?: boolean;
  effectValue?: boolean;
  rerollBelowProficiency?: boolean;
  exclusive?: string;
  combo?: boolean;
  proficiency?: number;
  roll: string;
  value?: ModValue;
  dice?: string;
  diceByLevel?: Record<string, string>;
  diceByFeature?: Record<string, string>;
  diceConditions?: Array<SheetCondition & { dice?: string; dieSize?: number }>;
  excludeRolls?: string[];
  multiplier?: 2;
  description?: string;
  uses?: Automation['uses'];
  consumeItem?: boolean;
  /** Effect started by the first roll that selects this option; while it is active the option is pre-selected and free. */
  effect?: EffectDef;
  weapon?: 'source' | 'primary' | 'secondary' | 'any';
  defaultOn?: boolean;
  advantage?: 'advantage' | 'disadvantage';
  ignoreDisadvantage?: boolean;
  requiresEffect?: string;
  hopeDie?: 20;
  fearDie?: 20;
  resourceEmpty?: string;
  input?: { label: string; default: number; min: number; max: number; source?: 'attackFear' };
  quantity?: { resource: string; label: string; max?: number };
  /** Preselected and cannot be unticked while it can be paid (Painful). */
  required?: boolean;
  /** Attack option: that attack's damage uses this die size for the weapon's dice (Devastating). */
  damageDie?: number;
  /** Only offered for rolls with the equipped weapon of this name (stored Channeling damage). */
  weaponName?: string;
}

/** A button a feature offers on a finished roll in the chat. */
export interface RollTrigger extends ActionDef {
  damageSource?: string;
  rerollAnyDie?: boolean;
  spellDomain?: string;
  weaponDamage?: boolean;
  containsDie?: number;
  deathrunResult?: boolean;
  damageDiceBelow?: number;
  halfProficiencyDamage?: boolean;
  rerollDualityDie?: 'hope' | 'fear';
  criticalOnly?: boolean;
  exclusiveRollGroup?: string;
  successIfKnown?: boolean;
  banishDifficulty?: boolean;
  spendHopeAmount?: boolean;
  automatic?: boolean;
  failedOnly?: boolean;
  rerollResourceDice?: string;
  primaryWeapon?: boolean;
  repeatable?: boolean;
  magicAction?: boolean;
  damageDice?: 'reroll' | 'double';
  /** Rerolls (`reroll: 'duality'`) with an added -d6 disadvantage die (Rebounding). */
  disadvantage?: boolean;
  choices?: Array<{ id: string; label: string; addDice?: string; steps?: Step[] }>;
  dualityDieBonus?: { die: 'hope' | 'fear'; value: number };
  advanceEffect?: string;
  swapDuality?: boolean;
  addDice?: string;
  on: string;
  excludeRolls?: string[];
  when?: 'always' | 'fear' | 'hope' | 'hopeOrCritical' | 'failure' | 'success' | 'hopeDie' | 'dieMatches';
  difficulty?: number;
  die?: number;
  matchResource?: string;
  reroll?: 'hope' | 'fear' | 'duality';
  advantage?: boolean;
  /** Offered only while an effect with this name is active. */
  requiresEffect?: string;
  /** Removes the effect with this name when the button is used. */
  endEffect?: string;
  description?: string;
  audience?: 'self' | 'ally' | 'any';
  outcome?: 'hope' | 'fear';
  weapon?: string;
  weaponAttack?: boolean;
  /** Weapon-owned trigger offered on attacks with any weapon, not just its own (Vorpal Shard, Void Needle). */
  anyWeapon?: boolean;
  tokenGain?: { resource: string; max: number };
  /** Chat button group; defaults via {@link rollTriggerSection}. */
  rollSection?: RollActionSection;
}

/** `result`: outcome of the roll (primary), `feature`: effects of the feature that made the roll, `other`: other features reacting to it. */
export type RollActionSection = 'result' | 'feature' | 'other';

export interface Automation {
  /** Attachable item: while attached, the chosen weapon/armor gains this feature, automation and/or attack trait. */
  attach?: AttachmentDef;
  hideSpellcast?: boolean;
  spellcastSuccessSteps?: Step[];
  unarmoredBase?: { score: number; trait: TraitName; thresholdsByTier: Record<string, [number, number]> };
  conditionalDamageRewards?: Array<{ minHp: number; noArmorMarked?: boolean; gainHope?: number; clearArmor?: number; loadoutDomain?: { domain: string; min: number } }>;
  banishDifficulty?: boolean;
  spellcastDifficulty?: number;
  automaticRestUse?: boolean;
  automaticRestUseOnAttempt?: boolean;
  conditionalRollTriggers?: RollTrigger[];
  conditionalModifiers?: Modifier[];
  conditionalRollOptions?: RollOption[];
  conditionalActions?: ActionDef[];
  conditionalMitigation?: MitigationOption[];
  experienceRefund?: { dice: string; min: number };
  lunarPhases?: Array<{ faces: number[]; effect: EffectDef }>;
  ignoreWeaponBurden?: boolean;
  partyDowntime?: boolean;
  classResourceOnly?: boolean;
  elementChoice?: string[];
  dicePool?: { id: string; label: string; count: ModValue; sides: number; extraByFeature?: Record<string, number> };
  hopeReward?: { removeFear?: number; favor?: number; resource?: string; amount?: number; roll: string };
  companion?: CompanionRules;
  companionTraining?: number;
  effectCounters?: Array<{ label: string; effect: string; cardOnly?: boolean }>;
  beastforms?: BeastformDef[];
  /** Beastform categories this item lets a druid assume without the Beastform Stress cost (Puddleduck's Fins). */
  beastformFree?: string[];
  channelBenefit?: { element: string; effect: EffectDef };
  unarmedWeapon?: Omit<CharacterSheet['activeWeapons'][number], 'trait'>;
  stances?: Array<{ name: string; tier: number; effect: EffectDef; chooseTrait?: boolean }>;
  criticalMelee?: { hope: number; stress: number; proficiency: number };
  grantsFeatures?: string[];
  groupGrantedFeatures?: boolean;
  usesOverrides?: Record<string, number>;
  actionBenefits?: Record<string, ChatBenefit[]>;
  deathMove?: { kind: 'hopeBonus' | 'hopeSpend' | 'mask' | 'undying' | 'spectral' | 'egg' | 'battleHardened' | 'lifeWard' | 'unbreakable'; moves?: ('risk' | 'avoid')[]; bonus?: number };
  restReroll?: boolean;
  rest?: {
    extraMoves?: number;
    longMovesInShort?: boolean;
    passives?: RestBenefit[];
    modifiers?: RestMoveModifier[];
  };
  rollTriggers?: RollTrigger[];
  modifiers?: Modifier[];
  uses?: { max: number; per: 'shortRest' | 'longRest' | 'rest' | 'scene' | 'session' };
  actions?: ActionDef[];
  resources?: ResourceDef[];
  creation?: { hp?: number; stress?: number; evasion?: number; domainCards?: number; domains?: string[]; experienceBonus?: number };
  rollOptions?: RollOption[];
  damage?: { extraDice: number; dropLowest: number; maximumOnes?: boolean; proficiencyOnly?: boolean;
    /** Host damage rule: damage dice showing 1 count as this value (Serrated 8, Self-Correcting 6). */
    onesAs?: number;
    /** Host damage rule: roll one die of this size for each kept damage die showing 1 and add it (Rampage d10). */
    onesBonus?: number;
    /** Host damage rule: each maximum face rolls one more die; `repeat` lets the new dice trigger again (Brutal/Recursive). */
    explode?: 'once' | 'repeat';
    /** Feature name shown in the damage breakdown for the host rules. */
    label?: string };
  /** Options offered in the incoming-damage dialog (see damage.ts). */
  mitigation?: MitigationOption[];
  /** Alternate weapon statistics offered as a separate attack row (Versatile, Stockpiled). */
  alternateProfiles?: WeaponProfile[];
  /** Successful attacks may deal physical or magic damage (Otherworldly). */
  damageTypeChoice?: boolean;
  /** Attacks with this weapon require the trait to stay within these bounds (Pompous). */
  requirement?: { trait: TraitName; min?: number; max?: number };
  /** Host rolls this die after every attack with the weapon; on `fail` the weapon becomes Unloaded (Reloading). */
  reloading?: { dice: string; fail: number };
  /** Armor: Hope gained when an Armor clear requests more slots than are marked (Overflowing). */
  overflowHope?: number;
  /** Armor: effect added when the armor is equipped and removed when it is unequipped (Oathbound). */
  equipEffect?: EffectDef;
  /** Armor, host-enforced: effect added once whenever the Armor track becomes full (Last Bastion). */
  armorFullEffect?: EffectDef;
  /** Armor, host-enforced: effect kept only while at least half of the Armor Slots are marked (Beacon of Despair). */
  armorHalfEffect?: EffectDef;
  /** Armor, host-enforced: effect kept only while every Armor Slot is marked (Reinforced). */
  armorFullHeldEffect?: EffectDef;
  /** Armor, host-enforced: Armor Slots cleared when Hope drops from above 0 to 0 (Last Light Engine). */
  zeroHopeClearArmor?: number;
}

export interface AttachmentDef {
  to: 'weapon' | 'armor';
  requires?: { noFeature?: boolean; range?: string; nameIncludes?: string[]; damageType?: 'phys' | 'magic' };
  feature?: { name: string; text: string };
  automation?: Automation;
  trait?: TraitName;
}

export interface WeaponProfile {
  label: string;
  trait: TraitName;
  range: string;
  damage: string;
  damageType?: 'phys' | 'magic';
}

export interface BeastformDef {
  name: string;
  tier: number;
  examples?: string;
  trait?: TraitName;
  bonus?: number;
  evasion?: number;
  damage?: string;
  range?: string;
  advantages?: string[];
  features?: Array<{ name: string; text: string; automation?: Automation }>;
  evolved?: { maxTier: number; damage: number; trait: number; evasion: number; increaseDie?: boolean };
  hybrid?: { count: number; maxTier: number; advantages: number; features: number; stress: number };
}

export function activeFeatures(c: CharacterSheet): Feature[] {
  return [...c.features, ...companionFeatures(c), ...c.effects.filter(e => actorAutomationAvailable(e.sourceActorId)).flatMap(e => e.features ?? [])];
}

export function activeWeapons(c: CharacterSheet): Weapon[] {
  const replacement = c.effects.find(e => e.weapon)?.weapon;
  return replacement ? [replacement] : c.activeWeapons;
}

export function syncEffectDependencies(c: CharacterSheet): void {
  c.effects = c.effects.filter(e => !e.tokenResource || (c.resources?.[e.tokenResource] ?? 0) > 0);
  c.effects = c.effects.filter(e => !e.requiresCompanion || companionAvailable(c));
  if (c.hp.slots > 0 && c.hp.on >= c.hp.slots) c.effects = c.effects.filter(e => !e.beastform);
  let count: number;
  do {
    count = c.effects.length;
    c.effects = c.effects.filter(e => !e.requiresEffect || c.effects.some(parent => parent.name === e.requiresEffect));
  } while (c.effects.length !== count);
}

export interface RestBenefit extends ActionDef {
  chooseAlly?: boolean;
  castBeforeRest?: string;
  defaultOn: boolean;
  rest?: RestKind;
  extraMoves?: number;
  reroll?: boolean;
  consumeItem?: boolean;
  move?: string;
  bonus?: number;
  condition?: 'noRecovery' | 'armorMarked' | 'noArmorMarked';
  /** Applies only when the character has at least this much Hope as the rest starts (Hopekeeper Locket). */
  minHope?: number;
}

export interface RestMoveModifier {
  repairAllies?: number;
  move: string;
  rest?: RestKind;
  target?: 'ally';
  bonus?: number;
  steps?: Step[];
  note?: string;
}

export interface ActiveEffect extends EffectDef {
  id: string;
  source?: string;
  hpMaintenance?: number;
  onlineCondition?: { tokenId?: string; id: string; iconId?: string };
}

const TRAIT_SET = new Set<string>(TRAIT_ORDER);

export interface SheetCondition {
  markedHp?: number;
  feature?: string;
  unmarkedHp?: { min: number; max: number };
  stressFull?: boolean;
}

export function hasFeature(c: CharacterSheet, name: string): boolean {
  return [...activeFeatures(c), ...c.heritage].some(f => f.name === name);
}

function conditionMatches(c: CharacterSheet, condition: SheetCondition): boolean {
  const hp = c.hp.slots - c.hp.on;
  return (condition.markedHp === undefined || c.hp.on >= condition.markedHp)
    && (!condition.feature || hasFeature(c, condition.feature))
    && (!condition.unmarkedHp || hp >= condition.unmarkedHp.min && hp <= condition.unmarkedHp.max)
    && (!condition.stressFull || c.stress.slots > 0 && c.stress.on >= c.stress.slots);
}

export function actionCost(c: CharacterSheet, action: ActionDef): ActionDef['cost'] {
  let cost = action.cost;
  for (const condition of action.costConditions ?? []) {
    if (conditionMatches(c, condition)) cost = { ...cost, ...condition.cost };
  }
  return cost;
}

export function loadoutDomainMatches(c: CharacterSheet, condition?: { domain: string; min: number }): boolean {
  return !condition || c.loadout.filter(card => card.domain.toLowerCase() === condition.domain.toLowerCase()).length >= condition.min;
}

function modifierActive(c: CharacterSheet, modifier: Modifier): boolean {
  return (modifier.hopeMin === undefined || c.hope.on >= modifier.hopeMin)
    && (!modifier.requiresArmor || !!c.activeArmor)
    && loadoutDomainMatches(c, modifier.loadoutDomain)
    && (!modifier.armorFull || c.armorTrack.slots > 0 && c.armorTrack.on >= c.armorTrack.slots)
    && (!modifier.requiresEffect || c.effects.some(e => e.name === modifier.requiresEffect));
}

/** Overflowing: clearing more Armor Slots than are marked gains Hope instead (once per clear). */
export function armorOverflowHope(c: CharacterSheet, requested: number, cleared: number): string | null {
  const gain = c.activeArmor?.automation?.overflowHope;
  if (!gain || requested <= cleared) return null;
  const gained = Math.min(gain, Math.max(0, c.hope.slots - c.hope.on));
  c.hope.on += gained;
  return `${c.activeArmor!.feature?.name ?? c.activeArmor!.name}: gained ${gained} Hope.`;
}

/** Automation of everything currently contributing passive modifiers. */
export function automationSources(c: CharacterSheet): Automation[] {
  const out: Automation[] = [];
  const add = (a?: Automation) => { if (a) out.push(a); };
  add(c.activeArmor?.automation);
  activeWeapons(c).forEach((w) => add(w.automation));
  activeFeatures(c).forEach((f) => add(f.automation));
  c.heritage.forEach((f) => add(f.automation));
  activeCards(c).forEach((d) => add(d.automation));
  c.inventory.filter((i) => i.qty > 0).forEach((i) => add(i.automation));
  return out;
}

export function resourceDefinitions(c: CharacterSheet): ResourceDef[] {
  const copied = activeCardSources(c).filter(source => source.key.startsWith('card:Copycat/')).flatMap(source => source.auto ? [source.auto] : []);
  return [...new Map([...automationSources(c), ...copied, ...c.effects].flatMap((a) => a.resources ?? []).map((r) => [r.id, r])).values()];
}

export function poolDice(c: CharacterSheet, pool: NonNullable<Automation['dicePool']>): ActiveEffect[] {
  return c.effects.filter(e => e.dicePool === pool.id);
}

export function rollDicePool(c: CharacterSheet, pool: NonNullable<Automation['dicePool']>, roll: DiceRoll = rollLocal): string[] {
  const count = Math.max(0, Math.floor(evalValue(pool.count, c)));
  const extra = Object.entries(pool.extraByFeature ?? {}).reduce((n, [feature, amount]) => n + (hasFeature(c, feature) ? amount : 0), 0);
  const faces = count > 0 ? Array.from({ length: count + extra }, () => roll(`1d${pool.sides}`)) : [];
  const kept = [...faces].sort((a, b) => b - a).slice(0, count);
  c.effects = c.effects.filter(e => e.dicePool !== pool.id);
  for (const [index, value] of kept.entries()) {
    const id = `pool-${pool.id}-${Date.now()}-${index}`;
    const name = `${pool.label} ${index + 1} (${value})`;
    c.effects.push({
      id, name, dicePool: pool.id, kind: 'buff', until: 'session', counter: { value, max: pool.sides },
      rollTriggers: [{ id: 'spend', label: `Prayer die: +${value}`, on: 'any', audience: 'any',
        addDice: String(value), endEffect: name, usesSource: false,
        description: 'Spend this die to aid yourself or an ally within Far range.' }],
      mitigation: [{ id: 'spend', label: `Prayer die: reduce by ${value}`, reduce: value, requiresEffect: name, endEffect: name }],
    });
  }
  return [`Rolled ${count + (count > 0 ? extra : 0)}d${pool.sides}${extra && count > 0 ? `, keep highest ${count}` : ''}: ${faces.join(', ') || 'no dice (Spellcast trait is 0 or lower)'}.`,
    `Available ${pool.label}: ${kept.join(', ') || 'none'}.`];
}

export function syncResources(c: CharacterSheet): void {
  c.resources ??= {};
  for (const r of resourceDefinitions(c)) {
    const max = r.max === undefined ? Infinity : Math.max(0, evalValue(r.max, c));
    const value = r.whileEffect && !c.effects.some(e => e.name === r.whileEffect) ? 0 : c.resources[r.id] ?? evalValue(r.initial, c);
    c.resources[r.id] = Math.max(0, Math.min(max, value));
  }
}

function allModifiers(c: CharacterSheet): Modifier[] {
  const mods = automationSources(c).flatMap((a) => [...(a.modifiers ?? []), ...(a.conditionalModifiers ?? [])]);
  for (const e of c.effects) mods.push(...(e.modifiers ?? []));
  return mods.filter(m => modifierActive(c, m));
}

let gmFear = 0;
/** The GM's current Fear count (0-12), supplied by the host; `@fear` values read it. */
export function setGmFear(n: number | undefined): void {
  gmFear = Math.max(0, Math.floor(n ?? 0));
}

export function evalValue(value: ModValue | undefined, c: CharacterSheet): number {
  if (typeof value === 'number') return value;
  if (!value) return 0;
  const m = /^([+-]?)@([\w-]+)$/.exec(value.trim());
  if (!m) return Number(value) || 0;
  const sign = m[1] === '-' ? -1 : 1;
  const key = m[2].toLowerCase();
  let n = 0;
  if (key === 'tier') n = tierOf(c.level);
  else if (key === 'fear') n = gmFear;
  else if (key === 'hope') n = c.hope.on;
  else if (key.startsWith('cards_')) n = [...c.loadout, ...c.vault].filter((d) => d.domain.toLowerCase() === key.slice(6)).length;
  else if (key === 'level') n = c.level;
  else if (key === 'proficiency') n = c.proficiency.on;
  else if (key === 'armor_available') n = Math.max(0, c.armorTrack.slots - c.armorTrack.on);
  else if (key === 'armor_score') n = derivedStats(c).armorSlots;
  else if (key === 'spellcast') n = c.traits.find((t) => t.spellcast)?.mod ?? 0;
  else if (key === 'total_spellcast') {
    const spellcast = c.traits.find(t => t.spellcast);
    n = spellcast ? traitTotal(c, spellcast.name) : 0;
  } else if (key === 'total_proficiency') n = derivedStats(c).proficiency;
  else if (key.startsWith('total_')) {
    const trait = c.traits.find(t => t.name.toLowerCase() === key.slice(6));
    n = trait ? traitTotal(c, trait.name) : 0;
  }
  else {
    const t = c.traits.find((x) => x.name.toLowerCase() === key);
    n = t ? t.mod : c.resources?.[m[2]] ?? Object.entries(c.resources ?? {}).find(([id]) => id.toLowerCase() === key)?.[1] ?? 0;
  }
  return sign * n;
}

function statTotal(c: CharacterSheet, ...stats: string[]): number {
  return allModifiers(c).filter((m) => m.stat && stats.includes(m.stat)).reduce((n, m) => n + modifierValue(m, c), 0);
}

function modifierValue(m: Modifier, c: CharacterSheet): number {
  if (m.markedHpDivisor && m.markedHpDivisor > 0) return Math.floor(c.hp.on / m.markedHpDivisor);
  if (m.markedHpMultiplier !== undefined) return c.hp.on * m.markedHpMultiplier;
  if (m.markedStressMultiplier !== undefined) return c.stress.on * m.markedStressMultiplier;
  const value = m.traitValue ? traitTotal(c, m.traitValue) : evalValue(m.value, c);
  const scaled = m.divisor ? m.round === 'up' ? Math.ceil(value / m.divisor) : Math.floor(value / m.divisor) : value;
  return Math.min(m.max ?? Infinity, Math.max(m.min ?? -Infinity, scaled));
}

export interface DerivedStats {
  evasion: number;
  armorSlots: number;
  major: number;
  severe: number;
  proficiency: number;
  loadoutMax: number;
}

/** Evasion, armor slots and damage thresholds from the equipped armor, level and all active modifiers. */
export function derivedStats(c: CharacterSheet): DerivedStats {
  const armor = c.activeArmor;
  const unarmored = unarmoredBase(c);
  const baseMajor = armor ? (armor.major ?? armor.score + 4) : unarmored?.thresholds[0] ?? 0;
  const baseSevere = armor ? (armor.severe ?? armor.score * 2 + 7) : unarmored?.thresholds[1] ?? 0;
  const both = statTotal(c, 'thresholds');
  return {
    evasion: c.evasion + statTotal(c, 'evasion'),
    armorSlots: Math.max(0, (armor?.score ?? unarmored?.score ?? 0) + statTotal(c, 'armorScore')),
    major: Math.max(0, baseMajor + c.level + both + statTotal(c, 'majorThreshold')),
    severe: Math.max(0, baseSevere + (armor || unarmored ? c.level : c.level * 2) + both + statTotal(c, 'severeThreshold')),
    proficiency: Math.max(1, c.proficiency.on + statTotal(c, 'proficiency')),
    loadoutMax: Math.max(0, 5 + statTotal(c, 'loadoutMax')),
  };
}

export function deathrunProficiency(c: CharacterSheet, count: number): number {
  const run = c.effects.find(e => e.deathrun && e.deathrunRoll);
  if (run?.deathrunRemaining) return run.deathrunRemaining.length - 1;
  return Math.max(0, c.proficiency.on + statTotal(c, 'proficiency')) + 1 - count;
}

function unarmoredBase(c: CharacterSheet): { name: string; score: number; thresholds: [number, number] } | undefined {
  if (c.activeArmor) return undefined;
  const source = activeCards(c).find(card => card.automation?.unarmoredBase);
  const base = source?.automation?.unarmoredBase;
  const thresholds = base?.thresholdsByTier[String(tierOf(c.level))];
  return source && base && thresholds
    ? { name: source.name, score: base.score + traitTotal(c, base.trait), thresholds }
    : undefined;
}

export interface StatSource { label: string; value: number }

/** Named sources of passive modifiers (equipped gear, features, loadout cards and active effects). */
function namedSources(c: CharacterSheet): Array<{ name: string; auto?: Automation; mods?: Modifier[]; weapon?: CharacterSheet['activeWeapons'][number] }> {
  return [
    ...(c.activeArmor ? [{ name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...activeWeapons(c).map((w) => ({ name: w.feature ? `${w.name} - ${w.feature.name}` : w.name, auto: w.automation, weapon: w })),
    ...activeFeatures(c).map((f) => ({ name: f.name, auto: f.automation })),
    ...c.heritage.map((f) => ({ name: f.name, auto: f.automation })),
    ...activeCards(c).map((d) => ({ name: d.name, auto: d.automation })),
    ...c.inventory.filter((i) => i.qty > 0).map((i) => ({ name: i.name, auto: i.automation })),
    ...c.effects.map((e) => ({ name: e.name, mods: e.modifiers })),
  ];
}

/** Every non-zero contribution to the given stats, by source name. */
export function statSources(c: CharacterSheet, ...stats: string[]): StatSource[] {
  const out: StatSource[] = [];
  for (const s of namedSources(c)) {
    const mods = s.mods ?? [...(s.auto?.modifiers ?? []), ...(s.auto?.conditionalModifiers ?? [])];
    const value = mods.filter((m) => modifierActive(c, m) && m.stat && stats.includes(m.stat)).reduce((n, m) => n + modifierValue(m, c), 0);
    if (value) out.push({ label: s.name, value });
  }
  return out;
}

/** Where evasion, armor slots and the damage thresholds come from, base values first. */
export function statBreakdown(c: CharacterSheet): Record<'evasion' | 'armor' | 'major' | 'severe', StatSource[]> {
  const armor = c.activeArmor;
  const unarmored = unarmoredBase(c);
  const both = statSources(c, 'thresholds');
  const base = (label: string, value: number): StatSource[] => (value ? [{ label, value }] : []);
  const manualEvasion = c.effects.find(isManualOverride)?.manualChanges?.evasion ?? 0;
  return {
    evasion: [{ label: c.className ? `${c.className} base (with level-ups)` : 'Base', value: c.evasion - manualEvasion },
      ...base('Manual override', manualEvasion), ...statSources(c, 'evasion')],
    armor: [...(armor ? [{ label: armor.name, value: armor.score }] : unarmored ? [{ label: unarmored.name, value: unarmored.score }] : []), ...statSources(c, 'armorScore')],
    major: [...base(armor?.name ?? unarmored?.name ?? 'No armor', armor ? (armor.major ?? armor.score + 4) : unarmored?.thresholds[0] ?? 0), { label: `Level ${c.level}`, value: c.level }, ...both, ...statSources(c, 'majorThreshold')],
    severe: [...base(armor?.name ?? unarmored?.name ?? 'No armor', armor ? (armor.severe ?? armor.score * 2 + 7) : unarmored?.thresholds[1] ?? 0), { label: armor || unarmored ? `Level ${c.level}` : `Level ${c.level} x2`, value: armor || unarmored ? c.level : c.level * 2 }, ...both, ...statSources(c, 'severeThreshold')],
  };
}

export function traitTotal(c: CharacterSheet, name: TraitName): number {
  const base = c.traits.find((t) => t.name === name)?.mod ?? 0;
  return base + statTotal(c, `trait.${name}`, 'traits');
}

/** Keeps the pip tracks consistent with derived values (armor slots follow the equipped armor). */
export function syncDerived(c: CharacterSheet): DerivedStats {
  migrateVitality(c);
  syncCompanion(c);
  syncEffectDependencies(c);
  syncDeathState(c);
  syncBrawler(c);
  const stances = automationSources(c).flatMap(a => a.stances ?? []);
  c.effects = c.effects.filter(e => !e.stance || c.hp.on < c.hp.slots
    && c.knownStances?.includes(e.name) && stances.some(s => s.name === e.name && s.tier <= tierOf(c.level)));
  syncResources(c);
  const d = derivedStats(c);
  c.armorTrack.slots = Math.min(c.armorTrack.total, d.armorSlots);
  c.armorTrack.on = Math.min(c.armorTrack.on, c.armorTrack.slots);
  return d;
}

export type ManualStat = 'hp' | 'stress' | 'proficiency' | 'evasion' | `trait.${TraitName}`;
export const MANUAL_OVERRIDE_SOURCE = 'manual:armor-slots';

export function isManualOverride(effect: ActiveEffect): boolean {
  return effect.source === MANUAL_OVERRIDE_SOURCE;
}

function manualOverride(c: CharacterSheet): ActiveEffect {
  const existing = c.effects.find(isManualOverride);
  return existing ?? addEffect(c, { name: 'Manual override', kind: 'buff', until: 'manual', modifiers: [] }, MANUAL_OVERRIDE_SOURCE);
}

function updateManualOverride(c: CharacterSheet, effect: ActiveEffect): void {
  const changes = Object.entries(effect.manualChanges ?? {}).filter(([, value]) => value);
  effect.modifiers = (effect.modifiers ?? []).filter(modifier => modifier.value !== 0);
  const armor = effect.modifiers.find(modifier => modifier.stat === 'armorScore');
  const rows = [...changes.map(([key, value]) => `${key.startsWith('trait.') ? `${key.slice(6)} base trait` : key === 'hp' ? 'HP slots' : key === 'stress' ? 'Stress slots' : key === 'evasion' ? 'Evasion' : 'Proficiency'}: ${signed(value!)}`),
    ...(armor ? [`Armor slots: ${signed(Number(armor.value))}`] : [])];
  effect.name = 'Manual override';
  effect.kind = 'buff';
  effect.until = 'manual';
  effect.text = rows.join('\n');
  if (!rows.length) c.effects = c.effects.filter(item => item !== effect);
}

function manualBase(c: CharacterSheet, stat: ManualStat): number {
  if (stat === 'hp' || stat === 'stress') return c[stat].slots;
  if (stat === 'proficiency') return c.proficiency.on;
  if (stat === 'evasion') return c.evasion;
  return c.traits.find(trait => `trait.${trait.name}` === stat)!.mod;
}

function writeManualBase(c: CharacterSheet, stat: ManualStat, value: number): void {
  if (stat === 'hp' || stat === 'stress') {
    const track = c[stat];
    track.slots = Math.max(stat === 'hp' ? hpHealingFloor(c) : 0, Math.min(track.total, value));
    track.on = Math.min(track.on, track.slots);
  } else if (stat === 'proficiency') c.proficiency.on = Math.max(0, Math.min(c.proficiency.slots, value));
  else if (stat === 'evasion') c.evasion = value;
  else c.traits.find(trait => `trait.${trait.name}` === stat)!.mod = value;
}

export function setManualStat(c: CharacterSheet, stat: ManualStat, value: number): void {
  const previous = manualBase(c, stat);
  writeManualBase(c, stat, value);
  const change = manualBase(c, stat) - previous;
  if (!change) return;
  const effect = manualOverride(c);
  effect.manualChanges ??= {};
  effect.manualChanges[stat] = (effect.manualChanges[stat] ?? 0) + change;
  if (!effect.manualChanges[stat]) delete effect.manualChanges[stat];
  updateManualOverride(c, effect);
}

export function removeManualOverride(c: CharacterSheet, effect: ActiveEffect): void {
  if (!isManualOverride(effect) || !c.effects.includes(effect)) return;
  for (const [stat, adjustment] of Object.entries(effect.manualChanges ?? {}) as Array<[ManualStat, number]>)
    writeManualBase(c, stat, manualBase(c, stat) - adjustment);
  c.effects = c.effects.filter(item => item !== effect);
  syncDerived(c);
}

/** Manual Armor capacity stays additive to equipment and buffs, including on older hosts. */
export function setTrackSlots(c: CharacterSheet, kind: 'hp' | 'stress' | 'armor', slots: number): void {
  const track = kind === 'armor' ? c.armorTrack : c[kind];
  const floor = kind === 'hp' ? hpHealingFloor(c) : 0;
  slots = Math.max(floor, Math.min(track.total, slots));
  if (kind === 'armor') {
    const effect = manualOverride(c);
    effect.modifiers = [];
    const adjustment = slots - derivedStats(c).armorSlots;
    if (adjustment) effect.modifiers.push({ stat: 'armorScore', value: adjustment });
    updateManualOverride(c, effect);
    syncDerived(c);
  } else setManualStat(c, kind, slots);
}

export interface RollBonus {
  value: number;
  dice: string[];
  /** Effects whose consume modifiers contributed; call consumeBonus after the roll was sent. */
  applied: Array<{ effect: ActiveEffect; modifier: Modifier }>;
  names: string[];
  contributions: RollContribution[];
}

export function rollTargets(targets: string[], reaction: boolean): string[] {
  return [reaction ? 'reaction' : 'action', ...targets.filter((target) => target !== 'action' && target !== 'reaction')];
}

/** Collects roll modifiers matching any of the targets (for example ['attack', 'trait:Finesse']). */
export function rollBonus(c: CharacterSheet, targets: string[], weapon?: CharacterSheet['activeWeapons'][number], withConsumables = true): RollBonus {
  const out: RollBonus = { value: 0, dice: [], applied: [], names: [], contributions: [] };
  const take = (m: Modifier, name: string, effect?: ActiveEffect) => {
    if (!modifierActive(c, m)) return;
    if (!m.roll || !targets.includes(m.roll)) return;
    if (m.consume && !withConsumables) return;
    const v = m.traitValue ? traitTotal(c, m.traitValue) : modifierValue(m, c);
    out.value += v;
    const level = Object.keys(m.diceByLevel ?? {}).map(Number).filter((n) => n <= c.level).sort((a, b) => b - a)[0];
    const dice = level === undefined ? m.dice : m.diceByLevel![String(level)];
    if (dice) out.dice.push(dice);
    out.names.push(`${name} ${dice ? `+${dice}` : v >= 0 ? `+${v}` : v}`);
    out.contributions.push({ label: name.slice(0, 120), expression: `${v}${dice ? `+${dice.startsWith('d') ? `1${dice}` : dice}` : ''}` });
    if (effect && m.consume) out.applied.push({ effect, modifier: m });
  };
  for (const e of c.effects) for (const m of e.modifiers ?? []) take(m, e.name, e);
  for (const s of namedSources(c)) {
    for (const m of [...(s.auto?.modifiers ?? []), ...(s.auto?.conditionalModifiers ?? [])]) {
      if (s.weapon && m.roll && !weaponModifierApplies(c, s.weapon, m, weapon)) continue;
      take(m, s.name);
    }
  }
  return out;
}

/** A weapon's roll modifier counts only for rolls made with that weapon, or with the slot it explicitly names. */
function weaponModifierApplies(c: CharacterSheet, source: Weapon, m: Modifier, weapon?: Weapon): boolean {
  if (!weapon) return false;
  if (m.weapon) return weapon.slot.toLowerCase() === m.weapon && activeWeapons(c).some(w => w.name === weapon.name);
  return sameWeapon(source, weapon);
}

/** Alternate statistics and attack-linked die changes are copies of the equipped weapon with the same name and slot. */
export function sameWeapon(a: Weapon | undefined, b: Weapon | undefined): boolean {
  return !!a && !!b && (a === b || a.name === b.name && a.slot === b.slot);
}

export function weaponProfiles(w: Weapon): WeaponProfile[] {
  return w.automation?.alternateProfiles ?? [];
}

/** The weapon as used with one of its alternate statistics; unknown labels return the weapon itself. */
export function weaponWithProfile(w: Weapon, label?: string): Weapon {
  const profile = label ? weaponProfiles(w).find(p => p.label === label) : undefined;
  return profile ? { ...w, trait: profile.trait, range: profile.range, damage: profile.damage,
    damageType: profile.damageType ?? w.damageType, profile: profile.label } : w;
}

/** Why the weapon cannot attack right now (Pompous requirement, Reloading). */
export function weaponAttackBlocker(c: CharacterSheet, w: Weapon): string | null {
  const unloaded = c.effects.find(e => e.unloadedWeapon === w.name);
  if (unloaded) return `${w.name} is unloaded. Reload it first (Effects or the weapon's Reload button).`;
  const requirement = w.automation?.requirement;
  if (requirement) {
    const value = traitTotal(c, requirement.trait);
    if (requirement.max !== undefined && value > requirement.max) return `Requires ${requirement.trait} ${signed(requirement.max)} or lower (currently ${signed(value)}).`;
    if (requirement.min !== undefined && value < requirement.min) return `Requires ${requirement.trait} ${signed(requirement.min)} or higher (currently ${signed(value)}).`;
  }
  return null;
}

export interface AvailableRollOption {
  key: string;
  source: string;
  /** Display-only provenance; source and key retain their automation identity. */
  origin?: string;
  automation: Automation;
  option: RollOption;
  item?: CharacterSheet['inventory'][number];
  /** The option's own effect is already running, so using it again is free. */
  active?: boolean;
  /** A prepared single-use bonus (for example a drunk potion): ticked by default, removed once the roll was sent. */
  prepared?: { effect: ActiveEffect; modifier: Modifier };
}

export function rollOptionQuantityMax(c: CharacterSheet, option: RollOption): number {
  const resource = option.quantity?.resource;
  const available = resource === 'hope' ? c.hope.on
    : resource === 'stress' ? Math.max(0, c.stress.slots - c.stress.on)
      : c.resources?.[resource ?? ''] ?? 0;
  return Math.min(option.quantity?.max ?? Infinity, available);
}

export function configuredRollOption(c: CharacterSheet, selection: AvailableRollOption, input?: number): AvailableRollOption {
  const { option } = selection;
  if (!option.input && !option.quantity) return selection;
  const min = option.input?.min ?? 1;
  const max = option.input?.max ?? rollOptionQuantityMax(c, option);
  const value = input ?? option.input?.default ?? 1;
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${option.input?.label ?? option.quantity?.label}: choose a whole number from ${min} to ${max}`);
  return { ...selection, option: { ...option,
    value: option.input ? value : evalValue(option.value, c) * value,
    ...(option.quantity ? {
      dice: rollOptionDice(c, option)?.replace(/(\d*)d(\d+)/gi, (_term, count: string, sides: string) => `${Number(count || 1) * value}d${sides}`),
      diceByLevel: undefined, diceByFeature: undefined, diceConditions: undefined,
      ...(option.linkedDamageTrait ? { linkedDamageMultiplier: (option.linkedDamageMultiplier ?? 2) * value } : {}),
    } : {}),
    cost: option.quantity ? Object.fromEntries(Object.entries(option.cost ?? {}).map(([key, cost]) => [key, evalValue(cost, c) * value])) : option.cost,
  } };
}

export function featureRollOrigin(c: CharacterSheet, f: Feature): string {
  if (f.source === 'Hope feature') return c.className || f.source;
  const multiclass = /^Multiclass (.+?) - (?:class feature|(.+) (?:foundation|specialization|mastery))$/.exec(f.source);
  if (multiclass) return multiclass[2] || multiclass[1];
  const origin = f.source.replace(/^(?:Ancestry|Community|Transformation|Class feature|Creation|Subclass (?:foundation|specialization|mastery)) - /, '');
  return f.source.startsWith('Ancestry - ') ? origin.replace(/ \((?:first|second)\)$/, '') : origin;
}

export function rollOptionLabel(s: Pick<AvailableRollOption, 'source' | 'origin' | 'option'>): string {
  return sourceActionLabel(s.origin, s.source, s.option.label);
}

export function sourceActionLabel(origin: string | undefined, source: string, action: string): string {
  const parts: string[] = [];
  for (const name of [origin, source, action]
    .filter((name): name is string => !!name?.trim())
    .map((name) => name.trim())) {
    const previous = parts[parts.length - 1];
    if (previous === name) continue;
    if (previous && [' (', ':', ' - '].some(suffix => name.startsWith(previous + suffix))) {
      parts[parts.length - 1] = name;
    } else {
      parts.push(name);
    }
  }
  return parts.join(' - ');
}

/** Optional effects are selected by the player, never inferred from situational prose. */
export function availableRollOptions(c: CharacterSheet, targets: string[], weapon?: CharacterSheet['activeWeapons'][number], outcome?: 'hope' | 'fear' | 'critical'): AvailableRollOption[] {
  const sources: Array<{ key: string; name: string; origin?: string; auto?: Automation; item?: CharacterSheet['inventory'][number]; weapon?: CharacterSheet['activeWeapons'][number] }> = [
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...activeWeapons(c).map((w) => ({ key: `weapon:${w.name}`, name: w.name, auto: w.automation, weapon: w })),
    ...activeFeatures(c).map((f) => ({ key: `feat:${f.name}`, name: f.name, origin: featureRollOrigin(c, f), auto: f.automation })),
    ...c.heritage.map((f) => ({ key: `feat:${f.name}`, name: f.name, origin: featureRollOrigin(c, f), auto: f.automation })),
    ...activeCardSources(c),
    ...c.inventory.filter((i) => i.qty > 0 || i.automation?.rollOptions?.some((o) => o.effect && c.effects.some((e) => e.name === o.effect!.name))).map((i) => ({ key: `item:${i.name}`, name: i.name, auto: i.automation, item: i })),
    ...c.effects.filter(e => actorAutomationAvailable(e.sourceActorId)).map((e) => ({ key: `effect:${e.id}`, name: e.name, auto: {
      rollOptions: (e.unique ? e.rollOptions?.filter(o => targets.includes(o.roll)).slice(0, 1) : e.rollOptions)
        ?.map(o => o.effectValue ? { ...o, value: e.counter?.value ?? 0 } : o),
    } })),
  ];
  const prepared: AvailableRollOption[] = c.effects.filter(e => actorAutomationAvailable(e.sourceActorId)).flatMap((e) => (e.modifiers ?? []).flatMap((modifier, i) => {
    if (!modifier.consume || !modifier.roll || !targets.includes(modifier.roll)) return [];
    const option: RollOption = { id: `prepared:${e.id}:${i}`, label: 'prepared', roll: modifier.roll, value: modifier.value, dice: modifier.dice, diceByLevel: modifier.diceByLevel };
    return [{ key: `effect:${e.id}:${i}`, source: e.name, automation: {}, option, active: true, prepared: { effect: e, modifier } }];
  }));
  return [...prepared, ...sources.flatMap((s) => [...(s.auto?.rollOptions ?? []), ...(s.auto?.conditionalRollOptions ?? [])]
    .filter((o) => loadoutDomainMatches(c, o.loadoutDomain))
    .filter((o) => targets.includes(o.roll) || o.roll === 'trait' && targets.some(t => t.startsWith('trait:'))
      || o.roll === 'duality' && (targets.includes('action') || targets.includes('reaction')))
    .filter((o) => !o.requiresFeature || hasFeature(c, o.requiresFeature))
    .filter((o) => !o.excludesFeature || !hasFeature(c, o.excludesFeature))
    .filter((o) => !o.excludesEffect || !c.effects.some(e => e.name === o.excludesEffect))
    .filter((o) => !o.condition || conditionMatches(c, o.condition))
    .filter((o) => !o.requiresRollOutcome || outcome === 'hope' || outcome === 'critical')
    .filter((o) => o.weapon !== 'any' || !!weapon && !targets.includes('spelldamage'))
    .filter((o) => !o.chooseTrait || !!weapon && targets.includes('attack'))
    .filter((o) => !o.secondaryWeaponDie || activeWeapons(c).some(w => w.slot === 'Secondary' && /\d*d\d+/i.test(w.damage)))
    .filter((o) => !o.weaponRange || weapon?.range.toLowerCase() === o.weaponRange.toLowerCase())
    .filter((o) => !o.weaponRanges || !!weapon && !targets.includes('spelldamage') && o.weaponRanges.some(range => range.toLowerCase() === weapon.range.toLowerCase()))
    .filter((o) => !o.excludeRolls?.some(target => targets.includes(target)))
    .filter((o) => !o.magicOnly || weapon?.damageType === 'magic' || targets.includes('magicdamage') || targets.includes('spelldamage'))
    .filter((o) => !o.requiresEffect || c.effects.some((e) => e.name === o.requiresEffect))
    .filter((o) => !o.resourceEmpty || (c.resources?.[o.resourceEmpty] ?? 0) === 0)
    .filter((o) => !o.requiresResource || (c.resources?.[o.requiresResource] ?? 0) > 0)
    .filter((o) => !o.resourceMultiplier || (c.resources?.[o.resourceMultiplier.resource] ?? 0) > 0)
    .filter((o) => !o.weaponName || weapon?.name === o.weaponName && activeWeapons(c).some(w => w.name === o.weaponName))
    .filter((o) => o.weapon === 'primary' || o.weapon === 'secondary'
      ? weapon?.slot.toLowerCase() === o.weapon && activeWeapons(c).some(w => sameWeapon(w, weapon))
      : !s.weapon || o.weapon === 'any' || !weapon || sameWeapon(weapon, s.weapon))
    .map((option) => ({
      key: option.uses ? `${s.key}:roll:${option.id}` : s.key,
      source: s.name, origin: s.origin, automation: option.uses ? { ...s.auto!, uses: option.uses } : option.usesSource === false ? { ...s.auto!, uses: undefined } : s.auto!, option,
      item: s.item,
      active: !!option.effect && c.effects.some((e) => e.name === option.effect!.name),
    })))];
}

export function rollOptionDice(c: CharacterSheet, option: RollOption): string | undefined {
  if (option.allResourceDice) return `${c.resources?.[option.allResourceDice.resource] ?? 0}d${option.allResourceDice.sides}`;
  if (option.secondaryWeaponDie) {
    const damage = activeWeapons(c).find(w => w.slot === 'Secondary')?.damage;
    const sides = /\d*d(\d+)/i.exec(damage ?? '')?.[1];
    return sides ? `1d${sides}` : undefined;
  }
  const level = Object.keys(option.diceByLevel ?? {}).map(Number).filter((n) => n <= c.level).sort((a, b) => b - a)[0];
  let dice = level === undefined ? option.dice ?? (option.dieSizes?.length ? `1d${option.dieSizes[0]}` : undefined) : option.diceByLevel![String(level)];
  for (const [feature, replacement] of Object.entries(option.diceByFeature ?? {})) {
    if (hasFeature(c, feature)) dice = replacement;
  }
  for (const condition of option.diceConditions ?? []) {
    if (!conditionMatches(c, condition)) continue;
    if (condition.dice) dice = condition.dice;
    if (condition.dieSize) dice = dice?.replace(/d\d+/g, `d${condition.dieSize}`);
  }
  if (dice?.includes('@fear') && evalValue('@fear', c) <= 0) return undefined;
  return dice?.replace(/@tier/g, String(tierOf(c.level))).replace(/@fear/g, String(Math.max(0, evalValue('@fear', c))));
}

export function damageRollFormula(expression: string, contributions: RollContribution[], selected: AvailableRollOption[]): { expression: string; contributions: RollContribution[] } {
  const doubling = selected.filter((s) => s.option.multiplier === 2);
  if (!doubling.length) return { expression, contributions };
  const notes = doubling.map((s) => ({ label: rollOptionLabel(s), impact: 'Double the rolled damage total' }));
  return {
    expression: `(${expression})*2`,
    contributions: contributions.map((part) => ({ ...part, expression: `(${part.expression})*2`, notes: [...(part.notes ?? []), ...notes] })),
  };
}

/** Replace one positive die with its maximum without rolling it. */
export function maximizeDamageDie(expression: string, index: number): string {
  let offset = 0;
  let changed = false;
  const result = expression.replace(/([+-]?)(\d*)d(\d+)(?:kh(\d+))?/gi, (term, sign: string, countText: string, sidesText: string, keptText: string | undefined) => {
    const count = Number(countText || 1), sides = Number(sidesText), kept = keptText ? Number(keptText) : count;
    const selected = index >= offset && index < offset + count;
    offset += count;
    if (!selected) return term;
    if (sign === '-' || kept < 1) throw new Error('This damage die cannot be maximized.');
    changed = true;
    const remaining = count - 1;
    const keptRemaining = kept - 1;
    return `${sign}${remaining ? `${remaining}d${sides}${keptRemaining < remaining ? `kh${keptRemaining}` : ''}+` : ''}${sides}`;
  });
  if (!changed) throw new Error('Choose an available damage die.');
  return result;
}

export function maximizableDamageDice(expression: string): Array<{ index: number; sides: number }> {
  const dice: Array<{ index: number; sides: number }> = [];
  let offset = 0;
  for (const match of expression.matchAll(/([+-]?)(\d*)d(\d+)(?:kh(\d+))?/gi)) {
    const count = Number(match[2] || 1), sides = Number(match[3]), kept = match[4] ? Number(match[4]) : count;
    if (match[1] !== '-' && kept > 0) {
      for (let i = 0; i < count; i++) dice.push({ index: offset + i, sides });
    }
    offset += count;
  }
  return dice;
}

export function selectedRollBonus(c: CharacterSheet, base: RollBonus, selected: AvailableRollOption[]): RollBonus {
  const out = { ...base, dice: [...base.dice], names: [...base.names], contributions: [...base.contributions] };
  for (const selection of selected) {
    const { option } = selection;
    const value = option.resourceMultiplier ? (c.resources?.[option.resourceMultiplier.resource] ?? 0) * option.resourceMultiplier.value
      : option.traitValue ? traitTotal(c, option.traitValue)
      : option.derivedProficiency ? derivedStats(c).proficiency : evalValue(option.value, c);
    const dice = rollOptionDice(c, option);
    const label = rollOptionLabel(selection).slice(0, 120);
    out.value += value;
    if (dice) out.dice.push(dice);
    out.names.push(label);
    out.contributions.push({ label, expression: `${value}${dice ? `+${dice.startsWith('d') ? `1${dice}` : dice}` : ''}`,
      ...(option.diceResource ? { notes: [{ label: 'Dice resource', impact: option.diceResource }] } : {}) });
  }
  return out;
}

export function suggestedAdvantage(selected: AvailableRollOption[]): Advantage {
  const advantage = selected.some((s) => s.option.advantage === 'advantage');
  const disadvantage = selected.some((s) => s.option.advantage === 'disadvantage');
  return advantage === disadvantage ? 0 : advantage ? 1 : -1;
}

export function effectiveAdvantage(requested: Advantage, selected: AvailableRollOption[]): Advantage {
  return requested === -1 && selected.some((s) => s.option.ignoreDisadvantage) ? 0 : requested;
}

export function selectionBlocker(c: CharacterSheet, selected: AvailableRollOption[], experience = false): string | null {
  if (selected.some(s => s.option.experience) && !experience) return 'Select an Experience to use Adept';
  if (selected.filter(s => s.option.experience).length > 1) return 'Choose only one Experience cost replacement';
  if (selected.filter(s => s.option.combo).length > 1) return 'Choose only one Combo Strike cost';
  const groups = selected.map(s => s.option.exclusive).filter(Boolean);
  if (new Set(groups).size !== groups.length) return 'Choose only one cost or bonus from each exclusive group';
  const cost: Record<string, number> = experience ? { [experienceResource(selected)]: 1 } : {};
  const uses = new Map<string, number>();
  const items = new Map<CharacterSheet['inventory'][number], number>();
  for (const s of selected) {
    if (s.option.allResourceDice && (c.resources?.[s.option.allResourceDice.resource] ?? 0) < 1)
      return `${s.source}: no dice left`;
    if (s.option.resourceMultiplier && (c.resources?.[s.option.resourceMultiplier.resource] ?? 0) < 1)
      return `${s.source}: no tokens left`;
    if (!loadoutDomainMatches(c, s.option.loadoutDomain)) return 'Required domain cards are no longer in the active loadout';
    if (s.option.condition && !conditionMatches(c, s.option.condition)) return 'A selected bonus no longer meets its condition';
    if (s.key.startsWith('effect:') && !c.effects.some(e => s.key === `effect:${e.id}` || s.key === `effect:${e.id}:pending` || s.prepared?.effect === e)) return 'This effect is no longer active';
    if (s.active) continue;
    const block = actionBlocker(c, s.key, s.automation, s.option);
    if (block) return block;
    if (s.item && (!c.inventory.includes(s.item) || s.item.qty <= 0)) return `${s.source}: none left`;
    if (s.option.consumeItem && s.item) items.set(s.item, (items.get(s.item) ?? 0) + 1);
    if (s.automation.uses) {
      const count = (uses.get(s.key) ?? 0) + 1;
      uses.set(s.key, count);
      const left = usesLeft(c, s.key, s.automation);
      if (left !== null && count > left) return `${s.source}: not enough uses left`;
    }
    for (const [res, value] of Object.entries(actionCost(c, s.option) ?? {})) cost[res] = (cost[res] ?? 0) + evalValue(value, c);
  }
  for (const [item, count] of items) if (item.qty < count) return `${item.name}: not enough inventory`;
  return costBlocker(c, cost);
}

/** Commit only after the send succeeded and the entire selection was checked together. */
export function commitRollOptions(c: CharacterSheet, selected: AvailableRollOption[], experience = false, targets: string[] = [], refundExperience = false): void {
  selected = selected.filter(s => !s.option.hostOwned);
  const blocker = selectionBlocker(c, selected, experience);
  if (blocker) throw new Error(blocker);
  if (experience && !refundExperience) {
    if (experienceResource(selected) === 'stress') c.stress.on++;
    else c.hope.on--;
  }
  for (const s of selected) {
    if (s.option.hostOwned) continue;
    if (s.prepared) {
      const { effect, modifier } = s.prepared;
      effect.modifiers = (effect.modifiers ?? []).filter((m) => m !== modifier);
      if (!effect.modifiers.length && c.effects.includes(effect)) c.effects.splice(c.effects.indexOf(effect), 1);
      continue;
    }
    if (s.active) continue;
    const steps = s.option.effect ? [...(s.option.steps ?? []), { op: 'effect' as const, effect: s.option.effect }] : s.option.steps;
    runAction(c, s.key, s.automation, { ...s.option, steps }, s.source);
    if (s.option.consumeItem && s.item) s.item.qty--;
  }
  if (targets.includes('damage')) c.effects = c.effects.filter(e => !e.endOnDealtDamage || selected.some(s => s.option.preserveEffect === e.name));
}

export function experienceResource(selected: AvailableRollOption[]): 'hope' | 'stress' {
  return selected.find(s => s.option.experience)?.option.experience?.resource ?? 'hope';
}

export function experienceModifier(mod: number, selected: AvailableRollOption[]): number {
  return mod * (selected.find(s => s.option.experience)?.option.experience?.multiplier ?? 1);
}

export function rollExperienceRefund(c: CharacterSheet, selected: AvailableRollOption[], roll: DiceRoll = rollLocal): { refunded: boolean; contribution: RollContribution } | undefined {
  const feature = [...activeFeatures(c), ...c.heritage].find(f => f.automation?.experienceRefund);
  const rule = feature?.automation?.experienceRefund;
  if (!feature || !rule) return undefined;
  const result = roll(rule.dice);
  const refunded = result >= rule.min;
  const resource = experienceResource(selected) === 'stress' ? 'Stress' : 'Hope';
  return { refunded, contribution: {
    label: `${feature.name}: ${rule.dice} = ${result}`.slice(0, 120), expression: '0',
    notes: [{ label: 'Experience cost', impact: refunded ? `1 ${resource} refunded` : `1 ${resource} spent` }],
  } };
}

export function traitContributions(c: CharacterSheet, name: TraitName): RollContribution[] {
  return [
    { label: `${name} trait`, expression: String(c.traits.find((t) => t.name === name)?.mod ?? 0) },
    ...statSources(c, `trait.${name}`, 'traits').map((s) => ({ label: s.label.slice(0, 120), expression: String(s.value) })),
  ];
}

export function damageContributions(c: CharacterSheet, weapon: CharacterSheet['activeWeapons'][number], bonus: RollBonus, proficiencyBonus = 0): RollContribution[] {
  const proficiency = derivedStats(c).proficiency + proficiencyBonus;
  const pool = damagePool(c, weapon);
  const base = damageExpression(weapon.damage, proficiency, { ...bonus, value: 0, dice: [] }, pool, weapon.fixedDice);
  const parts = /^(\d+d\d+(?:kh\d+)?)([+-]\d+)?$/i.exec(base);
  const sources = statSources(c, 'proficiency');
  const raw = c.proficiency.on + sources.reduce((n, s) => n + s.value, 0);
  const diceCount = (n: number) => `${n >= 0 ? '+' : ''}${n} ${Math.abs(n) === 1 ? 'die' : 'dice'}`;
  const notes = [
    { label: 'Base Proficiency', impact: `${c.proficiency.on} dice` },
    ...(proficiencyBonus ? [{ label: 'Attack-linked Proficiency', impact: diceCount(proficiencyBonus) }] : []),
    ...sources.map((s) => ({ label: s.label.slice(0, 120), impact: diceCount(s.value) })),
    ...c.effects.filter(e => e.damage && (!e.damage.proficiencyOnly || !weapon.fixedDice) && (e.damage.extraDice || e.damage.dropLowest)).map(e => ({
      label: e.name, impact: `Each pool: roll ${e.damage!.extraDice} extra; discard ${e.damage!.dropLowest} lowest`,
    })),
    ...(raw < 1 ? [{ label: 'Minimum Proficiency', impact: diceCount(1 - raw) }] : []),
    ...(weapon.automation?.damage && (weapon.automation.damage.extraDice || weapon.automation.damage.dropLowest)
      ? [{ label: weapon.feature?.name ?? weapon.name, impact: `Roll ${weapon.automation.damage.extraDice} extra; discard ${weapon.automation.damage.dropLowest} lowest` }] : []),
    ...(weapon.profile ? [{ label: weapon.profile, impact: `Alternate statistics: ${weapon.trait}, ${weapon.range}, ${weapon.damage}`.slice(0, 120) }] : []),
    ...(weapon.dieOverride ? [{ label: 'Attack option', impact: `d${weapon.dieOverride} damage dice for this attack` }] : []),
  ];
  return [
    { label: `${weapon.name} damage (Proficiency ${proficiency})`.slice(0, 120), expression: parts?.[1] ?? base, ...(parts ? { notes } : {}) },
    ...(parts?.[2] ? [{ label: `${weapon.name} damage bonus`.slice(0, 120), expression: parts[2] }] : []),
    ...bonus.contributions,
  ];
}

/** Removes the consumable modifiers (and emptied effects) once their roll was sent. */
export function consumeBonus(c: CharacterSheet, bonus: RollBonus): void {
  for (const { effect, modifier } of bonus.applied) {
    effect.modifiers = (effect.modifiers ?? []).filter((m) => m !== modifier);
    if (!effect.modifiers.length) c.effects.splice(c.effects.indexOf(effect), 1);
  }
}

let effectCounter = 0;

export function addEffect(c: CharacterSheet, def: EffectDef, source?: string): ActiveEffect {
  const effect: ActiveEffect = {
    ...def,
    ...(def.counter ? { counter: { ...def.counter } } : {}),
    until: def.until ?? 'manual',
    modifiers: (def.modifiers ?? []).map((m) => ({ ...m })),
    id: `e${Date.now().toString(36)}${effectCounter++}`,
    source,
  };
  if (def.group) c.effects = c.effects.filter(e => e.group !== def.group);
  syncEffectDependencies(c);
  const existing = c.effects.findIndex((e) => e.name === def.name);
  if (existing >= 0) c.effects.splice(existing, 1);
  c.effects.push(effect);
  return effect;
}

export type RestKind = 'short' | 'long';

/** Expires effects and refreshes limited-use abilities for a rest. */
export function applyRest(c: CharacterSheet, kind: RestKind): void {
  for (const card of [...c.loadout, ...c.vault]) clearBorrowedCard(c, card);
  if (kind === 'long') returnCompanion(c);
  syncResources(c);
  const restResources = [...new Map([
    ...c.vault.flatMap(card => card.automation?.resources ?? []),
    ...resourceDefinitions(c),
  ].map(resource => [resource.id, resource])).values()];
  if (kind === 'long') for (const r of resourceDefinitions(c)) {
    if (r.longRestLoss) c.resources![r.id] = Math.max(0, c.resources![r.id] - r.longRestLoss);
  }
  const expiring: Until[] = kind === 'long' ? ['rest', 'shortRest', 'longRest', 'scene'] : ['shortRest', 'rest', 'scene'];
  for (const effect of c.effects.filter(e => e.surgeResource)) {
    c.stress.on = Math.min(c.stress.slots, c.stress.on + 1);
    c.resources![effect.surgeResource!] = 0;
  }
  c.effects = c.effects.filter((e) => !expiring.includes(e.until ?? 'manual'));
  for (const r of restResources) {
    if (r.resetOn === 'rest' || r.resetOn === 'shortRest' || (kind === 'long' && r.resetOn === 'longRest')) {
      c.resources![r.id] = Math.max(r.minimumReset ?? 0, evalValue(r.resetValue ?? r.initial, c));
    }
  }
  for (const key of Object.keys(c.usage)) {
    const per = c.usage[key].per;
    if (per === 'shortRest' || per === 'rest' || per === 'scene' || (kind === 'long' && (per === 'longRest'))) {
      delete c.usage[key];
    }
  }
}

export function usesLeft(c: CharacterSheet, key: string, a: Automation): number | null {
  if (!a.uses) return null;
  return Math.max(0, usesMax(c, key, a) - (c.usage[key]?.used ?? 0));
}

/** Player-facing reason an exhausted limited-use ability is blocked. */
export function noUsesLeftText(per: NonNullable<Automation['uses']>['per'] | undefined): string {
  return per === 'session' ? 'No uses left this session' : per === 'scene' ? 'No uses left this scene' : 'No uses left until a rest';
}

export function usesMax(c: CharacterSheet, key: string, a: Automation): number {
  return automationSources(c).reduce((max, source) => Math.max(max, source.usesOverrides?.[key] ?? 0), a.uses?.max ?? 0);
}

export function costText(cost?: ActionDef['cost'], c?: CharacterSheet): string {
  if (!cost) return '';
  const parts: string[] = [];
  const labels: Record<string, string> = { hope: 'Hope', stress: 'Stress', armor: 'Armor Slot', hp: 'HP', gold: 'gold coins' };
  for (const [res, value] of Object.entries(cost)) {
    if (value === undefined) continue;
    const n = c ? evalValue(value, c) : value;
    if (n) parts.push(`${n} ${labels[res] ?? (c && resourceDefinitions(c).find((r) => r.id === res)?.label) ?? res}`);
  }
  return parts.join(', ');
}

function track(c: CharacterSheet, r: Resource) {
  return r === 'hp' ? c.hp : r === 'stress' ? c.stress : r === 'hope' ? c.hope : r === 'armor' ? c.armorTrack : undefined;
}

export function amountAvailable(c: CharacterSheet, resource: string): number {
  syncResources(c);
  if (resource === 'gold') return Math.max(0, Math.floor(c.gold ?? 0));
  const t = track(c, resource);
  return Math.max(0, t ? resource === 'hope' ? t.on : t.slots - t.on : c.resources?.[resource] ?? 0);
}

function costBlocker(c: CharacterSheet, cost: ActionDef['cost']): string | null {
  syncResources(c);
  for (const [res, value] of Object.entries(cost ?? {})) {
    const n = evalValue(value, c);
    if (!Number.isFinite(n) || n < 0) return `Invalid ${res} cost`;
    if (res === 'gold') {
      if ((c.gold ?? 0) < n) return `Not enough gold (${n} coins needed)`;
      continue;
    }
    const t = track(c, res);
    if (t) {
      if (res === 'hope' ? t.on < n : t.on + n > t.slots) return res === 'hope' ? 'Not enough Hope' : `Not enough free ${res === 'armor' ? 'Armor Slots' : res.toUpperCase()}`;
    } else {
      const def = resourceDefinitions(c).find((r) => r.id === res);
      if (!def) return `Resource unavailable: ${res}`;
      if ((c.resources?.[res] ?? 0) < n) return `Not enough ${def.label}`;
    }
  }
  return null;
}

function payCost(c: CharacterSheet, cost: ActionDef['cost']): void {
  for (const [res, value] of Object.entries(cost ?? {})) {
    const n = evalValue(value, c);
    if (res === 'gold') { c.gold = Math.max(0, (c.gold ?? 0) - n); continue; }
    const t = track(c, res);
    if (t) t.on += res === 'hope' ? -n : n;
    else c.resources![res] -= n;
  }
}

export function isRestOnlyAction(action: ActionDef): boolean {
  return action.timing === 'downtime' || action.timing === 'rest';
}

export function sheetActions(a: Automation | undefined): ActionDef[] {
  return [...(a?.actions ?? []), ...(a?.conditionalActions ?? [])].filter((action) => !action.chatOnly && (!isRestOnlyAction(action) || action.ancestryChoice));
}

export function routeDeathAction(action: ActionDef, open: (move: string) => void): boolean {
  if (!action.deathMove) return false;
  open(action.deathMove);
  return true;
}

/** Returns why the action cannot be used, or null when it can. */
export function actionBlocker(c: CharacterSheet, key: string, a: Automation, action: ActionDef, context: 'sheet' | 'rest' = 'sheet'): string | null {
  const amountResource = action.castTokens?.resource ?? action.selectAmount?.resource ?? action.resourceRoll?.resource;
  const minimum = action.selectAmount?.min ?? 1;
  if (amountResource && amountAvailable(c, amountResource) < minimum) return `Not enough ${amountResource}`;
  if (!loadoutDomainMatches(c, action.loadoutDomain)) return 'Requires more matching domain cards in the active loadout';
  if (action.requiresZeroHope && c.hope.on !== 0) return 'Requires zero Hope';
  if (action.minimumHope && c.hope.on < action.minimumHope) return 'Not enough Hope';
  if (action.usesSource === false) a = { ...a, uses: undefined };
  if ((action.companion || action.requiresCompanion) && !companionAvailable(c)) return 'Your companion is unavailable';
  if (action.aura && !c.effects.some(e => e.group === 'channel')) return 'Channel an element first';
  if ((action.patronDice || action.spendFavor) && (c.resources?.favor ?? 0) < 1) return 'Not enough Favor';
  if (action.menacingReach && !activeWeapons(c).some(w => w.slot === 'Primary')) return 'Equip a primary weapon first';
  if (action.menacingReach && c.effects.some(e => e.name === 'Menacing Reach')) return 'Menacing Reach is already active';
  if (action.requiresFeature && !hasFeature(c, action.requiresFeature)) return 'Required feature is not available';
  if (action.excludesFeature && hasFeature(c, action.excludesFeature)) return 'This option has been replaced by another feature';
  if (action.actionRoll?.trait === 'spellcast' && !c.traits.some(t => t.spellcast)) return 'No Spellcast trait is available';
  if (action.witchAction === 'talisman' && c.hope.on < 1) return 'Not enough Hope';
  if (action.witchAction === 'lunarAdvance' && !c.effects.some(e => e.group === 'lunar-phases')) return 'Roll your Lunar Phases die first';
  if (action.sendRoll?.dice.includes('@spellcast') && evalValue('@spellcast', c) < 1) return 'Requires a positive Spellcast trait to roll dice';
  if (action.deathMove && c.hp.slots > 0 && (c.permanentHp ?? 0) >= c.hp.slots) return 'All Hit Points are permanently marked; this character has crossed the veil of death.';
  if (action.deathMove && (c.hp.on < c.hp.slots || c.pendingDeathRoll)) return 'Requires all HP marked and no pending death recovery';
  if (context !== 'rest' && isRestOnlyAction(action) && !action.ancestryChoice) return 'Choose this move in the rest dialog';
  if (action.requiresEffect && !c.effects.some((e) => e.name === action.requiresEffect)) return 'Required effect is not active';
  if (action.excludesEffect && c.effects.some(e => e.name === action.excludesEffect)) return 'Effect is already active';
  if (action.uses) { key = `${key}:action:${action.id}`; a = { ...a, uses: action.uses }; }
  const left = usesLeft(c, key, a);
  if (left !== null && left <= 0) return noUsesLeftText(a.uses?.per);
  for (const step of action.steps ?? []) {
    if (step.resource === 'scars' || step.resource === 'permanentHp') continue;
    if (step.resource && !step.fallbackEffect && !track(c, step.resource) && !resourceDefinitions(c).some((r) => r.id === step.resource)) return `Resource unavailable: ${step.resource}`;
  }
  return costBlocker(c, actionCost(c, action));
}

export type DiceRoll = (expression: string, clearingResource?: string) => number;

/** Default local dice roller for expressions such as "1d4+1". */
export const rollLocal: DiceRoll = (expression) => {
  let total = 0;
  for (const m of expression.toLowerCase().replace(/\s/g, '').matchAll(/([+-]?)(?:(\d*)d(\d+)|(\d+))/g)) {
    const sign = m[1] === '-' ? -1 : 1;
    if (m[3]) {
      const count = m[2] ? Number(m[2]) : 1;
      for (let i = 0; i < count; i++) total += sign * (1 + Math.floor(Math.random() * Number(m[3])));
    } else total += sign * Number(m[4]);
  }
  return total;
};

/** Runs an action: pays costs, counts the use, executes steps. Returns a chat-friendly summary. */
export function runAction(c: CharacterSheet, key: string, a: Automation, action: ActionDef, label: string, roll: DiceRoll = rollLocal, context: 'sheet' | 'rest' = 'sheet'): string | null {
  const result = runActionDetailed(c, key, a, action, label, roll, context);
  return result && `${label}: ${result.join(', ') || 'used'}`;
}

/** Like {@link runAction}, but returns the individual effects (empty when nothing visible happened). */
export function runActionDetailed(c: CharacterSheet, key: string, a: Automation, action: ActionDef, label: string, roll: DiceRoll = rollLocal, context: 'sheet' | 'rest' = 'sheet'): string[] | null {
  if (actionBlocker(c, key, a, action, context)) return null;
  const lunar = action.witchAction === 'lunarRoll' || action.witchAction === 'lunarAdvance';
  const previousFace = c.effects.find(e => e.group === 'lunar-phases')?.counter?.value ?? 0;
  const face = lunar ? action.witchAction === 'lunarRoll' ? roll('1d6') : previousFace % 6 + 1 : 0;
  const phase = lunar ? a.lunarPhases?.find(p => p.faces.includes(face)) : undefined;
  if (lunar && !phase) throw new Error(`No Lunar Phases effect configured for ${face}.`);
  if (action.usesSource === false) a = { ...a, uses: undefined };
  if (action.uses) { key = `${key}:action:${action.id}`; a = { ...a, uses: action.uses }; }
  const log: string[] = [];
  const resolvedCost = actionCost(c, action);
  payCost(c, resolvedCost);
  const cost = costText(resolvedCost, c);
  if (cost) log.push(`spent ${cost}`);
  if (action.toggleEffect) {
    const definition = action.toggleEffect;
    if (c.effects.some(effect => effect.name === definition.name)) {
      c.effects = c.effects.filter(effect => effect.name !== definition.name);
      log.push(`ended ${definition.name}`);
    } else {
      addEffect(c, structuredClone(definition), label);
      log.push(`gained ${definition.name}`);
    }
  }
  if (a.uses && !action.deferSourceUse) {
    const u = c.usage[key] ?? { used: 0, per: a.uses.per };
    u.used++;
    c.usage[key] = u;
  }
  if (action.rollDicePool && a.dicePool) log.push(...rollDicePool(c, a.dicePool, roll));
  if (phase) {
    addEffect(c, { ...structuredClone(phase.effect), group: 'lunar-phases', counter: { value: face, max: 6 }, until: 'session' }, label);
    log.push(`Lunar Phases: ${action.witchAction === 'lunarRoll' ? 'rolled' : 'increased to'} ${face}. ${phase.effect.text}`);
  }
  if (action.companion === 'comfortHope') {
    const gained = Math.min(1, Math.max(0, c.hope.slots - c.hope.on));
    c.hope.on += gained;
    log.push(`gained ${gained} Hope`);
  }
  if (action.companion === 'comfortStress') {
    const cleared = Math.min(1, c.stress.on);
    c.stress.on -= cleared;
    const companionCleared = clearCompanionStress(c, 1);
    log.push(`cleared ${cleared} owner Stress and ${companionCleared} companion Stress`);
  }
  const steps = [...action.steps ?? []];
  if (action.table) {
    const total = roll(action.table.dice);
    const outcome = action.table.outcomes.find(o => total >= o.min && total <= (o.max ?? o.min));
    log.push(`rolled ${action.table.dice} = ${total}${outcome ? `: ${outcome.text}` : ''}`);
    steps.push(...outcome?.steps ?? []);
  }
  if (action.refreshUses) {
    const kinds = action.refreshUses === 'long' ? ['shortRest', 'rest', 'longRest'] : ['shortRest', 'rest'];
    const keys = Object.keys(c.usage).filter(k => kinds.includes(c.usage[k].per) && k !== key);
    for (const k of keys) delete c.usage[k];
    log.push(`refreshed ${keys.length} limited feature use${keys.length === 1 ? '' : 's'} as if after a ${action.refreshUses} rest`);
  }
  if (action.doomsday) log.push(...rollDoomsday(c, action.doomsday.effect, label, roll));
  // Report amounts describe the state before this action's steps (Lightning Scarab clears the pool it reports).
  const reportAmount = action.report ? (() => {
    const trait = TRAIT_ORDER.find(name => action.report!.amount === `@${name}`);
    return action.report!.dice ? roll(action.report!.dice) : trait ? traitTotal(c, trait) : evalValue(action.report!.amount, c);
  })() : 0;
  for (const step of steps) {
    if (step.fallbackEffect && !(step.resource && track(c, step.resource)) && !resourceDefinitions(c).some(r => r.id === step.resource)) {
      addEffect(c, structuredClone(step.fallbackEffect), label);
      log.push(`gained ${step.fallbackEffect.name}`);
      continue;
    }
    const t = step.resource ? track(c, step.resource) : null;
    const rawAmount = (fallback: number) => typeof step.amount === 'number' ? step.amount
      : step.amount && /\d*d\d+/i.test(step.amount)
        ? roll(step.amount.replace(/@[a-z]+/gi, (value) => String(evalValue(value, c))), step.op === 'clear' && t ? step.resource : undefined)
        : step.amount ? evalValue(step.amount, c) : fallback;
    const amount = (fallback: number) => Math.max(step.minimumAmount ?? 0, rawAmount(fallback));
    if (step.resource === 'scars' || step.resource === 'permanentHp') {
      const n = Math.max(0, amount(1));
      if (step.resource === 'scars') {
        const before = c.scars ?? 0;
        c.scars = step.op === 'clear' ? Math.max(0, before - n) : Math.min(c.hope.total, before + n);
        log.push(`${step.op === 'clear' ? 'healed' : 'gained'} ${Math.abs(c.scars - before)} scar${Math.abs(c.scars - before) === 1 ? '' : 's'}`);
      } else if (step.op !== 'clear') {
        const lost = Math.min(n, Math.max(0, c.hp.slots - (c.permanentHp ?? 0)));
        c.permanentHp = (c.permanentHp ?? 0) + lost;
        c.hp.on = Math.min(c.hp.slots, c.hp.on + lost);
        log.push(`permanently lost ${lost} HP slot${lost === 1 ? '' : 's'}`);
      }
      syncDeathState(c);
      continue;
    }
    if (step.resource && !t) {
      const def = resourceDefinitions(c).find((r) => r.id === step.resource)!;
      const before = c.resources![def.id];
      const n = Math.max(0, step.amount === 'all' ? before : amount(1));
      const max = def.max === undefined ? Infinity : Math.max(0, evalValue(def.max, c));
      c.resources![def.id] = step.op === 'gain' ? Math.min(max, before + n) : Math.max(0, before - n);
      log.push(`${step.op === 'gain' ? 'gained' : 'spent'} ${Math.abs(c.resources![def.id] - before)} ${def.label}`);
      continue;
    }
    if (step.op === 'clear' && t && step.resource) {
      const n = step.amount === 'all' ? t.on : amount(1);
      const cleared = Math.min(Math.max(0, t.on - (step.resource === 'hp' ? hpHealingFloor(c) : 0)), Math.max(0, n));
      t.on -= cleared;
      const detail = typeof step.amount === 'string' && step.amount !== 'all' && !/^[+-]?\d+$/.test(step.amount.trim())
        ? ` (${step.amount})` : '';
      log.push(`cleared ${cleared} ${step.resource === 'hp' ? 'HP' : step.resource === 'armor' ? 'Armor Slots' : 'Stress'}${detail}`);
      if (step.resource === 'armor') {
        const overflow = armorOverflowHope(c, Math.max(0, n), cleared);
        if (overflow) log.push(overflow);
      }
    } else if (step.op === 'gain' && t && step.resource) {
      const n = Math.max(0, amount(1));
      const before = t.on;
      t.on = Math.min(t.slots, t.on + n);
      log.push(`gained ${t.on - before} ${step.resource === 'hope' ? 'Hope' : step.resource}`);
    } else if (step.op === 'mark' && t && step.resource) {
      const n = step.amount === 'all' ? t.slots - t.on : Math.max(0, amount(1));
      const before = t.on;
      t.on = Math.min(t.slots, t.on + n);
      log.push(`marked ${t.on - before} ${step.resource === 'hp' ? 'HP' : step.resource === 'armor' ? 'Armor Slots' : step.resource === 'stress' ? 'Stress' : step.resource}`);
    } else if (step.op === 'effect' && step.effect) {
      const form = c.effects.find(e => e.beastform && e.features?.some(f => key === `feat:${f.name}`));
      addEffect(c, { ...step.effect, ...(form ? { requiresEffect: form.name } : {}) }, label);
      log.push(`${step.effect.kind === 'debuff' ? 'afflicted by' : 'gained'} ${step.effect.name}`);
    }
  }
  if (action.endEffect) {
    c.effects = c.effects.filter((e) => e.name !== action.endEffect);
    log.push(`ended ${action.endEffect}`);
  }
  if (action.report) log.push(action.report.text.replace('{amount}', String(Math.max(0, reportAmount))));
  syncEffectDependencies(c);
  syncDeathState(c);
  return log;
}

/** Doomsday Potion: d12 per Hope slot (2d12 keep highest if marked); below 12 scars the slot, a 12 stops and grants `effect`. */
export function rollDoomsday(c: CharacterSheet, effect: EffectDef, label: string, roll: DiceRoll = rollLocal): string[] {
  const log: string[] = [];
  const slots = c.hope.slots;
  const marked = c.hope.on;
  let scars = 0;
  for (let slot = 0; slot < slots; slot++) {
    const isMarked = slot < marked;
    const first = roll('1d12');
    const result = isMarked ? Math.max(first, roll('1d12')) : first;
    const text = `Hope slot ${slot + 1}${isMarked ? ' (marked, 2d12 keep highest)' : ''}: ${result}`;
    if (result >= 12) {
      const clearedHp = Math.max(0, c.hp.on - hpHealingFloor(c));
      c.hp.on -= clearedHp;
      const clearedStress = c.stress.on;
      c.stress.on = 0;
      addEffect(c, structuredClone(effect), label);
      log.push(`${text}: stopped rolling`, `cleared ${clearedHp} HP and ${clearedStress} Stress`, `gained ${effect.name}`);
      break;
    }
    scars++;
    log.push(`${text}: crossed out (scar)`);
  }
  if (scars) {
    c.scars = (c.scars ?? 0) + scars;
    log.push(`gained ${scars} scar${scars === 1 ? '' : 's'}`);
  }
  if (!slots) log.push('no Hope slots to roll');
  return log;
}

/**
 * Uses up one consumable unit after its sheet action, unless the action keeps it or portions of the unit remain.
 * Consuming a unit refills its portion pools for the next unit; an emptied stack leaves the inventory.
 */
export function useConsumableUnit(c: CharacterSheet, item: CharacterSheet['inventory'][number], action: ActionDef): void {
  if (action.keepItem) return;
  if (action.portions && (c.resources?.[action.portions] ?? 0) > 0) return;
  item.qty = Math.max(0, item.qty - 1);
  for (const id of new Set((item.automation?.actions ?? []).map(a => a.portions).filter((p): p is string => !!p))) {
    const def = item.automation?.resources?.find(r => r.id === id);
    if (def) c.resources = { ...c.resources, [id]: Math.max(0, evalValue(def.initial, c)) };
  }
  if (item.qty <= 0) c.inventory = c.inventory.filter(i => i !== item);
}

/** Replaces `@amount` in an action's cost, steps and damage dice with the amount chosen in its `selectAmount` dialog. */
export function withSelectedAmount<T extends ActionDef>(action: T, amount: number): T {
  const sub = <V>(value: V): V => (typeof value === 'string' ? value.replace(/@amount\b/g, String(amount)) : value) as V;
  return {
    ...action,
    selectedAmount: amount,
    ...(action.cost ? { cost: Object.fromEntries(Object.entries(action.cost).map(([k, v]) => [k, sub(v)])) } : {}),
    ...(action.steps ? { steps: action.steps.map(step => ({ ...step, amount: sub(step.amount) })) } : {}),
    ...(action.damage ? { damage: { ...action.damage, dice: sub(action.damage.dice) } } : {}),
  };
}

/** Parses a weapon damage string like "d8+1" or "1d10+2" against the proficiency (dice count). */
export function damageExpression(damage: string, proficiency: number, extra: RollBonus, pool?: Automation['damage'], fixed = false): string {
  let e = damage.replace(/\s/g, '');
  if (/^(?:\d*d\d+\+)*\d*d\d+(?:[+-]\d+)?$/i.test(e)) {
    e = e.replace(/(\d*)d(\d+)/gi, (_term, count: string, sides: string) => {
      const n = (fixed ? Number(count || 1) : proficiency) + (pool?.extraDice ?? 0);
      return `${n}d${sides}${pool?.dropLowest ? `kh${n - pool.dropLowest}` : ''}`;
    });
  }
  if (extra.value) e += extra.value > 0 ? `+${extra.value}` : `${extra.value}`;
  for (const d of extra.dice) e += `+${d.startsWith('d') ? `1${d}` : d}`;
  return e;
}

export function damagePool(c: CharacterSheet, weapon: CharacterSheet['activeWeapons'][number]): Automation['damage'] {
  const pools = [weapon.automation?.damage, ...c.effects.map(e => e.damage)]
    .filter((p): p is NonNullable<Automation['damage']> => !!p && (!p.proficiencyOnly || !weapon.fixedDice));
  if (!pools.length) return undefined;
  const rule = pools.find(p => p.onesAs || p.explode || p.onesBonus);
  return { extraDice: pools.reduce((n, p) => n + (p.extraDice ?? 0), 0), dropLowest: pools.reduce((n, p) => n + (p.dropLowest ?? 0), 0),
    maximumOnes: pools.some(p => p.maximumOnes),
    ...(rule?.onesAs ? { onesAs: rule.onesAs } : {}),
    ...(pools.some(p => p.onesBonus) ? { onesBonus: Math.max(...pools.map(p => p.onesBonus ?? 0)) } : {}),
    ...(pools.some(p => p.explode) ? { explode: pools.some(p => p.explode === 'repeat') ? 'repeat' as const : 'once' as const } : {}),
    ...(rule?.label ? { label: rule.label } : {}) };
}

/** Host-applied damage dice rules of the weapon's damage pool, sent with the damage roll. */
export function weaponDamageRules(c: CharacterSheet, weapon: Weapon): { onesAs?: number; onesBonus?: number; explode?: 'once' | 'repeat'; label?: string } | undefined {
  const pool = damagePool(c, weapon);
  if (!pool?.onesAs && !pool?.explode && !pool?.onesBonus) return undefined;
  return { ...(pool.onesAs ? { onesAs: pool.onesAs } : {}), ...(pool.onesBonus ? { onesBonus: pool.onesBonus } : {}),
    ...(pool.explode ? { explode: pool.explode } : {}),
    label: (pool.label ?? weapon.feature?.name ?? weapon.name).slice(0, 60) };
}

/** Extra damage of a critical hit: the maximum result of the (kept) damage dice, never the modifiers. */
export function criticalBonus(expression: string): number {
  let sum = 0;
  for (const m of expression.matchAll(/(?:^|\+)(\d+)d(\d+)(?:kh(\d+))?/gi)) sum += Number(m[3] ?? m[1]) * Number(m[2]);
  return sum;
}

/** True when a spell's text asks the player to make a Spellcast Roll (not merely mentions one). */
export function asksSpellcastRoll(text: string): boolean {
  return /\b(make|makes|made|roll|rolls|cast)\b[^.]{0,40}spellcast roll/i.test(text);
}

export function isTrait(name: string): name is TraitName {
  return TRAIT_SET.has(name);
}

export interface RollContext {
  damageSource?: string;
  dice?: number[];
  deathrun?: { effectId: string; count: number };
  resourceOnes?: string[];
  /** Comma separated roll targets of the original roll, for example `attack,trait:Agility,action`. */
  source: string;
  hope: number;
  fear: number;
  total: number;
  outcome?: 'hope' | 'fear';
  weapon?: string;
  kind?: 'duality' | 'damage';
  difficulty?: number;
}

export interface AvailableRollTrigger { key: string; source: string; automation: Automation; trigger: RollTrigger }

export function effectCounterMax(c: CharacterSheet, effect: EffectDef): number {
  const counter = effect.counter;
  const level = Object.keys(counter?.maxByLevel ?? {}).map(Number).filter(n => n <= c.level).sort((a, b) => b - a)[0];
  return level === undefined ? counter?.max ?? 0 : counter!.maxByLevel![String(level)];
}

export function effectCounterText(c: CharacterSheet, name: string): string {
  const effect = c.effects.find(e => e.name === name && e.counter);
  return effect ? `${effect.counter!.value} / d${effectCounterMax(c, effect)}` : 'Inactive';
}

function triggerApplies(c: CharacterSheet, t: RollTrigger, ctx: RollContext): boolean {
  if (t.damageSource && (ctx.kind !== 'damage' || ctx.damageSource !== t.damageSource)) return false;
  if (t.spellDomain && !activeCards(c).some(card => card.domain === t.spellDomain
    && ctx.source.split(',').includes(`card:${card.name}`))) return false;
  if (t.containsDie !== undefined && !ctx.dice?.includes(t.containsDie)) return false;
  if (t.deathrunResult && !ctx.deathrun) return false;
  if (t.weaponDamage && (!ctx.weapon || ctx.kind !== 'damage' || ctx.source.split(',').includes('spelldamage'))) return false;
  if (t.criticalOnly && (ctx.kind === 'damage' || ctx.hope < 1 || ctx.hope !== ctx.fear)) return false;
  if (!loadoutDomainMatches(c, t.loadoutDomain)) return false;
  if (t.successIfKnown && ctx.difficulty !== undefined && ctx.hope !== ctx.fear && ctx.total < ctx.difficulty) return false;
  if (t.failedOnly && (ctx.hope === ctx.fear || ctx.difficulty !== undefined && ctx.total >= ctx.difficulty)) return false;
  if (t.rerollResourceDice && (!(ctx.source.split(',').some(target => target === 'attack' || target === 'damage'))
      || !ctx.resourceOnes?.includes(t.rerollResourceDice))) return false;
  const targets = ctx.source.split(',');
  const profile = targets.find(tag => tag.startsWith('weaponProfile:'))?.slice('weaponProfile:'.length);
  const weapons = activeWeapons(c).map(w => profile && w.name === ctx.weapon ? weaponWithProfile(w, profile) : w);
  if (t.primaryWeapon && !weapons.some(w => w.slot === 'Primary' && w.name === ctx.weapon)) return false;
  if (t.spendFavor && ctx.difficulty !== undefined && ctx.total < ctx.difficulty) return false;
  const magic = targets.includes('magicdamage') || targets.includes('spelldamage')
    || weapons.some(w => w.name === ctx.weapon && w.damageType === 'magic');
  if (t.magicOnly && !magic) return false;
  if (t.magicAction && !(targets.includes('spellcast') || targets.includes('attack') && magic)) return false;
  if (t.on === 'companion' && !companionAvailable(c)) return false;
  if (t.swapDuality && (ctx.kind === 'damage' || ctx.hope === ctx.fear)) return false;
  const companion = companionWeapon(c);
  if (t.weaponRange && ![...weapons, ...(companion ? [companion] : [])].some(w => w.name === ctx.weapon && w.range.toLowerCase() === t.weaponRange!.toLowerCase())) return false;
  if (t.weaponRanges && !weapons.some(w => w.name === ctx.weapon && t.weaponRanges!.some(range => range.toLowerCase() === w.range.toLowerCase()))) return false;
  if (t.excludeRolls?.some((target) => ctx.source.split(',').includes(target))) return false;
  if (t.on === 'duality' && ctx.kind === 'damage') return false;
  if (t.on === 'trait' && !targets.some(target => target.startsWith('trait:'))) return false;
  if (t.on !== 'any' && t.on !== 'duality' && t.on !== 'trait' && !targets.includes(t.on)) return false;
  if (t.requiresEffect && !c.effects.some((e) => e.name === t.requiresEffect)) return false;
  if (t.weapon && t.weapon !== ctx.weapon) return false;
  if (t.weaponAttack && (!ctx.weapon || !ctx.source.split(',').includes('attack'))) return false;
  if (ctx.kind === 'damage' && t.when && t.when !== 'always') return false;
  // Players never know the difficulty, so any roll may have failed (Hope rolls and criticals included).
  const failed = true;
  switch (t.when ?? 'always') {
    case 'fear': return ctx.hope !== ctx.fear && (ctx.outcome ? ctx.outcome === 'fear' : ctx.fear > ctx.hope);
    case 'hopeOrCritical': return ctx.hope === ctx.fear || (ctx.outcome ? ctx.outcome === 'hope' : ctx.hope > ctx.fear);
    case 'hope': return ctx.hope !== ctx.fear && (ctx.outcome ? ctx.outcome === 'hope' : ctx.hope > ctx.fear);
    case 'failure': return failed;
    case 'success': {
      const difficulty = t.difficulty ?? ctx.difficulty;
      return difficulty !== undefined && (ctx.hope === ctx.fear || ctx.total >= difficulty);
    }
    case 'hopeDie': return ctx.hope === t.die;
    case 'dieMatches': {
      const n = t.matchResource ? c.resources?.[t.matchResource] ?? 0 : 0;
      return n > 0 && (ctx.hope === n || ctx.fear === n);
    }
    default: return true;
  }
}

/** Chat section of a roll button: its own `rollSection`, otherwise derived from whether the trigger's feature made the roll. */
export function rollTriggerSection(t: AvailableRollTrigger, rollSource: string): RollActionSection {
  if (t.trigger.rollSection) return t.trigger.rollSection;
  const tags = rollSource.split(',');
  const own = !!t.trigger.damageSource || /^(card|feat|featureAction|option):/.test(t.trigger.on)
    || tags.some(tag => tag === t.key || tag.startsWith(`featureAction:${t.key}:`));
  if (!own) return 'other';
  return t.trigger.damage || t.trigger.resourceRoll || t.trigger.spentTokenDamage ? 'result' : 'feature';
}

/** Buttons the character's features offer on a finished roll. */
export function availableRollTriggers(c: CharacterSheet, ctx: RollContext): AvailableRollTrigger[] {
  const sources: Array<{ key: string; name: string; auto?: Automation }> = [
    ...(c.activeArmor ? [{ key: `armor:${c.activeArmor.name}`, name: c.activeArmor.name, auto: c.activeArmor.automation }] : []),
    ...activeWeapons(c).map((w) => ({ key: `weapon:${w.name}`, name: w.name, auto: w.automation })),
    ...activeFeatures(c).map((f) => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...c.heritage.map((f) => ({ key: `feat:${f.name}`, name: f.name, auto: f.automation })),
    ...activeCardSources(c),
    ...c.inventory.filter((i) => i.qty > 0).map((i) => ({ key: `item:${i.name}`, name: i.name, auto: i.automation })),
    ...c.effects.filter(e => actorAutomationAvailable(e.sourceActorId)).map((e) => ({ key: `effect:${e.id}`, name: e.name, auto: { rollTriggers: e.rollTriggers } })),
  ];
  return sources.flatMap((s) => [...(s.auto?.rollTriggers ?? []), ...(s.auto?.conditionalRollTriggers ?? [])].filter((t) => !t.automatic && triggerApplies(c, t, ctx))
    .filter((t) => !s.key.startsWith('weapon:') || t.primaryWeapon || t.anyWeapon || ctx.weapon === s.name)
    .map((trigger) => ({ key: s.key, source: s.name,
      automation: trigger.usesSource === false ? { ...s.auto!, uses: undefined } : s.auto!, trigger })));
}

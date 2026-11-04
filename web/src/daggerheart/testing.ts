import { CharacterSheet, TestingBuild, TestingStage } from './data';
import { cardFrom, CompendiumEntry } from './compendium';
import { resourceDefinitions, syncDerived } from './engine';
import {
  buildCharacter, classEntries, creationAllowances, emptyChoices, findClass, guideFor, MAX_LEVEL, RulesData,
  shortSubclass, STAGES, subclassCard, subclassesOf, suggestedTraits,
  ancestryFeatureChoices,
} from './rules';

export function defaultTestingBuild(d: RulesData): TestingBuild {
  const cls = classEntries(d)[0];
  if (!cls) throw new Error('The compendium has no classes for a testing character.');
  return {
    className: cls.name, subclass: subclassesOf(d, cls.name)[0] ?? '', stage: 'mastery', level: 10,
    ancestry: d.ancestries[0]?.name ?? '', community: d.communities[0]?.name ?? '', multiclasses: [],
  };
}

export function testingBuildProblems(d: RulesData, b: TestingBuild): string[] {
  const problems: string[] = [];
  if (!Number.isInteger(b.level) || b.level < 1 || b.level > MAX_LEVEL) problems.push(`Choose a level from 1 to ${MAX_LEVEL}.`);
  for (const cls of [b, ...b.multiclasses]) {
    if (!findClass(d, cls.className) || !subclassesOf(d, cls.className).includes(cls.subclass)) problems.push('Choose a valid class and subclass.');
    if (!STAGES.includes(cls.stage)) problems.push('Choose a valid subclass stage.');
  }
  const classes = [b.className, ...b.multiclasses.map((m) => m.className)];
  if (new Set(classes).size !== classes.length) problems.push('Choose each class only once.');
  for (const m of b.multiclasses) if (!findClass(d, m.className)?.domains?.includes(m.domain)) problems.push(`Choose a domain of ${m.className}.`);
  for (const name of [b.ancestry, b.ancestry2].filter(Boolean)) {
    if (!d.ancestries.some((e) => e.name === name)) problems.push('Choose a valid ancestry.');
  }
  if (!b.ancestry || !d.communities.some((e) => e.name === b.community)) problems.push('Choose an ancestry and community.');
  if (b.transformation && !d.transformations.some((e) => e.name === b.transformation)) problems.push('Choose a valid transformation.');
  if (b.transformation === 'Shapeshifter' && !ancestryFeatureChoices(d.ancestries, b.ancestry2 && b.ancestry2 !== b.ancestry ? `${b.ancestry} / ${b.ancestry2}` : b.ancestry).some((f) => f.name === b.ancestryFeature)) problems.push('Choose the ancestry feature to retain.');
  return problems;
}

/** Rebuild only rule-owned fields; keep equipment, notes and the state of retained sources. */
export function applyTestingBuild(d: RulesData, c: CharacterSheet, build: TestingBuild): void {
  if (c.testingCharacter !== true) throw new Error('Only a DM-marked testing character can use the testing builder.');
  const problems = testingBuildProblems(d, build);
  if (problems.length) throw new Error(problems.join(' '));
  const b = structuredClone(build);
  const traits = suggestedTraits(guideFor(b.className));
  if (c.testingBuild) for (const trait of c.traits) traits[trait.name] = trait.mod;
  const choices = {
    ...emptyChoices(), ...b, name: c.name, pronouns: c.pronouns,
    primary: guideFor(b.className).primary, armor: guideFor(b.className).armor,
    experiences: ['Testing experience', 'Testing background'],
    experienceBonusIndex: 0,
    traits,
  };
  const base = buildCharacter(d, choices, c);
  base.level = b.level;
  const addEntry = (entry: CompendiumEntry | undefined, source: string) => {
    if (!entry) return;
    if (entry.automation) base.features.push({ source, name: entry.name, text: entry.text ?? '', automation: entry.automation });
    for (const f of entry.features ?? []) base.features.push({ source, name: f.name, text: f.text, automation: f.automation });
    if (!base.traits.some((t) => t.spellcast)) {
      const trait = base.traits.find((t) => t.name === entry.spellcastTrait);
      if (trait) trait.spellcast = true;
    }
  };
  const addStages = (subclass: string, stage: TestingStage, source: string, start: number) => {
    for (const s of STAGES.slice(start, STAGES.indexOf(stage) + 1)) addEntry(subclassCard(d, subclass, s), `${source} ${s} - ${shortSubclass(subclass)}`);
  };
  addStages(b.subclass, b.stage, 'Subclass', 1);
  for (const m of b.multiclasses) {
    const cls = findClass(d, m.className)!;
    addEntry({ ...cls, features: cls.features?.filter((f) => f.type !== 'hope') }, `Multiclass ${m.className} - class feature`);
    addStages(m.subclass, m.stage, `Multiclass ${m.className}`, 0);
    base.domains.push(m.domain);
    for (const entry of [cls, ...STAGES.slice(0, STAGES.indexOf(m.stage) + 1).map((s) => subclassCard(d, m.subclass, s))]) {
      base.domains.push(...(entry?.automation?.creation?.domains ?? []), ...(entry?.features ?? []).flatMap((f) => f.automation?.creation?.domains ?? []));
    }
  }
  base.domains.push(...creationAllowances(d, choices).domains);
  for (const f of base.features) base.domains.push(...(f.automation?.creation?.domains ?? []));
  base.domains = [...new Set(base.domains)];
  base.loadout = d.cards.filter((card) => card.domain && base.domains.includes(card.domain))
    .map((card) => cardFrom(card, base));

  const keys = (sheet: CharacterSheet) => new Set([
    ...[...sheet.features, ...sheet.heritage].map((f) => `feat:${f.name}`),
    ...sheet.loadout.map((card) => `card:${card.name}`),
  ]);
  const nextKeys = keys(base);
  const removed = [...keys(c)].filter((key) => !nextKeys.has(key));
  const removedNames = new Set(removed.map((key) => key.slice(key.indexOf(':') + 1)));
  c.effects = c.effects.filter((e) => {
    const source = e.source;
    return !source || ![...removedNames].some((name) => source === name || source.startsWith(`${name} - `));
  });
  for (const key of Object.keys(c.usage)) if (removed.some((old) => key === old || key.startsWith(`${old}:`))) delete c.usage[key];
  c.className = base.className;
  c.subclass = base.subclass;
  c.domains = base.domains;
  c.ancestry = base.ancestry;
  c.ancestryFeature = base.ancestryFeature;
  c.community = base.community;
  c.transformation = base.transformation;
  c.level = b.level;
  c.evasion = base.evasion;
  c.hp.slots = base.hp.slots;
  c.hp.on = Math.min(c.hp.on, c.hp.slots);
  c.stress.slots = base.stress.slots;
  c.stress.on = Math.min(c.stress.on, c.stress.slots);
  c.proficiency.on = b.level < 2 ? 1 : b.level < 5 ? 2 : b.level < 8 ? 3 : 4;
  c.traits = base.traits;
  c.features = base.features;
  c.heritage = base.heritage;
  c.loadout = base.loadout;
  c.vault = [];
  c.levelUps = [];
  if (!c.testingBuild && c.mancer?.done === false) {
    c.activeWeapons = base.activeWeapons;
    c.activeArmor = base.activeArmor;
    c.inventory = base.inventory;
    c.gold = base.gold;
    c.experiences = base.experiences;
  }
  c.mancer = { done: true };
  c.testingBuild = b;
  const resources = new Set(resourceDefinitions(c).map((r) => r.id));
  for (const id of Object.keys(c.resources ?? {})) if (!resources.has(id)) delete c.resources![id];
  syncDerived(c);
}

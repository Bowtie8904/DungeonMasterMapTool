import { checkJson } from './values';

export interface StoredDie {
  id: string;
  sides: number;
  face: number;
  kept: boolean;
  origin: 'rolled' | 'maximized' | 'stored';
}
interface Contribution {
  id: string;
  role: string;
  sourceId: string;
  label: string;
  total?: number;
}
export type RollContribution = Contribution & (
  { kind: 'dice'; dice: StoredDie[]; sign?: -1 | 1 } |
  { kind: 'flat'; value: number } |
  { kind: 'scale'; of: string[]; factor: number }
);
export interface StoredRoll {
  id: string;
  contributions: RollContribution[];
  excludedDice?: string[];
  total?: number;
}

function fail(path: string, message: string): never { throw new Error(`${path}: ${message}`); }
function object(value: unknown, path: string, fields: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(path, 'Expected an object.');
  const record: Record<string, unknown> = value as Record<string, unknown>;
  for (const key of Object.keys(record)) if (!fields.includes(key)) fail(`${path}/${key}`, 'Unknown field.');
  return record;
}
function id(value: unknown, path: string): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/.test(value)) fail(path, 'Invalid stable ID.');
  return value;
}
function numeric(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER)
    fail(path, 'Expected a number in the safe numeric range.');
  return value;
}
function integer(value: unknown, path: string, min: number, max: number): number {
  const n = numeric(value, path);
  if (!Number.isInteger(n) || n < min || n > max) fail(path, `Expected a whole number between ${min} and ${max}.`);
  return n;
}
function ids(value: unknown, path: string): string[] {
  if (!Array.isArray(value) || value.length > 1024) fail(path, 'Expected a bounded list of IDs.');
  const result = value.map(v => id(v, path));
  if (new Set(result).size !== result.length) fail(path, 'Duplicate ID.');
  return result;
}

export function resolveRoll(input: unknown, checkTotals = true): StoredRoll & { total: number } {
  checkJson(input);
  const roll = object(input, '/roll', ['id', 'contributions', 'excludedDice', 'total']);
  const parts = roll.contributions;
  if (!Array.isArray(parts) || !parts.length || parts.length > 256) fail('/roll/contributions', 'Expected 1-256 contributions.');
  const excluded = new Set(roll.excludedDice === undefined ? [] : ids(roll.excludedDice, '/roll/excludedDice'));
  const allDice = new Set<string>(), keptDice = new Set<string>(), totals = new Map<string, number>();
  const contributions: RollContribution[] = [];
  let total = 0;
  parts.forEach((value, i) => {
    const path = `/roll/contributions/${i}`;
    const common = ['id', 'kind', 'role', 'sourceId', 'label', 'total'];
    const candidate = object(value, path, [...common, 'dice', 'sign', 'value', 'of', 'factor']);
    const kind = candidate.kind;
    const fields = kind === 'dice' ? ['dice', 'sign'] : kind === 'flat' ? ['value'] : kind === 'scale' ? ['of', 'factor'] : fail(path, 'Unknown contribution kind.');
    const part = object(value, path, [...common, ...fields]);
    const partId = id(part.id, `${path}/id`);
    if (totals.has(partId)) fail(path, 'Duplicate contribution ID.');
    if (typeof part.label !== 'string' || !part.label.trim()) fail(path, 'Expected a nonempty label.');
    const base: Contribution = { id: partId, role: id(part.role, `${path}/role`), sourceId: id(part.sourceId, `${path}/sourceId`), label: part.label };
    let contribution: RollContribution;
    let sum = 0;
    if (kind === 'dice') {
      if (!Array.isArray(part.dice) || !part.dice.length || part.dice.length > 1024) fail(path, 'Expected 1-1024 dice.');
      const sign = part.sign === undefined ? 1 : integer(part.sign, path, -1, 1);
      if (sign !== -1 && sign !== 1) fail(path, 'Dice sign must be -1 or 1.');
      const dice: StoredDie[] = part.dice.map((item, j) => {
        const dp = `${path}/dice/${j}`, die = object(item, dp, ['id', 'sides', 'face', 'kept', 'origin']);
        const dieId = id(die.id, `${dp}/id`);
        if (allDice.has(dieId) || allDice.size >= 1024) fail(dp, 'Duplicate die ID or too many dice.');
        allDice.add(dieId);
        const sides = integer(die.sides, dp, 2, 1000), face = integer(die.face, dp, 1, sides);
        if (typeof die.kept !== 'boolean') fail(dp, 'Expected a boolean.');
        const origin = die.origin;
        if (origin !== 'rolled' && origin !== 'maximized' && origin !== 'stored') fail(dp, 'Unknown die provenance.');
        if (origin === 'maximized' && face !== sides) fail(dp, 'A maximized die must show its maximum.');
        if (die.kept) keptDice.add(dieId);
        if (die.kept && !excluded.has(dieId)) sum += sign * face;
        return { id: dieId, sides, face, kept: die.kept, origin };
      });
      contribution = { ...base, kind: 'dice', dice, ...(part.sign === undefined ? {} : { sign }) };
    } else if (kind === 'flat') {
      sum = numeric(part.value, path);
      contribution = { ...base, kind: 'flat', value: sum };
    } else {
      const dependencies = ids(part.of, `${path}/of`);
      if (!dependencies.length) fail(path, 'A scale contribution needs dependencies.');
      let subtotal = 0;
      for (const dependency of dependencies) {
        const before = totals.get(dependency);
        if (before === undefined) fail(path, 'Scale references must name earlier contributions.');
        subtotal = numeric(subtotal + before, path);
      }
      const factor = numeric(part.factor, path);
      if (factor < 0) fail(path, 'Scale factor cannot be negative.');
      sum = subtotal * (factor - 1);
      contribution = { ...base, kind: 'scale', of: dependencies, factor };
    }
    numeric(sum, path);
    if (checkTotals && part.total !== undefined && part.total !== sum) fail(path, 'Stored contribution total does not match its provenance.');
    contributions.push({ ...contribution, total: sum });
    totals.set(partId, sum);
    total = numeric(total + sum, '/roll/total');
  });
  if ([...excluded].some(die => !keptDice.has(die))) fail('/roll/excludedDice', 'Only original kept dice can be excluded.');
  if (checkTotals && roll.total !== undefined && roll.total !== total) fail('/roll/total', 'Stored total does not match its provenance.');
  return { id: id(roll.id, '/roll/id'), contributions, ...(roll.excludedDice === undefined ? {} : { excludedDice: [...excluded] }), total };
}

export function rollDice(roll: StoredRoll, role?: string, keptOnly = true): Array<StoredDie & { poolId: string; role: string }> {
  const resolved = resolveRoll(roll), excluded = new Set(resolved.excludedDice ?? []);
  return resolved.contributions.flatMap(part => part.kind !== 'dice' || role !== undefined && role !== part.role ? []
    : part.dice.map(die => ({ ...die, kept: die.kept && !excluded.has(die.id), poolId: part.id, role: part.role }))
      .filter(die => !keptOnly || die.kept));
}

export function deriveRoll(original: StoredRoll, eligibleDice: string[], keepDice: string[]): StoredRoll & { total: number } {
  const roll = resolveRoll(original);
  const eligible = new Set(ids(eligibleDice, '/derive/eligible')), keep = new Set(ids(keepDice, '/derive/keep'));
  const available = new Set(rollDice(roll).map(die => die.id));
  if ([...eligible].some(die => !available.has(die)) || [...keep].some(die => !eligible.has(die)))
    fail('/derive', 'Die selection contains an unknown, dropped or already excluded die.');
  const excluded = new Set(roll.excludedDice ?? []);
  for (const die of eligible) if (!keep.has(die)) excluded.add(die);
  return resolveRoll({ ...roll, excludedDice: [...excluded] }, false);
}

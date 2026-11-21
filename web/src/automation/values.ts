export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export type Context = { [key: string]: Json };

const roots = new Set(['source', 'actor', 'recipient', 'event', 'state', 'input', 'die', 'result', 'args']);
const reserved = new Set(['__proto__', 'constructor', 'prototype']);
const unary = new Set(['not', 'floor', 'ceil', 'negate', 'size']);
const binary = new Set(['subtract', 'divide', 'eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'includes']);
const many = new Set(['add', 'multiply', 'min', 'max', 'and', 'or']);

function fail(path: string, message: string): never { throw new Error(`${path}: ${message}`); }

export function checkJson(value: unknown): asserts value is Json {
  let count = 0;
  const check = (v: unknown, path: string, depth: number): void => {
    if (depth > 32 || ++count > 16_384) fail(path, 'Rule data exceeds its complexity limit.');
    if (v === null || typeof v === 'boolean') return;
    if (typeof v === 'number') { number(v, path); return; }
    if (typeof v === 'string') {
      if (v.length > 16_000) fail(path, 'Text is too long.');
      return;
    }
    if (Array.isArray(v)) { v.forEach((item, i) => check(item, `${path}/${i}`, depth + 1)); return; }
    if (typeof v !== 'object' || Object.getPrototypeOf(v) !== Object.prototype) fail(path, 'Expected JSON data.');
    for (const [key, child] of Object.entries(v)) {
      if (reserved.has(key)) fail(`${path}/${key}`, 'Reserved field name.');
      check(child, `${path}/${key}`, depth + 1);
    }
  };
  check(value, '/', 0);
}

export function validateRead(path: string): void {
  const parts = path.split('.');
  if (parts.length < 2 || parts.length > 12 || !roots.has(parts[0])) fail('/read', `Unknown context path: ${path}`);
  if (parts.some(p => !/^[a-zA-Z_][a-zA-Z0-9_-]*$/.test(p) || reserved.has(p))) fail('/read', `Invalid context path: ${path}`);
}

function find(context: Context, path: string): Json | undefined {
  let current: Json | undefined = context;
  for (const part of path.split('.')) {
    if (!current || typeof current !== 'object' || Array.isArray(current) || !Object.hasOwn(current, part)) return undefined;
    current = current[part];
  }
  return current;
}

export function read(context: Context, path: string): Json {
  validateRead(path);
  const value = find(context, path);
  if (value === undefined) fail(`/read/${path}`, 'Context value is unavailable.');
  return value;
}

export function validateExpression(expression: unknown): asserts expression is Json {
  checkJson(expression);
  const validate = (node: Json, path: string): void => {
    if (node === null || typeof node !== 'object') return;
    if (Array.isArray(node) || Object.keys(node).length !== 1) fail(path, 'An expression must be a scalar or one operator.');
    const [op, arg] = Object.entries(node)[0];
    if (op === 'literal') return;
    if (op === 'read' || op === 'exists') {
      if (typeof arg !== 'string') fail(path, 'Expected a context path.');
      validateRead(arg);
      return;
    }
    if (unary.has(op)) { validate(arg, `${path}/${op}`); return; }
    const min = binary.has(op) ? 2 : many.has(op) ? 1 : ['if', 'clamp'].includes(op) ? 3 : -1;
    if (min === -1) fail(`${path}/${op}`, 'Unknown expression operator.');
    if (!Array.isArray(arg) || arg.length < min || arg.length > (many.has(op) ? 128 : min)) fail(path, 'Wrong number of operands.');
    arg.forEach((item, i) => validate(item, `${path}/${op}/${i}`));
  };
  validate(expression, '/expression');
}

function number(value: Json, path = '/expression'): number {
  if (typeof value !== 'number') fail(path, 'Expected a number.');
  if (!Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER) fail(path, 'Number exceeds the safe numeric range.');
  return value === 0 ? 0 : value;
}

function bool(value: Json): boolean {
  if (typeof value !== 'boolean') fail('/expression', 'Expected a boolean.');
  return value;
}

function equal(a: Json, b: Json): boolean {
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a)) return Array.isArray(b) && a.length === b.length && a.every((v, i) => equal(v, b[i]));
  if (Array.isArray(b)) return false;
  return Object.keys(a).length === Object.keys(b).length && Object.entries(a).every(([k, v]) => Object.hasOwn(b, k) && equal(v, b[k]));
}

export function evaluate(expression: Json, context: Context): Json {
  validateExpression(expression);
  const evalNode = (node: Json): Json => {
    if (node === null || typeof node !== 'object' || Array.isArray(node)) return node;
    const [op, arg] = Object.entries(node)[0];
    if (op === 'literal') return structuredClone(arg);
    if (op === 'read') return structuredClone(read(context, String(arg)));
    if (op === 'exists') return find(context, String(arg)) !== undefined;
    const operands = Array.isArray(arg) ? arg : [];
    if (op === 'if') return evalNode(operands[bool(evalNode(operands[0])) ? 1 : 2]);
    if (op === 'and') return operands.every(x => bool(evalNode(x)));
    if (op === 'or') return operands.some(x => bool(evalNode(x)));
    if (unary.has(op)) {
      const value = evalNode(arg);
      switch (op) {
        case 'not': return !bool(value);
        case 'floor': return number(Math.floor(number(value)));
        case 'ceil': return number(Math.ceil(number(value)));
        case 'negate': return number(-number(value));
        case 'size':
          if (!Array.isArray(value)) fail('/expression', 'size requires a collection.');
          return value.length;
      }
    }
    const values = operands.map(evalNode);
    if (op === 'eq') return equal(values[0], values[1]);
    if (op === 'ne') return !equal(values[0], values[1]);
    if (op === 'includes') {
      if (!Array.isArray(values[0])) fail('/expression', 'includes requires a collection.');
      return values[0].some(v => equal(v, values[1]));
    }
    const ns = values.map(v => number(v));
    const [a, b] = ns;
    switch (op) {
      case 'gt': return a > b;
      case 'gte': return a >= b;
      case 'lt': return a < b;
      case 'lte': return a <= b;
      case 'subtract': return number(a - b);
      case 'divide':
        if (b === 0) fail('/expression', 'Division by zero.');
        return number(a / b);
      case 'clamp':
        if (b > ns[2]) fail('/expression', 'Minimum exceeds maximum.');
        return number(Math.max(b, Math.min(ns[2], a)));
      case 'add': return ns.reduce((total, n) => number(total + n));
      case 'multiply': return ns.reduce((total, n) => number(total * n));
      case 'min': return Math.min(...ns);
      case 'max': return Math.max(...ns);
      default: return fail('/expression', 'Unknown expression operator.');
    }
  };
  return evalNode(expression);
}

type Part = { literal: string } | { path: string; format: string };

function templateParts(text: string): Part[] {
  if (text.length > 8_000) fail('/template', 'Template is too long.');
  const parts: Part[] = [];
  let literal = '';
  for (let i = 0; i < text.length;) {
    const c = text[i];
    if ((c === '{' || c === '}') && text[i + 1] === c) { literal += c; i += 2; }
    else if (c === '{') {
      parts.push({ literal }); literal = '';
      const end = text.indexOf('}', i + 1);
      if (end < 0) fail('/template', 'Unclosed placeholder.');
      const fields = text.slice(i + 1, end).split('|');
      if (fields.length > 2) fail('/template', 'Invalid placeholder.');
      const [path, format = ''] = fields;
      validateRead(path);
      if (fields.length === 2 && !['signed', 'number'].includes(format) && !/^plural:[^:{}|]+:[^:{}|]+$/.test(format))
        fail('/template', `Unknown formatter: ${format}`);
      parts.push({ path, format }); i = end + 1;
    } else if (c === '}') fail('/template', 'Unmatched closing brace.');
    else { literal += c; i++; }
  }
  parts.push({ literal });
  return parts;
}

export function validateTemplate(text: string): void { templateParts(text); }

function decimal(value: number): string {
  const raw = String(value);
  if (!raw.includes('e')) return raw;
  const [mantissa, exponent] = raw.split('e');
  const negative = mantissa.startsWith('-');
  const unsigned = negative ? mantissa.slice(1) : mantissa;
  const [whole, fraction = ''] = unsigned.split('.');
  const digits = whole + fraction;
  const point = whole.length + Number(exponent);
  const expanded = point <= 0 ? '0.' + '0'.repeat(-point) + digits
    : point >= digits.length ? digits + '0'.repeat(point - digits.length)
    : digits.slice(0, point) + '.' + digits.slice(point);
  return (negative ? '-' : '') + expanded;
}

export function renderTemplate(text: string, context: Context): string {
  let out = '';
  for (const part of templateParts(text)) {
    if ('literal' in part) out += part.literal;
    else {
      const value = read(context, part.path);
      if (part.format.startsWith('plural:')) {
        const [, singular, plural] = part.format.split(':');
        out += number(value, '/template') === 1 ? singular : plural;
      } else if (part.format === 'signed' || part.format === 'number' || typeof value === 'number') {
        const n = number(value, '/template');
        out += (part.format === 'signed' && n > 0 ? '+' : '') + decimal(n);
      } else {
        if (typeof value !== 'string' && typeof value !== 'boolean') fail('/template', 'Token requires a scalar value.');
        out += String(value);
      }
    }
    if (out.length > 16_000) fail('/template', 'Rendered text is too long.');
  }
  return out;
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RuleDescriptor } from './controls';
import { openAutomationDamageDialog } from './damage';

class Element {
  children: Element[] = [];
  listeners = new Map<string, Array<(event?: unknown) => void>>();
  private textValue = '';
  className = '';
  disabled = false;
  checked = false;
  hidden = false;
  value = '';
  closed = false;
  constructor(readonly tagName: string) {}
  get textContent(): string { return this.textValue; }
  set textContent(value: string) { this.textValue = value; this.children = []; }
  append(...children: Array<Element | string>): void {
    this.children.push(...children.map(child => {
      if (typeof child !== 'string') return child;
      const node = new Element('#text'); node.textContent = child; return node;
    }));
  }
  replaceChildren(...children: Element[]): void { this.children = []; this.append(...children); }
  addEventListener(event: string, handler: (event?: unknown) => void): void {
    this.listeners.set(event, [...(this.listeners.get(event) ?? []), handler]);
  }
  removeEventListener(): void {}
  setAttribute(): void {}
  fire(event: string): void { this.listeners.get(event)?.forEach(handler => handler({ target: this })); }
  click(): void { if (!this.disabled) this.listeners.get('click')?.forEach(handler => handler({ target: this })); }
  focus(): void {}
  remove(): void { this.closed = true; }
  all(tag: string): Element[] { return [...(this.tagName === tag ? [this] : []), ...this.children.flatMap(child => child.all(tag))]; }
  get text(): string { return this.textContent + this.children.map(child => child.text).join(''); }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 20));
const brace: RuleDescriptor = {
  sourceId: 'feature:brace', sourceInstanceId: 'brace-1', ruleId: 'brace', bindingId: 'prepare',
  revision: 4, surface: 'damage', mode: 'option', label: 'Brace', available: true, sourceName: 'Guardian', cost: '1 Hope',
  uses: { limit: 'brace', left: 1, max: 1, resetOn: ['rest.long'] },
};
const tough: RuleDescriptor = {
  sourceId: 'feature:tough', sourceInstanceId: 'tough-1', ruleId: 'tough', bindingId: 'marks',
  revision: 4, surface: 'damage', mode: 'automatic', label: 'Tough', available: true, sourceName: 'Ancestry',
};
const offer = {
  revision: 4, thresholds: { major: 5, severe: 10 }, armorTrack: { marked: 1, capacity: 3 },
  marks: { armor: { allowed: 1 } },
  applied: { amount: 7, incoming: 2, severity: 2, hitPoints: 2, armorMarked: 0, hitPointsRemaining: 4, armorRemaining: 3 },
  stages: { 'damage.prepare': { actions: [brace] }, 'damage.marks': { actions: [tough] }, 'damage.applied': { actions: [] } },
};
const mitigated = { ...offer, applied: { ...offer.applied, amount: 4, incoming: 1, severity: 0, hitPoints: 0, wouldMark: 1,
  armorSpent: 1, armorMarked: 1 } };
let body: Element;
const dialogOf = () => body.children.find(child => child.className.includes('dh-rolldlg-backdrop'));
beforeEach(() => {
  body = new Element('body');
  vi.stubGlobal('document', { body, createElement: (tag: string) => new Element(tag),
    addEventListener: () => {}, removeEventListener: () => {} });
});
afterEach(() => vi.unstubAllGlobals());

describe('host-described incoming damage', () => {
  it('renders the format-1 dialog, previews the selected mitigation and retries an uncertain request identically', async () => {
    const posts: string[] = [], gets: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      if (!init?.body) {
        gets.push(url);
        return Response.json(new URL(url, 'http://host').searchParams.has('selections') ? mitigated : offer);
      }
      posts.push(String(init.body));
      if (posts.length === 1) throw new Error('Connection lost.');
      return Response.json({});
    }));
    let applied = 0;
    expect(await openAutomationDamageDialog('hero', async () => {}, () => { applied++; })).toBe(true);
    const dialog = dialogOf()!;
    expect(dialog.children[0].className).toBe('dh-rolldlg dh-damage-dlg dh-option-rolldlg');
    await settle();
    expect(dialog.text).toContain('Mark an Armor Slot');
    expect(dialog.text).toContain('(2/3 free)');
    expect(dialog.text).toContain('Brace - Guardian (1 Hope - 1/1 left)');
    expect(dialog.text).toContain('Applied automatically');
    expect(dialog.text).toContain('\u2713 Tough (Ancestry)');
    const [amount, armorBox, braceBox] = dialog.all('input');
    armorBox.checked = true;
    armorBox.fire('change');
    dialog.all('button').find(button => button.text === 'Magic')!.click();
    amount.value = '7';
    amount.fire('input');
    await settle();
    expect(dialog.text).toContain('7 damage \u00b7 Major');
    expect(dialog.text).toContain('Mark 2 HP');
    braceBox.checked = true;
    braceBox.fire('change');
    await settle();
    expect(JSON.parse(new URL(gets.at(-1)!, 'http://host').searchParams.get('selections')!)).toEqual([
      { sourceInstanceId: 'brace-1', ruleId: 'brace', bindingId: 'prepare', inputs: {} }]);
    expect(dialog.text).toContain('7 damage \u2192 4 \u00b7 Minor \u2192 None');
    expect(dialog.text).toContain('Mark 0 HP');
    expect(dialog.text).toContain('1 Armor Slot marked \u00b7 pay 1 Hope \u00b7 instead of 1 HP');
    dialog.all('button').find(button => button.text === 'Apply')!.click();
    await settle();
    expect(dialog.text).toContain('Connection lost.');
    dialog.all('button').find(button => button.text === 'Apply')!.click();
    await settle();
    expect(posts).toEqual([posts[0], posts[0]]);
    expect(JSON.parse(posts[0])).toEqual({ request: {
      requestId: expect.any(String), expectedRevision: 4, damage: { amount: 7, armor: 1, type: 'magical' },
      selections: [{ sourceInstanceId: 'brace-1', ruleId: 'brace', bindingId: 'prepare', inputs: {} }],
    } });
    expect(dialog.closed).toBe(true);
    expect(applied).toBe(1);
  });

  it('shows the Armor Slots count only when a mitigation allows several slots', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ ...offer, marks: { armor: { allowed: 3 } } })));
    await openAutomationDamageDialog('hero', async () => {});
    const dialog = dialogOf()!;
    await settle();
    expect(dialog.text).toContain('Mark Armor Slots');
    expect(dialog.all('label').some(label => label.className === 'dh-roll-option-control')).toBe(false);
    const armorBox = dialog.all('input')[1];
    armorBox.checked = true;
    armorBox.fire('change');
    await settle();
    expect(dialog.all('label').some(label => label.className === 'dh-roll-option-control' && label.text === 'Armor Slots')).toBe(true);
  });

  it('reloads the preview after a stale revision', async () => {
    let gets = 0;
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (!init?.body) { gets++; return Response.json(offer); }
      return Response.json({ error: 'The character changed.' }, { status: 409 });
    }));
    await openAutomationDamageDialog('hero', async () => {});
    const dialog = dialogOf()!;
    await settle();
    dialog.all('input')[0].value = '7';
    dialog.all('input')[0].fire('input');
    await settle();
    dialog.all('button').find(button => button.text === 'Apply')!.click();
    await settle();
    expect(gets).toBe(4);
    expect(dialog.text).toContain('The character changed.');
  });

  it('falls back to the legacy dialog for format-1 hosts', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ legacy: true })));
    expect(await openAutomationDamageDialog('hero', async () => {})).toBe(false);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })));
    expect(await openAutomationDamageDialog('hero', async () => {})).toBe(false);
    expect(dialogOf()).toBeUndefined();
  });
});

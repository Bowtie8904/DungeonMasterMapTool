import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';

class Element {
  className = '';
  dataset: Record<string, string> = {};
  children: Element[] = [];
  parent: Element | null = null;
  listeners = new Map<string, () => void>();
  attributes = new Map<string, string>();
  textContent = '';
  value = '';
  disabled = false;
  checked = false;
  namespaceURI = 'http://www.w3.org/2000/svg';

  constructor(readonly tag: string) {}

  classList = {
    contains: (name: string) => this.className.split(' ').includes(name),
    toggle: (name: string, force?: boolean) => {
      const classes = new Set(this.className.split(' ').filter(Boolean));
      const add = force ?? !classes.has(name);
      if (add) classes.add(name);
      else classes.delete(name);
      this.className = [...classes].join(' ');
      return add;
    },
    add: (name: string) => { this.classList.toggle(name, true); },
    remove: (name: string) => { this.classList.toggle(name, false); },
  };

  append(...children: Array<Element | string>) {
    for (const child of children) {
      if (typeof child === 'string') this.textContent += child;
      else { child.parent = this; this.children.push(child); }
    }
  }
  setAttribute(name: string, value: string) { this.attributes.set(name, value); }
  addEventListener(type: string, listener: () => void) { this.listeners.set(type, listener); }
  fire(type: string) { this.listeners.get(type)?.(); }
  click() { if (!this.disabled) this.fire('click'); }
  closest(selector: string): Element | null {
    if (selector.startsWith('.') && this.classList.contains(selector.slice(1))) return this;
    return this.parent?.closest(selector) ?? null;
  }
  querySelectorAll(selector: string): Element[] {
    const matches = (child: Element) => selector === 'button' ? child.tag === 'button'
      : selector === '[data-drawing-category]' ? 'drawingCategory' in child.dataset
      : selector === '[data-draw-mode]' && 'drawMode' in child.dataset;
    return this.children.flatMap(child => [...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
}

class Input extends Element { constructor() { super('input'); } }
class Select extends Element { constructor() { super('select'); } }

afterEach(() => vi.unstubAllGlobals());

describe('map tool controls', () => {
  it('keeps ink size controls and the distance ruler shortcut synchronized with their tool options', () => {
    vi.stubGlobal('HTMLElement', Element);
    vi.stubGlobal('HTMLInputElement', Input);
    vi.stubGlobal('HTMLSelectElement', Select);
    vi.stubGlobal('document', {
      createElement: (tag: string) => tag === 'input' ? new Input() : tag === 'select' ? new Select() : new Element(tag),
      createElementNS: (_namespace: string, tag: string) => new Element(tag),
      querySelector: () => null,
    });
    const view = { tool: 'navigate', drawMode: 'pen', measureMode: 'radius', paused: false, setTool(tool: string) { this.tool = tool; } };
    const context = {
      view, toolButtons: new Map<string, Element>(), flyout: new Element('aside'),
      drawingOptions: new Element('div'), measuringOptions: new Element('div'), toolTitle: new Element('h2'),
      quickAccessButton: new Element('button'), textureSelect: new Select(), colorInput: new Input(),
      sizeInput: new Input(), alphaInput: new Input(), borderInput: new Input(), emitsInput: new Input(),
      aboveInput: new Input(), measureColorInput: new Input(), deleteButton: new Element('button'),
      selectionHint: new Element('p'), effectsEl: new Element('aside'), lastInkMode: 'pen', lastEffectMode: 'brush',
      readStyle: vi.fn(),
      drawingCategory(mode: string) { return Reflect.get(App.prototype, 'drawingCategory').call(context, mode); },
      activateDrawingMode(mode: string) { Reflect.get(App.prototype, 'activateDrawingMode').call(context, mode); },
      updateDrawingMode(mode: string) { Reflect.get(App.prototype, 'updateDrawingMode').call(context, mode); },
      closeTools() { Reflect.get(App.prototype, 'closeTools').call(context); },
    };
    context.flyout.classList.add('hidden');
    Reflect.get(App.prototype, 'buildTools').call(context);
    const size = context.sizeInput.closest('.drawing-size')!;
    const value = size.children.find(child => child.tag === 'output')!;
    const effectsOptions = size.parent!;
    expect(effectsOptions.dataset.drawingCategory).toBe('effects');
    expect(value.textContent).toBe('2 tiles');

    context.toolButtons.get('ink')!.fire('click');
    expect(context.sizeInput.disabled).toBe(true);
    expect(size.classList.contains('hidden')).toBe(true);
    expect(effectsOptions.classList.contains('hidden')).toBe(true);

    // Selecting an existing brush while the Ink flyout remains open must not expose its size control there.
    context.updateDrawingMode('brush');
    expect(effectsOptions.classList.contains('hidden')).toBe(true);

    context.drawingOptions.querySelectorAll('[data-draw-mode]').find(button => button.dataset.drawMode === 'line')!.fire('click');
    expect(context.sizeInput.disabled).toBe(true);
    expect(size.classList.contains('hidden')).toBe(true);

    context.toolButtons.get('effects')!.fire('click');
    expect(context.sizeInput.disabled).toBe(false);
    expect(size.classList.contains('hidden')).toBe(false);
    expect(effectsOptions.classList.contains('hidden')).toBe(false);
    context.sizeInput.value = '3';
    context.sizeInput.fire('input');
    expect(value.textContent).toBe('3 tiles');

    context.toolButtons.get('ink')!.fire('click');
    expect(view.drawMode).toBe('line');
    expect(context.sizeInput.disabled).toBe(true);
    expect(size.classList.contains('hidden')).toBe(true);
    context.drawingOptions.querySelectorAll('[data-draw-mode]').find(button => button.dataset.drawMode === 'pen')!.fire('click');
    expect(size.classList.contains('hidden')).toBe(true);

    context.toolButtons.get('measure')!.disabled = false;
    const pressR = () => Reflect.get(App.prototype, 'playerShortcut').call(context,
      { key: 'r', target: null, preventDefault: vi.fn() });
    expect(pressR()).toBe(true);
    const distance = Reflect.get(context, 'distanceRulerButton') as Element;
    const modes = distance.parent!;
    const cone = modes.children.find(button => button.attributes.get('aria-label') === 'Cone (60 degrees)')!;
    expect(view.tool).toBe('measure');
    expect(view.measureMode).toBe('distance');
    expect(context.flyout.classList.contains('hidden')).toBe(false);
    expect(context.toolButtons.get('measure')!.attributes.get('aria-pressed')).toBe('true');
    expect(context.toolButtons.get('measure')!.attributes.get('aria-expanded')).toBe('true');
    expect(distance.attributes.get('aria-pressed')).toBe('true');
    cone.fire('click');
    expect(view.measureMode).toBe('cone');
    expect(pressR()).toBe(true);
    expect(view.measureMode).toBe('distance');
    expect(context.flyout.classList.contains('hidden')).toBe(false);
    expect(distance.attributes.get('aria-pressed')).toBe('true');
    expect(cone.attributes.get('aria-pressed')).toBe('false');
  });
});
